import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Cliente de consola (nível Intermédio).
 *
 * Uso:  java -Djava.net.preferIPv4Stack=true Cliente [host] [porto] [grupo] [portoUdp] [descartarN]
 *       por omissão: localhost 5050 239.255.0.1 6060 0
 *       descartarN = N > 0: simula perdas, deitando fora 1 de cada N avisos recebidos.
 *
 * Tudo o que se escreve é enviado ao servidor tal como está (comandos do
 * Anexo A e a extensão ESTADO <id>), exceto as linhas que começam por "/",
 * que são comandos LOCAIS do cliente e nunca saem para a rede:
 *     /seguir <id>   acompanha o leilão: mostra os avisos multicast em tempo real
 *     /parar <id>    deixa de acompanhar
 *     /ajuda
 *
 * Duas threads (como o ChatClient): esta (teclado -> servidor por TCP) e a
 * dos avisos (multicast -> ecrã), ver Acompanhamento. Partilham a LigacaoTcp.
 */
public final class Cliente {

    public static void main(String[] args) {
        String host = "localhost";
        int porto = 5050;
        String grupo = Multicast.GRUPO_OMISSAO;
        int portoUdp = Multicast.PORTO_OMISSAO;
        int descartarN = 0;
        try {
            if (args.length >= 1) host = args[0];
            if (args.length >= 2) porto = Integer.parseInt(args[1]);
            if (args.length >= 3) grupo = args[2];
            if (args.length >= 4) portoUdp = Integer.parseInt(args[3]);
            if (args.length >= 5) descartarN = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            System.err.println("Argumento numerico invalido. Uso: java Cliente [host] [porto] [grupo] [portoUdp] [descartarN]");
            System.exit(1);
        }

        // try-with-resources: ligação e teclado fechados sempre (G8).
        try (LigacaoTcp tcp = new LigacaoTcp(host, porto);
             BufferedReader teclado = new BufferedReader(
                     new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            Acompanhamento acompanhamento = new Acompanhamento(tcp, grupo, portoUdp, descartarN);

            System.out.println("Ligado a " + host + ":" + porto
                    + ". Comandos: REGISTAR LOGIN CRIAR LISTAR CONSULTAR LICITAR ESTADO SAIR");
            System.out.println("Locais: /seguir <id>  /parar <id>  /ajuda");
            System.out.println("Sair: SAIR, Ctrl+D (Linux/macOS) ou Ctrl+Z+Enter (Windows).");

            String linha;
            while ((linha = teclado.readLine()) != null) {
                if (linha.startsWith("/")) {
                    comandoLocal(linha, acompanhamento);
                    continue;
                }
                System.out.println(tcp.pedir(linha));
                if (linha.equals("SAIR")) break;       // o servidor fecha a ligação
            }
            acompanhamento.terminar();

        } catch (UnknownHostException e) {
            System.err.println("Não foi possível encontrar o servidor: " + host);
            System.exit(1);
        } catch (ConnectException e) {
            System.err.println("Não foi possível ligar a " + host + ":" + porto
                    + " - o servidor está em baixo? (" + e.getMessage() + ")");
            System.exit(1);
        } catch (EOFException e) {
            System.err.println("O servidor fechou a ligação.");
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Erro de I/O na ligação a " + host + ":" + porto + ": " + e.getMessage());
            System.exit(1);
        }
    }

    /** Comandos que começam por "/": tratados aqui, sem ir ao servidor. */
    private static void comandoLocal(String linha, Acompanhamento acompanhamento) throws IOException {
        String[] p = linha.trim().split("\\s+");
        try {
            switch (p[0]) {
                case "/seguir" -> acompanhamento.seguir(Integer.parseInt(p[1]));
                case "/parar" -> acompanhamento.parar(Integer.parseInt(p[1]));
                case "/ajuda" -> System.out.println(
                        "/seguir <id>: mostra os avisos do leilao em tempo real; /parar <id>: deixa de mostrar");
                default -> System.out.println("Comando local desconhecido. Use /ajuda.");
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            System.out.println("Uso: " + p[0] + " <id>");
        }
    }
}
