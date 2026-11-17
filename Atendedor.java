import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Uma instância por ligação TCP (corre numa thread da pool do Servidor).
 * Lê uma linha, valida-a segundo o Anexo A, chama o Estado e responde.
 *
 * Variável de sessão desta ligação: "utilizador" (null = sem login).
 * É só desta thread, por isso não precisa de proteção.
 *
 * REGRA DE OURO: um pedido mal formado ou fora de sequência recebe um ERRO
 * e a ligação continua. O servidor nunca cai por causa de um cliente.
 */
final class Atendedor implements Runnable {

    /** Máximo de caracteres de uma linha (A.1); acima disso é SINTAXE. */
    private static final int MAX_LINHA = 1024;

    private final Socket socket;
    private final Estado estado;

    private String utilizador = null;   // sessão: preenchido pelo LOGIN
    private boolean terminar = false;   // ligado pelo SAIR

    Atendedor(Socket socket, Estado estado) {
        this.socket = socket;
        this.estado = estado;
    }

    @Override
    public void run() {
        // Cada linha do log começa pelo nome da thread, para se ver que clientes
        // diferentes são atendidos por threads diferentes (como no ClientHandler).
        String nome = Thread.currentThread().getName();
        String cliente = String.valueOf(socket.getRemoteSocketAddress());
        System.out.println("[" + nome + "] Cliente ligado: " + cliente);
        // try-with-resources: socket e streams são sempre fechados (G8).
        try (Socket s = socket;
             BufferedReader in = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(
                     new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {

            // Pedidos e respostas pequenos: sem isto o algoritmo de Nagle pode
            // atrasar cada resposta uns 40 ms (importante nos testes de carga).
            s.setTcpNoDelay(true);

            while (!terminar) {
                String resposta;
                try {
                    String linha = lerLinha(in);
                    if (linha == null) break;               // cliente fechou a ligação
                    resposta = processar(linha);
                } catch (ErroProtocolo e) {
                    resposta = e.comoResposta();            // erro normal: responde e segue
                } catch (RuntimeException e) {
                    // Bug nosso: o servidor não cai, o cliente recebe INTERNO.
                    System.err.println("[" + nome + "] Erro interno: " + e);
                    resposta = "ERRO INTERNO falha do servidor";
                }
                // Enviamos sempre \n (nunca \r\n), como pede A.1.
                out.write(resposta);
                out.write("\n");
                out.flush();
            }
        } catch (IOException e) {
            // Ligação perdida (reset, etc.): só termina a thread deste cliente.
            System.err.println("[" + nome + "] Erro na ligação com " + cliente + ": " + e.getMessage());
        } finally {
            if (utilizador != null) estado.terminarSessao(utilizador);   // fim da sessão (SAIR ou queda)
            System.out.println("[" + nome + "] Cliente desligou: " + cliente);
        }
    }

    // =====================================================================
    //  LEITURA DE LINHAS
    // =====================================================================

    /**
     * Lê uma linha terminada em \n (aceita \r\n). Devolve null no fim da ligação.
     * Linha com mais de 1024 caracteres: lança SINTAXE, mas só depois de
     * consumir a linha toda, para o pedido seguinte começar limpo.
     * Não usa readLine() porque este aceitaria linhas gigantes (memória!).
     */
    private static String lerLinha(BufferedReader in) throws IOException, ErroProtocolo {
        StringBuilder sb = new StringBuilder();
        boolean demasiadoLonga = false;
        boolean terminada = false;
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') { terminada = true; break; }
            if (sb.length() < 2 * MAX_LINHA + 2) sb.append((char) c);
            else demasiadoLonga = true;                  // continua a ler mas não guarda
        }
        if (!terminada) return null;                      // EOF (linha incompleta é ignorada)

        // Retira UM \r final (caso \r\n).
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
            sb.setLength(sb.length() - 1);
        }
        // O limite conta caracteres (code points), não bytes.
        if (demasiadoLonga || sb.codePointCount(0, sb.length()) > MAX_LINHA) {
            throw new ErroProtocolo("SINTAXE", "linha demasiado longa");
        }
        return sb.toString();
    }

