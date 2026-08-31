package com.platinumcoin.outbox.api.http;

import com.platinumcoin.outbox.api.http.dto.RemessaEnviada;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.usecase.EnviarRemessaUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ciclo/enviar} — a transmissão por SSH, e a janela entre o
 * {@code put} e o {@code COMMIT} que o step-08 deixou à vista.
 *
 * <p>Lê o ciclo <b>depois</b> de enviar porque a resposta é o efeito: o nome no
 * parceiro e o status são os de agora, não os de antes da chamada. Ler é ler;
 * quem decide continua sendo o use case.
 */
@RestController
public class EnviarRemessaController {

    private final EnviarRemessaUseCase enviarRemessa;
    private final RepositorioCiclo ciclos;

    public EnviarRemessaController(EnviarRemessaUseCase enviarRemessa, RepositorioCiclo ciclos) {
        this.enviarRemessa = enviarRemessa;
        this.ciclos = ciclos;
    }

    @PostMapping("/ciclo/enviar")
    public RemessaEnviada enviar(@RequestParam("ciclo") String cicloId) {
        int enviadas = enviarRemessa.executar(cicloId);
        return RemessaEnviada.de(ciclos.buscar(cicloId).orElseThrow(), enviadas);
    }
}
