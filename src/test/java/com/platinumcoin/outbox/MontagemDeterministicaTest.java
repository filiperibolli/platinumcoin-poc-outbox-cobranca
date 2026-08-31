package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.ChaveArtefato;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Optional;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static com.platinumcoin.outbox.Cenario.DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-02: a montagem é a única escrita que importa, e por isso é a única que
 * não pode dar errado pela metade.
 *
 * <p>Prova três coisas: o ciclo puxa exatamente o seu recorte; uma falha no
 * meio não deixa ciclo órfão nem tentativa meio-atribuída; e a segunda montagem
 * do mesmo banco e data não cria um segundo ciclo — barrada pelo {@code UNIQUE},
 * não por uma consulta prévia.
 */
class MontagemDeterministicaTest extends AmbienteDeTeste {

    private Transacao.Fabrica transacoes;
    private RepositorioCiclo ciclos;
    private RepositorioTentativa tentativas;
    private MontarCicloUseCase montar;

    @BeforeEach
    void prepararBanco() throws SQLException {
        limparTabelas();
        transacoes = new TransacaoJdbc.Fabrica(dados());
        ciclos = new RepositorioCicloPostgres(dados());
        tentativas = new RepositorioTentativaPostgres(dados());
        montar = new MontarCicloUseCase(transacoes, ciclos);
    }

    @Test
    @DisplayName("o ciclo puxa as tentativas ABERTO do seu recorte, e só elas")
    void montagemAtribuiApenasORecorte() {
        Cenario.tentativaAberta("FAT-1");
        Cenario.tentativaAberta("FAT-2");
        Cenario.tentativaAberta("FAT-3", 1, "237", DATA);
        Cenario.tentativaAberta("FAT-4", 1, BANCO, DATA.plusDays(1));

        MontarCicloUseCase.Resultado resultado = montar.executar("CICLO-1", BANCO, DATA);

        assertEquals(2, resultado.tentativas());
        assertEquals(CicloCobranca.Status.MONTADO, resultado.ciclo().status());
        assertSolicitada("FAT-1-T1", "CICLO-1");
        assertSolicitada("FAT-2-T1", "CICLO-1");
        assertAberta("FAT-3-T1");
        assertAberta("FAT-4-T1");
    }

    @Test
    @DisplayName("falha no meio da montagem não deixa nem ciclo nem tentativa meio-atribuída")
    void falhaNoMeioNaoDeixaRastro() {
        Cenario.tentativaAberta("FAT-1");
        Cenario.tentativaAberta("FAT-2");
        MontarCicloUseCase montagemQueFalha =
                new MontarCicloUseCase(transacoes, new FalhaAoAtribuir(ciclos));

        assertThrows(FalhaDePersistencia.class,
                () -> montagemQueFalha.executar("CICLO-1", BANCO, DATA));

        assertEquals(Optional.empty(), ciclos.buscar("CICLO-1"),
                "o INSERT do ciclo estava na mesma transação do UPDATE que falhou");
        assertAberta("FAT-1-T1");
        assertAberta("FAT-2-T1");

        // E o recorte continua montável: a tentativa fracassada não gastou o
        // par (banco, data_ref).
        MontarCicloUseCase.Resultado resultado = montar.executar("CICLO-1", BANCO, DATA);

        assertEquals(2, resultado.tentativas());
        assertSolicitada("FAT-1-T1", "CICLO-1");
    }

    @Test
    @DisplayName("a segunda montagem do mesmo banco e data não cria um segundo ciclo")
    void segundaMontagemNaoCriaOutroCiclo() throws SQLException {
        Cenario.tentativaAberta("FAT-1");
        Cenario.tentativaAberta("FAT-2");
        montar.executar("CICLO-1", BANCO, DATA);

        assertThrows(FalhaDePersistencia.class,
                () -> montar.executar("CICLO-2", BANCO, DATA));

        assertEquals(1, quantosCiclos(), "o UNIQUE (banco, data_ref) é a idempotência");
        assertEquals(Optional.empty(), ciclos.buscar("CICLO-2"));
        assertSolicitada("FAT-1-T1", "CICLO-1");
        assertSolicitada("FAT-2-T1", "CICLO-1");
    }

    private void assertSolicitada(String tentativaId, String cicloId) {
        TentativaDebito tentativa = tentativas.buscar(tentativaId).orElseThrow();

        assertEquals(TentativaDebito.Status.SOLICITADO, tentativa.status(), tentativaId);
        assertEquals(cicloId, tentativa.cicloId(), tentativaId);
    }

    private void assertAberta(String tentativaId) {
        TentativaDebito tentativa = tentativas.buscar(tentativaId).orElseThrow();

        assertEquals(TentativaDebito.Status.ABERTO, tentativa.status(), tentativaId);
        assertNull(tentativa.cicloId(), tentativaId + " não pertence a ciclo nenhum");
    }

    private static int quantosCiclos() throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) FROM ciclo_cobranca")) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    /**
     * O ciclo real, mas a atribuição das tentativas estoura — o instante exato
     * em que a montagem poderia deixar metade escrita.
     */
    private record FalhaAoAtribuir(RepositorioCiclo real) implements RepositorioCiclo {

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
            throw new FalhaDePersistencia("banco caiu no meio da montagem");
        }

        @Override
        public void registrarRemessa(Transacao tx, String cicloId,
                                     ChaveArtefato chave, String sha256) {
            real.registrarRemessa(tx, cicloId, chave, sha256);
        }

        @Override
        public int fechar(Transacao tx, String cicloId) {
            return real.fechar(tx, cicloId);
        }
    }
}
