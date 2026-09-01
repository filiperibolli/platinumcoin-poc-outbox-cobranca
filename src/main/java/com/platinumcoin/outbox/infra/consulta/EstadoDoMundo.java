package com.platinumcoin.outbox.infra.consulta;

import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.infra.config.Ambiente;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
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
import java.util.Comparator;
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
                          List<Lancamento> lancamentos,
                          Parceiro parceiro,
                          List<Arquivo> artefatos,
                          int mensagensNaFila,
                          List<String> chavesNaFila) {
    }

    public record Ciclo(String id, String banco, LocalDate dataRef, String status,
                        String remessaChave, String remessaSha256) {
    }

    /**
     * Uma linha do outbox.
     *
     * <p>Existe ao lado da contagem por status, e não no lugar dela, porque as
     * duas respondem perguntas diferentes: a contagem diz <b>quanto</b>, a linha
     * diz <b>o quê</b>. E é a {@code chaveDedup} que faz o painel do step-12
     * valer a pena — a mesma chave aparece aqui e na fila, e é assim que a
     * duplicata do relay fica visível sem que ninguém precise deduzi-la.
     */
    public record Lancamento(long id, String faturaId, String chaveDedup, String status) {
    }

    /**
     * Um arquivo, onde quer que ele esteja: no diretório do parceiro ou no
     * bucket.
     *
     * <p>{@code nome} é o endereço como a fonte o reporta — caminho completo no
     * SFTP, chave no S3.
     */
    public record Arquivo(String nome, long tamanho) {
    }

    /** O que está no diretório do parceiro, dos dois lados do fio. */
    public record Parceiro(List<Arquivo> remessa, List<Arquivo> retorno) {
    }

    public Retrato ler() {
        return new Retrato(
                ciclos(),
                contarPorStatus("tentativa_debito"),
                contarPorStatus("outbox"),
                lancamentos(),
                new Parceiro(doParceiro(DIRETORIO_REMESSA), doParceiro(DIRETORIO_RETORNO)),
                artefatos(),
                mensagensNaFila(),
                chavesNaFila());
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

    /**
     * As linhas do outbox, da mais antiga para a mais nova.
     *
     * <p>A {@code chaveDedup} <b>é</b> o id da fatura — é o que
     * {@code LancamentoContabil.chaveDedup()} responde, e é o que faz duas
     * publicações da mesma linha carregarem a mesma chave. Aqui ela é lida da
     * coluna porque o retrato não monta objetos de domínio: ele lê tabelas.
     */
    private List<Lancamento> lancamentos() {
        String sql = "SELECT id, fatura_id, status FROM outbox ORDER BY id";
        try (Connection conexao = dados().getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            List<Lancamento> linhas = new ArrayList<>();
            while (rs.next()) {
                String faturaId = rs.getString("fatura_id");
                linhas.add(new Lancamento(
                        rs.getLong("id"), faturaId, faturaId, rs.getString("status")));
            }
            return List.copyOf(linhas);
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao ler o outbox para o retrato", e);
        }
    }

    /**
     * Os arquivos de um diretório do parceiro, com o tamanho de cada um.
     *
     * <p>Uma pergunta de atributos por arquivo, e cada uma é uma conexão SSH —
     * o preço de ler o outro lado pela <b>mesma</b> porta que a coleta usa, em
     * vez de abrir um caminho paralelo só para a tela. Com o punhado de arquivos
     * de uma demonstração, custa menos que a explicação de por que haveria dois
     * jeitos de listar o mesmo diretório.
     *
     * <p>O que sumiu entre a listagem e a pergunta some do retrato: o parceiro
     * pode ter movido o arquivo, e isso não é falha de ninguém — é o mundo
     * mudando entre duas leituras, exatamente como na quiescência do step-09.
     */
    private List<Arquivo> doParceiro(String diretorio) {
        return canal.listar(diretorio).stream()
                .flatMap(caminho -> canal.atributos(caminho).stream()
                        .map(atributos -> new Arquivo(caminho, atributos.tamanho())))
                .toList();
    }

    private List<Arquivo> artefatos() {
        return ambiente.s3().listObjectsV2(pedido -> pedido.bucket(ambiente.bucket()))
                .contents().stream()
                .sorted(Comparator.comparing(S3Object::key))
                .map(objeto -> new Arquivo(objeto.key(), objeto.size()))
                .toList();
    }

    /**
     * As chaves de dedup das mensagens que estão na fila agora, até dez.
     *
     * <p>Uma espiada, não um consumo: {@code visibilityTimeout(0)} devolve a
     * mensagem à fila no mesmo instante, e por isso o painel pode perguntar de
     * dois em dois segundos sem tirar da frente do mainframe o que ele ainda não
     * leu. É a mesma chamada do {@code awslocal sqs receive-message} do README.
     *
     * <p>É o outro lado da {@code chaveDedup} do outbox — e é olhando os dois ao
     * mesmo tempo, depois do {@code crash-relay}, que se vê a mesma chave duas
     * vezes aqui e uma vez lá.
     */
    private List<String> chavesNaFila() {
        return ambiente.sqs().receiveMessage(pedido -> pedido
                        .queueUrl(ambiente.urlDaFila())
                        .maxNumberOfMessages(10)
                        .visibilityTimeout(0)
                        .messageAttributeNames(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .messages().stream()
                .map(mensagem -> mensagem.messageAttributes()
                        .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .map(atributo -> atributo == null ? "(sem chave)" : atributo.stringValue())
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
