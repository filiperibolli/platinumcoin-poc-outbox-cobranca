package com.platinumcoin.outbox.infra.consulta;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.infra.config.Ambiente;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
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
 *
 * <p><b>Tentativa conta, outbox lista.</b> A assimetria é de propósito. De
 * tentativas há dezenas e só a distribuição importa — "3 em ENVIADO_PARCEIRO"
 * é a informação inteira. Do outbox há no máximo uma linha por fatura, e o que
 * importa é <b>qual</b>: a janela do relay se enxerga em "F-3 continua
 * PENDENTE enquanto a fila já tem F-3", e uma contagem por status esconderia
 * exatamente esse par.
 */
public final class EstadoDoMundo {

    /** Os dois diretórios combinados com o parceiro — contrato, não configuração. */
    private static final String DIRETORIO_REMESSA = "/remessa";
    private static final String DIRETORIO_RETORNO = "/retorno";

    /** O teto de uma espiada na fila. O SQS não entrega mais que isto por chamada. */
    private static final int ESPIADA = 10;

    private final Ambiente ambiente;
    private final CanalArquivos canal;

    public EstadoDoMundo(Ambiente ambiente, CanalArquivos canal) {
        this.ambiente = ambiente;
        this.canal = canal;
    }

    /** O mundo inteiro num objeto — o corpo do {@code GET /estado}. */
    public record Retrato(List<Ciclo> ciclos,
                          Map<String, Integer> tentativas,
                          List<Lancamento> outbox,
                          Parceiro parceiro,
                          List<Artefato> artefatos,
                          Fila fila) {
    }

    public record Ciclo(String id, String banco, LocalDate dataRef, String status,
                        String remessaChave, String remessaSha256) {
    }

    /** Uma linha do outbox: de que fatura é a intenção, e se ela já saiu. */
    public record Lancamento(long id, String faturaId, String status) {
    }

    /** O que está no diretório do parceiro, dos dois lados do fio. */
    public record Parceiro(List<String> remessa, List<String> retorno) {
    }

    /** Um objeto no bucket — remessa gerada ou retorno arquivado. */
    public record Artefato(String chave, long bytes) {
    }

    /**
     * A fila vista de fora, sem consumir.
     *
     * <p>{@code mensagens} é o contador aproximado do SQS; {@code chavesDedup}
     * são as chaves de até {@link #ESPIADA} mensagens espiadas. As duas podem
     * discordar, e a discordância é do SQS, não nossa.
     */
    public record Fila(int mensagens, List<String> chavesDedup) {
    }

    public Retrato ler() {
        return new Retrato(
                ciclos(),
                contarPorStatus("tentativa_debito"),
                outbox(),
                new Parceiro(canal.listar(DIRETORIO_REMESSA), canal.listar(DIRETORIO_RETORNO)),
                artefatos(),
                fila());
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
     * As linhas do outbox, da mais antiga para a mais nova.
     *
     * <p>O {@code payload} não vem: o corpo do lançamento não é o que se olha
     * numa tela de operação, e trazê-lo obrigaria este pacote a conhecer o
     * formato que {@code infra/persistence} guarda para si.
     */
    private List<Lancamento> outbox() {
        String sql = "SELECT id, fatura_id, status FROM outbox ORDER BY id";
        try (Connection conexao = dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<Lancamento> linhas = new ArrayList<>();
            while (rs.next()) {
                linhas.add(new Lancamento(
                        rs.getLong("id"), rs.getString("fatura_id"), rs.getString("status")));
            }
            return List.copyOf(linhas);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao ler o outbox para o retrato", e);
        }
    }

    /**
     * Quantas linhas em cada status, na ordem em que o banco as devolve.
     *
     * <p>A tabela vem concatenada no SQL porque não há como parametrizar um
     * nome de relação — e é seguro porque a única chamada está logo acima, com
     * um literal.
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

    private List<Artefato> artefatos() {
        return ambiente.s3().listObjectsV2(pedido -> pedido.bucket(ambiente.bucket()))
                .contents().stream()
                .sorted(Comparator.comparing(S3Object::key))
                .map(objeto -> new Artefato(objeto.key(), objeto.size()))
                .toList();
    }

    /**
     * A fila: quantas mensagens ela diz ter, e as chaves de algumas delas.
     *
     * <p>{@code Approximate} no nome do atributo, e a aproximação é do SQS, não
     * nossa: o número é uma amostra distribuída. É por isso que os testes que
     * afirmam alguma coisa sobre a fila continuam <b>consumindo</b> as
     * mensagens em vez de perguntar aqui.
     *
     * <p>As chaves saem de um {@code receive} com {@code visibilityTimeout=0} —
     * uma espiada, não um consumo: a mensagem volta a ficar visível no mesmo
     * instante e o relay de ninguém perde trabalho por causa desta tela.
     * <br>DECISÃO: espiar a fila em vez de só contá-la — sem isso a duplicata do
     * crash-relay vira "o contador subiu de 3 para 4", e o que ela tem de
     * demonstrável é a MESMA chaveDedup aparecendo duas vezes — ver ADR-0002
     */
    private Fila fila() {
        List<Message> espiadas = ambiente.sqs().receiveMessage(pedido -> pedido
                        .queueUrl(ambiente.urlDaFila())
                        .maxNumberOfMessages(ESPIADA)
                        .visibilityTimeout(0)
                        .messageAttributeNames(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .messages();

        List<String> chaves = espiadas.stream()
                .map(mensagem -> mensagem.messageAttributes()
                        .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .map(atributo -> atributo == null ? "(sem chave)" : atributo.stringValue())
                .sorted()
                .toList();

        return new Fila(quantasNaFila(), chaves);
    }

    private int quantasNaFila() {
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
