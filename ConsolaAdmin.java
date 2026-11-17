import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.util.List;

/**
 * Consola de administração (C1): processo SEPARADO do servidor, por Java RMI.
 * Não guarda estado: cada comando é uma chamada remota ao servidor.
 *
 * Uso:  java ConsolaAdmin [host] [portoRmi]     (por omissão localhost e 5051)
 *
 * Comandos:  leiloes | utilizadores | cancelar <id> | ajuda
 * Para sair, feche a entrada (Ctrl+D em Linux/macOS, Ctrl+Z e Enter em Windows).
 *
 * Se o servidor cair e voltar, o stub antigo deixa de servir: a consola
 * volta a fazer o lookup no comando seguinte.
 */
public final class ConsolaAdmin {

    public static void main(String[] args) {
        String host = "localhost";
        int porto = 5051;
        try {
            if (args.length >= 1) host = args[0];
            if (args.length >= 2) porto = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Porto invalido. Uso: java ConsolaAdmin [host] [portoRmi]");
            System.exit(1);
        }

        AdminRemoto admin = obter(host, porto);
        if (admin == null) System.exit(1);

        System.out.println("Ligado ao registo RMI " + host + ":" + porto + ". Comandos: leiloes | utilizadores | cancelar <id> | ajuda");
        System.out.println("Sair: Ctrl+D (Linux/macOS) ou Ctrl+Z+Enter (Windows).");

        try (BufferedReader teclado = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = teclado.readLine()) != null) {
                if (admin == null) admin = obter(host, porto);     // tenta recuperar
                if (admin == null) continue;
                try {
                    executar(admin, linha);
                } catch (ErroProtocolo e) {
                    System.out.println(e.comoResposta());
                } catch (RemoteException e) {
                    System.out.println("Falha na chamada remota (o servidor esta em baixo?): " + e.getMessage());
                    admin = null;                                   // força novo lookup
                }
            }
        } catch (IOException e) {
            System.err.println("Erro a ler o teclado: " + e.getMessage());
        }
    }

    /** Lookup do objeto "admin" no registo RMI; null (com mensagem) se falhar. */
    private static AdminRemoto obter(String host, int porto) {
        try {
            return (AdminRemoto) LocateRegistry.getRegistry(host, porto).lookup("admin");
        } catch (NotBoundException e) {
            System.err.println("O servidor nao registou o objeto \"admin\".");
        } catch (RemoteException e) {
            System.err.println("Nao foi possivel ligar ao registo RMI " + host + ":" + porto
                    + " - o servidor esta em baixo? (" + e.getMessage() + ")");
        }
        return null;
    }

    private static void executar(AdminRemoto admin, String linha) throws RemoteException, ErroProtocolo {
        String[] p = linha.trim().split("\\s+");
        switch (p[0].toLowerCase()) {
            case "" -> { }
            case "leiloes" -> {
                List<String> l = admin.listarLeiloes();
                System.out.println(l.size() + " leilao(oes)");
                l.forEach(r -> System.out.println("  " + r));
            }
            case "utilizadores" -> {
                List<String> u = admin.utilizadoresComSessao();
                System.out.println(u.size() + " utilizador(es) com sessao" + (u.isEmpty() ? "" : ": " + String.join(", ", u)));
            }
            case "cancelar" -> {
                if (p.length != 2) { System.out.println("Uso: cancelar <id>"); return; }
                try {
                    admin.cancelar(Integer.parseInt(p[1]));
                    System.out.println("OK leilao " + p[1] + " cancelado (sem vencedor)");
                } catch (NumberFormatException e) {
                    System.out.println("Uso: cancelar <id>");
                }
            }
            case "ajuda" -> System.out.println("leiloes | utilizadores | cancelar <id>");
            default -> System.out.println("Comando invalido. Use: leiloes | utilizadores | cancelar <id>");
        }
    }
}
