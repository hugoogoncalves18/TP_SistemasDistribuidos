import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;

/**
 * Constantes e funções comuns ao emissor (Difusor, no servidor) e aos
 * recetores (Acompanhamento, no cliente) do multicast UDP (I1).
 *
 * Servidor e clientes têm de usar a MESMA interface de rede, senão numa
 * máquina com várias placas (Wi-Fi, Docker, VPN...) o emissor envia por uma
 * e o recetor escuta noutra, e os avisos nunca chegam. Por isso a escolha
 * está aqui, num único sítio.
 *
 * Correr SEMPRE com -Djava.net.preferIPv4Stack=true (enunciado, secção 4).
 * Para forçar uma interface: -Dmulticast.interface=<nome ou endereço IP>.
 */
final class Multicast {

    /** Grupo e porto UDP por omissão (enunciado, I1): um grupo para todos os leilões. */
    static final String GRUPO_OMISSAO = "239.255.0.1";
    static final int PORTO_OMISSAO = 6060;

    private Multicast() { }

    /** Converte o texto num endereço de grupo; lança IllegalArgumentException se não for multicast. */
    static InetAddress grupo(String texto) {
        try {
            InetAddress a = InetAddress.getByName(texto);
            if (!a.isMulticastAddress()) {
                throw new IllegalArgumentException(texto + " nao e um endereco multicast (224.0.0.0 a 239.255.255.255)");
            }
            return a;
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("endereco invalido: " + texto);
        }
    }

    /**
     * Interface de rede para o multicast. Ordem: a pedida em
     * -Dmulticast.interface; senão a primeira interface ativa, com
     * multicast e IPv4, que não seja loopback; senão a loopback.
     * Pode devolver null (o sistema decide).
     */
    static NetworkInterface interfaceEscolhida() throws SocketException {
        String pedido = System.getProperty("multicast.interface");
        if (pedido != null) {
            NetworkInterface ni = NetworkInterface.getByName(pedido);
            if (ni == null) {
                try {
                    ni = NetworkInterface.getByInetAddress(InetAddress.getByName(pedido));
                } catch (UnknownHostException ignorado) { }
            }
            if (ni == null) throw new SocketException("interface desconhecida: " + pedido);
            return ni;
        }
        NetworkInterface loopback = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || !ni.supportsMulticast() || !temIPv4(ni)) continue;
            if (ni.isLoopback()) { loopback = ni; continue; }
            return ni;
        }
        return loopback;
    }

    private static boolean temIPv4(NetworkInterface ni) {
        return Collections.list(ni.getInetAddresses()).stream().anyMatch(a -> a instanceof Inet4Address);
    }
}
