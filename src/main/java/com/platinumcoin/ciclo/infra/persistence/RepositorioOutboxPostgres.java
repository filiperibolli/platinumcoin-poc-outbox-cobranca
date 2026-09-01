package com.platinumcoin.ciclo.infra.persistence;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.model.LancamentoContabil;
import com.platinumcoin.ciclo.domain.model.RegistroOutbox;
import com.platinumcoin.ciclo.domain.port.RepositorioOutbox;
import com.platinumcoin.ciclo.domain.port.Transacao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** {@link RepositorioOutbox} em JDBC puro. */
public final class RepositorioOutboxPostgres implements RepositorioOutbox {

    /** Violação de unicidade no Postgres. */
    private static final String CHAVE_DUPLICADA = "23505";

    private final DataSource fonte;

    public RepositorioOutboxPostgres(DataSource fonte) {
        this.fonte = fonte;
    }

    @Override
    public void inserir(Transacao tx, LancamentoContabil lancamento) {
        String sql = """
                INSERT INTO outbox (fatura_id, payload, status) VALUES (?, ?, 'PENDENTE')
                """;
        try (PreparedStatement stmt = TransacaoJdbc.conexaoDe(tx).prepareStatement(sql)) {
            stmt.setString(1, lancamento.faturaId());
            stmt.setString(2, Payload.escrever(lancamento));
            stmt.executeUpdate();
        } catch (SQLException e) {
            // A rede sob a guarda do UPDATE condicional: se por qualquer motivo
            // duas aplicações chegarem até aqui pela mesma fatura, o banco
            // recusa em vez de deixar passar um lançamento duplicado calado.
            if (CHAVE_DUPLICADA.equals(e.getSQLState())) {
                throw new FalhaDePersistencia(
                        "já existe lançamento no outbox para a fatura " + lancamento.faturaId(), e);
            }
            throw new FalhaDePersistencia(
                    "falha ao gravar o lançamento da fatura " + lancamento.faturaId(), e);
        }
    }

    @Override
    public List<RegistroOutbox> pendentes(int limite) {
        // Sem transação: o relay roda num ciclo de vida próprio, fora de
        // qualquer transação de negócio. ORDER BY id porque o mais antigo é o
        // que espera há mais tempo.
        String sql = """
                SELECT id, payload FROM outbox
                 WHERE status = 'PENDENTE' ORDER BY id LIMIT ?
                """;
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setInt(1, limite);
            List<RegistroOutbox> registros = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    registros.add(new RegistroOutbox(rs.getLong("id"),
                            Payload.ler(rs.getString("payload")),
                            RegistroOutbox.Status.PENDENTE));
                }
            }
            return List.copyOf(registros);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao listar o outbox pendente", e);
        }
    }

    @Override
    public int marcarPublicado(long registroId) {
        // Chamado DEPOIS do envio, nunca antes: a ordem é a decisão inteira do
        // relay — ver ADR-0002.
        //
        // A guarda por status é o mesmo idioma do retorno e do fechamento: zero
        // linhas afetadas significa "outro já marcou", e não é erro. Sem ela, um
        // segundo relay incrementaria a contagem de publicados desta passada
        // pela mesma linha.
        String sql = """
                UPDATE outbox SET status = 'PUBLICADO', publicado_em = now()
                 WHERE id = ? AND status = 'PENDENTE'
                """;
        try (Connection conexao = fonte.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setLong(1, registroId);
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia(
                    "falha ao marcar como publicada a linha " + registroId + " do outbox", e);
        }
    }
}
