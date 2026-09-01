package com.platinumcoin.ciclo.api.http.dto;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;

/**
 * O efeito de {@code POST /ciclo/enviar}: o nome que o arquivo tem no diretório
 * do parceiro e quantas tentativas passaram a {@code ENVIADO_PARCEIRO}.
 *
 * <p>O nome vem do ciclo, e não da transmissão: é ele que faz o reenvio
 * sobrescrever em vez de duplicar — ver step-08.
 */
public record RemessaEnviada(String cicloId,
                             String status,
                             String nomeNoParceiro,
                             int enviadas) {

    public static RemessaEnviada de(CicloCobranca ciclo, int enviadas) {
        return new RemessaEnviada(ciclo.id(), ciclo.status().name(),
                ChaveArtefato.nomeDaRemessaNoParceiro(ciclo), enviadas);
    }
}
