package com.platinumcoin.ciclo.domain.port;

import com.platinumcoin.ciclo.domain.model.TentativaDebito;

import java.util.List;
import java.util.Optional;

/**
 * Acesso às tentativas de débito.
 *
 * <p>Os métodos de transição devolvem o <b>número de linhas afetadas</b>, e não
 * {@code void} nem {@code boolean}. Isso é deliberado: a idempotência do
 * processamento do retorno é lida diretamente desse número. Zero linhas
 * significa "esse retorno já foi aplicado" — não é erro, é o caso normal de um
 * arquivo reprocessado.
 * <br>DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
 */
public interface RepositorioTentativa {

    /** Grava uma tentativa nova (fora de transação de negócio; prepara dados). */
    void inserir(TentativaDebito tentativa);

    Optional<TentativaDebito> buscar(String tentativaId);

    /** As tentativas de um ciclo, em ordem estável de id — a base da remessa. */
    List<TentativaDebito> doCiclo(String cicloId);

    /**
     * Aplica o resultado do retorno a uma tentativa, <b>somente</b> se ela ainda
     * estiver {@code ENVIADO_PARCEIRO} — ou seja, se ainda houver retorno a
     * receber.
     *
     * @return 1 se a transição aconteceu, 0 se a tentativa já havia sido
     *         resolvida (retorno duplicado, ou ciclo já fechado com
     *         {@code SEM_RETORNO}) ou não existe.
     */
    int registrarResultado(Transacao tx, String tentativaId,
                           TentativaDebito.Status resultado,
                           TentativaDebito.MotivoNaoPago motivo);
}
