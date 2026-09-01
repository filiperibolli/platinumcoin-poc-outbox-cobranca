package com.platinumcoin.ciclo.infra.persistence;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.port.Transacao;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A {@link Transacao} do domínio, encarnada numa {@link Connection} com
 * {@code autoCommit} desligado.
 *
 * <p>É o único lugar do projeto que sabe que "uma transação" e "uma conexão"
 * são a mesma coisa. Os repositórios pegam a conexão daqui por
 * {@link #conexaoDe}, e é isso que faz duas escritas de repositórios
 * diferentes caírem no mesmo {@code COMMIT} — a regra central do projeto.
 */
public final class TransacaoJdbc implements Transacao {

    private final Connection conexao;
    private boolean confirmada;

    private TransacaoJdbc(Connection conexao) {
        this.conexao = conexao;
    }

    /**
     * A conexão por trás da transação do domínio.
     *
     * <p>O cast é explícito e falha alto: um repositório Postgres recebendo uma
     * transação que não é JDBC é erro de montagem do programa, não caso de
     * negócio.
     */
    static Connection conexaoDe(Transacao tx) {
        if (tx instanceof TransacaoJdbc jdbc) {
            return jdbc.conexao;
        }
        throw new FalhaDePersistencia("transação não é JDBC: " + tx);
    }

    @Override
    public void commit() {
        try {
            conexao.commit();
            confirmada = true;
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao confirmar a transação", e);
        }
    }

    /**
     * Desfaz o que não foi confirmado. O {@code rollback} é explícito porque a
     * garantia "estourou no meio, não escreveu nada" é do projeto, e não algo a
     * herdar do comportamento de fechamento de um driver.
     *
     * <p>Não lança, como manda a porta: chamado pelo {@code try-with-resources},
     * um erro aqui esconderia a exceção que de fato abortou o use case — e é
     * essa que diz por que a transação está sendo desfeita.
     */
    @Override
    public void close() {
        try (Connection aFechar = conexao) {
            if (!confirmada) {
                aFechar.rollback();
            }
        } catch (SQLException ignorada) {
            // Conexão morta: nada foi confirmado, e é isso que importa.
        }
    }

    /** Quem abre transações no Postgres. */
    public static final class Fabrica implements Transacao.Fabrica {

        private final DataSource fonte;

        public Fabrica(DataSource fonte) {
            this.fonte = fonte;
        }

        @Override
        public Transacao abrir() {
            Connection conexao = null;
            try {
                conexao = fonte.getConnection();
                conexao.setAutoCommit(false);
                return new TransacaoJdbc(conexao);
            } catch (SQLException e) {
                fecharSemPropagar(conexao);
                throw new FalhaDePersistencia("falha ao abrir a transação", e);
            }
        }

        private static void fecharSemPropagar(Connection conexao) {
            if (conexao == null) {
                return;
            }
            try {
                conexao.close();
            } catch (SQLException ignorada) {
                // A falha que interessa é a original, não a do fechamento.
            }
        }
    }
}
