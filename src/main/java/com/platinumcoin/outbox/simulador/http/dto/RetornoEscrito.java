package com.platinumcoin.outbox.simulador.http.dto;

import com.platinumcoin.outbox.simulador.ParceiroSimulado;

import java.util.List;

/**
 * O efeito de uma chamada ao parceiro: o que ele leu da remessa e o que deixou
 * no diretório de retorno.
 *
 * <p>As duas contagens juntas são a informação. {@code tentativasLidas} sem
 * {@code linhasEscritas} é o silêncio — o parceiro recebeu a remessa e não vai
 * falar sobre ela —, e é o único jeito de o botão que <b>não produz nada</b>
 * dizer o que fez.
 *
 * <p>{@code partesPendentes} é o retorno que ainda não saiu: entre a chamada
 * que atrasa e a que completa, é ele que explica por que metade do ciclo
 * continua esperando.
 */
public record RetornoEscrito(String acao,
                             int tentativasLidas,
                             int linhasEscritas,
                             List<ParceiroSimulado.Arquivo> arquivos,
                             int partesPendentes) {

    public static RetornoEscrito de(String acao, ParceiroSimulado.Resultado resultado) {
        return new RetornoEscrito(acao, resultado.tentativasLidas(), resultado.linhasEscritas(),
                resultado.arquivos(), resultado.partesPendentes());
    }
}
