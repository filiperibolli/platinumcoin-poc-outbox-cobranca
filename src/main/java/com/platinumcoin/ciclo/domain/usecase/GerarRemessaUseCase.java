package com.platinumcoin.ciclo.domain.usecase;

import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.Remessa;
import com.platinumcoin.ciclo.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.RepositorioFatura;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;
import com.platinumcoin.ciclo.domain.port.Transacao;

/**
 * Gera a remessa de um ciclo já montado e a deixa gravada como artefato
 * durável.
 *
 * <p>Separado de {@link MontarCicloUseCase} porque as duas operações prometem
 * coisas opostas: a montagem acontece <b>uma</b> vez e a segunda é barrada; a
 * geração acontece quantas vezes for preciso e precisa devolver sempre os
 * mesmos bytes. Num método só, a segunda garantia — a que permite retransmitir
 * depois de uma falha de transmissão — ficaria escondida atrás da primeira.
 *
 * <p>Separado de {@code EnviarRemessaUseCase} pelo mesmo motivo ao contrário:
 * decidir o conteúdo e entregá-lo falham por razões diferentes, e o artefato no
 * meio é o que permite reenviar sem regerar — ver ADR-0003.
 *
 * <p>A ordem é a decisão inteira:
 *
 * <pre>
 * projeta (função pura)  →  put no armazenamento  →  COMMIT (chave + sha256)
 * </pre>
 *
 * <p>O {@code put} vem antes do {@code COMMIT}, como no relay. A diferença é
 * que <b>aqui a janela não custa nada</b>: a chave é determinística e o
 * conteúdo é função pura do ciclo, então o {@code put} reexecutado sobrescreve
 * os mesmos bytes. Morrer entre um e outro deixa um objeto órfão idêntico ao
 * que a reexecução vai gravar — e o ciclo sem chave, que é o estado a partir do
 * qual a próxima passada recomeça. No step-08 a mesma ordem custa uma
 * transmissão a mais, porque lá o efeito externo não é sobrescrevível pelo
 * próprio conteúdo.
 * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
 */
public final class GerarRemessaUseCase {

    private final Transacao.Fabrica transacoes;
    private final RepositorioCiclo ciclos;
    private final RepositorioTentativa tentativas;
    private final RepositorioFatura faturas;
    private final ArmazenamentoArtefato artefatos;

    public GerarRemessaUseCase(Transacao.Fabrica transacoes, RepositorioCiclo ciclos,
                               RepositorioTentativa tentativas, RepositorioFatura faturas,
                               ArmazenamentoArtefato artefatos) {
        this.transacoes = transacoes;
        this.ciclos = ciclos;
        this.tentativas = tentativas;
        this.faturas = faturas;
        this.artefatos = artefatos;
    }

    public Remessa executar(String cicloId) {
        CicloCobranca ciclo = ciclos.buscar(cicloId)
                .orElseThrow(() -> new IllegalArgumentException("ciclo inexistente: " + cicloId));

        // A projeção mora em Remessa.de, que não conhece repositório nenhum e é
        // pura: mesmo ciclo, mesmos bytes.
        Remessa remessa = Remessa.de(ciclo, tentativas.doCiclo(cicloId), faturas.doCiclo(cicloId));

        // Efeito externo FORA de transação aberta, como todo efeito externo
        // deste projeto — ver ADR-0001.
        artefatos.put(remessa.chave(), remessa.bytes());

        try (Transacao tx = transacoes.abrir()) {
            ciclos.registrarRemessa(tx, cicloId, remessa.chave(), remessa.sha256());
            tx.commit();
        }
        return remessa;
    }
}
