package com.platinumcoin.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-11: o retorno que o parceiro escreve é o retorno que a coleta aplica.
 *
 * <p>O ciclo inteiro por HTTP, com uma diferença em relação ao step-10: o
 * arquivo de retorno não é depositado pelo teste, é <b>produzido pelo
 * simulador</b> a partir da remessa que atravessou o SFTP. É o que fecha a
 * volta — até aqui o projeto provava que sabia ler um arquivo de retorno; a
 * partir daqui ele mostra o arquivo nascendo do outro lado do fio.
 *
 * <p>Os três testes são as três formas de o parceiro entregar o mesmo retorno:
 * num arquivo, em vários, e em vários momentos. Todas convergem para o mesmo
 * estado — que é a segunda invariante do projeto vista pelo lado do canal.
 */
class SimuladorProduzRetornoAplicavelTest extends AmbienteDeTeste {

    private static final String BANCO = "341";

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

    @BeforeEach
    void mundoZerado() throws SQLException {
        limparTabelas();
        drenarFila(0);
        parceiro.limpar(DIRETORIO_REMESSA);
        parceiro.limpar(DIRETORIO_RETORNO);
    }

    @Test
    @DisplayName("o parceiro responde num arquivo, e só as tentativas PAGO geram outbox")
    void retornoEmUmArquivoEhAplicado() {
        LocalDate data = LocalDate.of(2026, 11, 3);
        cicloEnviado("C-SIM-1", data, 3);

        ClienteHttp.Resposta escrito =
                ok(cliente.post("/parceiro/processar?resultado=PAGO,NAO_PAGO,ERRO"));

        assertEquals(3, escrito.inteiro("tentativasLidas"),
                "as tentativas vêm da remessa lida do SFTP, não de uma consulta ao banco");
        assertEquals(3, escrito.inteiro("linhasEscritas"));
        assertEquals(0, escrito.inteiro("partesPendentes"));
        assertEquals(List.of("341-20261103-C-SIM-1.ret"), nomes(escrito));
        assertEquals(List.of("341-20261103-C-SIM-1.ret"), parceiro.listar(DIRETORIO_RETORNO),
                "o arquivo está no diretório do parceiro, posto por ele");

        ClienteHttp.Resposta coletado = ok(cliente.post("/ciclo/coletar"));

        assertEquals(1, coletado.inteiro("vistos"));
        assertEquals(3, coletado.inteiro("aplicadas"));
        assertEquals("APLICADO", coletado.em("arquivos").get(0).get("desfecho").asText());
        assertEquals(Map.of("PAGO", 1, "NAO_PAGO", 1, "ERRO", 1), tentativas());
        assertEquals(Map.of("PENDENTE", 1), outbox(),
                "uma linha para a única tentativa PAGO — nem NAO_PAGO nem ERRO são pagamento");
    }

    @Test
    @DisplayName("o parceiro particiona o retorno em dois arquivos, e o ciclo chega ao mesmo estado")
    void retornoParticionadoConverge() {
        LocalDate data = LocalDate.of(2026, 11, 4);
        cicloEnviado("C-SIM-2", data, 3);

        ClienteHttp.Resposta escrito = ok(cliente.post("/parceiro/processar?particionar=2"));

        assertEquals(List.of("341-20261104-C-SIM-2-1.ret", "341-20261104-C-SIM-2-2.ret"),
                nomes(escrito), "o parceiro não promete um arquivo por ciclo");
        assertEquals(3, escrito.inteiro("linhasEscritas"));

        ClienteHttp.Resposta coletado = ok(cliente.post("/ciclo/coletar"));

        assertEquals(2, coletado.inteiro("vistos"));
        assertEquals(3, coletado.inteiro("aplicadas"));
        assertTrue(desfechos(coletado).equals(List.of("APLICADO", "APLICADO")),
                "cada arquivo fecha o próprio trailer: " + coletado.corpo());
        assertEquals(Map.of("PAGO", 3), tentativas());
        assertEquals(Map.of("PENDENTE", 3), outbox());
    }

    @Test
    @DisplayName("o parceiro entrega metade agora e o resto depois; o que falta espera, não vira nada")
    void retornoAtrasadoEsperaAProximaEntrega() {
        LocalDate data = LocalDate.of(2026, 11, 5);
        cicloEnviado("C-SIM-3", data, 3);

        ClienteHttp.Resposta primeira = ok(cliente.post("/parceiro/processar?atrasar=true"));

        assertEquals(List.of("341-20261105-C-SIM-3-1.ret"), nomes(primeira));
        assertEquals(2, primeira.inteiro("linhasEscritas"));
        assertEquals(1, primeira.inteiro("partesPendentes"),
                "o parceiro ainda tem o que dizer sobre este ciclo");

        ClienteHttp.Resposta parcial = ok(cliente.post("/ciclo/coletar"));

        assertEquals(2, parcial.inteiro("aplicadas"));
        assertEquals(Map.of("PAGO", 2, "ENVIADO_PARCEIRO", 1), tentativas(),
                "a tentativa sobre a qual o parceiro não falou continua esperando — é ela"
                        + " que o fechamento transformaria em SEM_RETORNO");

        ClienteHttp.Resposta segunda = ok(cliente.post("/parceiro/processar?atrasar=true"));

        assertEquals(List.of("341-20261105-C-SIM-3-2.ret"), nomes(segunda));
        assertEquals(0, segunda.inteiro("partesPendentes"));

        ClienteHttp.Resposta completa = ok(cliente.post("/ciclo/coletar"));

        assertEquals(2, completa.inteiro("vistos"), "a primeira parte continua no diretório");
        assertEquals(List.of("REPETIDO", "APLICADO"), desfechos(completa),
                "os mesmos bytes da parte já aplicada são reconhecidos pelo sha256"
                        + " e nem chegam a ser interpretados");
        assertEquals(1, completa.inteiro("aplicadas"));
        assertEquals(Map.of("PAGO", 3), tentativas());
        assertEquals(Map.of("PENDENTE", 3), outbox());
    }

    // ------------------------------------------------------------------ apoio

    /** Faturas, ciclo, remessa e transmissão — o estado em que o parceiro entra na história. */
    private static void cicloEnviado(String ciclo, LocalDate data, int quantidade) {
        String recorte = "banco=" + BANCO + "&data=" + data;
        ok(cliente.post("/faturas?quantidade=" + quantidade + "&" + recorte));
        ok(cliente.post("/ciclo/montar?ciclo=" + ciclo + "&" + recorte));
        ok(cliente.post("/ciclo/gerar-remessa?ciclo=" + ciclo));
        ok(cliente.post("/ciclo/enviar?ciclo=" + ciclo));
    }

    private static ClienteHttp.Resposta ok(ClienteHttp.Resposta resposta) {
        assertEquals(200, resposta.status(), () -> "resposta: " + resposta.corpo());
        return resposta;
    }

    private static List<String> nomes(ClienteHttp.Resposta escrito) {
        return textos(escrito.em("arquivos"), "nome");
    }

    private static List<String> desfechos(ClienteHttp.Resposta coletado) {
        return textos(coletado.em("arquivos"), "desfecho");
    }

    private static List<String> textos(JsonNode lista, String campo) {
        List<String> valores = new ArrayList<>();
        lista.forEach(item -> valores.add(item.get(campo).asText()));
        return List.copyOf(valores);
    }

    /**
     * As contagens do {@code GET /estado} — a mesma leitura que o painel do
     * step-12 faz.
     */
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
