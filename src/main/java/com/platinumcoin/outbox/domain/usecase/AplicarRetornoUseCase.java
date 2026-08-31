package com.platinumcoin.outbox.domain.usecase;

import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;

/**
 * Aplica a um débito o desfecho que o parceiro informou — e, quando o desfecho
 * é um pagamento, registra na <b>mesma transação</b> a intenção de publicar o
 * lançamento contábil.
 *
 * <p>É o coração do projeto: a transação que decide <b>e</b> registra a intenção
 * de publicar. Três escritas possíveis (tentativa, fatura, outbox), um banco, um
 * {@code COMMIT}. Nenhuma chamada externa acontece com a transação aberta — e
 * repare que {@code PublicadorLancamento} não aparece neste arquivo: se um dia
 * aparecer, o projeto perdeu a propriedade que existe para provar.
 * <br>DECISÃO: outbox na mesma transação, publicação fora — ver ADR-0001
 *
 * <p>A idempotência não tem tabela nem registro próprio: o estado atual da
 * tentativa <b>é</b> a chave. O {@code UPDATE} condicional por
 * {@code ENVIADO_PARCEIRO} afeta zero linhas quando o retorno já foi aplicado,
 * e zero linhas não é erro — é o caso normal de um arquivo reprocessado. Uma
 * tabela de dedup seria um segundo lugar para a mesma verdade ficar
 * desatualizada.
 * <br>DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
 *
 * <p>Um retorno por vez, uma transação por retorno. Cada linha do arquivo é uma
 * decisão independente: uma linha que estoura não pode desfazer as que já foram
 * aplicadas, e reprocessar o arquivo inteiro é seguro justamente porque as que
 * passaram viram zero linhas afetadas na segunda vez.
 */
public final class AplicarRetornoUseCase {

    private final Transacao.Fabrica transacoes;
    private final RepositorioTentativa tentativas;
    private final RepositorioFatura faturas;
    private final RepositorioOutbox outbox;

    public AplicarRetornoUseCase(Transacao.Fabrica transacoes,
                                 RepositorioTentativa tentativas,
                                 RepositorioFatura faturas,
                                 RepositorioOutbox outbox) {
        this.transacoes = transacoes;
        this.tentativas = tentativas;
        this.faturas = faturas;
        this.outbox = outbox;
    }

    /** O que a aplicação de um retorno fez — o suficiente para explicá-la num log. */
    public enum Resultado {
        /**
         * Zero linhas afetadas: o retorno já havia sido aplicado, o ciclo já
         * fechou e a tentativa virou {@code SEM_RETORNO}, ou a tentativa não
         * existe. Não é erro.
         */
        IGNORADO,
        /**
         * A tentativa transicionou, e nada entrou no outbox — o desfecho não é
         * um pagamento, ou outra tentativa da mesma fatura já havia pago.
         */
        APLICADO,
        /** A tentativa transicionou, a fatura virou {@code PAGA} e o outbox recebeu a linha. */
        APLICADO_COM_LANCAMENTO
    }

    public Resultado executar(String tentativaId,
                              TentativaDebito.Status resultado,
                              TentativaDebito.MotivoNaoPago motivo) {
        if (!resultado.vemDoRetorno()) {
            throw new IllegalArgumentException("tentativa " + tentativaId
                    + ": o parceiro não informa o desfecho " + resultado);
        }
        TentativaDebito.exigirMotivoCoerente("retorno da tentativa " + tentativaId, resultado, motivo);

        try (Transacao tx = transacoes.abrir()) {
            if (tentativas.registrarResultado(tx, tentativaId, resultado, motivo) == 0) {
                return Resultado.IGNORADO;
            }
            Resultado desfecho = resultado.geraLancamentoContabil()
                    ? decidirLancamento(tx, tentativaId)
                    : Resultado.APLICADO;
            tx.commit();
            return desfecho;
        }
    }

    /**
     * A fatura vira {@code PAGA} e a intenção de publicar entra no outbox.
     *
     * <p>A guarda está no {@code UPDATE} da fatura, não num {@code if} sobre uma
     * leitura anterior: quem consegue levá-la de {@code ABERTA} a {@code PAGA} é
     * quem ganha o direito de gravar o lançamento. Duas tentativas da mesma
     * fatura que paguem — o parceiro reapresentou e as duas voltaram
     * confirmadas — produzem uma linha só, e a segunda descobre isso pelo
     * número de linhas afetadas.
     */
    private Resultado decidirLancamento(Transacao tx, String tentativaId) {
        Fatura fatura = faturas.buscarPorTentativa(tx, tentativaId).orElseThrow(
                () -> new IllegalStateException("tentativa sem fatura: " + tentativaId));

        if (faturas.marcarPaga(tx, fatura.id()) == 0) {
            return Resultado.APLICADO;
        }
        outbox.inserir(tx, new LancamentoContabil(fatura.id(), fatura.valor()));
        return Resultado.APLICADO_COM_LANCAMENTO;
    }
}
