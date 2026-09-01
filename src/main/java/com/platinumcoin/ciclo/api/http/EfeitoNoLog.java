package com.platinumcoin.ciclo.api.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * O mesmo efeito que volta no corpo da resposta, também no log do processo.
 *
 * <p>Existe por causa do container: quem sobe o Compose vê a saída pelo
 * {@code docker compose logs -f app}, e não a resposta do {@code curl}. Como
 * cada resposta já <b>é</b> o efeito produzido — contagens e transições, não um
 * {@code 200} vazio —, logá-la dá a sequência do ciclo sem inventar uma segunda
 * narrativa que poderia divergir da primeira.
 *
 * <p>Um advice, e não uma linha em cada controller: o que se quer registrar é
 * "toda resposta", e repetir isso oito vezes criaria oito lugares para esquecer
 * no nono passo.
 *
 * <p><b>Fora {@code /estado}.</b> O painel o relê a cada 2s, e cada leitura
 * carrega as cinco fontes inteiras: logá-lo afogaria a sequência que este log
 * existe para mostrar. Ele não tem efeito nenhum — é o único {@code GET} do
 * servidor junto com o painel —, então nada se perde.
 */
@RestControllerAdvice
public class EfeitoNoLog implements ResponseBodyAdvice<Object> {

    private static final Logger log = LoggerFactory.getLogger(EfeitoNoLog.class);

    /** A leitura que o painel repete a cada 2s. */
    private static final String CONSULTA_DO_PAINEL = "/estado";

    private final ObjectMapper json;

    public EfeitoNoLog(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public boolean supports(MethodParameter retorno,
                            Class<? extends HttpMessageConverter<?>> conversor) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object corpo, MethodParameter retorno, MediaType tipo,
                                  Class<? extends HttpMessageConverter<?>> conversor,
                                  ServerHttpRequest pedido, ServerHttpResponse resposta) {
        if (!CONSULTA_DO_PAINEL.equals(pedido.getURI().getPath())) {
            log.info("[passo]  {} {} → {}", pedido.getMethod(), rota(pedido), corpoComoTexto(corpo));
        }
        return corpo;
    }

    /** Caminho com query: é o que se copia para repetir a chamada. */
    private static String rota(ServerHttpRequest pedido) {
        String query = pedido.getURI().getQuery();
        return query == null ? pedido.getURI().getPath() : pedido.getURI().getPath() + "?" + query;
    }

    private String corpoComoTexto(Object corpo) {
        try {
            return json.writeValueAsString(corpo);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            // Um efeito que não serializa é problema da resposta, não deste log:
            // deixar a exceção subir daqui trocaria um 200 por um 500 por causa
            // de uma linha de log.
            return String.valueOf(corpo);
        }
    }
}