    // =====================================================================
    //  PROCESSAMENTO DE COMANDOS
    // =====================================================================

    /** Devolve a resposta completa (pode ter várias linhas, no LISTAR). */
    private String processar(String linha) throws ErroProtocolo {
        if (linha.isEmpty()) throw sintaxe("linha vazia");

        // Comando = tudo até ao primeiro espaço.
        int esp = linha.indexOf(' ');
        String cmd = esp < 0 ? linha : linha.substring(0, esp);

        // CRIAR é a exceção: o artigo (resto da linha) pode ter espaços.
        if (cmd.equals("CRIAR")) return criar(linha);

        // Nos outros comandos, os campos são separados por UM espaço.
        // split com limite -1 preserva campos vazios: "A  B", " A" ou "A "
        // dão campos vazios -> SINTAXE (espaços a mais).
        String[] p = linha.split(" ", -1);
        for (String campo : p) {
            if (campo.isEmpty()) throw sintaxe("espacos a mais");
        }

        return switch (cmd) {
            case "REGISTAR"  -> registar(p);
            case "LOGIN"     -> login(p);
            case "LISTAR"    -> listar(p);
            case "CONSULTAR" -> consultar(p);
            case "ESTADO"    -> estadoLeilao(p);
            case "LICITAR"   -> licitar(p);
            case "SAIR"      -> sair(p);
            default          -> throw sintaxe("comando desconhecido");  // inclui minúsculas
        };
    }

    // ---- REGISTAR <utilizador> <palavra-passe> ------ SINTAXE, VALOR, EXISTE
    private String registar(String[] p) throws ErroProtocolo {
        exigirCampos(p, 3);
        if (!utilizadorValido(p[1]) || !palavraPasseValida(p[2])) {
            throw new ErroProtocolo("VALOR", "utilizador ou palavra-passe invalidos");
        }
        estado.registar(p[1], p[2]);     // lança EXISTE se já existir
        return "OK";                     // registar NÃO inicia sessão
    }

    // ---- LOGIN <utilizador> <palavra-passe> ---- SINTAXE, JA_AUTENTICADO, CREDENCIAIS
    private String login(String[] p) throws ErroProtocolo {
        exigirCampos(p, 3);
        if (utilizador != null) {
            throw new ErroProtocolo("JA_AUTENTICADO", "ja tem sessao iniciada");
        }
        if (!estado.autenticar(p[1], p[2])) {
            throw new ErroProtocolo("CREDENCIAIS", "utilizador ou palavra-passe errados");
        }
        utilizador = p[1];               // a sessão dura até SAIR ou fim da ligação
        estado.iniciarSessao(utilizador);   // para a consola de administração (C1)
        return "OK";
    }

    // ---- CRIAR <base> <incremento> <duração> <artigo> ---- SINTAXE, SEM_SESSAO, VALOR
    private String criar(String linha) throws ErroProtocolo {
        // Limite 5 -> [CRIAR, base, incremento, duracao, artigo-com-espaços].
        String[] p = linha.split(" ", 5);
        if (p.length < 5 || p[1].isEmpty() || p[2].isEmpty() || p[3].isEmpty()) {
            throw sintaxe("numero de campos errado");
        }
        exigirSessao();
        long base = numero(p[1], 0, 999_999_999L);
        long incremento = numero(p[2], 1, 999_999_999L);
        long duracao = numero(p[3], 1, 86_400L);
        String artigo = p[4];
        if (!artigoValido(artigo)) {
            throw new ErroProtocolo("VALOR", "artigo invalido");
        }
        int id = estado.criar(utilizador, base, incremento, duracao, artigo);
        return "OK id=" + id;
    }

    // ---- LISTAR ---- SINTAXE
    private String listar(String[] p) throws ErroProtocolo {
        exigirCampos(p, 1);
        List<String> linhas = estado.listar();
        StringBuilder sb = new StringBuilder("OK ").append(linhas.size());
        for (String l : linhas) sb.append("\nLEILAO ").append(l);
        return sb.toString();
    }

