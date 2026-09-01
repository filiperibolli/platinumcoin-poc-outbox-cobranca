package com.platinumcoin.outbox;

import com.platinumcoin.outbox.infra.config.Ambiente;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.util.Map;

/**
 * O servidor de verdade, numa porta livre, apontado para os containers da
 * suíte.
 *
 * <p>O {@link Ambiente} da suíte é registrado como bean primário antes do
 * refresh, e por isso a {@code Fiacao} monta os use cases contra o Postgres, o
 * LocalStack e o SFTP que já estão no ar — pela mesma razão que o
 * {@code CenarioPontaAPontaTest} entrega o {@code Ambiente} ao {@code Main}:
 * uma fiação de teste escrita à parte diverge da de produção sem que nada
 * acuse.
 *
 * <p>Sobe a aplicação inteira, inclusive o {@code simulador/}. É o que permite
 * a um teste pedir ao parceiro que escreva o retorno pelo mesmo canal que o
 * painel do step-12 vai usar, em vez de depositar o arquivo por uma conexão
 * própria.
 */
final class ServidorDeTeste implements AutoCloseable {

    private final ConfigurableApplicationContext contexto;
    private final ClienteHttp cliente;

    private ServidorDeTeste(ConfigurableApplicationContext contexto, ClienteHttp cliente) {
        this.contexto = contexto;
        this.cliente = cliente;
    }

    static ServidorDeTeste subir() {
        ApplicationContextInitializer<GenericApplicationContext> apontarParaOsContainers =
                contexto -> contexto.registerBean("ambienteDosContainers", Ambiente.class,
                        AmbienteDeTeste::ambiente, definicao -> definicao.setPrimary(true));

        SpringApplication aplicacao = new SpringApplication(AplicacaoHttp.class);
        // Porta zero: a suíte não pode brigar por 8080 com o que estiver rodando.
        aplicacao.setDefaultProperties(Map.of("server.port", "0"));
        aplicacao.addInitializers(apontarParaOsContainers);

        ConfigurableApplicationContext contexto = aplicacao.run();
        return new ServidorDeTeste(contexto, new ClienteHttp(
                ((ServletWebServerApplicationContext) contexto).getWebServer().getPort()));
    }

    ClienteHttp cliente() {
        return cliente;
    }

    @Override
    public void close() {
        contexto.close();
    }
}
