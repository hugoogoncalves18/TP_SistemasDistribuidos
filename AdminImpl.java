import java.rmi.RemoteException;
import java.util.List;

/**
 * Implementação do objeto remoto de administração. NÃO guarda estado: só
 * chama os MESMOS métodos do Estado que as threads TCP chamam (pista do
 * enunciado). Como esses métodos são synchronized no mesmo lock, um cancelamento
 * (thread RMI) e uma licitação (thread TCP) nunca se intercalam: ganha quem
 * entrar primeiro no lock.
 *   - licitação primeiro: recebe OK, fica gravada, e o cancelamento fecha depois
 *     (a licitação não se perde; o leilão fica sem vencedor);
 *   - cancelamento primeiro: a licitação recebe ERRO FECHADO.
 * Depois de o cancelar() devolver (a consola recebe a confirmação), nenhuma
 * licitação é aceite nesse leilão.
 *
 * Não herda de UnicastRemoteObject: quem a exporta é o ServicoAdmin.
 */
public class AdminImpl implements AdminRemoto {

    private final Estado estado;

    AdminImpl(Estado estado) {
        this.estado = estado;
    }

    /** Escreve no terminal do servidor, com o nome da thread RMI que executa a chamada. */
    private static void log(String texto) {
        System.out.println("[" + Thread.currentThread().getName() + "] ADMIN " + texto);
    }

    @Override
    public List<String> listarLeiloes() throws RemoteException {
        log("listarLeiloes");
        return estado.listar();
    }

    @Override
    public List<String> utilizadoresComSessao() throws RemoteException {
        log("utilizadoresComSessao");
        return estado.utilizadoresComSessao();
    }

    @Override
    public void cancelar(int id) throws RemoteException, ErroProtocolo {
        log("cancelar " + id);
        estado.cancelar(id);
    }
}
