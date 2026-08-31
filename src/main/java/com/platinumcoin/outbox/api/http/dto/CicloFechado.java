package com.platinumcoin.outbox.api.http.dto;

/**
 * O efeito de {@code POST /ciclo/fechar}: quantas tentativas continuavam
 * esperando resposta e viraram {@code SEM_RETORNO}.
 *
 * <p>Zero é resposta, e não erro: um ciclo já fechado devolve zero porque o
 * {@code UPDATE} é condicionado a {@code ENVIADO_PARCEIRO}.
 */
public record CicloFechado(String cicloId, int semRetorno) {
}