    // ---- CONSULTAR <id> ---- SINTAXE, VALOR, INEXISTENTE
    private String consultar(String[] p) throws ErroProtocolo {
        exigirCampos(p, 2);
        int id = (int) numero(p[1], 1, 999_999_999L);
        return "OK " + estado.consultar(id);
    }

    // ---- ESTADO <id> ---- EXTENSÃO NOSSA (I2) ---- SINTAXE, VALOR, INEXISTENTE
    // Igual ao CONSULTAR, com "seq=<k>" à frente. Não altera nenhum comando do Anexo A.
    private String estadoLeilao(String[] p) throws ErroProtocolo {
        exigirCampos(p, 2);
        int id = (int) numero(p[1], 1, 999_999_999L);
        return "OK " + estado.estadoComSeq(id);
    }

    // ---- LICITAR <id> <valor> ---- SINTAXE, SEM_SESSAO, VALOR, INEXISTENTE, FECHADO, PROPRIO, BAIXA
    private String licitar(String[] p) throws ErroProtocolo {
        exigirCampos(p, 3);
        exigirSessao();
        int id = (int) numero(p[1], 1, 999_999_999L);
        long valor = numero(p[2], 0, 999_999_999L);
        int n = estado.licitar(utilizador, id, valor);   // região crítica
        return "OK n=" + n;
    }

    // ---- SAIR ---- SINTAXE
    private String sair(String[] p) throws ErroProtocolo {
        exigirCampos(p, 1);
        terminar = true;                 // responde OK e depois fecha; o resto é ignorado
        return "OK";
    }

    // =====================================================================
    //  VALIDAÇÕES (A.2)
    // =====================================================================

    private static ErroProtocolo sintaxe(String motivo) {
        return new ErroProtocolo("SINTAXE", motivo);
    }

    /** Número de campos (incluindo o comando) tem de ser exatamente n. */
    private static void exigirCampos(String[] p, int n) throws ErroProtocolo {
        if (p.length != n) throw sintaxe("numero de campos errado");
    }

    private void exigirSessao() throws ErroProtocolo {
        if (utilizador == null) throw new ErroProtocolo("SEM_SESSAO", "faca LOGIN primeiro");
    }

    /**
     * Inteiro decimal sem sinal com 1 a 9 dígitos, dentro de [min, max].
     * Qualquer outra coisa (letras, sinal, vazio, fora do intervalo) -> VALOR.
     * Verifica '0'..'9' à mão porque Character.isDigit aceitaria dígitos Unicode.
     */
    private static long numero(String s, long min, long max) throws ErroProtocolo {
        if (s.length() < 1 || s.length() > 9) throw valor();
        long v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') throw valor();
            v = v * 10 + (c - '0');
        }
        if (v < min || v > max) throw valor();
        return v;
    }

    private static ErroProtocolo valor() {
        return new ErroProtocolo("VALOR", "valor invalido");
    }

    /** utilizador: 3 a 20 caracteres em A-Z a-z 0-9 _ */
    private static boolean utilizadorValido(String s) {
        if (s.length() < 3 || s.length() > 20) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    /** palavra-passe: 1 a 64 caracteres, sem espaços nem caracteres de controlo. */
    private static boolean palavraPasseValida(String s) {
        int n = s.codePointCount(0, s.length());
        if (n < 1 || n > 64) return false;
        return semEspacosNemControlo(s);
    }

    /** artigo: 1 a 60 caracteres, sem espaço no início/fim, sem controlo. */
    private static boolean artigoValido(String s) {
        int n = s.codePointCount(0, s.length());
        if (n < 1 || n > 60) return false;
        if (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ') return false;
        // Espaços DENTRO do artigo são permitidos; controlo não.
        return s.chars().noneMatch(Character::isISOControl);
    }

    private static boolean semEspacosNemControlo(String s) {
        return s.chars().noneMatch(ch -> ch == ' ' || Character.isISOControl(ch));
    }
}
