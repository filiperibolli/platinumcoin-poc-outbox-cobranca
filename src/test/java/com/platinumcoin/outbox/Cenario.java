package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * Os dados de partida de um teste: faturas abertas com tentativas esperando
 * entrar num ciclo.
 *
 * <p>Fica fora de {@link AmbienteDeTeste} de propósito — aquele arquivo cuida
 * dos containers, este cuida do estado de negócio.
 */
final class Cenario {

    static final String BANCO = "341";
    static final LocalDate DATA = LocalDate.of(2026, 8, 30);
    private static final BigDecimal VALOR = new BigDecimal("100.00");

    private Cenario() {
    }

    /** Uma fatura {@code ABERTA} com uma tentativa {@code ABERTO} no recorte informado. */
    static TentativaDebito tentativaAberta(String faturaId, int numero,
                                           String banco, LocalDate dataRef) {
        inserirFatura(faturaId);
        TentativaDebito tentativa = TentativaDebito.aberta(
                faturaId + "-T" + numero, faturaId, numero, banco, dataRef);
        new RepositorioTentativaPostgres(AmbienteDeTeste.dados()).inserir(tentativa);
        return tentativa;
    }

    /** Atalho para o recorte padrão dos testes: banco {@value #BANCO}, data {@link #DATA}. */
    static TentativaDebito tentativaAberta(String faturaId) {
        return tentativaAberta(faturaId, 1, BANCO, DATA);
    }

    private static void inserirFatura(String faturaId) {
        String sql = """
                INSERT INTO fatura (id, valor, status) VALUES (?, ?, 'ABERTA')
                ON CONFLICT (id) DO NOTHING
                """;
        try (Connection conexao = AmbienteDeTeste.dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, faturaId);
            stmt.setBigDecimal(2, VALOR);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao preparar a fatura " + faturaId, e);
        }
    }
}
