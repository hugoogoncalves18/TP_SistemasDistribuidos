import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * TODO O ESTADO PARTILHADO do servidor: utilizadores, leilões e histórico.
 *
 * DECISÃO DE CONCORRÊNCIA (para o relatório e para a defesa):
 *   Todos os métodos públicos são "synchronized" no próprio objeto Estado,
 *   ou seja, há UM lock global. Quando duas threads (clientes) licitam ao
 *   mesmo tempo, a segunda espera que a primeira acabe e é depois avaliada
 *   contra o estado que a primeira deixou (secção 2: "concorrentes").
 *   Dentro do mesmo bloco synchronized faz-se, por esta ordem:
 *       1. validar (existe? aberto? valor suficiente?)
 *       2. GRAVAR no ficheiro de histórico
 *       3. alterar a memória
 *       4. devolver (e só então o Atendedor responde OK)
 *   Logo, "aceite" = validada + gravada no histórico, no instante em que o
 *   servidor a processa. Nada se perde nem se sobrepõe.
 *   Troca-se desempenho (um lock para tudo) por simplicidade e correção.
 *   Também se decide "aberto/fechado" DENTRO do lock, com o relógio do servidor.
 *
 * FICHEIRO DE HISTÓRICO (texto, uma linha por evento, sempre terminada em \n):
 *   REG user sha256hex
 *   LEILAO id base incremento fechoMs criador artigo      (artigo = resto da linha)
 *   LICIT id n utilizador valor
 *   FECHO id seq                                          (nível Intermédio)
 *   CANCEL id seq                                         (nível Completo, C1)
 *   Nota: LICIT leva no fim a hora de fecho em vigor (C2); linhas antigas sem ela também se leem.
 *
 * DIFUSÃO (I1): depois de aceitar uma licitação, ou de fechar um leilão, o
 * Estado entrega o texto do aviso a "difusao" (o Difusor envia-o por UDP
 * multicast). Faz-se DENTRO do lock para que os avisos de um leilão saiam pela
 * ordem do seq; senão duas licitações seguidas podiam sair trocadas e o
 * cliente julgava que tinha havido perda.
 *
 * FECHO AUTOMÁTICO (I3/I4): fecharVencidos() é chamado pelo relógio do
 * servidor (a cada 200 ms) e uma vez no arranque. Fechar = gravar FECHO e
 * difundir o aviso. É o relógio do servidor que decide, nunca os clientes.
 *
 *   A palavra-passe NUNCA é gravada em claro, só o resumo SHA-256.
 *   Se o servidor morrer a meio de uma gravação, a última linha fica sem \n:
 *   ao arrancar, descarta-se esse fragmento (ver carregar()).
 */
final class Estado {

    /** fsync a cada gravação: garante que o OK só sai depois dos dados no disco. */
    private static final boolean FSYNC =
            !"false".equalsIgnoreCase(System.getProperty("fsync", "true"));

    private final Map<String, String> utilizadores = new HashMap<>();  // nome -> sha256
    private final Map<Integer, Leilao> leiloes = new TreeMap<>();      // ordenado por id (LISTAR)
    private int proximoId = 1;
    /** Leilões ainda sem fecho anunciado: é só estes que o relógio precisa de vigiar. */
    private final List<Leilao> porFechar = new ArrayList<>();
    private final Consumer<String> difusao;   // recebe o texto de cada aviso
    private final long antiSnipingMs;          // C2: janela final em ms (0 = desligado)
    private final Map<String, Integer> sessoes = new TreeMap<>();   // C1: utilizador -> nº de sessões

    private final Path ficheiro;
    private FileOutputStream saida;   // aberto em modo "append"

    Estado(Path ficheiro, Consumer<String> difusao, long antiSnipingMs) throws IOException {
        this.ficheiro = ficheiro;
        this.difusao = difusao;
        this.antiSnipingMs = antiSnipingMs;
        carregar();                                           // repõe o estado (B7)
        this.saida = new FileOutputStream(ficheiro.toFile(), true);
    }

    // =====================================================================
    //  OPERAÇÕES (chamadas pelas threads dos clientes)
    // =====================================================================

    /** REGISTAR: cria o utilizador. Erro EXISTE se já houver. */
    synchronized void registar(String utilizador, String palavraPasse) throws ErroProtocolo {
        if (utilizadores.containsKey(utilizador)) {
            throw new ErroProtocolo("EXISTE", "utilizador ja existe");
        }
        String hash = resumo(palavraPasse);
        gravar("REG " + utilizador + " " + hash);   // primeiro o disco...
        utilizadores.put(utilizador, hash);         // ...depois a memória
    }

