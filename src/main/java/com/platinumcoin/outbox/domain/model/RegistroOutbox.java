package com.platinumcoin.outbox.domain.model;

/**
 * Uma linha da tabela {@code outbox}: a intenção de publicar um lançamento,
 * gravada na mesma transação que decidiu o pagamento.
 *
 * <p>Enquanto o status é {@code PENDENTE}, o sistema sabe que há algo a
 * publicar. É isso que transforma uma perda silenciosa (mensagem que nunca foi
 * enviada e ninguém registrou) num atraso detectável — ver ADR-0001.
 */
public record RegistroOutbox(long id, LancamentoContabil lancamento, Status status) {

    public enum Status {
        PENDENTE,
        PUBLICADO
    }

    public RegistroOutbox {
        if (lancamento == null) {
            throw new IllegalArgumentException("registro de outbox " + id + " sem lançamento");
        }
    }
}
