package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.Transacao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** {@link RepositorioFatura} em JDBC puro. */
public final class RepositorioFaturaPostgres implements RepositorioFatura {

    private static final String COLUNAS = "id, valor, status";

    private final DataSource fonte;

    public RepositorioFaturaPostgres(DataSource fonte) {
        this.fonte = fonte;
    }

    @Override
    public void inserir(Fatura fatura) {
        String sql = "INSERT INTO fatura (%s) VALUES (?, ?, ?)".formatted(COLUNAS);
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, fatura.id());
            stmt.setBigDecimal(2, fatura.valor());
            stmt.setString(3, fatura.status().name());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao inserir a fatura " + fatura.id(), e);
        }
    }

    @Override
    public Optional<Fatura> buscar(String faturaId) {
        String sql = "SELECT %s FROM fatura WHERE id = ?".formatted(COLUNAS);
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, faturaId);
            return primeira(stmt);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao buscar a fatura " + faturaId, e);
        }
    }

    @Override
    public int marcarPaga(Transacao tx, String faturaId) {
        // A guarda AND status = 'ABERTA' é o que faz duas tentativas da mesma
        // fatura que paguem gerarem um lançamento só: a segunda afeta zero
        // linhas e o use case lê isso como "já pagou".
        // DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
        String sql = """
                UPDATE fatura SET status = 'PAGA' WHERE id = ? AND status = 'ABERTA'
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, faturaId);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao marcar paga a fatura " + faturaId, e);
        }
    }

    @Override
    public Optional<Fatura> buscarPorTentativa(Transacao tx, String tentativaId) {
        // Lida pela MESMA conexão da transação: o valor que entra no outbox
        // precisa ser o que a transação enxerga, e não o que outra conexão veria.
        String sql = """
                SELECT f.id, f.valor, f.status
                  FROM fatura f JOIN tentativa_debito t ON t.fatura_id = f.id
                 WHERE t.id = ?
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, tentativaId);
            return primeira(stmt);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao buscar a fatura da tentativa " + tentativaId, e);
        }
    }

    private static Optional<Fatura> primeira(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            return Optional.of(new Fatura(
                    rs.getString("id"),
                    rs.getBigDecimal("valor"),
                    Fatura.Status.valueOf(rs.getString("status"))));
        }
    }
}
