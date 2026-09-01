package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.api.LinhaRetorno;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.usecase.ColetarRetornoUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-09: vários arquivos por ciclo é o caso normal, não a exceção.
 *
 * <p>O parceiro particiona quando quer, e não avisa em quantas partes. Não há
 * "o arquivo de retorno do dia" — há o que chegou até agora. Cada parte é
 * aplicada incrementalmente, em passadas diferentes, e o estado final é o mesmo
 * que um arquivo único produziria.
 *
 * <p>A asserção é sobre o <b>estado inteiro</b> — as tentativas, as faturas e o
 * outbox, linha a linha — e não sobre uma contagem. Contar acertaria mesmo se
 * as partes tivessem se aplicado à tentativa errada, ou se o outbox tivesse a
 * mesma quantidade de linhas com outros valores. O que se afirma aqui é que os
 * dois caminhos chegam ao mesmo lugar, não que chegam a lugares do mesmo
 * tamanho.
 *
 * <p>Quem torna isso verdade não é a coleta: é o {@code UPDATE} condicional do
 * step-03, que decide uma tentativa por vez e não sabe — nem precisa saber — de
 * que arquivo a linha veio.
 */
class RetornoParticionadoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-PARTES";
    /** Data própria: os nomes e as chaves dos arquivos incluem a data. */
    private static final LocalDate DATA = LocalDate.of(2026, 10, 19);

    /**
     * O que o parceiro afirma sobre as quatro tentativas do ciclo — dois pagos,
     * uma recusa com motivo e um erro. Variedade de propósito: um cenário só de
     * pagamentos não distinguiria "aplicou a linha certa" de "aplicou alguma
     * linha".
     */
    private static final List<LinhaRetorno> AFIRMACOES = List.of(
            LinhaRetorno.paga("FAT-1-T1"),
            LinhaRetorno.naoPaga("FAT-2-T1", TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE),
            LinhaRetorno.paga("FAT-3-T1"),
            LinhaRetorno.comErro("FAT-4-T1"));

    private DiretorioDoParceiro parceiro;

    @BeforeEach
    void diretorioLimpo() throws SQLException {
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_RETORNO);
    }

    @Test
    @DisplayName("dois arquivos parciais em passadas diferentes chegam ao mesmo estado de um único")
    void particionadoConvergeParaOMesmoEstadoDoArquivoUnico() throws SQLException {
        List<String> particionado = estadoDepoisDe(ciclo -> {
            parceiro.escrever(caminho(RetornoDoParceiro.nome(ciclo, "01")),
                    RetornoDoParceiro.arquivo(ciclo, AFIRMACOES.subList(0, 2)));
            umaPassada(2);

            // A segunda parte chega depois, e o parceiro não avisou que viria.
            parceiro.escrever(caminho(RetornoDoParceiro.nome(ciclo, "02")),
                    RetornoDoParceiro.arquivo(ciclo, AFIRMACOES.subList(2, 4)));
            // Duas partes no diretório agora: a primeira volta a ser vista, e é
            // o hash que a reconhece pelos bytes — REPETIDO, sem reprocessar.
            umaPassada(2);
        });

        List<String> unico = estadoDepoisDe(ciclo -> {
            parceiro.escrever(caminho(RetornoDoParceiro.nome(ciclo)),
                    RetornoDoParceiro.arquivo(ciclo, AFIRMACOES));
            umaPassada(4);
        });

        assertEquals(unico, particionado,
                "o estado inteiro — tentativas, faturas e outbox — precisa ser o mesmo");
    }

    /**
     * Monta o cenário do zero, deixa o parceiro agir e devolve o estado do
     * banco.
     *
     * <p>Do zero as duas vezes, com os mesmos ids e a mesma data, porque é isso
     * que torna os dois retratos comparáveis linha a linha em vez de só em
     * quantidade.
     */
    private List<String> estadoDepoisDe(Roteiro roteiro) throws SQLException {
        limparTabelas();
        parceiro.limpar(DIRETORIO_RETORNO);
        for (int i = 1; i <= AFIRMACOES.size(); i++) {
            Cenario.tentativaAberta("FAT-" + i, 1, BANCO, DATA);
        }
        Cenario.cicloTransmitido(CICLO, DATA);

        roteiro.executar(new RepositorioCicloPostgres(dados()).buscar(CICLO).orElseThrow());
        return estado();
    }

    @FunctionalInterface
    private interface Roteiro {
        void executar(CicloCobranca ciclo);
    }

    /** Uma passada da coleta, conferindo quantas linhas ela aplicou. */
    private void umaPassada(int aplicadasEsperadas) {
        int aplicadas = coletaCom(canal()).executar().stream()
                .mapToInt(ColetarRetornoUseCase.Resultado::aplicadas)
                .sum();

        assertEquals(aplicadasEsperadas, aplicadas);
    }

    private static String caminho(String nome) {
        return DIRETORIO_RETORNO + "/" + nome;
    }

    /**
     * O retrato do banco: tentativas, faturas e outbox, em ordem estável.
     *
     * <p>Sem id de outbox e sem {@code criado_em}: os dois são de quando a linha
     * nasceu, não do que ela diz. Comparar relógio e sequência faria dois
     * caminhos corretos parecerem divergentes.
     *
     * <p>Sem {@code arquivo_retorno} também, e por um motivo diferente: aquela
     * tabela registra <b>quantos arquivos chegaram</b>, que é justamente a
     * única coisa que os dois caminhos não têm em comum. Ela não faz parte do
     * estado do negócio.
     */
    private static List<String> estado() throws SQLException {
        List<String> retrato = new ArrayList<>();
        try (Connection conexao = novaConexao(); Statement stmt = conexao.createStatement()) {
            ler(retrato, stmt, "tentativa", """
                    SELECT id, fatura_id, ciclo_id, status, coalesce(motivo, '-')
                      FROM tentativa_debito ORDER BY id
                    """);
            ler(retrato, stmt, "fatura", """
                    SELECT id, valor, status FROM fatura ORDER BY id
                    """);
            ler(retrato, stmt, "outbox", """
                    SELECT fatura_id, payload, status FROM outbox ORDER BY fatura_id
                    """);
        }
        return retrato;
    }

    private static void ler(List<String> retrato, Statement stmt, String etiqueta, String sql)
            throws SQLException {
        try (ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                StringBuilder linha = new StringBuilder(etiqueta);
                for (int coluna = 1; coluna <= rs.getMetaData().getColumnCount(); coluna++) {
                    linha.append(' ').append(rs.getString(coluna));
                }
                retrato.add(linha.toString());
            }
        }
    }
}
