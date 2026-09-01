package com.platinumcoin.outbox.simulador;

import com.platinumcoin.outbox.infra.config.Ambiente;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Os objetos do ambiente, para o Spring.
 *
 * <p>Fica no próprio pacote {@code simulador}, e não em
 * {@code infra/config/Fiacao}, porque a seta só pode apontar num sentido: o
 * ambiente conhece o {@link Ambiente} — precisa saber com que servidor SFTP
 * falar —, e nada de {@code domain} ou de {@code infra} pode conhecer o
 * ambiente. Uma linha do simulador dentro da fiação do sistema inverteria isso.
 */
@Configuration
public class FiacaoDoParceiro {

    @Bean
    public DiscoDoParceiro discoDoParceiro(Ambiente ambiente) {
        return new DiscoDoParceiro(ambiente.sftp());
    }

    @Bean
    public ParceiroSimulado parceiroSimulado(DiscoDoParceiro disco) {
        return new ParceiroSimulado(disco);
    }
}
