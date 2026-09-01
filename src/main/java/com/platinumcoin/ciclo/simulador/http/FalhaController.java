package com.platinumcoin.ciclo.simulador.http;

import com.platinumcoin.ciclo.infra.falha.FalhasArmadas;
import com.platinumcoin.ciclo.simulador.http.dto.FalhaArmada;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * As duas falhas do <b>nosso</b> lado, armadas para a próxima execução do passo
 * correspondente.
 *
 * <p>Fica junto do simulador, e não em {@code api/http}, porque armar uma falha
 * não é um passo do ciclo: é mexer no ambiente da demonstração, como pedir ao
 * parceiro que trunque um arquivo. Os oito passos do ciclo continuam sendo os
 * oito de {@code api/http}, e nenhum deles ganhou parâmetro de simulação.
 *
 * <p>A falha só acontece quando o passo é executado — {@code POST /ciclo/enviar}
 * ou {@code POST /outbox/publicar}. Entre uma chamada e outra fica o estado que
 * a janela produz, que é o que se quer ver.
 */
@RestController
public class FalhaController {

    private final FalhasArmadas falhas;

    public FalhaController(FalhasArmadas falhas) {
        this.falhas = falhas;
    }

    @PostMapping("/falha/crash-relay")
    public FalhaArmada crashRelay() {
        return armar(FalhasArmadas.Falha.CRASH_RELAY);
    }

    @PostMapping("/falha/crash-envio")
    public FalhaArmada crashEnvio() {
        return armar(FalhasArmadas.Falha.CRASH_ENVIO);
    }

    private FalhaArmada armar(FalhasArmadas.Falha falha) {
        falhas.armar(falha);
        return FalhaArmada.de(falha, falhas);
    }
}
