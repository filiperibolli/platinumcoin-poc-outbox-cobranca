package com.platinumcoin.ciclo.domain.port;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;

/**
 * Onde os artefatos duráveis do ciclo ficam guardados — na infra, um bucket S3.
 *
 * <p>Três métodos, e nenhum deles sabe de bucket, região ou SDK: o domínio
 * grava, lê e pergunta se já existe. É a mesma fronteira que
 * {@link PublicadorLancamento} faz com o SQS.
 *
 * <p>Sem {@code delete} e sem {@code list} de propósito. Expurgo é operação de
 * infra — em produção, uma lifecycle policy no bucket com prazo ditado pela
 * guarda contábil — e uma porta que o oferecesse convidaria uma regra de
 * negócio a decidir o que apagar.
 * <br>DECISÃO: artefato durável entre geração e transmissão — ver ADR-0003
 *
 * <p>O {@code put} é chamado <b>fora</b> de {@link Transacao} aberta, como todo
 * efeito externo deste projeto. Diferente da fila, ele é endereçável: repetir o
 * {@code put} da mesma chave sobrescreve com os mesmos bytes, e é por isso que
 * a janela entre ele e o {@code COMMIT} não custa nada.
 */
public interface ArmazenamentoArtefato {

    /** Grava (ou sobrescreve) o artefato endereçado por {@code chave}. */
    void put(ChaveArtefato chave, byte[] conteudo);

    /**
     * O conteúdo do artefato.
     *
     * @throws com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia
     *         se não houver nada na chave — perguntar antes é {@link #existe}.
     */
    byte[] get(ChaveArtefato chave);

    boolean existe(ChaveArtefato chave);
}