    /** LOGIN: true se utilizador existe e a palavra-passe confere. */
    synchronized boolean autenticar(String utilizador, String palavraPasse) {
        String esperado = utilizadores.get(utilizador);
        // Mesma resposta para "não existe" e "palavra-passe errada" (A.3).
        return esperado != null && esperado.equals(resumo(palavraPasse));
    }

    /** CRIAR: devolve o id escolhido pelo servidor. A hora de fecho é do servidor. */
    synchronized int criar(String criador, long base, long incremento,
                           long duracaoSeg, String artigo) throws ErroProtocolo {
        long fechoMs = System.currentTimeMillis() + duracaoSeg * 1000L;
        int id = proximoId;
        gravar("LEILAO " + id + " " + base + " " + incremento + " " + fechoMs
                + " " + criador + " " + artigo);
        Leilao novo = new Leilao(id, artigo, base, incremento, criador, fechoMs);
        leiloes.put(id, novo);
        porFechar.add(novo);
        proximoId++;
        return id;
    }

    /**
     * Um registo por leilão (A.4), por id crescente (snapshot coerente).
     * Serve o LISTAR (que acrescenta "LEILAO " à frente) e a consola RMI.
     */
    synchronized List<String> listar() {
        long agora = System.currentTimeMillis();
        List<String> linhas = new ArrayList<>();
        for (Leilao l : leiloes.values()) {
            linhas.add(l.registo(agora));
        }
        return linhas;
    }

    /**
     * ESTADO (extensão nossa): como CONSULTAR, mas com o número de sequência
     * atual à frente ("seq=<k> id=..."). É o "pedido de estado" do I2: o
     * cliente que detetou perda usa o seq para continuar a partir daí.
     */
    synchronized String estadoComSeq(int id) throws ErroProtocolo {
        Leilao l = leiloes.get(id);
        if (l == null) throw new ErroProtocolo("INEXISTENTE", "leilao nao existe");
        return "seq=" + l.seq() + " " + l.registo(System.currentTimeMillis());
    }

    /** CONSULTAR: o registo de um leilão. */
    synchronized String consultar(int id) throws ErroProtocolo {
        Leilao l = leiloes.get(id);
        if (l == null) throw new ErroProtocolo("INEXISTENTE", "leilao nao existe");
        return l.registo(System.currentTimeMillis());
    }

    /**
     * LICITAR: REGIÃO CRÍTICA. Devolve o número de ordem k da licitação aceite.
     * A ordem das verificações é a do Anexo A.3:
     *   INEXISTENTE, FECHADO, PROPRIO, BAIXA
     * (SINTAXE, SEM_SESSAO e VALOR já foram verificados antes, no Atendedor).
     */
    synchronized int licitar(String utilizador, int id, long valor) throws ErroProtocolo {
        Leilao l = leiloes.get(id);
        if (l == null) {
            throw new ErroProtocolo("INEXISTENTE", "leilao nao existe");
        }
        // Relógio do servidor, lido DENTRO do lock: é o "instante em que o
        // servidor processa" a licitação (secção 2). Nunca se usa hora de clientes.
        // Este mesmo "agora" serve para o anti-sniping, para ser tudo coerente.
        long agora = System.currentTimeMillis();
        if (!l.aberto(agora)) {                     // inclui cancelado (C1)
            throw new ErroProtocolo("FECHADO", "leilao fechado");
        }
        if (l.criador.equals(utilizador)) {
            throw new ErroProtocolo("PROPRIO", "o criador nao pode licitar o seu leilao");
        }
        if (valor < l.valorMinimo()) {
            throw new ErroProtocolo("BAIXA", "valor inferior ao minimo");
        }
        int n = l.licitacoes + 1;

        // C2 ANTI-SNIPING: licitação aceite nos últimos N segundos adia o fecho
        // para agora + N. Fora desse intervalo não adia nada, e o fecho nunca
        // é antecipado (só se aceita um valor MAIOR que o atual).
        long novoFecho = l.fechoMs;
        boolean prolongado = false;
        if (antiSnipingMs > 0 && l.fechoMs - agora <= antiSnipingMs
                && agora + antiSnipingMs > l.fechoMs) {
            novoFecho = agora + antiSnipingMs;
            prolongado = true;
        }

        // Gravar ANTES de alterar a memória e antes de o OK sair (B7). A hora de
        // fecho nova vai NA MESMA LINHA da licitação: uma só escrita, por isso
        // não pode ficar a licitação gravada sem o adiamento (e repõe-se no arranque).
        gravar("LICIT " + id + " " + n + " " + utilizador + " " + valor + " " + novoFecho);
        l.melhor = valor;
        l.vencedor = utilizador;
        l.licitacoes = n;
        l.fechoMs = novoFecho;
        // O aviso sai com seq = n. O adiamento vai NO PRÓPRIO aviso da licitação
        // (prolongado=SIM e o restante já atualizado), sem outro número de sequência.
        difundir("AVISO tipo=LICITACAO leilao=" + id + " seq=" + n
                + " valor=" + valor + " utilizador=" + utilizador
                + " restante=" + l.restante(agora) + " prolongado=" + (prolongado ? "SIM" : "NAO"));
        return n;
    }

