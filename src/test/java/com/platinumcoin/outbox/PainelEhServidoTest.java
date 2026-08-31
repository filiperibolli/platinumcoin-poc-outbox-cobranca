package com.platinumcoin.outbox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-12: o painel existe, é servido pelo próprio Spring e não depende de nada
 * de fora.
 *
 * <p>"Sem CDN" é a promessa mais fácil de quebrar sem perceber — basta uma
 * fonte, um ícone ou um {@code <script src>} colado de algum lugar — e a que
 * mais custa quando quebra: o demo local passa a exigir internet. Por isso ela
 * é asserida <b>literalmente</b>, no corpo que o navegador recebe.
 *
 * <p>A ordem dos botões também é asserida. Não é preciosismo de layout: a
 * coluna da esquerda é a única documentação do fluxo que alguém vai ler antes
 * de clicar, e um passo fora de lugar ensina a sequência errada.
 */
class PainelEhServidoTest extends AmbienteDeTeste {

    /** Os oito passos, na ordem em que o ciclo acontece. */
    private static final List<String> FLUXO = List.of(
            "/faturas",
            "/ciclo/montar",
            "/ciclo/gerar-remessa",
            "/ciclo/enviar",
            "/parceiro/processar",
            "/ciclo/coletar",
            "/ciclo/fechar",
            "/outbox/publicar");

    /** As seis rotas que mexem no ambiente, e não no sistema. */
    private static final List<String> AMBIENTE = List.of(
            "/parceiro/processar",
            "/parceiro/reenviar-retorno",
            "/parceiro/retorno-truncado",
            "/parceiro/silencio",
            "/falha/crash-relay",
            "/falha/crash-envio");

    private static ServidorDeTeste servidor;
    private static ClienteHttp.Pagina painel;

    @BeforeAll
    static void subirServidor() {
        servidor = ServidorDeTeste.subir();
        painel = servidor.cliente().pagina("/");
    }

    @AfterAll
    static void derrubarServidor() {
        servidor.close();
    }

    @Test
    @DisplayName("GET / devolve o painel em HTML, servido pelo próprio Spring")
    void painelEhServidoNaRaiz() {
        assertEquals(200, painel.status());
        assertTrue(painel.tipo().startsWith("text/html"), "tipo: " + painel.tipo());
        assertTrue(painel.corpo().contains("<title>"), "o corpo não parece uma página");
    }

    @Test
    @DisplayName("o painel não busca nada fora: sem CDN, sem fonte remota, sem framework")
    void painelNaoDependeDeNadaExterno() {
        List<String> externos = new ArrayList<>();
        for (String referencia : List.of("http://", "https://", "//cdn")) {
            if (painel.corpo().contains(referencia)) {
                externos.add(referencia);
            }
        }

        assertEquals(List.of(), externos,
                "um <script src> remoto transformaria um demo local numa coisa que só"
                        + " funciona com internet — e o painel é acessório do projeto,"
                        + " não um segundo projeto");
    }

    @Test
    @DisplayName("os oito passos do ciclo aparecem na ordem do fluxo")
    void osPassosEstaoNaOrdemDoFluxo() {
        List<String> foraDeOrdem = new ArrayList<>();
        int anterior = -1;
        for (String passo : FLUXO) {
            int posicao = painel.corpo().indexOf(passo);
            if (posicao < 0 || posicao < anterior) {
                foraDeOrdem.add(passo);
            }
            anterior = posicao;
        }

        assertEquals(List.of(), foraDeOrdem,
                "a coluna da esquerda é a única documentação do fluxo que alguém lê"
                        + " antes de clicar");
    }

    @Test
    @DisplayName("as seis rotas de ambiente estão no painel, e a seção delas é outra caixa")
    void asFalhasTemSecaoPropria() {
        List<String> faltando = new ArrayList<>();
        for (String rota : AMBIENTE) {
            if (!painel.corpo().contains(rota)) {
                faltando.add(rota);
            }
        }

        assertEquals(List.of(), faltando, "rotas de ambiente ausentes do painel");
        assertTrue(painel.corpo().contains("class=\"falhas\""),
                "quem abre o painel precisa distinguir num relance o que é operar o"
                        + " sistema do que é sabotá-lo");
    }

    @Test
    @DisplayName("o /estado devolve os seis blocos que o painel imprime")
    void oEstadoTrazOsSeisBlocos() {
        ClienteHttp.Resposta estado = servidor.cliente().get("/estado");

        assertEquals(200, estado.status(), () -> "resposta: " + estado.corpo());
        for (String bloco : List.of("tentativas", "ciclos", "outbox", "lancamentos",
                "parceiro", "artefatos", "mensagensNaFila", "chavesNaFila")) {
            // em() falha com a mensagem certa se o campo não existir: a tela
            // imprime o que o endpoint devolve, e um bloco renomeado no servidor
            // apagaria uma coluna do painel em silêncio.
            estado.em(bloco);
        }
        estado.em("parceiro").get("remessa");
        estado.em("parceiro").get("retorno");
    }

    @Test
    @DisplayName("o estado é relido a cada 2s, e o log não é")
    void oEstadoEhLidoPorPolling() {
        assertTrue(painel.corpo().contains("setInterval(atualizar, 2000)"),
                "sem polling, a tela mostra o mundo de quando foi aberta");
        assertTrue(painel.corpo().contains("/estado"),
                "o retrato das cinco fontes vem do único GET do projeto");
    }
}
