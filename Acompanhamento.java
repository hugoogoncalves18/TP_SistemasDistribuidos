import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Parte do cliente que ACOMPANHA leilões (I2): escuta os avisos multicast,
 * mostra-os em tempo real, deteta perdas e recupera o estado por TCP.
 *
 * Corre numa thread própria ("avisos"), porque receive() bloqueia e o
 * utilizador pode estar a escrever comandos ao mesmo tempo (como o ChatClient).
 *
 * ALGORITMO, por leilão acompanhado (guarda "ultimo" = último seq visto):
 *   - aviso com seq <= ultimo  -> repetido ou já incluído no estado: ignora;
 *   - aviso com seq == ultimo+1 -> o esperado: mostra e ultimo = seq;
 *   - aviso com seq >  ultimo+1 -> PERDA: pede ESTADO por TCP, mostra o estado
 *     atual e continua a partir do seq que ele traz. Os avisos já incluídos
 *     nesse estado (seq <= ultimo) são ignorados, por isso cada aviso aparece
 *     uma só vez; as licitações perdidas não são mostradas (não se exige).
 *
 * LIMITAÇÃO tratada: se se perder o ÚLTIMO aviso (por exemplo o do fecho), nenhum
 * aviso seguinte revela a perda. Por isso, a cada 5 s, pede-se o ESTADO de cada
 * leilão acompanhado e compara-se o seq.
 *
 * SIMULAÇÃO DE PERDAS: com descartarN = N > 0, deita fora 1 de cada N avisos
 * recebidos (o N-ésimo, o 2N-ésimo...) para demonstrar a recuperação.
 */
final class Acompanhamento implements Runnable {

    private static final long VERIFICACAO_MS = 5000;

    /** O que sabemos de um leilão acompanhado. */
    private static final class Seguido {
        int ultimo;                                   // último seq visto
        Seguido(int ultimo) { this.ultimo = ultimo; }
    }

    private final LigacaoTcp tcp;
    private final String grupo;
    private final int porto;
    private final int descartarN;

    // Protegido por synchronized(this): escrito pela thread principal (/seguir)
    // e pela thread dos avisos.
    private final Map<Integer, Seguido> seguidos = new HashMap<>();

    private MulticastSocket socket;
    private Thread thread;
    private volatile boolean terminar = false;
    private int recebidos = 0;                        // só a thread dos avisos

    Acompanhamento(LigacaoTcp tcp, String grupo, int porto, int descartarN) {
        this.tcp = tcp;
        this.grupo = grupo;
        this.porto = porto;
        this.descartarN = descartarN;
    }

    // =====================================================================
    //  COMANDOS DO UTILIZADOR (thread principal)
    // =====================================================================

    /** /seguir id: junta-se ao grupo (1.ª vez), pede o estado e começa a acompanhar. */
    void seguir(int id) throws IOException {
        if (!iniciar()) return;                       // junta-se ao grupo ANTES de pedir o estado
        String r = tcp.pedir("ESTADO " + id);
        if (!r.startsWith("OK ")) {
            dizer(r);
            return;
        }
        Map<String, String> c = campos(r);
        int seq = inteiro(c, "seq");
        synchronized (this) {
            dizer("A acompanhar o leilao " + id + ". Estado atual: " + r.substring(3));
            if (seq > inteiro(c, "licitacoes")) {     // o aviso de fecho já saiu: nada a acompanhar
                dizer("O leilao " + id + " ja esta fechado. " + resultado(c));
                return;
            }
            seguidos.put(id, new Seguido(seq));
        }
    }

    /** /parar id */
    synchronized void parar(int id) {
        dizer(seguidos.remove(id) != null ? "A parar de acompanhar o leilao " + id
                                           : "Nao estava a acompanhar o leilao " + id);
    }

    /** Termina a thread dos avisos e fecha o socket. */
    void terminar() {
        terminar = true;
        if (socket != null) socket.close();           // desbloqueia o receive()
    }

    /** Cria o socket, junta-se ao grupo e lança a thread (só na 1.ª vez). */
    private synchronized boolean iniciar() {
        if (thread != null) return true;
        try {
            InetAddress g = Multicast.grupo(grupo);
            NetworkInterface ni = Multicast.interfaceEscolhida();
            socket = new MulticastSocket(porto);      // vários clientes na mesma máquina: ok (reuse)
            socket.setSoTimeout(1000);                // acorda a cada 1 s para a verificação periódica
            socket.joinGroup(new InetSocketAddress(g, porto), ni);
        } catch (IOException | IllegalArgumentException e) {
            dizer("Nao foi possivel juntar-me ao grupo multicast " + grupo + ":" + porto + " (" + e.getMessage() + ")");
            if (socket != null) socket.close();
            socket = null;
            return false;
        }
        thread = new Thread(this, "avisos");
        thread.setDaemon(true);                       // não impede o programa de terminar
        thread.start();
        dizer("Ligado ao grupo multicast " + grupo + ":" + porto
                + (descartarN > 0 ? " (a descartar 1 em cada " + descartarN + " avisos)" : ""));
        return true;
    }

    // =====================================================================
    //  THREAD DOS AVISOS
    // =====================================================================

