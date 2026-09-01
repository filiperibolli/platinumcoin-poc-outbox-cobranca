package com.platinumcoin.ciclo;

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

    Resposta post(String caminho) {
        return enviar(HttpRequest.newBuilder(URI.create(base + caminho))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    Resposta get(String caminho) {
        return enviar(HttpRequest.newBuilder(URI.create(base + caminho)).GET());
    }

    /** Uma resposta que não é JSON — o painel, servido como arquivo estático. */
    record Pagina(int status, String tipo, String corpo) {
    }

    /**
     * Busca uma página como um navegador a buscaria.
     *
     * <p>O {@code Accept: text/html} não é decoração: a página de boas-vindas
     * do Spring só responde a quem pede HTML, e um pedido com
     * {@code Accept: application/json} — o padrão dos outros métodos daqui —
     * recebe 404 do mesmo servidor que serve o painel a um navegador.
     */
    Pagina pagina(String caminho) {
        try {
            HttpResponse<String> resposta = http.send(
                    HttpRequest.newBuilder(URI.create(base + caminho))
                            .header("Accept", "text/html")
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Pagina(resposta.statusCode(),
                    resposta.headers().firstValue("Content-Type").orElse(""),
                    resposta.body());
        } catch (IOException e) {
            throw new IllegalStateException("falha ao buscar a página " + caminho, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("busca da página " + caminho + " interrompida", e);
        }
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
