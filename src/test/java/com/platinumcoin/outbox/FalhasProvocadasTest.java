package com.platinumcoin.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-11: cada falha que o desenho defende, provocada por {@code POST}.
 *
 * <p>Os cinco cenários já eram provados por teste unitário —
 * {@code ArquivoIncompletoNaoEhProcessadoTest}, {@code ReenvioDeRetornoTest},
 * {@code FechamentoNaoInventaResultadoTest}, {@code CrashDepoisDoPutTest} e
 * {@code CrashDoRelayTest}. Aqui eles são reproduzidos <b>pelo mesmo caminho
 * que o painel do step-12 usa</b>: se um botão deixar de produzir o efeito que
 * anuncia, é este arquivo que falha, e não a demonstração na frente de alguém.
 *
 * <p>Em todos, o formato é o mesmo e é o que interessa: provocar, olhar o
 * estado <b>entre</b> as duas chamadas, reexecutar, e ver convergir. A janela
 * só existe nesse intervalo.
 */
class FalhasProvocadasTest extends AmbienteDeTeste {

    private static final String BANCO = "341";
    private static final String CICLO = "C-FALHA";
    private static final LocalDate DATA = LocalDate.of(2026, 11, 10);
    private static final String RECORTE = "banco=" + BANCO + "&data=" + DATA;
    private static final String REMESSA_NO_PARCEIRO = "341-20261110-C-FALHA.rem";
    private static final String RETORNO_DO_PARCEIRO = "341-20261110-C-FALHA.ret";
    /** A primeira fatura do recorte: a única PAGO nos cenários que distribuem desfechos. */
    private static final String PAGA = "F-20261110-1";

    private static ServidorDeTeste servidor;
    private static ClienteHttp cliente;
    private static DiretorioDoParceiro parceiro;

    @BeforeAll
    static void subirServidor() {
        servidor = ServidorDeTeste.subir();
        cliente = servidor.cliente();
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
    }

    @AfterAll
    static void derrubarServidor() {
        servidor.close();
    }

    /**
     * Faturas, ciclo e remessa gerada — e para por aí.
     *
     * <p>A transmissão fica com cada teste porque um deles precisa armar a
     * falha <b>antes</b> dela: armar e disparar são chamadas separadas, e o
     * passo disparado é o {@code POST /ciclo/enviar}.
     */
    @BeforeEach
    void remessaGerada() throws SQLException {
        limparTabelas();
        drenarFila(0);
        parceiro.limpar(DIRETORIO_REMESSA);
        parceiro.limpar(DIRETORIO_RETORNO);

        ok(cliente.post("/faturas?quantidade=3&" + RECORTE));
        ok(cliente.post("/ciclo/montar?ciclo=" + CICLO + "&" + RECORTE));
        ok(cliente.post("/ciclo/gerar-remessa?ciclo=" + CICLO));
    }

    @Test
    @DisplayName("retorno truncado: nada é aplicado, e o arquivo continua lá para a próxima passada")
    void retornoTruncadoEhDescartadoInteiro() {
        ok(cliente.post("/ciclo/enviar?ciclo=" + CICLO));

        ClienteHttp.Resposta escrito = ok(cliente.post("/parceiro/retorno-truncado"));

        assertEquals(2, escrito.inteiro("linhasEscritas"),
                "o parceiro escreveu duas das três linhas e o trailer que pretendia cumprir");

        ClienteHttp.Resposta coletado = ok(cliente.post("/ciclo/coletar"));

        assertEquals("INCOMPLETO", coletado.em("arquivos").get(0).get("desfecho").asText());
        assertEquals(0, coletado.inteiro("aplicadas"),
                "descartado INTEIRO: aplicar as duas linhas que vieram seria indistinguível"
                        + " de um retorno legítimo menor");
        assertEquals(Map.of("ENVIADO_PARCEIRO", 3), tentativas());
        assertEquals(Map.of(), outbox());
        assertEquals(List.of(RETORNO_DO_PARCEIRO), parceiro.listar(DIRETORIO_RETORNO),
                "o arquivo é do parceiro e continua onde ele o deixou — nada é apagado");
    }

