package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.api.LinhaRetorno;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.RepositorioOutbox;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;
import com.platinumcoin.ciclo.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.ciclo.domain.usecase.FecharCicloUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.ciclo.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;

import static com.platinumcoin.ciclo.domain.model.TentativaDebito.Status.NAO_PAGO;
import static com.platinumcoin.ciclo.domain.model.TentativaDebito.Status.PAGO;
import static com.platinumcoin.ciclo.domain.model.TentativaDebito.Status.SEM_RETORNO;
import static com.platinumcoin.ciclo.domain.usecase.AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO;
import static com.platinumcoin.ciclo.domain.usecase.AplicarRetornoUseCase.Resultado.IGNORADO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-04 · teste de falha intocável: o parceiro não respondeu por três
 * tentativas e respondeu por uma.
 *
 * <p>Silêncio não é resposta. {@code NAO_PAGO} é uma afirmação do parceiro e vem
 * com motivo; a ausência de retorno não tem motivo porque não houve fato. O
 * fechamento tem todo o incentivo para colapsar os dois — é um {@code UPDATE} a
 * menos — e é exatamente isso que este teste proíbe: marcar como não pago
 * dispararia notificação de falha de débito ao cliente com base em nada.
 */
class FechamentoNaoInventaResultadoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-1";

    private RepositorioTentativa tentativas;
    private RepositorioCiclo ciclos;
    private RepositorioOutbox outbox;
    private AplicarRetornoUseCase aplicar;
    private FecharCicloUseCase fechar;

    @BeforeEach
    void tresTentativasTransmitidas() throws SQLException {
        limparTabelas();
        tentativas = new RepositorioTentativaPostgres(dados());
        ciclos = new RepositorioCicloPostgres(dados());
        outbox = new RepositorioOutboxPostgres(dados());
        TransacaoJdbc.Fabrica transacoes = new TransacaoJdbc.Fabrica(dados());
        aplicar = new AplicarRetornoUseCase(transacoes, tentativas,
                new RepositorioFaturaPostgres(dados()), outbox);
        fechar = new FecharCicloUseCase(transacoes, ciclos);

        Cenario.tentativaAberta("FAT-1");
        Cenario.tentativaAberta("FAT-2");
        Cenario.tentativaAberta("FAT-3");
        Cenario.cicloTransmitido(CICLO);
    }

    @Test
    @DisplayName("quem não respondeu vira SEM_RETORNO, e nunca NAO_PAGO")
    void ausenciaDeRetornoNaoViraRecusa() {
        assertEquals(APLICADO_COM_LANCAMENTO, LinhaRetorno.paga("FAT-1-T1").aplicarCom(aplicar));

        assertEquals(2, fechar.executar(CICLO), "duas tentativas ficaram sem resposta");

        assertEquals(Map.of(PAGO, 1L, SEM_RETORNO, 2L), porStatus());
        assertEquals(0L, porStatus().getOrDefault(NAO_PAGO, 0L),
                "o parceiro não recusou nada: inventar a recusa notificaria o cliente de uma falha que não houve");
        assertEquals(1, outbox.pendentes(10).size(),
                "o fechamento não é um pagamento — só o PAGO deixou linha no outbox");
        assertEquals(CicloCobranca.Status.FECHADO, ciclos.buscar(CICLO).orElseThrow().status());
    }

    @Test
    @DisplayName("SEM_RETORNO não tem motivo, porque não houve fato para justificar")
    void semRetornoNaoTemMotivo() {
        fechar.executar(CICLO);

        tentativas.doCiclo(CICLO).forEach(tentativa -> {
            assertEquals(SEM_RETORNO, tentativa.status());
            assertNull(tentativa.motivo(), tentativa.id() + " ganhou um motivo que ninguém informou");
        });
    }

    @Test
    @DisplayName("retorno que chega depois do fechamento é ignorado, não é erro")
    void retornoAtrasadoNaoReabreTentativaFechada() {
        fechar.executar(CICLO);

        assertEquals(IGNORADO, LinhaRetorno.paga("FAT-2-T1").aplicarCom(aplicar),
                "a tentativa já não está ENVIADO_PARCEIRO: zero linhas afetadas");

        assertEquals(SEM_RETORNO, tentativas.buscar("FAT-2-T1").orElseThrow().status());
        assertTrue(outbox.pendentes(10).isEmpty());
    }

    @Test
    @DisplayName("fechar um ciclo já fechado não altera nada")
    void segundoFechamentoNaoAlteraNada() {
        assertEquals(3, fechar.executar(CICLO));

        assertEquals(0, fechar.executar(CICLO), "não sobrou ninguém em ENVIADO_PARCEIRO");

        assertEquals(Map.of(SEM_RETORNO, 3L), porStatus());
        assertEquals(CicloCobranca.Status.FECHADO, ciclos.buscar(CICLO).orElseThrow().status());
    }

    private Map<TentativaDebito.Status, Long> porStatus() {
        return tentativas.doCiclo(CICLO).stream().collect(
                Collectors.groupingBy(TentativaDebito::status, Collectors.counting()));
    }
}
