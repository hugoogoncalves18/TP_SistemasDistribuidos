import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

/**
 * Teste de concorrência (B5), só com comandos do Anexo A.
 *
 * Uso:  java TesteConcorrencia [host] [porto] [clientes] [valoresPorCliente]
 *
 * T clientes (cada um com a sua ligação) licitam AO MESMO TEMPO no mesmo
 * leilão (base 0, incremento 1), todos com os valores 1, 2, 3, ..., V.
 * No fim verifica três propriedades:
 *   1. os números de ordem n das licitações aceites são únicos e vão de 1 a K
 *      (nenhuma se perdeu nem duplicou);
 *   2. ordenadas por n, os valores crescem pelo menos o incremento
 *      (nenhuma se sobrepôs / foi avaliada contra estado antigo);
 *   3. CONSULTAR confirma licitacoes=K e melhor = último valor aceite.
 */
public final class TesteConcorrencia {

    /** Uma ligação TCP com pedido/resposta de uma linha. */
    private static final class Ligacao implements AutoCloseable {
        final Socket s;
        final BufferedReader in;
        final BufferedWriter out;

        Ligacao(String host, int porto) throws IOException {
            s = new Socket(host, porto);
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

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int porto = args.length > 1 ? Integer.parseInt(args[1]) : 5050;
        int clientes = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        int valores = args.length > 3 ? Integer.parseInt(args[3]) : 100;

        // Sufixo único para poder repetir o teste sem colidir com execuções anteriores.
        String sufixo = Long.toString(System.nanoTime() % 100_000_000L, 36);

        // --- preparação: criador + leilão ---
        int idLeilao;
        try (Ligacao c = new Ligacao(host, porto)) {
            String criador = "crt" + sufixo;
            c.pedir("REGISTAR " + criador + " pw");
            c.pedir("LOGIN " + criador + " pw");
            String r = c.pedir("CRIAR 0 1 300 Teste de concorrencia");
            if (!r.startsWith("OK id=")) { System.out.println("Falhou CRIAR: " + r); return; }
            idLeilao = Integer.parseInt(r.substring(6));
        }

        // --- os licitantes ---
        ConcurrentLinkedQueue<int[]> aceites = new ConcurrentLinkedQueue<>(); // {n, valor}
        CountDownLatch pronto = new CountDownLatch(clientes);   // todos ligados e com login
        CountDownLatch partida = new CountDownLatch(1);         // tiro de partida comum
        List<Thread> threads = new ArrayList<>();
        ConcurrentLinkedQueue<String> problemas = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < clientes; i++) {
            final String nome = "lic" + i + "x" + sufixo;
            Thread t = new Thread(() -> {
                try (Ligacao c = new Ligacao(host, porto)) {
                    c.pedir("REGISTAR " + nome + " pw");
                    c.pedir("LOGIN " + nome + " pw");
                    pronto.countDown();
                    partida.await();
                    for (int v = 1; v <= valores; v++) {
                        String r = c.pedir("LICITAR " + idLeilao + " " + v);
                        if (r.startsWith("OK n=")) {
                            aceites.add(new int[]{Integer.parseInt(r.substring(5)), v});
                        } else if (!r.startsWith("ERRO BAIXA")) {
                            problemas.add(nome + ": resposta inesperada: " + r);
                        }
                    }
                } catch (Exception e) {
                    problemas.add(nome + ": " + e);
                }
            });
            threads.add(t);
            t.start();
        }
        pronto.await();
        partida.countDown();                       // todos disparam ao mesmo tempo
        for (Thread t : threads) t.join();

        // --- verificação ---
        List<int[]> lista = new ArrayList<>(aceites);
        lista.sort(Comparator.comparingInt(a -> a[0]));
        boolean ok = problemas.isEmpty();
        problemas.forEach(p -> System.out.println("PROBLEMA " + p));

        for (int k = 0; k < lista.size(); k++) {
            if (lista.get(k)[0] != k + 1) {
                System.out.println("FALHA: n em falta ou repetido junto a " + (k + 1));
                ok = false;
                break;
            }
            if (k > 0 && lista.get(k)[1] < lista.get(k - 1)[1] + 1) {
                System.out.println("FALHA: licitacao n=" + (k + 1) + " nao respeita o incremento");
                ok = false;
                break;
            }
        }

        try (Ligacao c = new Ligacao(host, porto)) {
            String r = c.pedir("CONSULTAR " + idLeilao);
            int ultimo = lista.isEmpty() ? 0 : lista.get(lista.size() - 1)[1];
            if (!r.contains(" licitacoes=" + lista.size() + " ")
                    || !r.contains(" melhor=" + ultimo + " ")) {
                System.out.println("FALHA: CONSULTAR nao confere: " + r);
                ok = false;
            }
            System.out.println("CONSULTAR -> " + r);
        }

        System.out.println(clientes + " clientes x " + valores + " valores; "
                + lista.size() + " licitacoes aceites.");
        System.out.println(ok ? "PASSA" : "FALHA");
        System.exit(ok ? 0 : 1);
    }
}
