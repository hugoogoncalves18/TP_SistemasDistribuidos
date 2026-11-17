import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.UnicastRemoteObject;

/**
 * Arranca e pára o serviço RMI de administração DENTRO do processo do servidor
 * (o registo RMI é criado aqui, num porto diferente do TCP: 5051 por omissão).
 *
 * Passos (como o KVServidorRMI): criar o objeto, exportá-lo (porto 0 = qualquer
 * porto livre), criar o registo e registar o stub com o nome "admin".
 */
final class ServicoAdmin {

    // Referências FORTES: sem elas o coletor de lixo podia apagar o objeto
    // exportado e a consola receberia NoSuchObjectException.
    private static AdminImpl objeto;
    private static Registry registo;

    private ServicoAdmin() { }

    static synchronized void iniciar(Estado estado, int porto) throws RemoteException {
        // Sem isto o stub pode apontar para um endereço que a consola não alcança.
        // Para administrar a partir de OUTRA máquina: -Djava.rmi.server.hostname=<IP do servidor>.
        if (System.getProperty("java.rmi.server.hostname") == null) {
            System.setProperty("java.rmi.server.hostname", "localhost");
        }
        objeto = new AdminImpl(estado);
        AdminRemoto stub = (AdminRemoto) UnicastRemoteObject.exportObject(objeto, 0);
        registo = LocateRegistry.createRegistry(porto);
        registo.rebind("admin", stub);
        System.out.println("Administracao RMI pronta: registo no porto " + porto + ", objeto \"admin\".");
    }

    static synchronized void parar() {
        try {
            if (registo != null) {
                registo.unbind("admin");
                UnicastRemoteObject.unexportObject(registo, true);
            }
            if (objeto != null) UnicastRemoteObject.unexportObject(objeto, true);
        } catch (RemoteException | NotBoundException ignorado) {
            // a terminar: não há nada a fazer
        }
    }
}
