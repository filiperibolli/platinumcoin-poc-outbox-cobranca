package com.platinumcoin.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-12: o painel existe, é servido pelo Spring e não depende de nada de fora.
 *
 * <p>Os dois testes olham para coisas diferentes de propósito. O primeiro sobe
 * o servidor e pergunta pela rota, porque "o arquivo está em
 * {@code resources/static}" não é o mesmo que "o navegador o recebe". O segundo
 * lê o arquivo do disco e não sobe nada: a asserção "sem CDN" é sobre o
 * conteúdo, e um teste que precisasse de container para prová-la seria caro sem
 * ser mais verdadeiro.
 *
 * <p><b>Por que "sem host externo" é um teste, e não uma convenção.</b> Um
 * {@code <script src="https://…">} não quebra nada na máquina de quem o
 * escreveu — quebra na de quem abre o projeto sem internet, ou daqui a dois
 * anos, quando o CDN mudar de ideia. É exatamente o tipo de dependência que
 * some da revisão e aparece na demonstração.
 */
class PainelEhServidoTest extends AmbienteDeTeste {

    private static final Path PAINEL =
            Path.of("src", "main", "resources", "static", "index.html");

    /** {@code src=} ou {@code href=} apontando para fora — com esquema ou protocol-relative. */
    private static final Pattern APONTA_PARA_FORA =
            Pattern.compile("(?:src|href)\\s*=\\s*[\"'](\\s*(?:https?:)?//[^\"']*)[\"']",
                    Pattern.CASE_INSENSITIVE);

    private static ServidorDeTeste servidor;
    private static ClienteHttp cliente;

    @BeforeAll
    static void subirOServidor() {
        servidor = ServidorDeTeste.subir();
        cliente = servidor.cliente();
    }

    @AfterAll
    static void derrubarOServidor() {
        if (servidor != null) {
            servidor.close();
        }
    }

    @Test
    @DisplayName("GET / devolve o painel em HTML, com um botão por passo do ciclo")
    void painelEhServidoNaRaiz() {
        ClienteHttp.Pagina painel = cliente.pagina("/");

        assertEquals(200, painel.status(),
                () -> "a raiz precisa servir o painel: " + painel.corpo());
        assertTrue(painel.tipo().startsWith("text/html"),
                () -> "o painel é HTML, e o Content-Type precisa dizer isso: " + painel.tipo());

        // Os oito passos do ciclo, na ordem do fluxo — inclusive o do parceiro,
        // que é o que falta a quem tenta operar o ciclo só pela lista de rotas.
        List<String> passos = List.of(
                "/faturas", "/ciclo/montar", "/ciclo/gerar-remessa", "/ciclo/enviar",
                "/parceiro/processar", "/ciclo/coletar", "/ciclo/fechar", "/outbox/publicar");
        for (String passo : passos) {
            assertTrue(painel.corpo().contains("data-post=\"" + passo),
                    () -> "o painel precisa ter um botão para " + passo);
        }

        // E as duas janelas, que são o motivo de o projeto existir.
        assertTrue(painel.corpo().contains("/falha/crash-envio")
                        && painel.corpo().contains("/falha/crash-relay"),
                "a seção de falhas precisa oferecer as duas janelas provocáveis");
    }

    @Test
    @DisplayName("os blocos que o painel pinta são os que o /estado devolve")
    void painelEOEstadoFalamADaMesmaCoisa() {
        JsonNode estado = cliente.get("/estado").corpo();

        // O painel lê estes seis campos por nome, em JavaScript, onde um nome
        // errado não quebra a compilação — vira um bloco vazio que ninguém
        // relaciona com o rename feito no Retrato três semanas antes.
        for (String bloco : List.of("ciclos", "tentativas", "outbox", "parceiro",
                "artefatos", "fila")) {
            assertTrue(estado.has(bloco),
                    () -> "o painel pinta '" + bloco + "' e o /estado não o devolve: " + estado);
            assertTrue(html().contains("id=\"" + bloco + "\""),
                    () -> "o /estado devolve '" + bloco + "' e o painel não tem onde pintá-lo");
        }

        assertTrue(estado.get("parceiro").has("remessa")
                        && estado.get("parceiro").has("retorno"),
                "o bloco do parceiro mostra os dois lados do fio: " + estado.get("parceiro"));
        assertTrue(estado.get("fila").has("mensagens")
                        && estado.get("fila").has("chavesDedup"),
                "a fila mostra quantas e quais — a duplicata do relay é a MESMA chave"
                        + " duas vezes, e uma contagem sozinha esconderia isso: "
                        + estado.get("fila"));
    }

    private static String html() {
        try {
            return Files.readString(PAINEL);
        } catch (IOException e) {
            throw new IllegalStateException("o painel precisa existir em " + PAINEL, e);
        }
    }

    @Test
    @DisplayName("o painel não busca nada de fora: sem CDN, sem fonte remota, sem script externo")
    void painelNaoDependeDeNadaDeFora() throws IOException {
        String html = Files.readString(PAINEL);

        List<String> externos = new ArrayList<>();
        Matcher aponta = APONTA_PARA_FORA.matcher(html);
        while (aponta.find()) {
            externos.add(aponta.group(1));
        }

        assertEquals(List.of(), externos,
                "todo src/href do painel precisa ser relativo — um arquivo, sem build e sem CDN");
        assertTrue(!html.contains("http://") && !html.contains("https://"),
                "nem em comentário: uma URL absoluta no painel é uma dependência esperando"
                        + " para ser colada num atributo");
    }
}
