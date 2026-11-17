import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;

/**
 * Emissor dos avisos multicast UDP (I1). Um datagrama por aviso.
 *
 * FORMATO DO AVISO (extensão nossa, documentada no relatório). Texto UTF-8,
 * campos nome=valor separados por um espaço; o tipo vem logo a seguir a AVISO:
 *
 *   AVISO tipo=LICITACAO leilao=<id> seq=<n> valor=<valor> utilizador=<quem>
 *   AVISO tipo=FECHO     leilao=<id> seq=<n> vencedor=<quem ou -> melhor=<valor>
 *
 * seq é o número de sequência POR LEILÃO: 1, 2, 3... um por licitação aceite
 * e um para o fecho (que é sempre licitacoes + 1). Um recetor que veja um seq
 * maior que o último visto + 1 sabe que perdeu avisos (UDP não garante entrega).
 *
 * O envio não bloqueia nem falha o pedido: se o datagrama se perder, o cliente
 * recupera o estado por TCP (comando ESTADO).
 */
final class Difusor implements AutoCloseable {

    private final MulticastSocket socket;
    private final InetAddress grupo;
    private final int porto;

    Difusor(String grupo, int porto) throws IOException {
        this.grupo = Multicast.grupo(grupo);
        this.porto = porto;
        this.socket = new MulticastSocket();      // porto de origem qualquer
        this.socket.setTimeToLive(1);             // não sai da rede local
        NetworkInterface ni = Multicast.interfaceEscolhida();
        if (ni != null) this.socket.setNetworkInterface(ni);
        System.out.println("Multicast: grupo " + grupo + ", porto UDP " + porto
                + ", interface " + (ni == null ? "(por omissao)" : ni.getName()));
    }

    /** Envia um aviso. Erros só são registados: um aviso perdido não é um erro do pedido. */
    void enviar(String texto) {
        try {
            byte[] dados = texto.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(dados, dados.length, grupo, porto));
        } catch (IOException e) {
            System.err.println("Aviso multicast nao enviado: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        socket.close();
    }
}
