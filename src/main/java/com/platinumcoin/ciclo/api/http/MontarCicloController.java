package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.api.http.dto.CicloMontado;
import com.platinumcoin.ciclo.domain.usecase.MontarCicloUseCase;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * {@code POST /ciclo/montar} — a única escrita que importa.
 *
 * <p>Chamar duas vezes no mesmo recorte devolve {@code 409}: a montagem é
 * recusada pelo {@code UNIQUE (banco, data_ref)}, e não por uma consulta prévia
 * deste controller. Ver {@link FalhasComoResposta}.
 */
@RestController
public class MontarCicloController {

    private final MontarCicloUseCase montarCiclo;

    public MontarCicloController(MontarCicloUseCase montarCiclo) {
        this.montarCiclo = montarCiclo;
    }

    @PostMapping("/ciclo/montar")
    public CicloMontado montar(
            @RequestParam("ciclo") String cicloId,
            @RequestParam(name = "banco", defaultValue = Recorte.BANCO_PADRAO) String banco,
            @RequestParam(name = "data", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        Recorte recorte = Recorte.de(banco, data);
        return CicloMontado.de(
                montarCiclo.executar(cicloId, recorte.banco(), recorte.dataRef()));
    }
}
