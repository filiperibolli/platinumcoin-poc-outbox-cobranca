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

import static com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO;
import static com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase.Resultado.IGNORADO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-03 · teste de falha intocável: o arquivo de retorno chega duas vezes.
 *
 * <p>Acontece de verdade — o parceiro reenvia, o operador reprocessa, o job
 * roda duas vezes. A garantia não vem de ninguém lembrar de conferir: vem do
 * {@code UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'}, que na segunda vez
 * afeta zero linhas. Zero linhas não é erro; é a resposta.
 */
class RetornoDuplicadoTest extends AmbienteDeTeste {

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
        Cenario.cicloTransmitido("CICLO-1");
    }

    @Test
    @DisplayName("aplicar o mesmo retorno PAGO duas vezes deixa UMA linha no outbox")
    void segundaAplicacaoNaoGeraSegundoLancamento() {
        LinhaRetorno linha = LinhaRetorno.paga("FAT-1-T1");

        assertEquals(APLICADO_COM_LANCAMENTO, linha.aplicarCom(aplicar));
        assertEquals(IGNORADO, linha.aplicarCom(aplicar),
                "a tentativa já não está ENVIADO_PARCEIRO: zero linhas afetadas");

        assertEquals(1, outbox.pendentes(10).size(),
                "um lançamento por fatura, qualquer que seja o número de reprocessamentos");
        assertEquals(TentativaDebito.Status.PAGO, tentativas.buscar("FAT-1-T1").orElseThrow().status());
        assertEquals(Fatura.Status.PAGA, faturas.buscar("FAT-1").orElseThrow().status());
    }

    @Test
    @DisplayName("o arquivo inteiro reprocessado não muda nada na segunda passagem")
    void arquivoInteiroReprocessadoEInofensivo() {
        List<LinhaRetorno> arquivo = List.of(LinhaRetorno.paga("FAT-1-T1"));

        arquivo.forEach(linha -> linha.aplicarCom(aplicar));
        arquivo.forEach(linha -> assertEquals(IGNORADO, linha.aplicarCom(aplicar)));

        assertEquals(1, outbox.pendentes(10).size());
    }

    @Test
    @DisplayName("linha de retorno para tentativa inexistente é ignorada, não é erro")
    void tentativaInexistenteEIgnorada() {
        assertEquals(IGNORADO, LinhaRetorno.paga("NAO-EXISTE").aplicarCom(aplicar));

        assertEquals(List.of(), outbox.pendentes(10));
    }
}
