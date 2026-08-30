package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@link RepositorioTentativa} em JDBC puro. */
public final class RepositorioTentativaPostgres implements RepositorioTentativa {

    private static final String COLUNAS =
            "id, fatura_id, numero, banco, data_ref, ciclo_id, status, motivo";

    private final DataSource fonte;

    public RepositorioTentativaPostgres(DataSource fonte) {
        this.fonte = fonte;
    }

    @Override
    public void inserir(TentativaDebito tentativa) {
        String sql = """
                INSERT INTO tentativa_debito (%s)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.formatted(COLUNAS);
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, tentativa.id());
            stmt.setString(2, tentativa.faturaId());
            stmt.setInt(3, tentativa.numero());
            stmt.setString(4, tentativa.banco());
            stmt.setDate(5, Date.valueOf(tentativa.dataRef()));
            stmt.setString(6, tentativa.cicloId());
            stmt.setString(7, tentativa.status().name());
            stmt.setString(8, tentativa.motivo() == null ? null : tentativa.motivo().name());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao inserir a tentativa " + tentativa.id(), e);
        }
    }

    @Override
    public Optional<TentativaDebito> buscar(String tentativaId) {
        String sql = "SELECT %s FROM tentativa_debito WHERE id = ?".formatted(COLUNAS);
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, tentativaId);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next() ? Optional.of(ler(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao buscar a tentativa " + tentativaId, e);
        }
    }

    @Override
    public List<TentativaDebito> doCiclo(String cicloId) {
        // ORDER BY id: a remessa se compara byte a byte, e uma ordem de leitura
        // que depende do plano do banco tornaria essa comparação uma loteria.
        String sql = "SELECT %s FROM tentativa_debito WHERE ciclo_id = ? ORDER BY id"
                .formatted(COLUNAS);
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, cicloId);
            List<TentativaDebito> tentativas = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    tentativas.add(ler(rs));
                }
            }
            return List.copyOf(tentativas);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao listar as tentativas do ciclo " + cicloId, e);
        }
    }

    @Override
    public int registrarResultado(Transacao tx, String tentativaId,
                                  TentativaDebito.Status resultado,
                                  TentativaDebito.MotivoNaoPago motivo) {
        // O UPDATE condicional do retorno é o step-03, e ele nasce junto com os
        // testes que provam a idempotência — não antes deles.
        throw new UnsupportedOperationException("aplicação de retorno chega no step-03");
    }

    private static TentativaDebito ler(ResultSet rs) throws SQLException {
        String motivo = rs.getString("motivo");
        return new TentativaDebito(
                rs.getString("id"),
                rs.getString("fatura_id"),
                rs.getInt("numero"),
                rs.getString("banco"),
                rs.getDate("data_ref").toLocalDate(),
                rs.getString("ciclo_id"),
                TentativaDebito.Status.valueOf(rs.getString("status")),
                motivo == null ? null : TentativaDebito.MotivoNaoPago.valueOf(motivo));
    }
}
