package com.platinumcoin.outbox.domain.model;

import java.time.LocalDate;

/**
 * Uma tentativa de débito. Uma fatura tem N — o banco reapresenta o débito
 * quando não há saldo — mas no máximo uma delas paga.
 *
 * <p>Máquina de estados:
 * <pre>
 *   ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO
 *                                          → SEM_RETORNO (via fechamento)
 * </pre>
 *
 * <p>O estado é a chave de idempotência do processamento do retorno: só uma
 * tentativa {@code ENVIADO_PARCEIRO} aceita um resultado, e o {@code UPDATE}
 * condicional do step-03 usa isso para ignorar retornos repetidos sem tabela
 * auxiliar.
 */
public record TentativaDebito(
        String id,
        String faturaId,
        int numero,
        String banco,
        LocalDate dataRef,
        String cicloId,
        Status status,
        MotivoNaoPago motivo) {

    public enum Status {
        /** Criada; ainda não atribuída a um ciclo. */
        ABERTO,
        /** Atribuída a um ciclo; ainda não transmitida ao parceiro. */
        SOLICITADO,
        /** Transmitida ao parceiro; aguardando retorno. */
        ENVIADO_PARCEIRO,
        /** O retorno confirmou o débito. */
        PAGO,
        /** O retorno recusou o débito, e disse por quê. */
        NAO_PAGO,
        /** Falha técnica ao processar a linha de retorno. */
        ERRO,
        /** O ciclo fechou e nenhum retorno chegou para esta tentativa. */
        SEM_RETORNO;

        /**
         * Se este desfecho autoriza um lançamento contábil.
         *
         * <p>Só {@code PAGO}. {@code NAO_PAGO}, {@code ERRO} e
         * {@code SEM_RETORNO} nunca geram lançamento — nenhum dos três é um
         * pagamento, e o mainframe não tem o que contabilizar.
         *
         * <p>A regra mora aqui, e não espalhada num {@code if} dentro do use
         * case, porque é a pergunta que decide se uma linha entra no outbox.
         * <br>DECISÃO: só PAGO gera lançamento — ver README
         */
        public boolean geraLancamentoContabil() {
            return this == PAGO;
        }

        /**
         * Se este é um desfecho que o <b>parceiro</b> pode informar num arquivo
         * de retorno.
         *
         * <p>{@code SEM_RETORNO} fica de fora porque não é afirmação de
         * ninguém — é o que o fechamento do ciclo conclui do silêncio
         * (step-04). Os estados anteriores ao envio ficam de fora porque uma
         * linha de retorno não anda para trás.
         */
        public boolean vemDoRetorno() {
            return this == PAGO || this == NAO_PAGO || this == ERRO;
        }
    }

    /**
     * Por que o parceiro recusou o débito.
     *
     * <p>Existe só para {@code NAO_PAGO}: houve um fato e o parceiro disse qual.
     * A ausência de retorno não tem motivo, porque não houve fato — ver
     * {@code Status#SEM_RETORNO} e o step-04.
     */
    public enum MotivoNaoPago {
        SALDO_INSUFICIENTE,
        CONTA_ENCERRADA,
        AUTORIZACAO_REVOGADA
    }

    public TentativaDebito {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tentativa sem id");
        }
        if (faturaId == null || faturaId.isBlank()) {
            throw new IllegalArgumentException("tentativa " + id + " sem fatura");
        }
        if (numero < 1) {
            throw new IllegalArgumentException("tentativa " + id + " com número inválido: " + numero);
        }
        if (banco == null || banco.isBlank()) {
            throw new IllegalArgumentException("tentativa " + id + " sem banco");
        }
        if (dataRef == null) {
            throw new IllegalArgumentException("tentativa " + id + " sem data de referência");
        }
        exigirMotivoCoerente("tentativa " + id, status, motivo);
    }

    /**
     * Motivo existe se, e somente se, o status é {@code NAO_PAGO}.
     *
     * <p>Espelha a constraint {@code tentativa_motivo_so_com_nao_pago} do
     * schema: um {@code NAO_PAGO} sem motivo é um "não deu certo" que ninguém
     * consegue explicar ao cliente, e um motivo em qualquer outro estado é
     * invenção.
     *
     * <p>É {@code static} para ter <b>um</b> dono. A mesma regra é cobrada de
     * quem constrói uma tentativa, de quem lê uma linha de retorno e de quem
     * aplica o resultado — três chamadores, uma frase. Repetida em cada um,
     * seria três frases livres para divergirem.
     */
    public static void exigirMotivoCoerente(String contexto, Status status, MotivoNaoPago motivo) {
        if ((status == Status.NAO_PAGO) != (motivo != null)) {
            throw new IllegalArgumentException(
                    contexto + ": motivo existe se, e somente se, o status é NAO_PAGO"
                            + " (status=" + status + ", motivo=" + motivo + ")");
        }
    }

    /** Tentativa recém-criada, ainda fora de qualquer ciclo. */
    public static TentativaDebito aberta(String id, String faturaId, int numero,
                                         String banco, LocalDate dataRef) {
        return new TentativaDebito(id, faturaId, numero, banco, dataRef, null, Status.ABERTO, null);
    }
}
