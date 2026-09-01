package com.platinumcoin.ciclo.infra.persistence;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.Transacao;

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

    private static final String COLUNAS =
            "id, banco, data_ref, status, remessa_chave, remessa_sha256";

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
        String sql = "SELECT %s FROM ciclo_cobranca WHERE id = ?".formatted(COLUNAS);
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
                SELECT %s FROM ciclo_cobranca WHERE banco = ? AND data_ref = ?
                """.formatted(COLUNAS);
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
    public void registrarRemessa(Transacao tx, String cicloId, ChaveArtefato chave, String sha256) {
        // Sem guarda de status: a chave é determinística e o conteúdo é função
        // pura do ciclo, então uma segunda geração grava exatamente os mesmos
        // dois valores. Guardar contra a reexecução seria proteger contra o
        // caso que este step existe para mostrar como inofensivo.
        String sql = """
                UPDATE ciclo_cobranca SET remessa_chave = ?, remessa_sha256 = ? WHERE id = ?
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, chave.valor());
            stmt.setString(2, sha256);
            stmt.setString(3, cicloId);
            if (stmt.executeUpdate() == 0) {
                throw new FalhaDePersistencia("ciclo inexistente ao registrar a remessa: " + cicloId);
            }
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao registrar a remessa do ciclo " + cicloId, e);
        }
    }

    @Override
    public int registrarEnvio(Transacao tx, String cicloId) {
        // As duas guardas de status são o que absorve a retransmissão que a
        // janela entre o put e o COMMIT produz: a tentativa que já saiu não sai
        // de novo, e o ciclo já ENVIADO não volta atrás.
        String transmitidas = """
                UPDATE tentativa_debito SET status = 'ENVIADO_PARCEIRO'
                 WHERE ciclo_id = ? AND status = 'SOLICITADO'
                """;
        String ciclo = """
                UPDATE ciclo_cobranca SET status = 'ENVIADO'
                 WHERE id = ? AND status = 'MONTADO'
                """;
        Connection conexao = TransacaoJdbc.conexaoDe(tx);
        try (PreparedStatement tentativas = conexao.prepareStatement(transmitidas);
             PreparedStatement enviado = conexao.prepareStatement(ciclo)) {
            tentativas.setString(1, cicloId);
            int afetadas = tentativas.executeUpdate();
            enviado.setString(1, cicloId);
            enviado.executeUpdate();
            return afetadas;
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao registrar o envio do ciclo " + cicloId, e);
        }
    }

    @Override
    public int fechar(Transacao tx, String cicloId) {
        // DECISÃO: ausência de retorno vira SEM_RETORNO, não NAO_PAGO — marcar
        // como não pago dispararia notificação de falha ao cliente com base em
        // um fato que não ocorreu. SEM_RETORNO é exceção operacional. Ver README.
        String semRetorno = """
                UPDATE tentativa_debito SET status = 'SEM_RETORNO'
                 WHERE ciclo_id = ? AND status = 'ENVIADO_PARCEIRO'
                """;
        String fechado = "UPDATE ciclo_cobranca SET status = 'FECHADO' WHERE id = ?";
        Connection conexao = TransacaoJdbc.conexaoDe(tx);
        try (PreparedStatement pendentes = conexao.prepareStatement(semRetorno);
             PreparedStatement ciclo = conexao.prepareStatement(fechado)) {
            pendentes.setString(1, cicloId);
            // A guarda por ENVIADO_PARCEIRO é o que torna o refechamento inócuo:
            // quem já tem desfecho não é tocado, e o segundo fechamento afeta
            // zero linhas em vez de sobrescrever resultados que o parceiro deu.
            int afetadas = pendentes.executeUpdate();
            ciclo.setString(1, cicloId);
            ciclo.executeUpdate();
            return afetadas;
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao fechar o ciclo " + cicloId, e);
        }
    }

    private static Optional<CicloCobranca> primeiro(PreparedStatement stmt) throws SQLException {
        try (ResultSet rs = stmt.executeQuery()) {
            if (!rs.next()) {
                return Optional.empty();
            }
            String chave = rs.getString("remessa_chave");
            return Optional.of(new CicloCobranca(
                    rs.getString("id"),
                    rs.getString("banco"),
                    rs.getDate("data_ref").toLocalDate(),
                    CicloCobranca.Status.valueOf(rs.getString("status")),
                    chave == null ? null : new ChaveArtefato(chave),
                    rs.getString("remessa_sha256")));
        }
    }
}
