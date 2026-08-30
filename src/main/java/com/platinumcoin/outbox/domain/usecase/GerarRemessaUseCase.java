package com.platinumcoin.outbox.domain.usecase;

import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.Remessa;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;

/**
 * Gera a remessa de um ciclo já montado.
 *
 * <p>Separado de {@link MontarCicloUseCase} porque as duas operações prometem
 * coisas opostas: a montagem acontece <b>uma</b> vez e a segunda é barrada; a
 * geração acontece quantas vezes for preciso e precisa devolver sempre os
 * mesmos bytes. Num método só, a segunda garantia — a que permite retransmitir
 * depois de uma falha de transmissão — ficaria escondida atrás da primeira.
 *
 * <p>Não abre transação: só lê, e não escreve nada. A projeção em si mora em
 * {@link Remessa#de}, que não conhece repositório nenhum.
 */
public final class GerarRemessaUseCase {

    private final RepositorioCiclo ciclos;
    private final RepositorioTentativa tentativas;

    public GerarRemessaUseCase(RepositorioCiclo ciclos, RepositorioTentativa tentativas) {
        this.ciclos = ciclos;
        this.tentativas = tentativas;
    }

    public Remessa executar(String cicloId) {
        CicloCobranca ciclo = ciclos.buscar(cicloId)
                .orElseThrow(() -> new IllegalArgumentException("ciclo inexistente: " + cicloId));

        return Remessa.de(ciclo, tentativas.doCiclo(cicloId));
    }
}
