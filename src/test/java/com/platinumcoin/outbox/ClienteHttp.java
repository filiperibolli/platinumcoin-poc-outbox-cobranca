package com.platinumcoin.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * O teste falando com o servidor por HTTP de verdade: soquete, porta e JSON.
 *
 * <p>Nenhum atalho de framework aqui, e é de propósito. Um teste que chamasse o
 * método do controller direto provaria que o método existe; este prova que a
 * rota está publicada, que o parâmetro chega convertido e que a resposta
 * serializa — que é o que o {@code curl} do README e o painel do step-12 vão
 * encontrar.
 */
final class ClienteHttp {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();
    private final String base;

    ClienteHttp(int porta) {
        this.base = "http://localhost:" + porta;
    }

    /** Uma resposta: o código e o corpo já parseado. */
    record Resposta(int status, JsonNode corpo) {

        JsonNode em(String campo) {
            JsonNode valor = corpo.get(campo);
            if (valor == null) {
                throw new AssertionError("resposta sem o campo '" + campo + "': " + corpo);
            }
            return valor;
        }

        int inteiro(String campo) {
            return em(campo).asInt();
        }

        String texto(String campo) {
            return em(campo).asText();
        }
    }

    /**
     * Uma página, como o navegador a receberia: sem {@code Accept} de JSON e sem
     * parsear o corpo.
     *
     * <p>Existe porque o painel do step-12 não é JSON — e um cliente que
     * parseasse tudo como JSON não teria como afirmar que o HTML foi servido.
     */
    Pagina pagina(String caminho) {
        try {
            HttpResponse<String> resposta = http.send(
                    HttpRequest.newBuilder(URI.create(base + caminho)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Pagina(resposta.statusCode(),
                    resposta.headers().firstValue("Content-Type").orElse(""),
                    resposta.body());
        } catch (IOException e) {
            throw new IllegalStateException("falha ao chamar o servidor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chamada ao servidor interrompida", e);
        }
    }

    /** O que o navegador recebeu: o código, o tipo do conteúdo e o corpo cru. */
    record Pagina(int status, String tipo, String corpo) {
    }

    Resposta post(String caminho) {
        return enviar(HttpRequest.newBuilder(URI.create(base + caminho))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    Resposta get(String caminho) {
        return enviar(HttpRequest.newBuilder(URI.create(base + caminho)).GET());
    }

    private Resposta enviar(HttpRequest.Builder pedido) {
        try {
            HttpResponse<String> resposta = http.send(
                    pedido.header("Accept", "application/json").build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Resposta(resposta.statusCode(), json.readTree(resposta.body()));
        } catch (IOException e) {
            throw new IllegalStateException("falha ao chamar o servidor", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("chamada ao servidor interrompida", e);
        }
    }
}
