package com.platinumcoin.outbox.domain.model;

import java.math.BigDecimal;

/**
 * Uma fatura de débito automático.
 *
 * <p>O ciclo é {@code ABERTA → PAGA → LANCADA}. {@code PAGA} é a decisão de
 * negócio; {@code LANCADA} é o reconhecimento de que o lançamento contábil já
 * foi publicado. A separação existe porque a decisão e a publicação acontecem
 * em momentos diferentes — ver ADR-0001.
 */
public record Fatura(String id, BigDecimal valor, Status status) {

    public enum Status {
        ABERTA,
        PAGA,
        LANCADA
    }

    public Fatura {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("fatura sem id");
        }
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("fatura " + id + " com valor inválido: " + valor);
        }
    }
}