    @Test
    @DisplayName("reenvio do mesmo retorno: o sha256 curto-circuita, e o reagrupado afeta 0 linhas")
    void reenvioNaoDuplicaLancamento() {
        ok(cliente.post("/ciclo/enviar?ciclo=" + CICLO));
        ok(cliente.post("/parceiro/processar"));
        assertEquals(3, ok(cliente.post("/ciclo/coletar")).inteiro("aplicadas"));
        assertEquals(Map.of("PENDENTE", 3), outbox());

        ok(cliente.post("/parceiro/reenviar-retorno"));
        ClienteHttp.Resposta identico = ok(cliente.post("/ciclo/coletar"));

        assertEquals(List.of("REPETIDO"), desfechos(identico),
                "os mesmos bytes nem chegam a ser interpretados");
        assertEquals(0, identico.inteiro("aplicadas"));

        // O mesmo retorno reagrupado em três arquivos: outros bytes, e por isso
        // o atalho do hash não o reconhece. Está certo que não reconheça — quem
        // sabe o que já foi aplicado é o UPDATE condicional do step-03.
        ok(cliente.post("/parceiro/processar?particionar=3"));
        ClienteHttp.Resposta reagrupado = ok(cliente.post("/ciclo/coletar"));

        assertEquals(4, reagrupado.inteiro("vistos"));
        assertEquals(3, desfechos(reagrupado).stream().filter("APLICADO"::equals).count(),
                "os três arquivos novos são lidos e entregues linha a linha: " + reagrupado.corpo());
        assertEquals(0, reagrupado.inteiro("aplicadas"),
                "e nenhuma linha muda estado: o UPDATE condicional afeta zero linhas,"
                        + " que é o caso normal de um reprocessamento");
        assertEquals(Map.of("PAGO", 3), tentativas());
        assertEquals(Map.of("PENDENTE", 3), outbox(),
                "um lançamento por fatura, e nenhum a mais");
    }

    @Test
    @DisplayName("silêncio: o parceiro não escreve nada, e o fechamento conclui SEM_RETORNO")
    void silencioViraSemRetornoNoFechamento() {
        ok(cliente.post("/ciclo/enviar?ciclo=" + CICLO));

        ClienteHttp.Resposta silencio = ok(cliente.post("/parceiro/silencio"));

        assertEquals(3, silencio.inteiro("tentativasLidas"));
        assertEquals(0, silencio.inteiro("linhasEscritas"));
        assertEquals(0, silencio.em("arquivos").size());
        assertEquals(List.of(), parceiro.listar(DIRETORIO_RETORNO));
        assertEquals(0, ok(cliente.post("/ciclo/coletar")).inteiro("vistos"),
                "não há o que coletar: a ausência de arquivo não é um arquivo vazio");

        ClienteHttp.Resposta fechado = ok(cliente.post("/ciclo/fechar?ciclo=" + CICLO));

        assertEquals(3, fechado.inteiro("semRetorno"));
        assertEquals(Map.of("SEM_RETORNO", 3), tentativas(),
                "silêncio não é recusa: SEM_RETORNO, nunca NAO_PAGO");
        assertEquals(Map.of(), outbox(), "e nenhum dos dois gera lançamento");
    }

    @Test
    @DisplayName("crash entre o put e o COMMIT: o parceiro tem o arquivo, o banco não sabe disso")
    void crashDepoisDoPutConvergeNaReexecucao() {
        ClienteHttp.Resposta armada = ok(cliente.post("/falha/crash-envio"));

        assertEquals(List.of("CRASH_ENVIO"), textos(armada.em("armadas")));
        assertEquals("POST /ciclo/enviar", armada.texto("dispara"));
        assertEquals(List.of(), parceiro.listar(DIRETORIO_REMESSA),
                "armar não executa nada: até aqui o parceiro não recebeu coisa alguma");

        ClienteHttp.Resposta crash = cliente.post("/ciclo/enviar?ciclo=" + CICLO);

        assertEquals(409, crash.status(), () -> "resposta: " + crash.corpo());
        assertEquals("FalhaDePersistencia", crash.texto("erro"));
        assertEquals(List.of(REMESSA_NO_PARCEIRO), parceiro.listar(DIRETORIO_REMESSA),
                "o put já aconteceu: é exatamente por isso que a janela existe");
        assertEquals(Map.of("SOLICITADO", 3), tentativas(),
                "o COMMIT não aconteceu: para o banco, o arquivo ainda não saiu");
        assertEquals("MONTADO", cliente.get("/estado").em("ciclos").get(0).get("status").asText());

        // A falha se desarmou ao disparar. A próxima passada encontra esse
        // estado e transmite de novo.
        ClienteHttp.Resposta reenvio = ok(cliente.post("/ciclo/enviar?ciclo=" + CICLO));

        assertEquals(3, reenvio.inteiro("enviadas"));
        assertEquals(List.of(REMESSA_NO_PARCEIRO), parceiro.listar(DIRETORIO_REMESSA),
                "UM arquivo no destino, não dois: o nome é derivado do ciclo e a segunda"
                        + " entrega sobrescreve a primeira");
        assertEquals(Map.of("ENVIADO_PARCEIRO", 3), tentativas());
    }

