import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;

/**
 * A INTERFACE REMOTA da consola de administração (C1): o "contrato" entre a
 * ConsolaAdmin e o servidor. Cada operação é um método (como o KVRemoto).
 *
 * Regras (as mesmas do KVRemoto): estende java.rmi.Remote e TODOS os métodos
 * declaram "throws RemoteException". Strings e List são passadas por valor
 * (copiadas por serialização); ErroProtocolo é serializável (é uma Exception).
 *
 * OPERAÇÕES (para o relatório):
 *   listarLeiloes()           -> um registo por leilão (o mesmo formato do Anexo A.4,
 *                                sem o prefixo "LEILAO "), por id crescente
 *   utilizadoresComSessao()   -> utilizadores com sessão iniciada agora
 *   cancelar(id)              -> fecha o leilão aberto SEM vencedor. Erros (ErroProtocolo):
 *                                INEXISTENTE se não existe; FECHADO se já fechou/foi cancelado
 */
public interface AdminRemoto extends Remote {

    List<String> listarLeiloes() throws RemoteException;

    List<String> utilizadoresComSessao() throws RemoteException;

    void cancelar(int id) throws RemoteException, ErroProtocolo;
}
