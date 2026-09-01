package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.api.LinhaRetorno;
import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;
import com.platinumcoin.ciclo.domain.usecase.ColetarRetornoUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-09: o trailer é a única coisa que diz que o parceiro terminou.
 *
 * <p>Quiescência não prova completude. O arquivo aqui está parado — tamanho e
 * mtime não mudam, o download funciona, o SFTP não tem do que reclamar — e
 * ainda assim está cortado ao meio: o parceiro morreu depois de escrever 7 das
 * 10 linhas que o trailer promete, e o arquivo vai ficar ali parado para
 * sempre.
 *
 * <p>A decisão é descartá-lo <b>inteiro</b>. Aplicar as 7 que estão lá seria
 * indistinguível de um retorno legítimo de 7 linhas — e o fechamento do ciclo
 * transformaria as 3 restantes em {@code SEM_RETORNO}, afirmando silêncio onde
 * havia ruído. Um retorno pela metade aplicado é pior que nenhum, porque nada
 * no banco registra que ele estava pela metade.
 *
 * <p>Descartar não é esquecer: o arquivo é arquivado no S3 <b>antes</b> da
 * validação, justamente porque o arquivo que não fecha é o que alguém vai
 * querer olhar. E continua no diretório do parceiro, para ser reavaliado na
 * próxima passada — se ele um dia for completado, ele passa.
 */
class ArquivoIncompletoNaoEhProcessadoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-CORTADO";
    /** Data própria: o nome e a chave do arquivo incluem a data. */
    private static final LocalDate DATA = LocalDate.of(2026, 10, 5);
    private static final int PROMETIDAS = 10;
    private static final int ESCRITAS = 7;

    private DiretorioDoParceiro parceiro;
    private RepositorioTentativa tentativas;
    private CicloCobranca ciclo;
    private byte[] cortado;
    private String nome;

    @BeforeEach
    void arquivoCortadoNoParceiro() throws SQLException {
        limparTabelas();
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_RETORNO);
        tentativas = new RepositorioTentativaPostgres(dados());

        IntStream.rangeClosed(1, PROMETIDAS)
                .forEach(i -> Cenario.tentativaAberta("FAT-" + i, 1, BANCO, DATA));
        Cenario.cicloTransmitido(CICLO, DATA);
        ciclo = new RepositorioCicloPostgres(dados()).buscar(CICLO).orElseThrow();

        // O parceiro escreveu o header, 7 detalhes e o trailer que ele
        // PRETENDIA cumprir. As 3 linhas do meio nunca chegaram.
        List<LinhaRetorno> escritas = IntStream.rangeClosed(1, ESCRITAS)
                .mapToObj(i -> LinhaRetorno.paga("FAT-" + i + "-T1"))
                .toList();
        cortado = RetornoDoParceiro.arquivoDeclarando(ciclo, escritas, PROMETIDAS);
        nome = RetornoDoParceiro.nome(ciclo);
        parceiro.escrever(DIRETORIO_RETORNO + "/" + nome, cortado);
    }

    @Test
    @DisplayName("trailer que não bate descarta o arquivo inteiro, sem tocar em nenhuma linha do banco")
    void trailerDivergenteNaoAplicaNada() {
        List<ColetarRetornoUseCase.Resultado> passada = coletaCom(canal()).executar();

        assertEquals(1, passada.size());
        assertEquals(ColetarRetornoUseCase.Desfecho.INCOMPLETO, passada.get(0).desfecho());
        assertEquals(ESCRITAS, passada.get(0).linhas(),
                "o parser leu as 7 que existem — é o trailer, e não a leitura, que denuncia");
        assertEquals(0, passada.get(0).aplicadas());

        assertTrue(tentativas.doCiclo(CICLO).stream()
                        .allMatch(t -> t.status() == TentativaDebito.Status.ENVIADO_PARCEIRO),
                "nenhuma das 10 mudou de estado — nem as 7 que estavam no arquivo");
        assertEquals(List.of(), new RepositorioOutboxPostgres(dados()).pendentes(PROMETIDAS),
                "e nada entrou no outbox: sem transição não há lançamento");
    }

    @Test
    @DisplayName("o arquivo que não fecha é arquivado no S3 e continua no diretório do parceiro")
    void arquivoIncompletoEhArquivadoEPermaneceNoParceiro() {
        coletaCom(canal()).executar();

        ChaveArtefato chave = ChaveArtefato.doRetorno(ciclo.banco(), ciclo.dataRef(), nome);
        assertTrue(artefatos().existe(chave),
                "arquivar acontece ANTES de validar: o arquivo que não fechou é o"
                        + " que alguém vai querer olhar");
        assertArrayEquals(cortado, artefatos().get(chave));

        assertEquals(List.of(nome), parceiro.listar(DIRETORIO_RETORNO),
                "e ele continua lá, para ser reavaliado na próxima passada — nada"
                        + " foi marcado como 'em processamento'");
    }
}
