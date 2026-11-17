import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Teste de atomicidade do CANCELAMENTO (C1/G1): licitações por TCP a chegar
 * ao mesmo tempo que a consola cancela por RMI.
 *
 * Uso:  java TesteCancelamento [host] [portoTcp] [portoRmi] [licitantes]
 *
 * Verifica:
 *   1. nenhuma licitação ENVIADA depois de o cancelar() devolver recebe OK;
 *   2. nenhuma licitação que recebeu OK se perde (licitacoes no CONSULTAR = nº de OK);
 *   3. o leilão acaba FECHADO e com vencedor=-.
 */
public final class TesteCancelamento {

    private static final class Ligacao implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final BufferedWriter out;

        Ligacao(String host, int porto) throws IOException {
            s = new Socket(host, porto);
            s.setTcpNoDelay(true);
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }

        String pedir(String linha) throws IOException {
            out.write(linha + "\n");
            out.flush();
            return in.readLine();
        }

        @Override public void close() throws IOException { s.close(); }
    }

    private static volatile boolean parar = false;

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int tcp = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        int rmi = args.length > 2 ? Integer.parseInt(args[2]) : 5051;
        int licitantes = args.length > 3 ? Integer.parseInt(args[3]) : 10;
        String sufixo = Long.toString(System.nanoTime() % 100_000_000L, 36);

        int id;
        try (Ligacao c = new Ligacao(host, tcp)) {
            String criador = "cnc" + sufixo;
            c.pedir("REGISTAR " + criador + " pw");
            c.pedir("LOGIN " + criador + " pw");
            String r = c.pedir("CRIAR 0 1 300 Teste de cancelamento");
            if (!r.startsWith("OK id=")) { System.out.println("Falhou CRIAR: " + r); return; }
            id = Integer.parseInt(r.substring(6));
        }
        final int idLeilao = id;

        AtomicInteger proximoValor = new AtomicInteger();
        AtomicInteger recusadasFechado = new AtomicInteger();
        ConcurrentLinkedQueue<long[]> aceites = new ConcurrentLinkedQueue<>();   // {instante de envio}
        ConcurrentLinkedQueue<String> problemas = new ConcurrentLinkedQueue<>();
        CountDownLatch prontos = new CountDownLatch(licitantes);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < licitantes; i++) {
            final String nome = "lic" + i + "x" + sufixo;
            Thread t = new Thread(() -> {
                try (Ligacao c = new Ligacao(host, tcp)) {
                    c.pedir("REGISTAR " + nome + " pw");
                    c.pedir("LOGIN " + nome + " pw");
                    prontos.countDown();
                    while (!parar) {
                        int v = proximoValor.incrementAndGet();
                        long envio = System.nanoTime();               // ANTES de enviar
                        String r = c.pedir("LICITAR " + idLeilao + " " + v);
                        if (r.startsWith("OK n=")) aceites.add(new long[]{envio});
                        else if (r.startsWith("ERRO FECHADO")) recusadasFechado.incrementAndGet();
                        else if (!r.startsWith("ERRO BAIXA")) problemas.add(nome + ": " + r);
                    }
                } catch (Exception e) {
                    problemas.add(nome + ": " + e);
                }
            });
            threads.add(t);
            t.start();
        }
        prontos.await();
        Thread.sleep(500);                                             // deixa licitar um pouco

        AdminRemoto admin = (AdminRemoto) LocateRegistry.getRegistry(host, rmi).lookup("admin");
        admin.cancelar(idLeilao);
        long confirmacao = System.nanoTime();                          // a consola recebeu a confirmação

        Thread.sleep(500);                                             // continuam a licitar depois
        parar = true;
        for (Thread t : threads) t.join();

        boolean ok = problemas.isEmpty();
        problemas.forEach(p -> System.out.println("PROBLEMA " + p));

        long depois = aceites.stream().filter(a -> a[0] > confirmacao).count();
        if (depois > 0) {
            System.out.println("FALHA: " + depois + " licitacao(oes) enviada(s) apos a confirmacao receberam OK");
            ok = false;
        }
        try (Ligacao c = new Ligacao(host, tcp)) {
            String r = c.pedir("CONSULTAR " + idLeilao);
            System.out.println("CONSULTAR -> " + r);
            if (!r.contains(" licitacoes=" + aceites.size() + " ")) {
                System.out.println("FALHA: licitacoes aceites (" + aceites.size() + ") nao conferem");
                ok = false;
            }
            if (!r.contains(" estado=FECHADO ") || !r.contains(" vencedor=- ")) {
                System.out.println("FALHA: o leilao devia estar FECHADO e sem vencedor");
                ok = false;
            }
        }
        System.out.println(licitantes + " licitantes; " + aceites.size() + " aceites antes do cancelamento; "
                + recusadasFechado.get() + " recusadas com FECHADO depois.");
        System.out.println(ok ? "PASSA" : "FALHA");
        System.exit(ok ? 0 : 1);
    }
}
