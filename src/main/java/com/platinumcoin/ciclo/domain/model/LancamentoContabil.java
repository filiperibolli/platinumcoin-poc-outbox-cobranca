package com.platinumcoin.ciclo.domain.model;

import java.math.BigDecimal;

/**
 * O evento financeiro que o mainframe legado consome.
 *
 * <p><b>Invariante do projeto:</b> no máximo um lançamento por fatura,
 * independentemente do número de tentativas de débito ou de reprocessamentos do
 * arquivo de retorno. Um lançamento duplicado não quebra nada tecnicamente —
 * gera uma divergência que alguém concilia à mão.
 *
 * <p>A {@link #chaveDedup()} é derivada do domínio, não gerada no envio:
 * republicar a mesma linha do outbox produz sempre a mesma chave. É o que
 * permite ao consumidor descartar a segunda ocorrência quando o relay
 * republica — ver ADR-0002.
 */
public record LancamentoContabil(String faturaId, BigDecimal valor) {

    public LancamentoContabil {
        if (faturaId == null || faturaId.isBlank()) {
            throw new IllegalArgumentException("lançamento sem fatura");
        }
        if (valor == null || valor.signum() <= 0) {
            throw new IllegalArgumentException("lançamento da fatura " + faturaId
                    + " com valor inválido: " + valor);
        }
    }

    /**
     * Chave de deduplicação determinística. O id da fatura basta porque existe
     * no máximo um lançamento por fatura — a invariante e a chave são a mesma
     * afirmação, vista de dois lados.
     */
    public String chaveDedup() {
        return faturaId;
    }
}
