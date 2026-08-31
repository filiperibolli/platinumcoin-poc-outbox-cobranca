package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;

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

    /**
     * Monta o ciclo do recorte padrão e o transmite ao parceiro: as tentativas
     * saem de {@code ABERTO} e chegam a {@code ENVIADO_PARCEIRO}, que é onde o
     * arquivo de retorno as encontra.
     *
     * <p>A montagem usa o use case de verdade. A transmissão é {@code UPDATE}
     * direto porque {@code EnviarRemessa} não tem classe neste repositório —
     * SFTP e CNAB 240 estão fora de escopo, e o retorno começa depois que o
     * arquivo já foi entregue.
     */
    static void cicloTransmitido(String cicloId) {
        new MontarCicloUseCase(
                new TransacaoJdbc.Fabrica(AmbienteDeTeste.dados()),
                new RepositorioCicloPostgres(AmbienteDeTeste.dados()))
                .executar(cicloId, BANCO, DATA);

        executar("""
                UPDATE tentativa_debito SET status = 'ENVIADO_PARCEIRO'
                 WHERE ciclo_id = ? AND status = 'SOLICITADO'
                """, cicloId);
        executar("UPDATE ciclo_cobranca SET status = 'ENVIADO' WHERE id = ?", cicloId);
    }

    private static void executar(String sql, String parametro) {
        try (Connection conexao = AmbienteDeTeste.dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, parametro);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao preparar o cenário: " + sql, e);
        }
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
