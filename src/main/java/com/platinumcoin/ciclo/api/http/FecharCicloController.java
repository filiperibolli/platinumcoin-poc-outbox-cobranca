package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.api.http.dto.CicloFechado;
import com.platinumcoin.ciclo.domain.usecase.FecharCicloUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /ciclo/fechar} — o horário chegou, e quem não respondeu vira
 * {@code SEM_RETORNO}.
 *
 * <p>Em produção, este é o {@code POST} que o EventBridge dispara mais tarde no
 * dia. Ter o botão é o que torna visível a diferença entre "o parceiro recusou"
 * e "o parceiro não falou".
 */
@RestController
public class FecharCicloController {

    private final FecharCicloUseCase fecharCiclo;

    public FecharCicloController(FecharCicloUseCase fecharCiclo) {
        this.fecharCiclo = fecharCiclo;
    }

    @PostMapping("/ciclo/fechar")
    public CicloFechado fechar(@RequestParam("ciclo") String cicloId) {
        return new CicloFechado(cicloId, fecharCiclo.executar(cicloId));
    }
}
