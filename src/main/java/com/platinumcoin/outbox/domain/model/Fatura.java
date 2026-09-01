package com.platinumcoin.outbox.domain.model;

import java.math.BigDecimal;

/**
 * Uma fatura de débito automático.
 *
 * <p>O ciclo é {@code ABERTA → PAGA}, e para por aí. {@code PAGA} é a decisão
 * de negócio; se o lançamento contábil correspondente já saiu para a fila é
 * pergunta que o <b>outbox</b> responde, e não esta tabela.
 *
 * <p>Houve aqui um terceiro estado, {@code LANCADA}, que nada nunca escreveu.
 * Ele seria uma segunda cópia de um fato que o {@code UNIQUE (fatura_id)} do
 * outbox já guarda uma vez — e duas cópias do mesmo fato em duas tabelas é a
 * forma pequena do dual write que o ADR-0001 recusa na forma grande. Marcá-lo
 * exigiria, ainda por cima, um {@code UPDATE} depois do {@code send}: dentro
 * exatamente da janela que este projeto existe para discutir.
 * <br>DECISÃO: quem sabe se o lançamento saiu é o outbox — ver README
 */
public record Fatura(String id, BigDecimal valor, Status status) {

    public enum Status {
        ABERTA,
        PAGA
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