    @Test
    @DisplayName("crash entre o send e o UPDATE: duas mensagens na fila, uma chave de dedup")
    void crashDoRelayRepublicaComAMesmaChave() {
        ok(cliente.post("/ciclo/enviar?ciclo=" + CICLO));
        ok(cliente.post("/parceiro/processar?resultado=PAGO,NAO_PAGO,ERRO"));
        ok(cliente.post("/ciclo/coletar"));
        assertEquals(Map.of("PENDENTE", 1), outbox());

        ok(cliente.post("/falha/crash-relay"));
        ClienteHttp.Resposta crash = cliente.post("/outbox/publicar");

        assertEquals(409, crash.status(), () -> "resposta: " + crash.corpo());
        assertEquals(Map.of("PENDENTE", 1), outbox(),
                "a mensagem saiu, mas a linha continua PENDENTE — é o que autoriza a"
                        + " republicação");

        ClienteHttp.Resposta segunda = ok(cliente.post("/outbox/publicar"));

        assertEquals(1, segunda.inteiro("publicados"));
        assertEquals(List.of(PAGA), textos(segunda.em("chavesDedup")));
        assertEquals(Map.of("PUBLICADO", 1), outbox());

        List<Message> naFila = drenarFila(2);

        assertEquals(2, naFila.size(), "duas mensagens para um lançamento: o preço da ordem correta");
        assertEquals(Set.of(PAGA), chaves(naFila),
                "a mesma chaveDedup nas duas — derivada da fatura, não gerada no envio");
        assertEquals(1, naFila.stream().map(Message::body).distinct().count(),
                "e o mesmo corpo: republicar não produz uma mensagem diferente");
    }

    // ------------------------------------------------------------------ apoio

    private static ClienteHttp.Resposta ok(ClienteHttp.Resposta resposta) {
        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        return resposta;
    }

    private static Set<String> chaves(List<Message> mensagens) {
        return mensagens.stream()
                .map(mensagem -> mensagem.messageAttributes()
                        .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP))
                .map(MessageAttributeValue::stringValue)
                .collect(Collectors.toSet());
    }

    private static List<String> desfechos(ClienteHttp.Resposta coletado) {
        List<String> valores = new ArrayList<>();
        coletado.em("arquivos").forEach(item -> valores.add(item.get("desfecho").asText()));
        return List.copyOf(valores);
    }

    private static List<String> textos(JsonNode lista) {
        List<String> valores = new ArrayList<>();
        lista.forEach(item -> valores.add(item.asText()));
        return List.copyOf(valores);
    }

    /** As contagens do {@code GET /estado} — a mesma leitura que o painel faz. */
    private static Map<String, Integer> tentativas() {
        return contagens("tentativas");
    }

    /**
     * As linhas do outbox agrupadas por status.
     *
     * <p>O {@code /estado} devolve as <b>linhas</b>, e não a contagem: do
     * outbox há no máximo uma por fatura, e qual delas está pendente é a
     * informação — ver {@code EstadoDoMundo}. Quem quer só a distribuição,
     * como estes testes, agrupa aqui.
     */
    private static Map<String, Integer> outbox() {
        Map<String, Integer> porStatus = new LinkedHashMap<>();
        cliente.get("/estado").em("outbox").forEach(
                linha -> porStatus.merge(linha.get("status").asText(), 1, Integer::sum));
        return porStatus;
    }

    private static Map<String, Integer> contagens(String bloco) {
        JsonNode contado = cliente.get("/estado").em(bloco);
        Map<String, Integer> porStatus = new LinkedHashMap<>();
        contado.fieldNames().forEachRemaining(
                status -> porStatus.put(status, contado.get(status).asInt()));
        return porStatus;
    }
}