    /**
     * C1: cancela um leilão ABERTO. Fecha-o sem vencedor, grava, e difunde um
     * aviso (que conta como fecho na sequência: seq = licitacoes + 1).
     * Corre na thread RMI, mas toma o MESMO lock das licitações TCP: é atómico
     * em relação a elas. Quem entra primeiro no lock ganha (ver AdminImpl).
     */
    synchronized void cancelar(int id) throws ErroProtocolo {
        Leilao l = leiloes.get(id);
        if (l == null) throw new ErroProtocolo("INEXISTENTE", "leilao nao existe");
        if (!l.aberto(System.currentTimeMillis())) {
            throw new ErroProtocolo("FECHADO", "o leilao ja fechou ou foi cancelado");
        }
        int seq = l.licitacoes + 1;
        gravar("CANCEL " + id + " " + seq);        // primeiro o disco...
        l.cancelado = true;                          // ...depois a memória: daqui em diante
        l.fechoAnunciado = true;                     // licitar() recusa com FECHADO
        porFechar.remove(l);                         // o relógio já não tem de o fechar
        System.out.println("Leilao " + id + " cancelado pelo administrador.");
        difundir("AVISO tipo=CANCELADO leilao=" + id + " seq=" + seq);
    }

    /** C1: sessões iniciadas (um utilizador pode ter várias ligações). */
    synchronized void iniciarSessao(String utilizador) {
        sessoes.merge(utilizador, 1, Integer::sum);
    }

    synchronized void terminarSessao(String utilizador) {
        sessoes.computeIfPresent(utilizador, (u, n) -> n == 1 ? null : n - 1);
    }

    /** C1: utilizadores com sessão iniciada neste momento, por ordem alfabética. */
    synchronized List<String> utilizadoresComSessao() {
        List<String> r = new ArrayList<>();
        sessoes.forEach((u, n) -> r.add(n == 1 ? u : u + " (" + n + " sessoes)"));
        return r;
    }

    /**
     * I3/I4: fecha, anuncia e grava o fecho de todos os leilões cuja hora de
     * fecho já passou. Devolve quantos fechou. Usa o relógio do servidor, dentro
     * do lock: uma licitação que chegue depois da hora de fecho (mesmo antes de
     * este método correr) já foi recusada com FECHADO em licitar().
     * Se a gravação falhar, o leilão fica por fechar e tenta-se no ciclo seguinte.
     */
    synchronized int fecharVencidos() {
        long agora = System.currentTimeMillis();
        int fechados = 0;
        Iterator<Leilao> it = porFechar.iterator();
        while (it.hasNext()) {
            Leilao l = it.next();
            if (l.aberto(agora)) continue;
            int seq = l.licitacoes + 1;               // o fecho é o aviso a seguir à última licitação
            try {
                gravar("FECHO " + l.id + " " + seq);  // primeiro o disco, depois o aviso
            } catch (ErroProtocolo e) {
                continue;
            }
            l.fechoAnunciado = true;
            it.remove();
            fechados++;
            System.out.println("Leilao " + l.id + " fechado: "
                    + (l.vencedor == null ? "sem vencedor" : "vencedor " + l.vencedor + " com " + l.melhor));
            difundir("AVISO tipo=FECHO leilao=" + l.id + " seq=" + seq
                    + " vencedor=" + (l.vencedor == null ? "-" : l.vencedor) + " melhor=" + l.melhor);
        }
        return fechados;
    }

    /** Entrega um aviso à difusão. Nunca deixa uma falha de rede estragar a operação. */
    private void difundir(String aviso) {
        try {
            difusao.accept(aviso);
        } catch (RuntimeException e) {
            System.err.println("Falha ao difundir aviso: " + e);
        }
    }

    /** Fecha o ficheiro (chamado no encerramento do servidor). */
    synchronized void fechar() {
        try {
            if (saida != null) saida.close();
        } catch (IOException e) {
            System.err.println("Aviso: erro ao fechar o historico: " + e.getMessage());
        }
    }

