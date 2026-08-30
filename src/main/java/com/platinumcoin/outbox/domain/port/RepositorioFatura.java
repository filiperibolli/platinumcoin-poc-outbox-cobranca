package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.TentativaDebito;

import java.util.Optional;

/**
 * Acesso às faturas e às tentativas de débito.
 *
 * <p>Os métodos de transição devolvem o <b>número de linhas afetadas</b>, e não
 * {@code void} nem {@code boolean}. Isso é deliberado: a idempotência do
 * processamento do retorno é lida diretamente desse número. Zero linhas
 * significa "esse retorno já foi aplicado" — não é erro, é o caso normal de um
 * arquivo reprocessado.
 * <br>DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
 */
public interface RepositorioFatura {

    /** Grava uma fatura nova (fora de transação de negócio; usado para preparar dados). */
    void inserir(Fatura fatura);

    /** Grava uma tentativa nova (fora de transação de negócio; usado para preparar dados). */
    void inserir(TentativaDebito tentativa);

    Optional<Fatura> buscar(String faturaId);

    Optional<TentativaDebito> buscarTentativa(String tentativaId);

    /**
     * Aplica o resultado do retorno a uma tentativa, <b>somente</b> se ela ainda
     * estiver {@code ENVIADO_PARCEIRO} — ou seja, se ainda houver retorno a
     * receber.
     *
     * @return 1 se a transição aconteceu, 0 se a tentativa já havia sido
     *         resolvida (retorno duplicado, ou ciclo já fechado com
     *         {@code SEM_RETORNO}) ou não existe.
     */
    int registrarResultadoDaTentativa(Transacao tx, String tentativaId, TentativaDebito.Status resultado);

    /**
     * Marca a fatura como {@code PAGA}, <b>somente</b> se ela ainda estiver
     * {@code ABERTA}.
     *
     * @return 1 se a transição aconteceu, 0 se a fatura já estava paga — o caso
     *         de uma segunda tentativa da mesma fatura também pagar.
     */
    int marcarFaturaPaga(Transacao tx, String faturaId);

    /** A fatura à qual a tentativa pertence, lida dentro da transação. */
    Optional<Fatura> buscarFaturaDaTentativa(Transacao tx, String tentativaId);
}
