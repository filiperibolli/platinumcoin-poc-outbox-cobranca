package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.Transacao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/** {@link RepositorioCiclo} em JDBC puro. */
public final class RepositorioCicloPostgres implements RepositorioCiclo {

    /** Violação de unicidade no Postgres. */
    private static final String CHAVE_DUPLICADA = "23505";

    private final DataSource fonte;

    public RepositorioCicloPostgres(DataSource fonte) {
        this.fonte = fonte;
    }

    @Override
    public void criar(Transacao tx, CicloCobranca ciclo) {
        String sql = """
                INSERT INTO ciclo_cobranca (id, banco, data_ref, status)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, ciclo.id());
            stmt.setString(2, ciclo.banco());
            stmt.setDate(3, Date.valueOf(ciclo.dataRef()));
            stmt.setString(4, ciclo.status().name());
            stmt.executeUpdate();
        } catch (SQLException e) {
            // A segunda montagem do mesmo banco e data para aqui, e é assim que
            // a reexecução é segura: o banco recusa no momento da escrita, sem
            // depender de alguém ter conferido antes.
            if (CHAVE_DUPLICADA.equals(e.getSQLState())) {
                throw new FalhaDePersistencia("já existe ciclo para " + ciclo.banco()
                        + " em " + ciclo.dataRef(), e);
            }
            throw new FalhaDePersistencia("falha ao criar o ciclo " + ciclo.id(), e);
        }
    }

    @Override
    public Optional<CicloCobranca> buscar(String cicloId) {
        String sql = """
                SELECT id, banco, data_ref, status FROM ciclo_cobranca WHERE id = ?
                """;
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, cicloId);
            return primeiro(stmt);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao buscar o ciclo " + cicloId, e);
        }
    }

    @Override
    public Optional<CicloCobranca> buscarPor(String banco, LocalDate dataRef) {
        String sql = """
                SELECT id, banco, data_ref, status FROM ciclo_cobranca
                 WHERE banco = ? AND data_ref = ?
                """;
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, banco);
            stmt.setDate(2, Date.valueOf(dataRef));
            return primeiro(stmt);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao buscar o ciclo de " + banco + " em " + dataRef, e);
        }
    }

    @Override
    public int atribuirTentativasAbertas(Transacao tx, CicloCobranca ciclo) {
        String sql = """
                UPDATE tentativa_debito
                   SET ciclo_id = ?, status = 'SOLICITADO'
                 WHERE status = 'ABERTO' AND banco = ? AND data_ref = ?
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, ciclo.id());
            stmt.setString(2, ciclo.banco());
            stmt.setDate(3, Date.valueOf(ciclo.dataRef()));
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia(
                    "falha ao atribuir tentativas ao ciclo " + ciclo.id(), e);
        }
    }

    @Override
    public int fechar(Transacao tx, String cicloId) {
        // O fechamento é o step-04. Deixar o método declarado e não implementado
        // é preferível a um SQL sem teste que nasce parecendo pronto.
        throw new UnsupportedOperationException("fechamento de ciclo chega no step-04");
    }

    private static Optional<CicloCobranca> primeiro(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new CicloCobranca(
                    rs.getString("id"),
                    rs.getString("banco"),
                    rs.getDate("data_ref").toLocalDate(),
                    CicloCobranca.Status.valueOf(rs.getString("status"))));
        }
    }
}
