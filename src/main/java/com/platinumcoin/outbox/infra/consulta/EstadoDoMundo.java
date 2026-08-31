package com.platinumcoin.outbox.infra.consulta;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.infra.config.Ambiente;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O retrato das cinco fontes que o ciclo toca: banco, outbox, diretório do
 * parceiro, bucket e fila.
 *
 * <p>É leitura de <b>operação</b>, não de negócio, e por isso não passa pelas
 * portas do domínio. Nenhuma regra deste projeto pergunta "quantas tentativas
 * há em cada status" ou "que objetos existem no bucket"; quem pergunta é quem
 * está olhando o mecanismo acontecer. Acrescentar {@code listar()} ao
 * {@link com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato} ou
 * {@code todos()} ao {@link com.platinumcoin.outbox.domain.port.RepositorioCiclo}
 * para servir esta tela colocaria no domínio perguntas que nenhuma decisão faz —
 * e a porta do step-07 nasceu sem {@code delete} e sem {@code list} exatamente
 * para não convidar esse tipo de acréscimo.
 * <br>DECISÃO: o /estado não passa pelo domínio — ver docs/steps/step-10.md
 *
 * <p>A exceção é o parceiro: ali a leitura já existe como porta desde o
 * step-09, e abrir uma segunda conexão SSH só para esta tela seria inventar um
 * caminho paralelo ao que a coleta usa.
 */
public final class EstadoDoMundo {

    /** Os dois diretórios combinados com o parceiro — contrato, não configuração. */
    private static final String DIRETORIO_REMESSA = "/remessa";
    private static final String DIRETORIO_RETORNO = "/retorno";

    private final Ambiente ambiente;
    private final CanalArquivos canal;

    public EstadoDoMundo(Ambiente ambiente, CanalArquivos canal) {
        this.ambiente = ambiente;
        this.canal = canal;
    }

    /** O mundo inteiro num objeto — o corpo do {@code GET /estado}. */
    public record Retrato(List<Ciclo> ciclos,
                          Map<String, Integer> tentativas,
                          Map<String, Integer> outbox,
                          Parceiro parceiro,
                          List<String> artefatos,
                          int mensagensNaFila) {
    }

    public record Ciclo(String id, String banco, LocalDate dataRef, String status,
                        String remessaChave, String remessaSha256) {
    }

    /** O que está no diretório do parceiro, dos dois lados do fio. */
    public record Parceiro(List<String> remessa, List<String> retorno) {
    }

    public Retrato ler() {
        return new Retrato(
                ciclos(),
                contarPorStatus("tentativa_debito"),
                contarPorStatus("outbox"),
                new Parceiro(canal.listar(DIRETORIO_REMESSA), canal.listar(DIRETORIO_RETORNO)),
                artefatos(),
                mensagensNaFila());
    }

    private List<Ciclo> ciclos() {
        String sql = """
                SELECT id, banco, data_ref, status, remessa_chave, remessa_sha256
                  FROM ciclo_cobranca ORDER BY data_ref, id
                """;
        try (Connection conexao = dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<Ciclo> ciclos = new ArrayList<>();
            while (rs.next()) {
                ciclos.add(new Ciclo(
                        rs.getString("id"),
                        rs.getString("banco"),
                        rs.getObject("data_ref", LocalDate.class),
                        rs.getString("status"),
                        rs.getString("remessa_chave"),
                        rs.getString("remessa_sha256")));
            }
            return List.copyOf(ciclos);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao ler os ciclos para o retrato", e);
        }
    }

    /**
     * Quantas linhas em cada status, na ordem em que o banco as devolve.
     *
     * <p>A tabela vem concatenada no SQL porque não há como parametrizar um
     * nome de relação — e é seguro porque as duas únicas chamadas estão logo
     * acima, com literais.
     */
    private Map<String, Integer> contarPorStatus(String tabela) {
        String sql = "SELECT status, count(*) FROM " + tabela + " GROUP BY status ORDER BY status";
        try (Connection conexao = dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            Map<String, Integer> porStatus = new LinkedHashMap<>();
            while (rs.next()) {
                porStatus.put(rs.getString(1), rs.getInt(2));
            }
            // unmodifiableMap, e não Map.copyOf: a ordem do ORDER BY é o que
            // faz o retrato ser lido sempre igual.
            return Collections.unmodifiableMap(porStatus);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao contar " + tabela + " por status", e);
        }
    }

    private List<String> artefatos() {
        return ambiente.s3().listObjectsV2(pedido -> pedido.bucket(ambiente.bucket()))
                .contents().stream()
                .map(S3Object::key)
                .sorted()
                .toList();
    }

    /**
     * Quantas mensagens a fila diz ter.
     *
     * <p>{@code Approximate} no nome do atributo, e a aproximação é do SQS, não
     * nossa: o número é uma amostra distribuída, e é por isso que os testes que
     * afirmam alguma coisa sobre a fila continuam <b>consumindo</b> as
     * mensagens em vez de perguntar aqui.
     */
    private int mensagensNaFila() {
        String quantas = ambiente.sqs().getQueueAttributes(pedido -> pedido
                        .queueUrl(ambiente.urlDaFila())
                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                .attributes()
                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
        return quantas == null ? 0 : Integer.parseInt(quantas);
    }

    private DataSource dados() {
        return ambiente.dados();
    }
}
