package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.api.http.dto.RemessaGerada;
import com.platinumcoin.ciclo.domain.usecase.GerarRemessaUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ciclo/gerar-remessa} — a projeção do ciclo gravada como artefato
 * durável.
 *
 * <p>Chamar duas vezes é seguro e devolve a mesma chave e o mesmo {@code sha256}:
 * a geração é função pura do ciclo, e o {@code put} sobrescreve os mesmos bytes.
 */
@RestController
public class GerarRemessaController {

    private final GerarRemessaUseCase gerarRemessa;

    public GerarRemessaController(GerarRemessaUseCase gerarRemessa) {
        this.gerarRemessa = gerarRemessa;
    }

    @PostMapping("/ciclo/gerar-remessa")
    public RemessaGerada gerar(@RequestParam("ciclo") String cicloId) {
        return RemessaGerada.de(gerarRemessa.executar(cicloId));
    }
}
