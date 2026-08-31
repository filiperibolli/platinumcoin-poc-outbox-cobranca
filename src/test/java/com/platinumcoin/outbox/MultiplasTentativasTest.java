package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
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

import java.sql.SQLException;
import java.util.List;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static com.platinumcoin.outbox.Cenario.DATA;
import static com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase.Resultado.APLICADO;
import static com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-03 · teste de falha intocável: a mesma fatura tem N tentativas de débito.
 *
 * <p>O banco reapresenta o débito quando não há saldo, e nada impede que duas
 * reapresentações voltem confirmadas — o parceiro processou o mesmo lote duas
 * vezes, ou a segunda foi enviada antes de a primeira ser conciliada. A
 * invariante não é "só uma tentativa paga": é "no máximo um lançamento por
 * fatura". Quem a sustenta é a guarda {@code AND status = 'ABERTA'} no
 * {@code UPDATE} da fatura — quem consegue movê-la ganha o direito de gravar.
 */
class MultiplasTentativasTest extends AmbienteDeTeste {

    private RepositorioTentativa tentativas;
    private RepositorioFatura faturas;
    private RepositorioOutbox outbox;
    private AplicarRetornoUseCase aplicar;

    @BeforeEach
    void duasTentativasPorFatura() throws SQLException {
        limparTabelas();
        tentativas = new RepositorioTentativaPostgres(dados());
        faturas = new RepositorioFaturaPostgres(dados());
        outbox = new RepositorioOutboxPostgres(dados());
        aplicar = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(dados()), tentativas, faturas, outbox);

        Cenario.tentativaAberta("FAT-1", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-1", 2, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 2, BANCO, DATA);
        Cenario.cicloTransmitido("CICLO-1");
    }

    @Test
    @DisplayName("recusa na primeira e pagamento na segunda deixam UMA linha no outbox")
    void reapresentacaoQuePagaGeraUmLancamento() {
        assertEquals(APLICADO,
                LinhaRetorno.naoPaga("FAT-1-T1", TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE)
                        .aplicarCom(aplicar));
        assertEquals(APLICADO_COM_LANCAMENTO, LinhaRetorno.paga("FAT-1-T2").aplicarCom(aplicar));

        assertEquals(1, outbox.pendentes(10).size());
        assertEquals("FAT-1", outbox.pendentes(10).get(0).lancamento().faturaId());
        assertEquals(Fatura.Status.PAGA, faturas.buscar("FAT-1").orElseThrow().status());
    }

    @Test
    @DisplayName("duas tentativas da mesma fatura que paguem ainda deixam UMA linha no outbox")
    void duasTentativasPagasGeramUmLancamentoSo() {
        assertEquals(APLICADO_COM_LANCAMENTO, LinhaRetorno.paga("FAT-2-T1").aplicarCom(aplicar));
        assertEquals(APLICADO, LinhaRetorno.paga("FAT-2-T2").aplicarCom(aplicar),
                "a tentativa transiciona, mas a fatura já estava PAGA: sem segundo lançamento");

        assertEquals(1, outbox.pendentes(10).size());
        assertEquals(TentativaDebito.Status.PAGO, tentativas.buscar("FAT-2-T1").orElseThrow().status());
        assertEquals(TentativaDebito.Status.PAGO, tentativas.buscar("FAT-2-T2").orElseThrow().status(),
                "o desfecho da segunda tentativa é um fato, e é registrado como tal");
    }

    @Test
    @DisplayName("as duas faturas juntas: quatro tentativas, dois lançamentos")
    void umLancamentoPorFaturaENaoPorTentativa() {
        List.of(LinhaRetorno.naoPaga("FAT-1-T1", TentativaDebito.MotivoNaoPago.CONTA_ENCERRADA),
                        LinhaRetorno.paga("FAT-1-T2"),
                        LinhaRetorno.paga("FAT-2-T1"),
                        LinhaRetorno.paga("FAT-2-T2"))
                .forEach(linha -> linha.aplicarCom(aplicar));

        assertEquals(List.of("FAT-1", "FAT-2"),
                outbox.pendentes(10).stream().map(r -> r.lancamento().faturaId()).toList());
    }
}
