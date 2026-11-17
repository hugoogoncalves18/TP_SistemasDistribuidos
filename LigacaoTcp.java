import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A ligação TCP do cliente ao servidor, partilhada por DUAS threads:
 *   - a thread principal (comandos escritos pelo utilizador);
 *   - a thread dos avisos multicast (Acompanhamento), que pede o ESTADO
 *     quando deteta uma perda.
 *
 * O protocolo é "um pedido, uma resposta, pela ordem": se as duas threads
 * escrevessem ao mesmo tempo, as respostas trocavam-se. Por isso pedir() é
 * synchronized: cada par pedido/resposta é feito de uma vez, sem intercalar.
 */
final class LigacaoTcp implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final BufferedWriter out;

    LigacaoTcp(String host, int porto) throws IOException {
        socket = new Socket(host, porto);
        socket.setTcpNoDelay(true);   // pedidos pequenos: não esperar para enviar
        in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    /**
     * Envia uma linha e devolve a resposta (uma linha; no LISTAR, "OK n" e as
     * n linhas seguintes, separadas por \n). EOFException se o servidor fechar.
     */
    synchronized String pedir(String linha) throws IOException {
        out.write(linha);
        out.write("\n");
        out.flush();

        String resposta = in.readLine();
        if (resposta == null) throw new EOFException("o servidor fechou a ligacao");

        if (linha.equals("LISTAR") && resposta.startsWith("OK ")) {
            StringBuilder sb = new StringBuilder(resposta);
            try {
                int n = Integer.parseInt(resposta.substring(3).trim());
                for (int i = 0; i < n; i++) {
                    String l = in.readLine();
                    if (l == null) throw new EOFException("o servidor fechou a ligacao");
                    sb.append('\n').append(l);
                }
            } catch (NumberFormatException ignorado) { }
            return sb.toString();
        }
        return resposta;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
