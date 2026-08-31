package com.platinumcoin.outbox.api.http;

import com.platinumcoin.outbox.api.http.dto.RetornoColetado;
import com.platinumcoin.outbox.domain.usecase.ColetarRetornoUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ciclo/coletar} — uma passada pelo diretório de retorno do
 * parceiro.
 *
 * <p>Sem parâmetro de ciclo, e não por economia: a passada varre o diretório,
 * e de que ciclo é cada arquivo quem diz é o header dele. Pedir o ciclo aqui
 * seria supor que o parceiro respeita a nossa contagem — e o step-09 existe
 * porque ele não respeita.
 *
 * <p>A chamada demora: a quiescência são duas leituras de atributos separadas
 * por um intervalo, por arquivo visto.
 */
@RestController
public class ColetarRetornoController {

    private final ColetarRetornoUseCase coletarRetorno;

    public ColetarRetornoController(ColetarRetornoUseCase coletarRetorno) {
        this.coletarRetorno = coletarRetorno;
    }

    @PostMapping("/ciclo/coletar")
    public RetornoColetado coletar() {
        return RetornoColetado.de(coletarRetorno.executar());
    }
}
