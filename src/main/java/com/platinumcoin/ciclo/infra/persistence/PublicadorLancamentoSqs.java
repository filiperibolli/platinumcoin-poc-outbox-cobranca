package com.platinumcoin.ciclo.infra.persistence;

import com.platinumcoin.ciclo.domain.exception.FalhaDePublicacao;
import com.platinumcoin.ciclo.domain.model.LancamentoContabil;
import com.platinumcoin.ciclo.domain.port.PublicadorLancamento;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.util.Map;

/**
 * {@link PublicadorLancamento} sobre uma fila SQS — o mainframe legado do outro
 * lado.
 *
 * <p>A {@code chaveDedup} viaja como atributo da mensagem, e não dentro do
 * corpo: o consumidor decide se já viu esta chave <b>antes</b> de desserializar
 * o payload, e o corpo continua sendo exatamente o texto gravado na coluna
 * {@code payload} do outbox.
 * <br>DECISÃO: at-least-once assumido em vez de fila FIFO — ver ADR-0002
 *
 * <p>Fila padrão, não FIFO. A dedup do FIFO vale por 5 minutos, e o caso que
 * este projeto precisa cobrir é o relay que volta depois de um incidente longo —
 * exatamente o que a janela não cobre. A responsabilidade fica declarada no
 * atributo em vez de escondida numa configuração de fila.
 */
public final class PublicadorLancamentoSqs implements PublicadorLancamento {

    /** O nome do atributo é contrato com o consumidor, tanto quanto o payload. */
    public static final String ATRIBUTO_DEDUP = "chaveDedup";

    private final SqsClient sqs;
    private final String urlDaFila;

    public PublicadorLancamentoSqs(SqsClient sqs, String urlDaFila) {
        this.sqs = sqs;
        this.urlDaFila = urlDaFila;
    }

    @Override
    public String publicar(LancamentoContabil lancamento) {
        try {
            return sqs.sendMessage(mensagem -> mensagem
                            .queueUrl(urlDaFila)
                            .messageBody(Payload.escrever(lancamento))
                            .messageAttributes(Map.of(ATRIBUTO_DEDUP, MessageAttributeValue.builder()
                                    .dataType("String")
                                    .stringValue(lancamento.chaveDedup())
                                    .build())))
                    .messageId();
        } catch (SdkException e) {
            // Traduz antes de atravessar a porta: o relay não pode depender de
            // um tipo do AWS SDK para saber que o envio falhou. E a falha aqui
            // é ambígua por natureza — a mensagem pode ter chegado —, o que é
            // justamente o motivo de a linha continuar PENDENTE.
            throw new FalhaDePublicacao(
                    "falha ao publicar o lançamento da fatura " + lancamento.faturaId(), e);
        }
    }
}
