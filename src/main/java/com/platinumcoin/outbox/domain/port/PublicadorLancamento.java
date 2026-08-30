package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.model.LancamentoContabil;

/**
 * A saída para o mundo externo — na infra, uma fila SQS consumida pelo
 * mainframe legado.
 *
 * <p>Esta porta <b>nunca</b> é chamada por {@code AplicarRetornoUseCase}. Só o
 * relay a usa, e sempre fora de uma {@link Transacao} aberta. Se um dia esta
 * interface aparecer no construtor do use case de retorno, o projeto perdeu a
 * propriedade que existe para provar.
 */
public interface PublicadorLancamento {

    /**
     * Envia o lançamento carregando a chave de deduplicação
     * ({@link LancamentoContabil#chaveDedup()}) como atributo da mensagem.
     *
     * <p>O contrato é at-least-once: pode entregar a mesma chave mais de uma
     * vez, e o consumidor é responsável por descartar a repetição.
     * <br>DECISÃO: dedup delegada ao consumidor — ver ADR-0002
     *
     * @return identificador da mensagem no destino, para log.
     */
    String publicar(LancamentoContabil lancamento);
}