    // =====================================================================
    //  PERSISTÊNCIA
    // =====================================================================

    /**
     * Acrescenta uma linha ao histórico. Escreve a linha INTEIRA numa só
     * chamada (bytes + \n) para que um crash só possa truncar a última.
     * Chamado sempre com o lock de Estado já tomado.
     */
    private void gravar(String linha) throws ErroProtocolo {
        try {
            saida.write((linha + "\n").getBytes(StandardCharsets.UTF_8));
            saida.flush();
            if (FSYNC) saida.getFD().sync();   // força a ida ao disco
        } catch (IOException e) {
            System.err.println("Erro ao gravar historico: " + e.getMessage());
            throw new ErroProtocolo("INTERNO", "falha ao gravar historico");
        }
    }

    /**
     * Lê o histórico no arranque e repõe utilizadores, leilões e melhores
     * valores. Uma última linha sem \n (servidor morreu a gravar) é descartada
     * e cortada do ficheiro, para que as gravações seguintes não colem nela.
     */
    private void carregar() throws IOException {
        if (!Files.exists(ficheiro)) return;               // primeira execução
        byte[] bytes = Files.readAllBytes(ficheiro);

        int ultimoNl = -1;
        for (int i = bytes.length - 1; i >= 0; i--) {
            if (bytes[i] == '\n') { ultimoNl = i; break; }
        }
        int validos = ultimoNl + 1;                        // bytes completos
        if (validos < bytes.length) {
            System.err.println("Aviso: linha final truncada no historico; descartada.");
            try (FileChannel ch = FileChannel.open(ficheiro, StandardOpenOption.WRITE)) {
                ch.truncate(validos);
            }
        }

        String texto = new String(bytes, 0, validos, StandardCharsets.UTF_8);
        int numLinha = 0;
        for (String linha : texto.split("\n")) {
            numLinha++;
            if (linha.isEmpty()) continue;
            try {
                aplicar(linha);
            } catch (RuntimeException e) {
                // Linha estragada a meio do ficheiro: avisa e continua.
                System.err.println("Aviso: linha " + numLinha + " do historico ignorada ("
                        + e.getClass().getSimpleName() + ")");
            }
        }
        System.out.println("Historico lido: " + utilizadores.size() + " utilizadores, "
                + leiloes.size() + " leiloes.");
    }

    /** Aplica UMA linha do histórico ao estado em memória (só no arranque). */
    private void aplicar(String linha) {
        String[] p;
        switch (linha.substring(0, linha.indexOf(' '))) {
            case "REG" -> {
                p = linha.split(" ", 3);
                utilizadores.put(p[1], p[2]);
            }
            case "LEILAO" -> {
                // LEILAO id base incremento fechoMs criador artigo
                p = linha.split(" ", 7);
                int id = Integer.parseInt(p[1]);
                Leilao l = new Leilao(id, p[6], Long.parseLong(p[2]),
                        Long.parseLong(p[3]), p[5], Long.parseLong(p[4]));
                leiloes.put(id, l);
                porFechar.add(l);                     // sai daqui se houver um FECHO a seguir
                if (id >= proximoId) proximoId = id + 1;
            }
            case "LICIT" -> {
                // LICIT id n utilizador valor [fechoMs]   (fechoMs: C2; falta nas linhas antigas)
                p = linha.split(" ", 6);
                Leilao l = leiloes.get(Integer.parseInt(p[1]));
                l.licitacoes = Integer.parseInt(p[2]);
                l.vencedor = p[3];
                l.melhor = Long.parseLong(p[4]);
                if (p.length == 6) l.fechoMs = Long.parseLong(p[5]);   // repõe o adiamento
            }
            case "FECHO" -> {
                // FECHO id seq
                p = linha.split(" ", 3);
                Leilao l = leiloes.get(Integer.parseInt(p[1]));
                l.fechoAnunciado = true;
                porFechar.remove(l);
            }
            case "CANCEL" -> {
                // CANCEL id seq
                p = linha.split(" ", 3);
                Leilao l = leiloes.get(Integer.parseInt(p[1]));
                l.cancelado = true;
                l.fechoAnunciado = true;
                porFechar.remove(l);
            }
            default -> throw new IllegalArgumentException("tipo desconhecido");
        }
    }

    /** SHA-256 em hexadecimal (B3). Sem sal, como pede o enunciado. */
    private static String resumo(String palavraPasse) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(palavraPasse.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 existe sempre no Java
        }
    }
}
