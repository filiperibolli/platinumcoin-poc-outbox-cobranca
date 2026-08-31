package com.platinumcoin.outbox.simulador.http.dto;

import com.platinumcoin.outbox.infra.falha.FalhasArmadas;

import java.util.List;

/**
 * O efeito de armar uma falha: nada quebrou <b>ainda</b>.
 *
 * <p>A resposta diz qual chamada vai quebrar e com que mensagem, porque armar e
 * disparar são passos separados — e quem está olhando precisa saber o que
 * clicar em seguida para ver a janela se abrir.
 */
public record FalhaArmada(String falha, String dispara, String mensagem, List<String> armadas) {

    public static FalhaArmada de(FalhasArmadas.Falha falha, FalhasArmadas falhas) {
        return new FalhaArmada(falha.name(), falha.passo(), falha.mensagem(), falhas.armadas());
    }
}
