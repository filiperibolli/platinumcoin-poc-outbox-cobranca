package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * step-03: as três escritas são uma só, ou não são nenhuma.
 *
 * <p>É o teste que separa este desenho do dual write. No dual write, o que
 * falha depois da decisão não desfaz a decisão: a fatura fica {@code PAGA} e a
 * publicação some — perda silenciosa, que ninguém detecta porque não sobrou
 * registro de que havia algo a publicar.
 *
 * <p>Aqui, a falha entre o {@code UPDATE fatura} e o {@code INSERT outbox}
 * derruba a transação inteira. O sistema volta ao estado anterior, e a
 * reexecução — que é o comportamento normal de um arquivo reprocessado —
 * processa como se nada tivesse acontecido.
 * <br>DECISÃO: outbox na mesma transação, publicação fora — ver ADR-0001
 */
class DualWriteEvitadoTest extends AmbienteDeTeste {

    private RepositorioTentativa tentativas;
    private RepositorioFatura faturas;
    private RepositorioOutbox outbox;

    @BeforeEach
    void cicloJaTransmitido() throws SQLException {
        limparTabelas();
        tentativas = new RepositorioTentativaPostgres(dados());
        faturas = new RepositorioFaturaPostgres(dados());
        outbox = new RepositorioOutboxPostgres(dados());

        Cenario.tentativaAberta("FAT-1");
        Cenario.cicloTransmitido("CICLO-1");
    }

    @Test
    @DisplayName("falha ao gravar o outbox desfaz também a tentativa e a fatura")
    void falhaNoOutboxDesfazADecisao() {
        AplicarRetornoUseCase aplicarQueFalha = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(dados()), tentativas, faturas, new FalhaAoInserir());

        assertThrows(FalhaDePersistencia.class,
                () -> LinhaRetorno.paga("FAT-1-T1").aplicarCom(aplicarQueFalha));

        assertEquals(List.of(), outbox.pendentes(10),
                "o INSERT que estourou não deixou linha");
        assertEquals(Fatura.Status.ABERTA, faturas.buscar("FAT-1").orElseThrow().status(),
                "o UPDATE da fatura estava na mesma transação do INSERT que falhou");
        assertEquals(TentativaDebito.Status.ENVIADO_PARCEIRO,
                tentativas.buscar("FAT-1-T1").orElseThrow().status(),
                "e a tentativa continua esperando retorno — nada foi decidido pela metade");
    }

    @Test
    @DisplayName("depois da falha, reprocessar o retorno funciona normalmente")
    void reexecucaoDepoisDaFalhaProcessaNormal() {
        AplicarRetornoUseCase aplicarQueFalha = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(dados()), tentativas, faturas, new FalhaAoInserir());
        LinhaRetorno linha = LinhaRetorno.paga("FAT-1-T1");
        assertThrows(FalhaDePersistencia.class, () -> linha.aplicarCom(aplicarQueFalha));

        AplicarRetornoUseCase aplicar = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(dados()), tentativas, faturas, outbox);

        assertEquals(AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO,
                linha.aplicarCom(aplicar),
                "a tentativa nunca saiu de ENVIADO_PARCEIRO: a mesma linha ainda tem efeito");
        assertEquals(1, outbox.pendentes(10).size());
        assertEquals(Fatura.Status.PAGA, faturas.buscar("FAT-1").orElseThrow().status());
    }

    /**
     * O outbox que estoura no instante exato em que o dual write perderia a
     * mensagem: a decisão já foi tomada e a publicação ainda não foi registrada.
     */
    private static final class FalhaAoInserir implements RepositorioOutbox {

        @Override
        public void inserir(Transacao tx, LancamentoContabil lancamento) {
            throw new FalhaDePersistencia("banco caiu ao gravar o outbox");
        }

        @Override
        public List<RegistroOutbox> pendentes(int limite) {
            throw new UnsupportedOperationException("o relay não participa deste teste");
        }

        @Override
        public int marcarPublicado(long registroId) {
            throw new UnsupportedOperationException("o relay não participa deste teste");
        }
    }
}
