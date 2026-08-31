package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.model.Fatura;

import java.util.List;
import java.util.Optional;

/**
 * Acesso às faturas.
 *
 * <p>Só faturas. As tentativas de débito têm porta própria
 * ({@link RepositorioTentativa}) porque são escritas por três operações
 * diferentes — montagem, retorno e fechamento — enquanto a fatura só muda no
 * retorno.
 */
public interface RepositorioFatura {

    /** Grava uma fatura nova (fora de transação de negócio; prepara dados). */
    void inserir(Fatura fatura);

    Optional<Fatura> buscar(String faturaId);

    /**
     * As faturas das tentativas de um ciclo, em ordem estável de id.
     *
     * <p>A remessa leva o valor a debitar, e o valor é da fatura — não da
     * tentativa. Uma consulta por ciclo, e não uma por tentativa dentro do
     * laço da projeção: o número de idas ao banco não pode depender do
     * tamanho do lote.
     */
    List<Fatura> doCiclo(String cicloId);

    /**
     * Marca a fatura como {@code PAGA}, <b>somente</b> se ela ainda estiver
     * {@code ABERTA}.
     *
     * @return 1 se a transição aconteceu, 0 se a fatura já estava paga — o caso
     *         de uma segunda tentativa da mesma fatura também pagar.
     */
    int marcarPaga(Transacao tx, String faturaId);

    /** A fatura à qual a tentativa pertence, lida dentro da transação. */
    Optional<Fatura> buscarPorTentativa(Transacao tx, String tentativaId);
}
