import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.rmi.RemoteException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Servidor de leilões (nível Completo).
 *
 * Uso:  java "-Djava.net.preferIPv4Stack=true" Servidor [porto] [historico] [grupo] [portoUdp] [portoRmi] [antiSniping]
 *       por omissão: 5050, historico.log, 239.255.0.1, 6060, 5051 e 30
 *       antiSniping = N segundos (0 desliga): licitação nos últimos N s adia o fecho para agora + N.
 *
 * Arquitetura:
 *   - thread principal: só faz accept() de novas ligações.
 *   - 1 thread por cliente, vinda de uma newCachedThreadPool (B1): há sempre
 *     thread para TODOS os clientes ligados ao mesmo tempo.
 *   - 1 thread "relogio" (I3): a cada 200 ms fecha os leilões vencidos.
 *     Atraso máximo de fecho: 0,2 s (o enunciado aceita até 1 s).
 *   - threads RMI (C1): atendem a consola de administração (registo no porto 5051).
 *     Chamam os MESMOS métodos do Estado que as threads TCP.
 *   - 1 objeto Estado partilhado por todas (protegido por lock, ver Estado)
 *     e 1 Difusor que envia os avisos multicast.
 */
public final class Servidor {

    /** Período do relógio de fecho, em milissegundos. */
    private static final long PERIODO_RELOGIO_MS = 200;

    @SuppressWarnings("try")   // close() explícito do ServerSocket no shutdown hook, de propósito
    public static void main(String[] args) {
        int porto = 5050;
        Path historico = Path.of("historico.log");
        String grupo = Multicast.GRUPO_OMISSAO;
        int portoUdp = Multicast.PORTO_OMISSAO;
        int portoRmi = 5051;
        int antiSniping = 30;
        try {
            if (args.length >= 1) porto = Integer.parseInt(args[0]);
            if (args.length >= 2) historico = Path.of(args[1]);
            if (args.length >= 3) grupo = args[2];
            if (args.length >= 4) portoUdp = Integer.parseInt(args[3]);
            if (args.length >= 5) portoRmi = Integer.parseInt(args[4]);
            if (args.length >= 6) antiSniping = Integer.parseInt(args[5]);
            Multicast.grupo(grupo);                          // valida o endereço do grupo
        } catch (IllegalArgumentException e) {               // inclui NumberFormatException
            System.err.println("Argumento invalido: " + e.getMessage());
            System.err.println("Uso: java Servidor [porto] [historico] [grupo] [portoUdp] [portoRmi] [antiSniping]");
            System.exit(1);
        }
        if (porto < 1024 || porto > 65535 || portoUdp < 1024 || portoUdp > 65535
                || portoRmi < 1024 || portoRmi > 65535) {
            System.err.println("Os portos tem de estar entre 1024 e 65535.");
            System.exit(1);
        }
        if (portoRmi == porto) {
            System.err.println("O porto RMI tem de ser diferente do porto TCP.");
            System.exit(1);
        }
        if (antiSniping < 0 || antiSniping > 86_400) {
            System.err.println("antiSniping: segundos de 0 (desligado) a 86400.");
            System.exit(1);
        }

        // Difusor multicast. Se falhar, o servidor continua sem avisos (o resto funciona).
        Difusor difusor = null;
        try {
            difusor = new Difusor(grupo, portoUdp);
        } catch (IOException e) {
            System.err.println("Aviso: multicast indisponivel (" + e.getMessage()
                    + "); o servidor continua sem avisos.");
        }
        final Difusor dif = difusor;
        Consumer<String> difusao = dif == null ? aviso -> { } : dif::enviar;

        final Estado estado;
        try {
            estado = new Estado(historico, difusao, antiSniping * 1000L);   // lê o histórico (B7)
        } catch (IOException e) {
            System.err.println("Nao foi possivel abrir o historico: " + e.getMessage());
            System.exit(1);
            return;
        }

        // I4: se o servidor esteve parado à hora de fecho, fecha e anuncia agora.
        int vencidos = estado.fecharVencidos();
        if (vencidos > 0) {
            System.out.println("Arranque: " + vencidos + " leilao(oes) vencido(s) fechado(s).");
        }

        // C1: consola de administração por RMI (o registo vive neste processo).
        try {
            ServicoAdmin.iniciar(estado, portoRmi);
        } catch (RemoteException e) {
            System.err.println("Nao foi possivel arrancar o RMI no porto " + portoRmi + ": " + e.getMessage());
            System.exit(1);
        }
        System.out.println("Anti-sniping: " + (antiSniping == 0 ? "desligado" : antiSniping + " s"));

        // I3: o relógio do servidor fecha os leilões à hora, sem pedido de ninguém.
        // Daemon: não impede a JVM de terminar. O try/catch é essencial: uma exceção
        // que escapasse da tarefa cancelava, em silêncio, todas as execuções seguintes.
        ScheduledExecutorService relogio = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "relogio");
            t.setDaemon(true);
            return t;
        });
        relogio.scheduleWithFixedDelay(() -> {
            try {
                estado.fecharVencidos();
            } catch (RuntimeException e) {
                System.err.println("[relogio] Erro: " + e);
            }
        }, PERIODO_RELOGIO_MS, PERIODO_RELOGIO_MS, TimeUnit.MILLISECONDS);

        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket servidor = new ServerSocket(porto)) {
            System.out.println("Servidor de leilões à escuta no porto " + porto
                    + " (historico: " + historico + ")");

            // Ctrl+C: pára o relógio, fecha socket (desbloqueia o accept), pool, multicast e ficheiro.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                relogio.shutdownNow();
                ServicoAdmin.parar();
                try { servidor.close(); } catch (IOException ignorado) { }
                pool.shutdown();
                if (dif != null) dif.close();
                estado.fechar();
                System.out.println("Servidor terminado.");
            }));

            while (true) {
                try {
                    Socket cliente = servidor.accept();         // bloqueia até haver cliente
                    pool.execute(new Atendedor(cliente, estado));
                } catch (SocketException e) {
                    if (servidor.isClosed()) break;             // encerramento normal
                    System.err.println("Erro no accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Nao foi possivel usar o porto " + porto + ": " + e.getMessage());
            System.exit(1);
        }
    }
}