    @Override
    public void run() {
        byte[] buffer = new byte[2048];
        long ultimaVerificacao = System.currentTimeMillis();
        try {
            while (!terminar) {
                try {
                    DatagramPacket p = new DatagramPacket(buffer, buffer.length);
                    socket.receive(p);
                    String texto = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    recebidos++;
                    if (descartarN > 0 && recebidos % descartarN == 0) {
                        dizer("[simulacao] aviso descartado: " + texto);
                    } else {
                        processar(texto);
                    }
                } catch (SocketTimeoutException e) {
                    // sem avisos neste segundo: segue para a verificação periódica
                }
                long agora = System.currentTimeMillis();
                if (agora - ultimaVerificacao >= VERIFICACAO_MS) {
                    verificar();
                    ultimaVerificacao = agora;
                }
            }
        } catch (IOException e) {
            if (!terminar) dizer("Acompanhamento terminado: " + e.getMessage());
        }
    }

    /** Trata um aviso recebido (ver o algoritmo no topo da classe). */
    private synchronized void processar(String texto) throws IOException {
        if (!texto.startsWith("AVISO ")) return;      // datagrama de outra aplicação
        Map<String, String> c = campos(texto);
        int id = inteiro(c, "leilao");
        int seq = inteiro(c, "seq");
        if (id < 0 || seq < 0) return;                // aviso mal formado: ignora

        Seguido s = seguidos.get(id);
        if (s == null) return;                        // leilão que não acompanho
        if (seq <= s.ultimo) return;                  // repetido ou já incluído no estado

        if (seq > s.ultimo + 1) {                     // PERDA
            dizer("[!] Perda no leilao " + id + ": esperava o n." + (s.ultimo + 1)
                    + ", recebi o n." + seq + ". A recuperar o estado por TCP...");
            aplicarEstado(id, s, tcp.pedir("ESTADO " + id));
            if (!seguidos.containsKey(id) || seq <= s.ultimo) return;  // o estado já incluía este aviso
        }
        mostrar(c);
        s.ultimo = seq;
        String tipo = c.get("tipo");
        if ("FECHO".equals(tipo) || "CANCELADO".equals(tipo)) seguidos.remove(id);
    }

    /** A cada 5 s: confirma por TCP que não se perdeu o último aviso de nenhum leilão. */
    private synchronized void verificar() throws IOException {
        for (Integer id : new ArrayList<>(seguidos.keySet())) {
            Seguido s = seguidos.get(id);
            String r = tcp.pedir("ESTADO " + id);
            if (r.startsWith("OK ") && inteiro(campos(r), "seq") > s.ultimo) {
                dizer("[!] Ha avisos do leilao " + id + " que nao recebi. A atualizar pelo estado...");
                aplicarEstado(id, s, r);
            }
        }
    }

    /**
     * Aplica uma resposta ESTADO: mostra o estado atual e continua a partir do
     * seq que ele traz. Se o leilão já fechou, deixa de o acompanhar.
     */
    private void aplicarEstado(int id, Seguido s, String r) {
        if (!r.startsWith("OK ")) {
            dizer("Nao foi possivel recuperar o estado do leilao " + id + ": " + r);
            return;
        }
        Map<String, String> c = campos(r);
        int seq = inteiro(c, "seq");
        dizer("Estado atual (n." + seq + "): " + r.substring(3));
        s.ultimo = Math.max(s.ultimo, seq);
        if (seq > inteiro(c, "licitacoes")) {         // seq > licitacoes <=> o fecho já foi anunciado
            dizer("Leilao " + id + " FECHADO. " + resultado(c));
            seguidos.remove(id);
        }
    }

    // =====================================================================
    //  APRESENTAÇÃO E LEITURA DE CAMPOS
    // =====================================================================

    private static void mostrar(Map<String, String> c) {
        String prefixo = "[leilao " + c.get("leilao") + " . n." + c.get("seq") + "] ";
        if ("FECHO".equals(c.get("tipo"))) {
            dizer(prefixo + "FECHADO. " + resultado(c));
        } else if ("CANCELADO".equals(c.get("tipo"))) {
            dizer(prefixo + "CANCELADO pelo administrador. Sem vencedor.");
        } else if ("LICITACAO".equals(c.get("tipo"))) {
            // C2: se a licitação adiou o fecho, o próprio aviso traz o tempo que resta.
            String extra = "SIM".equals(c.get("prolongado"))
                    ? " | FECHO ADIADO: restam " + c.get("restante") + " s" : "";
            dizer(prefixo + "nova licitacao: " + c.get("valor") + " por " + c.get("utilizador") + extra);
        } else {
            dizer(prefixo + "aviso do tipo " + c.get("tipo"));
        }
    }

    private static String resultado(Map<String, String> c) {
        String v = c.get("vencedor");
        return (v == null || v.equals("-")) ? "Sem vencedor."
                                            : "Vencedor: " + v + " com " + c.get("melhor") + ".";
    }

    /** nome=valor separados por espaço; pára no artigo (o resto da linha, pode ter espaços). */
    private static Map<String, String> campos(String texto) {
        Map<String, String> m = new HashMap<>();
        for (String t : texto.split(" ")) {
            if (t.startsWith("artigo=")) break;
            int i = t.indexOf('=');
            if (i > 0) m.put(t.substring(0, i), t.substring(i + 1));
        }
        return m;
    }

    /** Valor inteiro de um campo; -1 se faltar ou não for número. */
    private static int inteiro(Map<String, String> c, String nome) {
        try {
            return Integer.parseInt(c.get(nome));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static void dizer(String texto) {
        System.out.println(texto);
    }
}
