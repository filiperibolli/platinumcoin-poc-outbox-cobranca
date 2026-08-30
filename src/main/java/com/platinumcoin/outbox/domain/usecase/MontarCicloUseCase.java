package com.platinumcoin.outbox.domain.usecase;

import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.Transacao;

import java.time.LocalDate;

/**
 * Monta o ciclo de cobrança de um banco numa data: cria o ciclo e puxa para
 * dentro dele todas as tentativas {@code ABERTO} do recorte.
 *
 * <p>É a <b>única escrita que importa</b>. Remessa, retorno, fechamento e
 * publicação são trabalho derivado — dado o ciclo, todos podem ser refeitos e
 * chegam ao mesmo lugar. Errar aqui é o único erro que não se conserta
 * reprocessando.
 *
 * <p>As duas escritas são uma só: ou existe o ciclo com suas tentativas em
 * {@code SOLICITADO}, ou não existe nada. Um ciclo vazio porque o {@code UPDATE}
 * falhou seria um lote fantasma; tentativas apontando para um ciclo que o
 * {@code INSERT} não confirmou seriam trabalho perdido de vista.
 *
 * <p>Reexecutar é seguro <b>por construção</b>: a segunda montagem do mesmo
 * banco e data esbarra no {@code UNIQUE (banco, data_ref)} e nada é escrito.
 * Não há consulta prévia — ela perderia a corrida contra outro processo que
 * lesse "não existe" no mesmo instante.
 * <br>DECISÃO: idempotência por constraint, não por consulta prévia — ver step-02
 */
public final class MontarCicloUseCase {

    private final Transacao.Fabrica transacoes;
    private final RepositorioCiclo ciclos;

    public MontarCicloUseCase(Transacao.Fabrica transacoes, RepositorioCiclo ciclos) {
        this.transacoes = transacoes;
        this.ciclos = ciclos;
    }

    /** O ciclo montado e quantas tentativas entraram nele. */
    public record Resultado(CicloCobranca ciclo, int tentativas) {
    }

    public Resultado executar(String cicloId, String banco, LocalDate dataRef) {
        CicloCobranca ciclo = CicloCobranca.montado(cicloId, banco, dataRef);

        try (Transacao tx = transacoes.abrir()) {
            ciclos.criar(tx, ciclo);
            int tentativas = ciclos.atribuirTentativasAbertas(tx, ciclo);
            tx.commit();
            return new Resultado(ciclo, tentativas);
        }
    }
}
