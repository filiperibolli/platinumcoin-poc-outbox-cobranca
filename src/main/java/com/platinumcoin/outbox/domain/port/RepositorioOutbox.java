package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;

import java.util.List;

/**
 * A tabela de saída: intenções de publicar, gravadas junto com a decisão que as
 * autoriza.
 *
 * <p>Note a assimetria proposital das assinaturas: {@link #inserir} exige uma
 * {@link Transacao} — ele faz parte da decisão de negócio; {@link #pendentes} e
 * {@link #marcarPublicado} não a recebem — o relay roda fora de qualquer
 * transação de negócio, num ciclo de vida próprio.
 * <br>DECISÃO: outbox na mesma transação, publicação fora — ver ADR-0001
 */
public interface RepositorioOutbox {

    /** Grava a intenção de publicar dentro da transação que decidiu o pagamento. */
    void inserir(Transacao tx, LancamentoContabil lancamento);

    /** O que ainda não foi publicado, mais antigo primeiro. */
    List<RegistroOutbox> pendentes(int limite);

    /**
     * Marca como publicado. Chamado <b>depois</b> do envio ao SQS — nunca antes.
     * Marcar antes trocaria duplicata (que o consumidor detecta) por perda (que
     * ninguém detecta).
     * <br>DECISÃO: at-least-once assumido em vez de fila FIFO — ver ADR-0002
     *
     * @return número de linhas afetadas.
     */
    int marcarPublicado(long registroId);
}
