package com.platinumcoin.ciclo;

import com.fasterxml.jackson.databind.JsonNode;
import com.platinumcoin.ciclo.api.LinhaRetorno;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.infra.persistence.PublicadorLancamentoSqs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.sqs.model.Message;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-10: o ciclo inteiro por HTTP, na ordem, contra os containers.
 *
 * <p>Duas afirmações em cada passo, e a segunda é a que importa: <b>a resposta
 * descreve o efeito</b>, e o efeito descrito é o que o mundo sofreu. Cada
 * contagem que a API devolve é conferida contra o banco, contra o diretório do
 * parceiro ou contra a fila — um corpo que dissesse "3 tentativas solicitadas"
 * sem que o banco tivesse três seria pior que um {@code 200} vazio.
 *
 * <p>Os testes são ordenados porque o assunto é ordenado: não existe "enviar a
 * remessa" antes de "montar o ciclo". É o mesmo cenário do {@code Main} do
 * step-06, dito por outro canal — e o {@code Main} continua existindo,
 * inalterado.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EndpointsDevolvemEfeitoTest extends AmbienteDeTeste {

    private static final String CICLO = "C-HTTP";
    private static final String BANCO = "341";
    /** Data própria: os ids das faturas, as chaves e os nomes dos arquivos a carregam. */
    private static final LocalDate DATA = LocalDate.of(2026, 9, 1);
    private static final String RECORTE = "banco=" + BANCO + "&data=" + DATA;

    private static final String PAGA = "F-20260901-1";
    private static final String RECUSADA = "F-20260901-2";
    private static final String SILENCIOSA = "F-20260901-3";

    private static ServidorDeTeste servidor;
    private static ClienteHttp cliente;
    private static DiretorioDoParceiro parceiro;

    /** Sobe o servidor de verdade numa porta livre, apontado para os mesmos containers. */
    @BeforeAll
    static void subirServidor() throws SQLException {
        limparTabelas();
        drenarFila(0);
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_REMESSA);
        parceiro.limpar(DIRETORIO_RETORNO);

        servidor = ServidorDeTeste.subir();
        cliente = servidor.cliente();
    }

    @AfterAll
    static void derrubarServidor() {
        servidor.close();
    }

    @Test
    @Order(1)
    @DisplayName("POST /faturas devolve as faturas que passaram a existir")
    void faturasAbertas() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/faturas?quantidade=3&" + RECORTE);

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals(3, resposta.inteiro("criadas"));
        assertEquals(List.of(PAGA, RECUSADA, SILENCIOSA), textos(resposta.em("faturas")));
        assertEquals(BANCO, resposta.texto("banco"));
        assertEquals(DATA.toString(), resposta.texto("dataRef"));
        assertEquals(Map.of("ABERTO", 3), porStatus("tentativa_debito"));
    }

    @Test
    @Order(2)
    @DisplayName("POST /ciclo/montar devolve o ciclo e quantas tentativas ele puxou")
    void cicloMontado() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/ciclo/montar?ciclo=" + CICLO + "&" + RECORTE);

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals(CICLO, resposta.texto("cicloId"));
        assertEquals(CicloCobranca.Status.MONTADO.name(), resposta.texto("status"));
        assertEquals(3, resposta.inteiro("solicitadas"));
        assertEquals(Map.of("SOLICITADO", 3), porStatus("tentativa_debito"));
    }

    @Test
    @Order(3)
    @DisplayName("POST /ciclo/gerar-remessa devolve a chave, o sha256 e a contagem do trailer")
    void remessaGerada() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/ciclo/gerar-remessa?ciclo=" + CICLO);

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals("remessa/341/20260901/C-HTTP.rem", resposta.texto("chave"));
        assertEquals(3, resposta.inteiro("detalhes"));
        // O hash que a resposta afirma é o que o ciclo guardou: se a geração
        // gravasse uma coisa e respondesse outra, é aqui que apareceria.
        assertEquals(resposta.texto("sha256"), umValor("SELECT remessa_sha256 FROM ciclo_cobranca"));
    }

    @Test
    @Order(4)
    @DisplayName("POST /ciclo/enviar devolve o nome no parceiro, e o arquivo está lá")
    void remessaEnviada() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/ciclo/enviar?ciclo=" + CICLO);

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals("341-20260901-C-HTTP.rem", resposta.texto("nomeNoParceiro"));
        assertEquals(CicloCobranca.Status.ENVIADO.name(), resposta.texto("status"));
        assertEquals(3, resposta.inteiro("enviadas"));
        // Do lado do parceiro, por outra conexão: o que se afirma é o arquivo
        // que ele tem, não o que o nosso canal diz ter enviado.
        assertEquals(List.of(resposta.texto("nomeNoParceiro")), parceiro.listar(DIRETORIO_REMESSA));
        assertEquals(Map.of("ENVIADO_PARCEIRO", 3), porStatus("tentativa_debito"));
    }

    @Test
    @Order(5)
    @DisplayName("POST /ciclo/coletar devolve o que foi visto, baixado e aplicado")
    void retornoColetado() throws SQLException {
        // O parceiro fala sobre duas das três tentativas. A terceira é o
        // silêncio que o fechamento vai ter de interpretar.
        parceiro.escrever(DIRETORIO_RETORNO + "/" + RetornoDoParceiro.nome(ciclo()),
                RetornoDoParceiro.arquivo(ciclo(), List.of(
                        LinhaRetorno.paga(PAGA + "-T1"),
                        LinhaRetorno.naoPaga(RECUSADA + "-T1",
                                TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE))));

        ClienteHttp.Resposta resposta = cliente.post("/ciclo/coletar");

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals(1, resposta.inteiro("vistos"));
        assertEquals(2, resposta.inteiro("aplicadas"));
        JsonNode arquivo = resposta.em("arquivos").get(0);
        assertEquals(RetornoDoParceiro.nome(ciclo()), arquivo.get("nome").asText());
        assertEquals("APLICADO", arquivo.get("desfecho").asText());
        assertEquals(2, arquivo.get("linhas").asInt());
        assertEquals(
                Map.of("PAGO", 1, "NAO_PAGO", 1, "ENVIADO_PARCEIRO", 1),
                porStatus("tentativa_debito"));
        assertEquals(Map.of("PENDENTE", 1), porStatus("outbox"));
    }

    @Test
    @Order(6)
    @DisplayName("POST /ciclo/fechar devolve quantas tentativas viraram SEM_RETORNO")
    void cicloFechado() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/ciclo/fechar?ciclo=" + CICLO);

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals(1, resposta.inteiro("semRetorno"));
        assertEquals(
                Map.of("PAGO", 1, "NAO_PAGO", 1, "SEM_RETORNO", 1),
                porStatus("tentativa_debito"));
        // Silêncio não vira lançamento: o outbox continua com a única linha que
        // o pagamento gravou.
        assertEquals(Map.of("PENDENTE", 1), porStatus("outbox"));
    }

    @Test
    @Order(7)
    @DisplayName("POST /outbox/publicar devolve as chaves de dedup que foram para a fila")
    void outboxPublicado() throws SQLException {
        ClienteHttp.Resposta resposta = cliente.post("/outbox/publicar");

        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        assertEquals(1, resposta.inteiro("publicados"));
        assertEquals(List.of(PAGA), textos(resposta.em("chavesDedup")));
        assertEquals(Map.of("PUBLICADO", 1), porStatus("outbox"));
    }

    @Test
    @Order(8)
    @DisplayName("GET /estado mostra as cinco fontes: banco, outbox, parceiro, bucket e fila")
    void estadoMostraAsCincoFontes() {
        ClienteHttp.Resposta estado = estadoComAFilaVisivel();

        JsonNode ciclo = estado.em("ciclos").get(0);
        assertEquals(CICLO, ciclo.get("id").asText());
        assertEquals(CicloCobranca.Status.FECHADO.name(), ciclo.get("status").asText());

        assertEquals(3, estado.em("tentativas").size());
        assertEquals(1, estado.em("tentativas").get("SEM_RETORNO").asInt());

        // O outbox vem como linhas, e não como contagem: qual fatura está em
        // qual status é o que a janela do relay se enxerga — ver EstadoDoMundo.
        assertEquals(1, estado.em("outbox").size());
        assertEquals(PAGA, estado.em("outbox").get(0).get("faturaId").asText());
        assertEquals("PUBLICADO", estado.em("outbox").get(0).get("status").asText());

        assertTrue(textos(estado.em("parceiro").get("remessa"))
                        .contains(DIRETORIO_REMESSA + "/341-20260901-C-HTTP.rem"),
                "a remessa transmitida precisa aparecer no diretório do parceiro: " + estado.corpo());
        assertTrue(textos(estado.em("parceiro").get("retorno"))
                        .contains(DIRETORIO_RETORNO + "/" + RetornoDoParceiro.nome(ciclo())),
                "o retorno recebido precisa aparecer no diretório do parceiro: " + estado.corpo());

        List<String> artefatos = campos(estado.em("artefatos"), "chave");
        assertTrue(artefatos.contains("remessa/341/20260901/C-HTTP.rem"), "artefatos: " + artefatos);
        assertTrue(artefatos.contains(
                        "retorno/341/20260901/" + RetornoDoParceiro.nome(ciclo())),
                "o retorno também é arquivado, e antes de ser validado: " + artefatos);
        estado.em("artefatos").forEach(objeto -> assertTrue(objeto.get("bytes").asLong() > 0,
                "objeto vazio no bucket: " + objeto));

        assertTrue(estado.em("fila").get("mensagens").asInt() >= 1,
                "a fila precisa mostrar o lançamento publicado: " + estado.corpo());
        // A espiada não consome: a chave está lá, e o teste seguinte ainda drena.
        assertTrue(textos(estado.em("fila").get("chavesDedup")).contains(PAGA),
                "a fila precisa mostrar a chaveDedup do lançamento: " + estado.corpo());
    }

    @Test
    @Order(9)
    @DisplayName("o lançamento chegou à fila com a chave de dedup que a resposta anunciou")
    void filaRecebeuOLancamento() {
        List<Message> naFila = drenarFila(1);

        assertEquals(1, naFila.size());
        assertEquals(PAGA, naFila.get(0).messageAttributes()
                .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP).stringValue());
    }

    // ------------------------------------------------------------------ apoio

    /** O ciclo como o parceiro o conhece — só o que o header do arquivo carrega. */
    private static CicloCobranca ciclo() {
        return CicloCobranca.montado(CICLO, BANCO, DATA);
    }

    /**
     * O retrato, esperando a fila aparecer nele.
     *
     * <p>{@code ApproximateNumberOfMessages} é aproximado por contrato: a
     * mensagem já foi aceita quando o {@code POST} respondeu, mas o número pode
     * demorar um instante a refletir isso.
     */
    private static ClienteHttp.Resposta estadoComAFilaVisivel() {
        ClienteHttp.Resposta estado = cliente.get("/estado");
        for (int espera = 0; espera < 20 && estado.em("fila").get("mensagens").asInt() < 1; espera++) {
            dormir();
            estado = cliente.get("/estado");
        }
        return estado;
    }

    private static void dormir() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("espera interrompida", e);
        }
    }

    /** Os itens de um array JSON como texto, na ordem em que a resposta os trouxe. */
    private static List<String> textos(JsonNode lista) {
        List<String> valores = new ArrayList<>();
        lista.forEach(item -> valores.add(item.asText()));
        return List.copyOf(valores);
    }

    /** Um campo de cada objeto de um array JSON — os artefatos trazem chave e tamanho. */
    private static List<String> campos(JsonNode lista, String campo) {
        List<String> valores = new ArrayList<>();
        lista.forEach(item -> valores.add(item.get(campo).asText()));
        return List.copyOf(valores);
    }

    private static Map<String, Integer> porStatus(String tabela) throws SQLException {
        Map<String, Integer> contagem = new LinkedHashMap<>();
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT status, count(*) FROM " + tabela + " GROUP BY status")) {
            while (rs.next()) {
                contagem.put(rs.getString(1), rs.getInt(2));
            }
        }
        return contagem;
    }

    private static String umValor(String sql) throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            assertTrue(rs.next(), "nenhuma linha para: " + sql);
            return rs.getString(1);
        }
    }
}
