package com.platinumcoin.outbox.domain.usecase;

import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.port.PublicadorLancamento;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;

/**
 * O relay: entrega ao mundo externo o que a transação de negócio deixou
 * registrado como pendente.
 *
 * <p>Três passos, nesta ordem, e sem transação que os una — porque não existe
 * transação que uma um banco e uma fila:
 *
 * <pre>
 * SELECT PENDENTE  →  sqs.send  →  UPDATE PUBLICADO
 * </pre>
 *
 * <p>A ordem é a decisão inteira. Marcar {@code PUBLICADO} antes de enviar
 * fecharia a janela de duplicata e abriria a de perda: uma mensagem que nunca
 * saiu, numa linha que diz que saiu, é uma perda que ninguém detecta. Do jeito
 * que está, um processo que morre entre o envio e o {@code UPDATE} deixa a linha
 * {@code PENDENTE} e a próxima passada republica — duplicata que o consumidor
 * descarta pela {@code chaveDedup}.
 * <br>DECISÃO: at-least-once assumido em vez de fila FIFO — ver ADR-0002
 *
 * <p>Separado de {@code AplicarRetornoUseCase} porque os dois têm ciclos de vida
 * distintos: o aplicador roda por arquivo de retorno, o relay roda
 * continuamente. Juntá-los colocaria o SQS de volta dentro da transação que
 * decide o pagamento — o dual write que o ADR-0001 descarta.
 *
 * <p>Uma mensagem por vez, sem lote no envio: um lote parcialmente enviado
 * exigiria saber quais linhas do lote saíram, e essa resposta o {@code send} em
 * lote não dá de graça. O lote existe só na leitura, onde é barato e não decide
 * nada.
 */
public final class PublicarOutboxUseCase {

    private final RepositorioOutbox outbox;
    private final PublicadorLancamento publicador;

    public PublicarOutboxUseCase(RepositorioOutbox outbox, PublicadorLancamento publicador) {
        this.outbox = outbox;
        this.publicador = publicador;
    }

    /**
     * Publica até {@code limite} pendências, da mais antiga para a mais nova.
     *
     * <p>Uma falha de envio interrompe a passada e propaga: sem backoff, sem DLQ,
     * sem pular a linha que falhou — ver README, "o que foi deliberadamente
     * simplificado". O que já foi publicado antes dela continua publicado, e o
     * que veio depois continua pendente, que é o estado de onde a próxima
     * passada recomeça.
     *
     * @return quantas linhas saíram de {@code PENDENTE} nesta passada.
     */
    public int executar(int limite) {
        int publicados = 0;
        for (RegistroOutbox registro : outbox.pendentes(limite)) {
            // O id que o destino devolve é do transporte, não do domínio: não
            // vira estado nem coluna. O que o relay precisa saber é se a linha
            // saiu de PENDENTE, e isso quem responde é o UPDATE abaixo.
            publicador.publicar(registro.lancamento());
            publicados += outbox.marcarPublicado(registro.id());
        }
        return publicados;
    }
}
