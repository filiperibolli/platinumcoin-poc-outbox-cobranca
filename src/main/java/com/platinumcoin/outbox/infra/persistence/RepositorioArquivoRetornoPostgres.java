package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.port.RepositorioArquivoRetorno;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link RepositorioArquivoRetorno} em JDBC puro. */
public final class RepositorioArquivoRetornoPostgres implements RepositorioArquivoRetorno {

    /** Violação de chave estrangeira no Postgres. */
    private static final String CICLO_INEXISTENTE = "23503";

    private final DataSource fonte;

    public RepositorioArquivoRetornoPostgres(DataSource fonte) {
        this.fonte = fonte;
    }

    @Override
    public boolean jaAplicado(String sha256) {
        String sql = "SELECT 1 FROM arquivo_retorno WHERE sha256 = ?";
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, sha256);
            try (ResultSet rs = stmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao consultar o arquivo de retorno " + sha256, e);
        }
    }

    @Override
    public void registrar(String nome, String sha256, String cicloId, int linhas) {
        // ON CONFLICT DO NOTHING: duas passadas concorrentes podem ter aplicado
        // o mesmo arquivo, e a segunda descobre isso aqui. Não é erro — o
        // trabalho que interessa (as transições) já foi feito pelo UPDATE
        // condicional, que absorve a repetição sem ajuda desta tabela.
        String sql = """
                INSERT INTO arquivo_retorno (nome, sha256, ciclo_id, linhas)
                VALUES (?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT arquivo_retorno_bytes_ja_vistos DO NOTHING
                """;
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, nome);
            stmt.setString(2, sha256);
            stmt.setString(3, cicloId);
            stmt.setInt(4, linhas);
            stmt.executeUpdate();
        } catch (SQLException e) {
            // O cabeçalho do arquivo declara um ciclo que não existe aqui: o
            // parceiro respondeu sobre um recorte que não é nosso. A FK recusa
            // no momento da escrita, sem depender de alguém ter conferido antes.
            if (CICLO_INEXISTENTE.equals(e.getSQLState())) {
                throw new FalhaDePersistencia(
                        "arquivo de retorno " + nome + " declara ciclo inexistente: " + cicloId, e);
            }
            throw new FalhaDePersistencia("falha ao registrar o arquivo de retorno " + nome, e);
        }
    }
}
