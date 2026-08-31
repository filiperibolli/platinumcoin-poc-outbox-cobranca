package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.ChaveArtefato;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.Remessa;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.EnviarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-08: a segunda janela do projeto, aberta de propósito — o {@code put} no
 * parceiro acontece antes do {@code COMMIT} que registra que ele aconteceu, e
 * não existe transação que una os dois.
 *
 * <p>Este teste <b>documenta</b> a janela; não a elimina. O processo morre com
 * o arquivo já entregue e o banco ainda dizendo que o ciclo está
 * {@code MONTADO} — e é justamente esse estado que faz a próxima passada
 * transmitir de novo.
 *
 * <p>O que o nome determinístico compra não é o fechamento da janela, é o preço
 * da reexecução. Comparado a {@code CrashDoRelayTest}, onde a mesma ordem custa
 * uma duplicata na fila que o consumidor precisa descartar pela
 * {@code chaveDedup}:
 *
 * <pre>
 * relay   (step-05)   mensagem sem nome   → reexecutar DUPLICA
 * envio   (step-08)   arquivo com nome    → reexecutar SOBRESCREVE
 * remessa (step-07)   objeto com chave    → reexecutar SOBRESCREVE
 * </pre>
 *
 * <p>Sobrescrever não é de graça: o parceiro que já leu o arquivo antes da
 * segunda entrega processa a remessa duas vezes. Quem absorve isso é o
 * {@code UPDATE} condicional do step-03 — {@code RetornoDuplicadoTest} —, e não
 * o SFTP.
 */
class CrashDepoisDoPutTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-CRASH-SFTP";
    /** Data própria: o nome no parceiro inclui a data, e este teste precisa da sua. */
    private static final LocalDate DATA = LocalDate.of(2026, 9, 21);

    private RepositorioCiclo ciclos;
    private RepositorioTentativa tentativas;
    private DiretorioDoParceiro parceiro;
    private Remessa remessa;
    private String nomeNoParceiro;

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
        nomeNoParceiro = ChaveArtefato.nomeDaRemessaNoParceiro(ciclos.buscar(CICLO).orElseThrow());
    }

    @Test
    @DisplayName("morrer entre o put e o COMMIT entrega o arquivo e não avança estado nenhum")
    void crashEntrePutECommitDeixaOArquivoEOEstadoAnterior() {
        assertThrows(FalhaDePersistencia.class,
                () -> enviarCom(new MorreAoRegistrarEnvio(ciclos)).executar(CICLO));

        assertEquals(List.of(nomeNoParceiro), parceiro.listar(DIRETORIO_REMESSA),
                "o put já aconteceu: o parceiro tem o arquivo, e é por isso que a janela existe");
        byte[] primeiraEntrega = parceiro.baixar(DIRETORIO_REMESSA + "/" + nomeNoParceiro);
        assertArrayEquals(remessa.bytes(), primeiraEntrega);

        assertTrue(tentativas.doCiclo(CICLO).stream()
                        .allMatch(t -> t.status() == TentativaDebito.Status.SOLICITADO),
                "o COMMIT não aconteceu: para o banco, o arquivo ainda não saiu");
        assertEquals(CicloCobranca.Status.MONTADO, ciclos.buscar(CICLO).orElseThrow().status());

        // A próxima passada encontra exatamente esse estado e transmite de novo.
        int transmitidas = enviarCom(ciclos).executar(CICLO);

        assertEquals(2, transmitidas);
        assertEquals(1, parceiro.listar(DIRETORIO_REMESSA).size(),
                "UM arquivo no destino, não dois: o nome é derivado do ciclo, e a"
                        + " segunda entrega sobrescreve a primeira");
        assertArrayEquals(primeiraEntrega, parceiro.baixar(DIRETORIO_REMESSA + "/" + nomeNoParceiro),
                "e sobrescreve com os mesmos bytes — o artefato do S3 não mudou");
        assertTrue(tentativas.doCiclo(CICLO).stream()
                        .allMatch(t -> t.status() == TentativaDebito.Status.ENVIADO_PARCEIRO));
        assertEquals(CicloCobranca.Status.ENVIADO, ciclos.buscar(CICLO).orElseThrow().status());
    }

    private EnviarRemessaUseCase enviarCom(RepositorioCiclo ciclos) {
        return new EnviarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()), ciclos, artefatos(), canal());
    }

    /**
     * O processo que morre no instante exato: depois do {@code put}, antes do
     * {@code COMMIT}.
     *
     * <p>Um decorador, e não um {@code if} dentro do use case: simular a morte
     * no código de produção colocaria lá uma linha que só existe para o teste.
     */
    private record MorreAoRegistrarEnvio(RepositorioCiclo real) implements RepositorioCiclo {

        @Override
        public void criar(Transacao tx, CicloCobranca ciclo) {
            real.criar(tx, ciclo);
        }

        @Override
        public Optional<CicloCobranca> buscar(String cicloId) {
            return real.buscar(cicloId);
        }

        @Override
        public Optional<CicloCobranca> buscarPor(String banco, LocalDate dataRef) {
            return real.buscarPor(banco, dataRef);
        }

        @Override
        public int atribuirTentativasAbertas(Transacao tx, CicloCobranca ciclo) {
            return real.atribuirTentativasAbertas(tx, ciclo);
        }

        @Override
        public void registrarRemessa(Transacao tx, String cicloId,
                                     ChaveArtefato chave, String sha256) {
            real.registrarRemessa(tx, cicloId, chave, sha256);
        }

        @Override
        public int registrarEnvio(Transacao tx, String cicloId) {
            throw new FalhaDePersistencia("o processo morreu entre o put e o COMMIT");
        }

        @Override
        public int fechar(Transacao tx, String cicloId) {
            return real.fechar(tx, cicloId);
        }
    }
}
