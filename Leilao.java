/**
 * Um leilão (secção 2 do enunciado): identificador, artigo, preço base,
 * incremento mínimo, criador e hora de fecho.
 *
 * ATENÇÃO À CONCORRÊNCIA: os campos mutáveis (melhor, vencedor, licitacoes)
 * NÃO têm proteção própria. Só podem ser lidos/alterados dentro de métodos
 * synchronized da classe Estado, que é quem tem o "cadeado" de todo o estado
 * partilhado. Assim há um único sítio a vigiar (e a explicar na defesa).
 */
final class Leilao {

    // ---- Campos imutáveis (definidos na criação) ----
    final int id;
    final String artigo;
    final long base;          // preço base (0 .. 999 999 999)
    final long incremento;    // incremento mínimo (>= 1)
    final String criador;
    /**
     * Hora de fecho em milissegundos desde 1970, pelo relógio DO SERVIDOR.
     * Deixou de ser final por causa do anti-sniping (C2): pode ser ADIADA,
     * nunca antecipada. Só se altera dentro do lock de Estado.
     */
    long fechoMs;

    // ---- Estado mutável (só dentro do lock de Estado) ----
    long melhor = 0;          // melhor valor aceite; 0 se não há licitações
    String vencedor = null;   // autor da última licitação aceite; null se não há
    int licitacoes = 0;       // número de licitações aceites (= último n)
    boolean fechoAnunciado = false;   // true depois de o fecho (ou cancelamento) ser gravado e difundido
    boolean cancelado = false;        // cancelado pela consola de administração (C1): fecha sem vencedor

    Leilao(int id, String artigo, long base, long incremento, String criador, long fechoMs) {
        this.id = id;
        this.artigo = artigo;
        this.base = base;
        this.incremento = incremento;
        this.criador = criador;
        this.fechoMs = fechoMs;
    }

    /**
     * Número de sequência atual do leilão, o do último aviso emitido:
     * uma por licitação aceite e, no fim, uma para o fecho (licitacoes + 1).
     */
    int seq() {
        return licitacoes + (fechoAnunciado ? 1 : 0);
    }

    /** O leilão está aberto se e só se a hora do servidor é anterior à de fecho (B6). */
    boolean aberto(long agoraMs) {
        return !cancelado && agoraMs < fechoMs;
    }

    /** Segundos até ao fecho, arredondados para cima; 0 se já não está aberto. */
    long restante(long agoraMs) {
        return aberto(agoraMs) ? (fechoMs - agoraMs + 999) / 1000 : 0;
    }

    /**
     * Valor mínimo que uma nova licitação tem de ter (secção 2):
     * o preço base se ainda não há licitação aceite; senão melhor + incremento.
     * Usa long: melhor + incremento pode passar de 2^31.
     */
    long valorMinimo() {
        return licitacoes == 0 ? base : melhor + incremento;
    }

    /**
     * Registo de leilão do Anexo A.4, campos nome=valor pela ordem pedida.
     * O artigo é o último campo porque pode ter espaços.
     * restante = segundos até ao fecho ARREDONDADOS PARA CIMA (0 se fechado).
     */
    String registo(long agoraMs) {
        boolean aberto = aberto(agoraMs);
        long restante = restante(agoraMs);
        // Um leilão cancelado está FECHADO, com vencedor=- e restante=0 (A.4);
        // os outros campos mantêm-se.
        return "id=" + id
                + " estado=" + (aberto ? "ABERTO" : "FECHADO")
                + " melhor=" + melhor
                + " vencedor=" + (vencedor == null || cancelado ? "-" : vencedor)
                + " criador=" + criador
                + " base=" + base
                + " incremento=" + incremento
                + " licitacoes=" + licitacoes
                + " restante=" + restante
                + " artigo=" + artigo;
    }
}
