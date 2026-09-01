package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.Remessa;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;
import com.platinumcoin.ciclo.domain.usecase.EnviarRemessaUseCase;
import com.platinumcoin.ciclo.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.ciclo.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.ciclo.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-08: a transmissão acontecendo de verdade — conexão SSH, arquivo
 * aparecendo no diretório de outro host.
 *
 * <p>Até o step-06 a transmissão era um {@code UPDATE} solto com um comentário
 * dizendo "transporte fora de escopo". Aqui ela é I/O contra um servidor SFTP,
 * e a asserção é feita do lado do parceiro: outra conexão, outro cliente,
 * listando o diretório e baixando o que estiver lá.
 *
 * <p>O que o teste amarra é a ordem: {@code s3.get → sftp.put → COMMIT}. Os
 * bytes que chegam ao parceiro são os do artefato do step-07, e não uma
 * segunda geração — é o que impede que o arquivo enviado e o arquivado
 * divirjam no dia em que a projeção mudar.
 */
class EnvioChegaNoParceiroTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-ENVIO";
    /** Data própria: o nome no parceiro inclui a data, e este teste precisa da sua. */
    private static final LocalDate DATA = LocalDate.of(2026, 9, 14);

    private RepositorioCiclo ciclos;
    private RepositorioTentativa tentativas;
    private DiretorioDoParceiro parceiro;
    private Remessa remessa;

    @BeforeEach
    void remessaGerada() throws SQLException {
        limparTabelas();
        ciclos = new RepositorioCicloPostgres(dados());
        tentativas = new RepositorioTentativaPostgres(dados());
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_REMESSA);

        Cenario.tentativaAberta("FAT-1", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 1, BANCO, DATA);
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar(CICLO, BANCO, DATA);
        remessa = new GerarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()), ciclos,
                tentativas, new RepositorioFaturaPostgres(dados()), artefatos())
                .executar(CICLO);
    }

    @Test
    @DisplayName("o arquivo chega ao parceiro com os bytes do artefato do S3")
    void bytesDoArtefatoChegamAoParceiro() {
        enviar().executar(CICLO);

        String nome = ChaveArtefato.nomeDaRemessaNoParceiro(ciclos.buscar(CICLO).orElseThrow());
        assertEquals("341-20260914-CICLO-ENVIO.rem", nome,
                "o nome é derivado do ciclo: é o que faz o reenvio sobrescrever");
        assertEquals(List.of(nome), parceiro.listar(DIRETORIO_REMESSA),
                "um arquivo no diretório do parceiro, com o nome do ciclo");

        byte[] noParceiro = parceiro.baixar(DIRETORIO_REMESSA + "/" + nome);
        assertArrayEquals(artefatos().get(remessa.chave()), noParceiro,
                "o que foi transmitido é o artefato do S3, não uma segunda geração");
        assertArrayEquals(remessa.bytes(), noParceiro);
    }

    @Test
    @DisplayName("as transições do ciclo inteiro acontecem depois do put")
    void transicoesAcontecemDepoisDoPut() {
        int transmitidas = enviar().executar(CICLO);

        assertEquals(2, transmitidas, "as duas tentativas do ciclo saíram no mesmo arquivo");
        assertTrue(tentativas.doCiclo(CICLO).stream()
                        .allMatch(t -> t.status() == TentativaDebito.Status.ENVIADO_PARCEIRO),
                "um arquivo é um evento: ou o parceiro recebeu o ciclo, ou não recebeu");
        assertEquals(CicloCobranca.Status.ENVIADO, ciclos.buscar(CICLO).orElseThrow().status());

        // Reenvio: nada mais a transmitir, e nenhum estado sobrescrito.
        assertEquals(0, enviar().executar(CICLO),
                "as guardas de status tornam a segunda passada inócua");
        assertEquals(1, parceiro.listar(DIRETORIO_REMESSA).size(),
                "e o nome determinístico faz dela uma sobrescrita, não um segundo arquivo");
    }

    @Test
    @DisplayName("enviar antes de gerar a remessa é erro de ordem, não transmissão vazia")
    void cicloSemRemessaNaoEhTransmitido() throws SQLException {
        limparTabelas();
        Cenario.tentativaAberta("FAT-3", 1, BANCO, DATA);
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar(CICLO, BANCO, DATA);

        IllegalStateException semRemessa =
                assertThrows(IllegalStateException.class, () -> enviar().executar(CICLO));

        assertTrue(semRemessa.getMessage().contains(CICLO), semRemessa.getMessage());
        assertEquals(List.of(), parceiro.listar(DIRETORIO_REMESSA),
                "o parceiro não recebe arquivo nenhum quando não há artefato para ler");
    }

    private EnviarRemessaUseCase enviar() {
        return new EnviarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()), ciclos, artefatos(), canal());
    }
}
