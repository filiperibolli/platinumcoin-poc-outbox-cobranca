package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
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
        return tentativaAberta(faturaId, numero, banco, dataRef, VALOR);
    }

    /** Atalho para o recorte padrão dos testes: banco {@value #BANCO}, data {@link #DATA}. */
    static TentativaDebito tentativaAberta(String faturaId) {
        return tentativaAberta(faturaId, 1, BANCO, DATA);
    }

    /**
     * A mesma coisa, com valor próprio — é o que a remessa projeta em centavos,
     * e um cenário de valor único não distinguiria o campo do preenchimento.
     */
    static TentativaDebito tentativaAberta(String faturaId, String valor) {
        return tentativaAberta(faturaId, 1, BANCO, DATA, new BigDecimal(valor));
    }

    private static TentativaDebito tentativaAberta(String faturaId, int numero, String banco,
                                                   LocalDate dataRef, BigDecimal valor) {
        inserirFatura(faturaId, valor);
        TentativaDebito tentativa = TentativaDebito.aberta(
                faturaId + "-T" + numero, faturaId, numero, banco, dataRef);
        new RepositorioTentativaPostgres(AmbienteDeTeste.dados()).inserir(tentativa);
        return tentativa;
    }

    /**
     * Monta o ciclo do recorte padrão e o transmite ao parceiro: as tentativas
     * saem de {@code ABERTO} e chegam a {@code ENVIADO_PARCEIRO}, que é onde o
     * arquivo de retorno as encontra.
     *
     * <p>A montagem usa o use case de verdade. A transmissão é {@code UPDATE}
     * direto de propósito: o caminho real está em {@code EnviarRemessaUseCase}
     * e é exercitado por {@code EnvioChegaNoParceiroTest}. Estes testes começam
     * depois que o arquivo já foi entregue, e fazê-los gerar remessa e abrir
     * conexão SSH só para chegar ao estado inicial trocaria o sujeito deles.
     */
    static void cicloTransmitido(String cicloId) {
        cicloTransmitido(cicloId, DATA);
    }

    /**
     * O mesmo, noutra data de referência — o recorte de um segundo ciclo do
     * mesmo banco, que o {@code UNIQUE (banco, data_ref)} não deixaria repetir
     * no mesmo dia.
     */
    static void cicloTransmitido(String cicloId, LocalDate dataRef) {
        new MontarCicloUseCase(
                new TransacaoJdbc.Fabrica(AmbienteDeTeste.dados()),
                new RepositorioCicloPostgres(AmbienteDeTeste.dados()))
                .executar(cicloId, BANCO, dataRef);

        executar("""
                UPDATE tentativa_debito SET status = 'ENVIADO_PARCEIRO'
                 WHERE ciclo_id = ? AND status = 'SOLICITADO'
                """, cicloId);
        executar("UPDATE ciclo_cobranca SET status = 'ENVIADO' WHERE id = ?", cicloId);
    }

    /**
     * Um pagamento já decidido: a tentativa {@code PAGO}, a fatura {@code PAGA}
     * e uma linha {@code PENDENTE} no outbox — o estado em que o relay encontra
     * o mundo.
     *
     * <p>Chega até aqui pelo {@code AplicarRetornoUseCase} de verdade, e não por
     * um {@code INSERT} direto no outbox: o que o relay publica precisa ser o
     * que a transação de negócio grava.
     */
    static void pagamentoPendente(String faturaId, String cicloId, LocalDate dataRef) {
        tentativaAberta(faturaId, 1, BANCO, dataRef);
        cicloTransmitido(cicloId, dataRef);

        AplicarRetornoUseCase aplicar = new AplicarRetornoUseCase(
                new TransacaoJdbc.Fabrica(AmbienteDeTeste.dados()),
                new RepositorioTentativaPostgres(AmbienteDeTeste.dados()),
                new RepositorioFaturaPostgres(AmbienteDeTeste.dados()),
                new RepositorioOutboxPostgres(AmbienteDeTeste.dados()));
        LinhaRetorno.paga(faturaId + "-T1").aplicarCom(aplicar);
    }

    /** Atalho para o recorte padrão: banco {@value #BANCO}, data {@link #DATA}. */
    static void pagamentoPendente(String faturaId, String cicloId) {
        pagamentoPendente(faturaId, cicloId, DATA);
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

    private static void inserirFatura(String faturaId, BigDecimal valor) {
        String sql = """
                INSERT INTO fatura (id, valor, status) VALUES (?, ?, 'ABERTA')
                ON CONFLICT (id) DO NOTHING
                """;
        try (Connection conexao = AmbienteDeTeste.dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            stmt.setString(1, faturaId);
            stmt.setBigDecimal(2, valor);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("falha ao preparar a fatura " + faturaId, e);
        }
    }
}
