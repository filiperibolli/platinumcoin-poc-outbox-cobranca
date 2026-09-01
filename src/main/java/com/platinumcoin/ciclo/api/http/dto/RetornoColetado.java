package com.platinumcoin.ciclo.api.http.dto;

import com.platinumcoin.ciclo.domain.usecase.ColetarRetornoUseCase;

import java.util.List;

/**
 * O efeito de {@code POST /ciclo/coletar}: um item por arquivo <b>visto</b> na
 * passada, inclusive os que não foram baixados e os que foram descartados.
 *
 * <p>Uma resposta que só contasse o que foi aplicado esconderia justamente o
 * que o step-09 existe para mostrar: o arquivo que ainda está sendo escrito, o
 * que o trailer reprovou e o que já tinha sido aplicado byte a byte.
 */
public record RetornoColetado(int vistos, int aplicadas, List<Arquivo> arquivos) {

    /** Um arquivo do diretório do parceiro e o que aconteceu com ele. */
    public record Arquivo(String nome, String desfecho, int linhas, int aplicadas) {
    }

    public static RetornoColetado de(List<ColetarRetornoUseCase.Resultado> passada) {
        return new RetornoColetado(
                passada.size(),
                passada.stream().mapToInt(ColetarRetornoUseCase.Resultado::aplicadas).sum(),
                passada.stream()
                        .map(resultado -> new Arquivo(resultado.nome(),
                                resultado.desfecho().name(),
                                resultado.linhas(),
                                resultado.aplicadas()))
                        .toList());
    }
}
