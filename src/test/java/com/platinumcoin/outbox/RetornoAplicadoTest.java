package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.PublicadorLancamento;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-03: a transação que decide e registra a intenção de publicar.
 *
 * <p>O caminho feliz e os dois desfechos que <b>não</b> são pagamento. A
 * assimetria é o ponto: {@code PAGO} move três linhas em um {@code COMMIT};
 * {@code NAO_PAGO} e {@code ERRO} movem uma e param — a pergunta é
 * {@code geraLancamentoContabil()}, e só um estado responde sim.
 */
class RetornoAplicadoTest extends AmbienteDeTeste {

    private RepositorioTentativa tentativas;
    private RepositorioFatura faturas;
    private RepositorioOutbox outbox;
    private AplicarRetornoUseCase aplicar;

    @BeforeEach
    void cicloJaTransmitido() throws SQLException {
        limparTabelas();
        tentativas = new RepositorioTentativaPostgres(dados());
        faturas = new RepositorioFaturaPostgres(dados());
        outbox = new RepositorioOutboxPostgres(dados());
        aplicar = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(dados()), tentativas, faturas, outbox);

        Cenario.tentativaAberta("FAT-1");
        Cenario.tentativaAberta("FAT-2");
        Cenario.tentativaAberta("FAT-3");
        Cenario.cicloTransmitido("CICLO-1");
    }

    @Test
    @DisplayName("retorno PAGO move tentativa, fatura e outbox no mesmo COMMIT")
    void pagoDecideERegistraAIntencaoDePublicar() {
        AplicarRetornoUseCase.Resultado resultado = LinhaRetorno.paga("FAT-1-T1").aplicarCom(aplicar);

        assertEquals(AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO, resultado);
        assertEquals(TentativaDebito.Status.PAGO, statusDa("FAT-1-T1"));
        assertEquals(Fatura.Status.PAGA, statusDaFatura("FAT-1"));

        List<RegistroOutbox> pendentes = outbox.pendentes(10);
        assertEquals(1, pendentes.size());
        assertEquals("FAT-1", pendentes.get(0).lancamento().faturaId());
        assertEquals(0, new BigDecimal("100.00").compareTo(pendentes.get(0).lancamento().valor()));
        assertEquals("FAT-1", pendentes.get(0).lancamento().chaveDedup(),
                "a chave de dedup é derivada do domínio, não gerada no envio");
    }

    @Test
    @DisplayName("retorno NAO_PAGO grava o motivo e não gera linha no outbox")
    void naoPagoGravaMotivoENaoGeraLancamento() {
        AplicarRetornoUseCase.Resultado resultado =
                LinhaRetorno.naoPaga("FAT-2-T1", TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE)
                        .aplicarCom(aplicar);

        assertEquals(AplicarRetornoUseCase.Resultado.APLICADO, resultado);
        TentativaDebito tentativa = tentativas.buscar("FAT-2-T1").orElseThrow();
        assertEquals(TentativaDebito.Status.NAO_PAGO, tentativa.status());
        assertEquals(TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE, tentativa.motivo());
        assertEquals(Fatura.Status.ABERTA, statusDaFatura("FAT-2"),
                "recusa não paga fatura");
        assertEquals(List.of(), outbox.pendentes(10),
                "NAO_PAGO não é pagamento — o mainframe não tem o que contabilizar");
    }

    @Test
    @DisplayName("retorno ERRO resolve a tentativa sem motivo e sem outbox")
    void erroNaoGeraLancamento() {
        AplicarRetornoUseCase.Resultado resultado = LinhaRetorno.comErro("FAT-3-T1").aplicarCom(aplicar);

        assertEquals(AplicarRetornoUseCase.Resultado.APLICADO, resultado);
        TentativaDebito tentativa = tentativas.buscar("FAT-3-T1").orElseThrow();
        assertEquals(TentativaDebito.Status.ERRO, tentativa.status());
        assertNull(tentativa.motivo(), "só NAO_PAGO tem motivo: ERRO não é afirmação do parceiro");
        assertEquals(Fatura.Status.ABERTA, statusDaFatura("FAT-3"));
        assertEquals(List.of(), outbox.pendentes(10));
    }

    /**
     * A propriedade que o projeto existe para provar não é sobre o que o use
     * case faz, mas sobre o que ele <b>não pode</b> fazer: se ele não conhece o
     * publicador, não há como chamar o SQS com a transação aberta. Por
     * construção, não por disciplina.
     */
    @Test
    @DisplayName("o use case de retorno não conhece o publicador — o SQS não cabe na transação")
    void nenhumSistemaExternoDentroDaTransacao() {
        List<String> vazamentos = Arrays.stream(AplicarRetornoUseCase.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName)
                .filter(nome -> nome.equals(PublicadorLancamento.class.getName()))
                .toList();

        assertEquals(List.of(), vazamentos, "PublicadorLancamento não pode ser dependência do aplicador");
        for (Constructor<?> construtor : AplicarRetornoUseCase.class.getDeclaredConstructors()) {
            assertTrue(Arrays.stream(construtor.getParameterTypes())
                            .noneMatch(tipo -> tipo == PublicadorLancamento.class),
                    "nem sequer recebido no construtor: " + construtor);
        }
    }

    private TentativaDebito.Status statusDa(String tentativaId) {
        return tentativas.buscar(tentativaId).orElseThrow().status();
    }

    private Fatura.Status statusDaFatura(String faturaId) {
        return faturas.buscar(faturaId).orElseThrow().status();
    }
}
