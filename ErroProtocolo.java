/**
 * Erro do protocolo (Anexo A.3): um código (SINTAXE, VALOR, BAIXA, ...) e um
 * motivo em texto livre para humanos.
 *
 * É uma exceção "de controlo de fluxo": quem deteta o erro lança-a e o
 * Atendedor converte-a na linha "ERRO CODIGO motivo". Nunca fecha a ligação.
 */
public final class ErroProtocolo extends Exception {

    // Escrito à mão (como no Pedido/Resposta das aulas): se o deixarmos ao
    // compilador, qualquer alteração à classe muda o valor sem avisar.
    private static final long serialVersionUID = 1L;

    /** Código lido pelos programas (A.3). */
    final String codigo;

    ErroProtocolo(String codigo, String motivo) {
        // Sem stack trace: estas exceções são frequentes e esperadas, não bugs.
        super(motivo, null, false, false);
        this.codigo = codigo;
    }

    /** A linha de resposta pronta a enviar ao cliente (sem o \n final). */
    String comoResposta() {
        return "ERRO " + codigo + " " + getMessage();
    }
}
