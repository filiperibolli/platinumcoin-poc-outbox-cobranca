package com.platinumcoin.ciclo.api.http.dto;

import com.platinumcoin.ciclo.domain.model.Remessa;

/**
 * O efeito de {@code POST /ciclo/gerar-remessa}: onde o artefato ficou, que
 * bytes ele tinha e quantos detalhes o trailer conta.
 *
 * <p>Chamar duas vezes devolve exatamente esta resposta duas vezes — é a
 * geração ser função pura do ciclo, visível sem ler teste nenhum.
 */
public record RemessaGerada(String cicloId,
                            String chave,
                            String sha256,
                            int detalhes,
                            int bytes) {

    public static RemessaGerada de(Remessa remessa) {
        return new RemessaGerada(
                remessa.cicloId(),
                remessa.chave().valor(),
                remessa.sha256(),
                remessa.quantidadeDeDetalhes(),
                remessa.bytes().length);
    }
}
