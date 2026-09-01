package com.platinumcoin.ciclo.api.http.dto;

import com.platinumcoin.ciclo.domain.model.Fatura;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.usecase.AbrirFaturasUseCase;

import java.time.LocalDate;
import java.util.List;

/** O efeito de {@code POST /faturas}: as faturas que passaram a existir. */
public record FaturasAbertas(String banco,
                             LocalDate dataRef,
                             int criadas,
                             List<String> faturas,
                             List<String> tentativas) {

    public static FaturasAbertas de(AbrirFaturasUseCase.Resultado resultado) {
        TentativaDebito qualquer = resultado.tentativas().get(0);
        return new FaturasAbertas(
                qualquer.banco(),
                qualquer.dataRef(),
                resultado.faturas().size(),
                resultado.faturas().stream().map(Fatura::id).toList(),
                resultado.tentativas().stream().map(TentativaDebito::id).toList());
    }
}
