package com.platinumcoin.ciclo.api.http.dto;

import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.usecase.MontarCicloUseCase;

import java.time.LocalDate;

/**
 * O efeito de {@code POST /ciclo/montar}: o ciclo que nasceu e quantas
 * tentativas ele puxou de {@code ABERTO} para {@code SOLICITADO}.
 *
 * <p>"Montou" não é informação; "montou C-1 e moveu 5 tentativas" é.
 */
public record CicloMontado(String cicloId,
                           String banco,
                           LocalDate dataRef,
                           String status,
                           int solicitadas) {

    public static CicloMontado de(MontarCicloUseCase.Resultado resultado) {
        CicloCobranca ciclo = resultado.ciclo();
        return new CicloMontado(ciclo.id(), ciclo.banco(), ciclo.dataRef(),
                ciclo.status().name(), resultado.tentativas());
    }
}
