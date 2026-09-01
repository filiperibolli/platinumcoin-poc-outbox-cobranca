package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.api.LinhaRetorno;
import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.CanalArquivos;
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
import java.util.Optional;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-09: um arquivo aparece na listagem antes de estar pronto.
 *
 * <p>O {@code put} do parceiro escreve direto no caminho final, sem temporário
 * e sem rename — é o que {@code CanalArquivosSftp} faz do nosso lado, e é o que
 * um parceiro de verdade faz do dele. Baixar um arquivo nesse estado dá um
 * arquivo <b>válido para o SFTP</b> e cortado para o negócio: o transporte não
 * tem como saber que faltava coisa.
 *
 * <p>A regra é tamanho <b>e</b> mtime iguais em duas leituras separadas por um
 * intervalo. Quem cresce entre elas não é baixado — e o teste prova isso
 * contando os downloads, não inferindo do estado do banco.
 *
 * <p>O crescimento vem de um decorador do canal, e não de uma escrita
 * cronometrada em outra thread: um teste cuja asserção depende de qual das duas
 * threads chegou primeiro falha sozinho uma vez a cada tantas execuções, e um
 * teste que falha sozinho deixa de ser lido.
 */
class ArquivoEmEscritaNaoEhBaixadoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-ESCRITA";
    /** Data própria: o nome e a chave do arquivo incluem a data. */
    private static final LocalDate DATA = LocalDate.of(2026, 10, 12);

    private DiretorioDoParceiro parceiro;
    private RepositorioTentativa tentativas;
    private CicloCobranca ciclo;
    private String caminho;
    private byte[] completo;

    @BeforeEach
    void arquivoPelaMetadeNoParceiro() throws SQLException {
        limparTabelas();
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_RETORNO);
        tentativas = new RepositorioTentativaPostgres(dados());

        Cenario.tentativaAberta("FAT-1", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 1, BANCO, DATA);
        Cenario.cicloTransmitido(CICLO, DATA);
        ciclo = new RepositorioCicloPostgres(dados()).buscar(CICLO).orElseThrow();

        completo = RetornoDoParceiro.arquivo(ciclo,
                List.of(LinhaRetorno.paga("FAT-1-T1"), LinhaRetorno.paga("FAT-2-T1")));
        caminho = DIRETORIO_RETORNO + "/" + RetornoDoParceiro.nome(ciclo);

        // O que o parceiro tinha escrito até agora: header e a primeira linha.
        // Nem o trailer chegou — não há sequer o que conferir.
        parceiro.escrever(caminho, RetornoDoParceiro.arquivoSemTrailer(
                ciclo, List.of(LinhaRetorno.paga("FAT-1-T1"))));
    }

    @Test
    @DisplayName("o que cresce entre as duas leituras não é baixado, e a passada seguinte o processa")
    void arquivoEmEscritaEsperaAProximaPassada() {
        CanalQueCresce crescendo = new CanalQueCresce(
                canal(), () -> parceiro.escrever(caminho, completo));

        List<ColetarRetornoUseCase.Resultado> primeira = coletaCom(crescendo).executar();

        assertEquals(1, primeira.size());
        assertEquals(ColetarRetornoUseCase.Desfecho.EM_ESCRITA, primeira.get(0).desfecho());
        assertEquals(0, crescendo.downloads(),
                "não basta o estado do banco não ter mudado: o arquivo não pode nem"
                        + " ter sido transferido");
        assertFalse(artefatos().existe(
                        ChaveArtefato.doRetorno(ciclo.banco(), ciclo.dataRef(),
                                RetornoDoParceiro.nome(ciclo))),
                "e nada foi arquivado: não se arquiva o que não se baixou");
        assertTrue(tentativas.doCiclo(CICLO).stream()
                .allMatch(t -> t.status() == TentativaDebito.Status.ENVIADO_PARCEIRO));

        // O parceiro terminou de escrever. Nada foi marcado como "em
        // processamento" na passada anterior, então esta recomeça do mundo.
        List<ColetarRetornoUseCase.Resultado> segunda = coletaCom(canal()).executar();

        assertEquals(ColetarRetornoUseCase.Desfecho.APLICADO, segunda.get(0).desfecho());
        assertEquals(2, segunda.get(0).linhas());
        assertEquals(2, segunda.get(0).aplicadas());
        assertTrue(tentativas.doCiclo(CICLO).stream()
                .allMatch(t -> t.status() == TentativaDebito.Status.PAGO));
        assertEquals(2, new RepositorioOutboxPostgres(dados()).pendentes(10).size());
    }

    /**
     * O parceiro escrevendo mais um pedaço entre as duas leituras de atributos.
     *
     * <p>Um decorador, e não uma thread: o crescimento acontece <b>depois</b> da
     * primeira leitura e <b>antes</b> da segunda, que é o instante que a
     * quiescência existe para pegar. Uma thread acertaria esse instante às
     * vezes.
     */
    private static final class CanalQueCresce implements CanalArquivos {

        private final CanalArquivos real;
        private final Runnable escritaDoParceiro;
        private boolean cresceu;
        private int downloads;

        private CanalQueCresce(CanalArquivos real, Runnable escritaDoParceiro) {
            this.real = real;
            this.escritaDoParceiro = escritaDoParceiro;
        }

        int downloads() {
            return downloads;
        }

        @Override
        public void enviar(String nome, byte[] conteudo) {
            real.enviar(nome, conteudo);
        }

        @Override
        public List<String> listar(String diretorio) {
            return real.listar(diretorio);
        }

        @Override
        public Optional<Atributos> atributos(String caminho) {
            Optional<Atributos> lidos = real.atributos(caminho);
            if (!cresceu) {
                cresceu = true;
                escritaDoParceiro.run();
            }
            return lidos;
        }

        @Override
        public byte[] baixar(String caminho) {
            downloads++;
            return real.baixar(caminho);
        }
    }
}
