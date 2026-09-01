package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.domain.model.Fatura;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.RepositorioFatura;
import com.platinumcoin.ciclo.domain.port.RepositorioOutbox;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;
import com.platinumcoin.ciclo.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-06: o cenário do {@code Main} rodando inteiro, e a saída que ele imprime.
 *
 * <p>Os outros testes provam cada propriedade isolada; este confere que o
 * programa que as <b>mostra</b> continua mostrando. Sem ele, o {@code Main}
 * seria a única parte do projeto que só quebra na mão de quem for demonstrá-lo.
 *
 * <p>As asserções são as linhas da Definition of Done do step-06 — o retorno
 * duplicado com zero linhas afetadas, {@code NAO_PAGO} e {@code SEM_RETORNO}
 * sem outbox, e a contagem final que explicita a duplicata — mais o estado que
 * o banco ficou tendo depois.
 */
class CenarioPontaAPontaTest extends AmbienteDeTeste {

    private static String saida;

    /**
     * Roda o cenário uma vez para a classe inteira: ele é o sujeito dos testes,
     * não o preparo de cada um. O próprio {@code Main} zera banco e fila antes
     * de começar.
     */
    @BeforeAll
    static void rodarOCenario() {
        ByteArrayOutputStream capturada = new ByteArrayOutputStream();
        new Main(ambiente(), new PrintStream(capturada, true, StandardCharsets.UTF_8)).executar();
        saida = capturada.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a saída mostra o retorno duplicado afetando zero linhas")
    void retornoDuplicadoApareceComZeroLinhas() {
        assertEquals(2, ocorrencias("[retorno]  T-1"),
                "o mesmo retorno é aplicado duas vezes, e as duas aparecem");
        assertEquals(1, ocorrencias("0 linhas afetadas — ignorado"),
                "a segunda não encontra a tentativa em ENVIADO_PARCEIRO");
        assertEquals(1, ocorrencias("[outbox]   F-1 + PENDENTE"),
                "e o outbox recebeu uma linha só");
    }

    @Test
    @DisplayName("a saída mostra NAO_PAGO e SEM_RETORNO sem linha de outbox")
    void recusaESilencioNaoGeramOutbox() {
        assertTrue(saida.contains("T-2 ENVIADO_PARCEIRO → NAO_PAGO (SALDO_INSUFICIENTE)"),
                "a recusa vem com motivo, porque houve fato:\n" + saida);
        assertTrue(saida.contains("T-5 (F-4)"),
                "o silêncio vira SEM_RETORNO no fechamento:\n" + saida);
        assertEquals(3, ocorrencias("+ PENDENTE"),
                "três pagamentos, três linhas no outbox — F-2 e F-4 não entram por outro caminho");
        assertTrue(saida.contains("nem NAO_PAGO nem SEM_RETORNO geram lançamento"));
    }

    @Test
    @DisplayName("a saída termina contando a fila e explicitando a duplicata da F-3")
    void aFilaTerminaCom4MensagensE3Chaves() {
        assertEquals(4, ocorrencias("[send]"), "quatro envios: três lançamentos, um republicado");
        assertTrue(saida.contains("4 lançamentos, 3 chaves distintas"),
                "a contagem final é a prova do at-least-once:\n" + saida);
        assertEquals(2, naFilaCom("chaveDedup=F-3"),
                "a duplicata carrega a MESMA chave — é o que o consumidor usa para descartá-la");
    }

    @Test
    @DisplayName("no fim, uma linha de outbox por fatura paga, todas publicadas")
    void invarianteUmLancamentoPorFatura() throws SQLException {
        RepositorioOutbox outbox = new RepositorioOutboxPostgres(dados());
        RepositorioFatura faturas = new RepositorioFaturaPostgres(dados());
        RepositorioTentativa tentativas = new RepositorioTentativaPostgres(dados());

        assertEquals(3, contarOutbox("PUBLICADO"), "F-1, F-2 e F-3 — uma linha cada, publicada");
        assertEquals(List.of(), outbox.pendentes(10), "e nada ficou para trás");

        assertEquals(Fatura.Status.PAGA, faturas.buscar("F-2").orElseThrow().status(),
                "duas tentativas, a segunda pagou");
        assertEquals(Fatura.Status.ABERTA, faturas.buscar("F-4").orElseThrow().status(),
                "silêncio não paga fatura");
        assertEquals(TentativaDebito.Status.SEM_RETORNO,
                tentativas.buscar("T-5").orElseThrow().status());
        assertEquals(TentativaDebito.Status.NAO_PAGO,
                tentativas.buscar("T-2").orElseThrow().status());
    }

    /** Só as linhas do que foi recebido da fila — a chave também aparece no outbox. */
    private static long naFilaCom(String trecho) {
        return saida.lines()
                .filter(linha -> linha.startsWith("[fila]") && linha.contains(trecho))
                .count();
    }

    private static int ocorrencias(String trecho) {
        int total = 0;
        for (int posicao = saida.indexOf(trecho); posicao >= 0;
             posicao = saida.indexOf(trecho, posicao + trecho.length())) {
            total++;
        }
        return total;
    }

    private static int contarOutbox(String status) throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) FROM outbox WHERE status = '" + status + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
