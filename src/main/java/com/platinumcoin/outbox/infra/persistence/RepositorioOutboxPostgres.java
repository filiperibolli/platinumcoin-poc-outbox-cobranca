package com.platinumcoin.outbox.infra.persistence;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.Transacao;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        // O relay é o step-05, e marcar publicado só faz sentido depois que
        // existe um envio para vir antes dele.
        throw new UnsupportedOperationException("publicação do outbox chega no step-05");
    }

    /**
     * O corpo do lançamento, como o consumidor o lê.
     *
     * <p>JSON escrito à mão, pela mesma razão do SQL: são dois campos, e uma
     * biblioteca de serialização seria dependência sem ganho. O que a gravação
     * escreve, a leitura devolve idêntico — é essa ida e volta que o relay
     * precisa, e por isso as duas moram juntas.
     */
    private static final class Payload {

        private static final Pattern CAMPO = Pattern.compile("\"(\\w+)\":\"([^\"]*)\"");

        private Payload() {
        }

        static String escrever(LancamentoContabil lancamento) {
            // Os dois campos são controlados pelo domínio — id de fatura e
            // decimal. Um id com aspas escaparia do formato calado, então o
            // caso é recusado aqui, e não descoberto na leitura.
            if (lancamento.faturaId().matches(".*[\"\\\\].*")) {
                throw new FalhaDePersistencia(
                        "id de fatura inválido para o payload: " + lancamento.faturaId());
            }
            return "{\"faturaId\":\"" + lancamento.faturaId()
                    + "\",\"valor\":\"" + lancamento.valor().toPlainString() + "\"}";
        }

        static LancamentoContabil ler(String payload) {
            String faturaId = null;
            BigDecimal valor = null;
            Matcher campo = CAMPO.matcher(payload);
            while (campo.find()) {
                switch (campo.group(1)) {
                    case "faturaId" -> faturaId = campo.group(2);
                    case "valor" -> valor = new BigDecimal(campo.group(2));
                    default -> { }
                }
            }
            if (faturaId == null || valor == null) {
                throw new FalhaDePersistencia("payload de outbox ilegível: " + payload);
            }
            return new LancamentoContabil(faturaId, valor);
        }
    }
}
