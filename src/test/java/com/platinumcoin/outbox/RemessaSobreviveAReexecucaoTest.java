package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.ChaveArtefato;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.Remessa;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.Transacao;
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
import java.util.Optional;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-07: a janela entre o {@code put} e o {@code COMMIT}, aberta de
 * propósito — e o motivo de ela não custar nada.
 *
 * <p>O processo morre depois de gravar o objeto e antes de registrar a chave no
 * ciclo. O que fica é um artefato órfão: existe no S3, não é apontado por
 * ninguém. Em qualquer outro efeito externo isso seria um problema; aqui não é,
 * porque a chave é determinística e o conteúdo é função pura do ciclo — a
 * reexecução grava exatamente os mesmos bytes na mesma chave, e o órfão vira o
 * artefato definitivo sem que nada precise ser limpo.
 *
 * <p>É o contraste com {@code CrashDoRelayTest}, onde a mesma ordem custa uma
 * duplicata na fila: a diferença não é a confiabilidade do S3, é que o objeto é
 * <b>endereçável</b> e a mensagem não. E é o contraste com o step-08, onde o
 * mesmo desenho custa uma transmissão a mais.
 * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
 */
class RemessaSobreviveAReexecucaoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-CRASH";
    /** Data própria: a chave inclui a data, e este teste precisa da sua. */
    private static final LocalDate DATA = LocalDate.of(2026, 9, 7);

    private RepositorioCiclo ciclos;
    private ArmazenamentoArtefato artefatos;
    private ChaveArtefato chave;

    @BeforeEach
    void cicloMontado() throws SQLException {
        limparTabelas();
        ciclos = new RepositorioCicloPostgres(dados());
        artefatos = artefatos();

        Cenario.tentativaAberta("FAT-1", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 1, BANCO, DATA);
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar(CICLO, BANCO, DATA);

        chave = ChaveArtefato.daRemessa(ciclos.buscar(CICLO).orElseThrow());
    }

    @Test
    @DisplayName("morrer entre o put e o COMMIT deixa um órfão idêntico ao que a reexecução grava")
    void crashEntrePutECommitNaoProduzArtefatoDivergente() {
        assertFalse(artefatos.existe(chave), "o cenário começa sem artefato nenhum na chave");

        assertThrows(FalhaDePersistencia.class,
                () -> gerarCom(new MorreAoRegistrar(ciclos)).executar(CICLO));

        assertTrue(artefatos.existe(chave),
                "o put já aconteceu: o objeto está lá, e é justamente por isso que a janela existe");
        byte[] orfao = artefatos.get(chave);

        CicloCobranca semRemessa = ciclos.buscar(CICLO).orElseThrow();
        assertFalse(semRemessa.temRemessa(), "o COMMIT não aconteceu: o ciclo não aponta para nada");
        assertNull(semRemessa.remessaChave());
        assertNull(semRemessa.remessaSha256());

        Remessa remessa = gerarCom(ciclos).executar(CICLO);

        assertArrayEquals(orfao, remessa.bytes(),
                "a reexecução projeta os mesmos bytes — a remessa é função pura do ciclo");
        assertArrayEquals(orfao, artefatos.get(chave),
                "e o objeto final é o mesmo órfão: sobrescrita idêntica, nada a limpar");
        CicloCobranca comRemessa = ciclos.buscar(CICLO).orElseThrow();
        assertEquals(chave, comRemessa.remessaChave());
        assertEquals(remessa.sha256(), comRemessa.remessaSha256());
    }

    private GerarRemessaUseCase gerarCom(RepositorioCiclo ciclos) {
        return new GerarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()),
                ciclos,
                new RepositorioTentativaPostgres(dados()),
                new RepositorioFaturaPostgres(dados()),
                artefatos);
    }

    /**
     * O processo que morre no instante exato em que o órfão nasce: depois do
     * {@code put}, antes do {@code COMMIT}.
     *
     * <p>Um decorador, e não um {@code if} dentro do use case: simular a morte
     * no código de produção colocaria lá uma linha que só existe para o teste.
     */
    private record MorreAoRegistrar(RepositorioCiclo real) implements RepositorioCiclo {

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
            throw new FalhaDePersistencia("o processo morreu entre o put e o COMMIT");
        }

        @Override
        public int fechar(Transacao tx, String cicloId) {
            return real.fechar(tx, cicloId);
        }
    }
}
