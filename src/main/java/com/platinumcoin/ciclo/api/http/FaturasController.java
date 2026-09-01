package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.api.http.dto.FaturasAbertas;
import com.platinumcoin.ciclo.domain.usecase.AbrirFaturasUseCase;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * {@code POST /faturas} — o estado inicial do mundo, que num sistema de verdade
 * chegaria da originação.
 *
 * <p>É o único passo que não corresponde a um job do EventBridge. Os outros
 * seis correspondem.
 */
@RestController
public class FaturasController {

    private final AbrirFaturasUseCase abrirFaturas;

    public FaturasController(AbrirFaturasUseCase abrirFaturas) {
        this.abrirFaturas = abrirFaturas;
    }

    @PostMapping("/faturas")
    public FaturasAbertas abrir(
            @RequestParam(name = "quantidade", defaultValue = "4") int quantidade,
            @RequestParam(name = "banco", defaultValue = Recorte.BANCO_PADRAO) String banco,
            @RequestParam(name = "data", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        Recorte recorte = Recorte.de(banco, data);
        return FaturasAbertas.de(
                abrirFaturas.executar(quantidade, recorte.banco(), recorte.dataRef()));
    }
}
