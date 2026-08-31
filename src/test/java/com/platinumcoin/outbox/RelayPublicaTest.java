package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.usecase.PublicarOutboxUseCase;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-05: o que a transação registrou como pendente chega de fato à fila.
 *
 * <p>A mensagem é <b>recebida</b> do LocalStack, e não conferida num mock. Um
 * mock provaria que o código chamou o método certo; o que precisa ser provado
 * aqui é que o outro lado tem o que consumir — com a {@code chaveDedup} no lugar
 * onde o consumidor a procura.
 */
class RelayPublicaTest extends AmbienteDeTeste {

    private RepositorioOutbox outbox;
    private PublicarOutboxUseCase relay;

    @BeforeEach
    void pagamentoDecididoEPendente() throws SQLException {
        limparTabelas();
        drenarFila(0);
        outbox = new RepositorioOutboxPostgres(dados());
        relay = new PublicarOutboxUseCase(outbox,
                new PublicadorLancamentoSqs(sqs(), urlDaFila()));

        Cenario.pagamentoPendente("FAT-1", "CICLO-1");
    }

    @Test
    @DisplayName("a linha pendente vira PUBLICADO e a mensagem chega na fila")
    void publicaEMarca() throws SQLException {
        long registroId = outbox.pendentes(10).get(0).id();

        assertEquals(1, relay.executar(10));

        List<Message> naFila = drenarFila(1);
        assertEquals(1, naFila.size(), "o mainframe recebe exatamente uma mensagem");

        Message mensagem = naFila.get(0);
        assertEquals("FAT-1",
                mensagem.messageAttributes().get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP).stringValue(),
                "a chave de dedup viaja como atributo, onde o consumidor a lê sem abrir o corpo");
        assertEquals("{\"faturaId\":\"FAT-1\",\"valor\":\"100.00\"}", mensagem.body(),
                "o corpo publicado é o mesmo texto gravado na coluna payload");

        assertEquals(List.of(), outbox.pendentes(10), "nada mais espera publicação");
        assertEquals(RegistroOutbox.Status.PUBLICADO.name(), statusNoBanco(registroId));
        assertNotNull(publicadoEm(registroId), "publicado_em registra quando a linha saiu");
    }

    @Test
    @DisplayName("a segunda passada não republica o que já saiu")
    void segundaPassadaNaoRepublica() {
        assertEquals(1, relay.executar(10));
        assertEquals(1, drenarFila(1).size());

        assertEquals(0, relay.executar(10), "o SELECT de PENDENTE não devolve mais nada");
        assertEquals(List.of(), drenarFila(0), "e nenhuma mensagem nova apareceu na fila");
    }

    @Test
    @DisplayName("o relay publica cada decisão pendente, na ordem em que foram tomadas")
    void publicaTodasAsPendencias() {
        Cenario.pagamentoPendente("FAT-2", "CICLO-2", Cenario.DATA.plusDays(1));

        assertEquals(2, relay.executar(10));

        List<Message> naFila = drenarFila(2);
        assertEquals(2, naFila.size());
        assertTrue(naFila.stream().anyMatch(m -> m.body().contains("FAT-1")));
        assertTrue(naFila.stream().anyMatch(m -> m.body().contains("FAT-2")));
    }

    @Test
    @DisplayName("o limite recorta a passada, e o resto continua pendente")
    void limiteRecortaAPassada() {
        Cenario.pagamentoPendente("FAT-2", "CICLO-2", Cenario.DATA.plusDays(1));

        assertEquals(1, relay.executar(1));

        List<RegistroOutbox> pendentes = outbox.pendentes(10);
        assertEquals(1, pendentes.size(), "a segunda decisão continua esperando");
        assertEquals("FAT-2", pendentes.get(0).lancamento().faturaId(),
                "a mais antiga é a que sai primeiro");
        assertEquals(1, drenarFila(1).size());
    }

    @Test
    @DisplayName("sem pendência, a passada é de zero — não é erro")
    void semPendenciaNadaAcontece() {
        relay.executar(10);
        drenarFila(1);

        assertEquals(0, relay.executar(10));
    }

    @Test
    @DisplayName("marcar de novo o que já saiu não afeta linha nenhuma")
    void segundaMarcacaoAfetaZeroLinhas() throws SQLException {
        long registroId = outbox.pendentes(10).get(0).id();
        assertEquals(1, relay.executar(10));
        drenarFila(1);
        String quandoSaiu = publicadoEm(registroId);

        assertEquals(0, outbox.marcarPublicado(registroId),
                "zero linhas é a resposta de 'outro já marcou', o mesmo idioma do retorno e do fechamento");
        assertEquals(quandoSaiu, publicadoEm(registroId), "e publicado_em continua o do envio real");
    }

    private static String statusNoBanco(long registroId) throws SQLException {
        return coluna(registroId, "status");
    }

    private static String publicadoEm(long registroId) throws SQLException {
        return coluna(registroId, "publicado_em");
    }

    private static String coluna(long registroId, String nome) throws SQLException {
        try (Connection conexao = novaConexao();
             PreparedStatement stmt = conexao.prepareStatement(
                     "SELECT " + nome + " FROM outbox WHERE id = ?")) {
            stmt.setLong(1, registroId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
