package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.exception.FalhaDePublicacao;
import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.port.PublicadorLancamento;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.PublicarOutboxUseCase;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * step-05: o preço da ordem correta, cobrado e aceito.
 *
 * <p>O relay morre entre o {@code send} e o {@code UPDATE}. A mensagem já saiu,
 * a linha continua {@code PENDENTE}, e a próxima passada publica de novo:
 * <b>duas mensagens para um lançamento</b>. Isto não é um bug tolerado — é o
 * comportamento correto do desenho, e por isso está asserido num teste em vez de
 * anotado num rodapé.
 * <br>DECISÃO: at-least-once assumido em vez de fila FIFO — ver ADR-0002
 *
 * <p>A alternativa — marcar {@code PUBLICADO} antes de enviar — está no segundo
 * teste. Ela fecha a janela de duplicata e abre a de perda, que é estritamente
 * pior: a duplicata o consumidor descarta pela {@code chaveDedup}; a mensagem
 * que nunca saiu, numa linha que diz que saiu, ninguém procura.
 */
class CrashDoRelayTest extends AmbienteDeTeste {

    private RepositorioOutbox outbox;
    private PublicadorLancamento sqs;

    @BeforeEach
    void pagamentoDecididoEPendente() throws SQLException {
        limparTabelas();
        drenarFila(0);
        outbox = new RepositorioOutboxPostgres(dados());
        sqs = new PublicadorLancamentoSqs(sqs(), urlDaFila());

        Cenario.pagamentoPendente("FAT-1", "CICLO-1");
    }

    @Test
    @DisplayName("morrer entre o envio e a marcação republica: 2 mensagens, 1 chave de dedup")
    void crashEntreEnvioEMarcacaoRepublica() {
        PublicarOutboxUseCase relayQueMorre =
                new PublicarOutboxUseCase(new MorreAoMarcar(outbox), sqs);

        assertThrows(FalhaDePersistencia.class, () -> relayQueMorre.executar(10));

        assertEquals(1, outbox.pendentes(10).size(),
                "a mensagem saiu, mas a linha continua PENDENTE — é o que autoriza a republicação");

        assertEquals(1, new PublicarOutboxUseCase(outbox, sqs).executar(10));

        List<Message> naFila = drenarFila(2);
        assertEquals(2, naFila.size(), "duas mensagens para um lançamento: o preço da ordem correta");
        assertEquals(Set.of("FAT-1"), chaves(naFila),
                "a mesma chave de dedup nas duas — derivada da fatura, não gerada no envio");
        assertEquals(1, naFila.stream().map(Message::body).distinct().count(),
                "e o mesmo corpo: republicar não produz uma mensagem diferente");
        assertEquals(List.of(), outbox.pendentes(10), "a segunda passada fechou a pendência");
    }

    @Test
    @DisplayName("falha no envio não marca nada: nenhuma mensagem se perde")
    void falhaNoEnvioMantemAPendencia() {
        PublicarOutboxUseCase relayQueNaoEnvia =
                new PublicarOutboxUseCase(outbox, new FalhaAoEnviar());

        assertThrows(FalhaDePublicacao.class, () -> relayQueNaoEnvia.executar(10));

        assertEquals(1, outbox.pendentes(10).size(), "sem envio, nada é marcado como publicado");
        assertEquals(List.of(), drenarFila(0));

        assertEquals(1, new PublicarOutboxUseCase(outbox, sqs).executar(10));
        assertEquals(1, drenarFila(1).size(),
                "a passada seguinte publica uma vez — o lançamento não sumiu no caminho");
    }

    @Test
    @DisplayName("o crash não avança para as pendências seguintes")
    void crashInterrompeAPassada() {
        Cenario.pagamentoPendente("FAT-2", "CICLO-2", Cenario.DATA.plusDays(1));

        assertThrows(FalhaDePersistencia.class,
                () -> new PublicarOutboxUseCase(new MorreAoMarcar(outbox), sqs).executar(10));

        assertEquals(2, outbox.pendentes(10).size(),
                "as duas continuam pendentes: a que saiu sem ser marcada e a que nem chegou a sair");
        assertEquals(Set.of("FAT-1"), chaves(drenarFila(1)),
                "só a primeira foi enviada — sem backoff nem pulo, a passada para na falha");
    }

    private static Set<String> chaves(List<Message> mensagens) {
        return mensagens.stream()
                .map(mensagem -> mensagem.messageAttributes()
                        .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .map(MessageAttributeValue::stringValue)
                .collect(Collectors.toSet());
    }

    /**
     * O processo que morre no instante exato em que a duplicata nasce: depois do
     * {@code send}, antes do {@code UPDATE}.
     */
    private record MorreAoMarcar(RepositorioOutbox real) implements RepositorioOutbox {

        @Override
        public void inserir(Transacao tx, LancamentoContabil lancamento) {
            real.inserir(tx, lancamento);
        }

        @Override
        public List<RegistroOutbox> pendentes(int limite) {
            return real.pendentes(limite);
        }

        @Override
        public int marcarPublicado(long registroId) {
            throw new FalhaDePersistencia("o relay morreu entre o send e o UPDATE");
        }
    }

    /** O envio que falha antes de sair — a janela oposta. */
    private static final class FalhaAoEnviar implements PublicadorLancamento {

        @Override
        public String publicar(LancamentoContabil lancamento) {
            throw new FalhaDePublicacao("a fila não respondeu",
                    new IllegalStateException("timeout"));
        }
    }
}
