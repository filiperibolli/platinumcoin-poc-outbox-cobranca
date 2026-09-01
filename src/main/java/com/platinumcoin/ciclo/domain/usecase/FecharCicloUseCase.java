package com.platinumcoin.ciclo.domain.usecase;

import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.Transacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encerra o ciclo depois da janela de retorno: o que continua
 * {@code ENVIADO_PARCEIRO} vira {@code SEM_RETORNO} e o ciclo vira
 * {@code FECHADO}.
 *
 * <p>Silêncio não é resposta. {@code NAO_PAGO} é uma afirmação do parceiro e vem
 * com motivo ({@code SALDO_INSUFICIENTE}, {@code CONTA_ENCERRADA},
 * {@code AUTORIZACAO_REVOGADA}); a ausência de retorno não tem motivo porque não
 * houve fato. A diferença é visível para o cliente — {@code NAO_PAGO} dispara
 * notificação de falha de débito, {@code SEM_RETORNO} é exceção operacional que
 * alguém investiga.
 * <br>DECISÃO: ausência de retorno vira SEM_RETORNO, não NAO_PAGO — ver README
 *
 * <p>Nenhum dos dois gera lançamento: {@code Status.geraLancamentoContabil()} só
 * é verdadeiro para {@code PAGO}, e por isso o fechamento não toca no outbox.
 *
 * <p>Reexecutar é seguro pelo mesmo mecanismo do retorno: o {@code UPDATE} é
 * condicionado a {@code ENVIADO_PARCEIRO}, então o segundo fechamento afeta zero
 * linhas — e zero linhas é a resposta, não um erro. Um retorno que chegue depois
 * disso também é ignorado: a tentativa já saiu de {@code ENVIADO_PARCEIRO}.
 */
public final class FecharCicloUseCase {

    private static final Logger log = LoggerFactory.getLogger(FecharCicloUseCase.class);

    private final Transacao.Fabrica transacoes;
    private final RepositorioCiclo ciclos;

    public FecharCicloUseCase(Transacao.Fabrica transacoes, RepositorioCiclo ciclos) {
        this.transacoes = transacoes;
        this.ciclos = ciclos;
    }

    /**
     * Fecha o ciclo. As duas escritas são uma só: um ciclo {@code FECHADO} com
     * tentativas ainda esperando retorno seria um lote que ninguém mais coleta.
     *
     * @return quantas tentativas ficaram {@code SEM_RETORNO} — zero num ciclo já
     *         fechado.
     */
    public int executar(String cicloId) {
        try (Transacao tx = transacoes.abrir()) {
            int semRetorno = ciclos.fechar(tx, cicloId);
            tx.commit();
            log.info("[fecha]   {} FECHADO — {} tentativa(s) ENVIADO_PARCEIRO → SEM_RETORNO"
                            + " (silêncio registrado como silêncio, nunca como NAO_PAGO)",
                    cicloId, semRetorno);
            return semRetorno;
        }
    }
}
