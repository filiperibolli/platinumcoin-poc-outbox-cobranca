package com.platinumcoin.outbox.api.http.dto;

import java.util.List;

/**
 * O efeito de {@code POST /outbox/publicar}: quantas linhas saíram de
 * {@code PENDENTE} e que chaves de dedup foram para a fila.
 *
 * <p>As duas contagens podem divergir, e é aí que está a informação: uma chave
 * enviada sem a linha correspondente marcada é a janela do relay acontecendo —
 * a mensagem saiu, o {@code UPDATE} não. A próxima passada republica a mesma
 * chave, e o consumidor descarta pela {@code chaveDedup} — ver ADR-0002.
 */
public record OutboxPublicado(int publicados, List<String> chavesDedup) {
}
