package com.platinumcoin.ciclo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * O servidor: um processo, todos os endpoints.
 *
 * <p>É o único motivo de o Spring Boot existir neste projeto — o projeto passa
 * a expor HTTP. Nada mais do desenho mudou por causa dele: os use cases são os
 * mesmos, as portas são as mesmas, e o domínio continua sem saber que existe
 * framework — {@code FundacaoTest.dominioIsolado} falha se isso mudar.
 *
 * <p>O {@link Main} de console continua existindo e continua rodando por
 * {@code mvn compile exec:java}. Os dois entram pelo mesmo lugar: o
 * {@link com.platinumcoin.ciclo.infra.config.Ambiente}, que continua sendo o
 * único a ler configuração.
 *
 * <p><b>Não há {@code @Scheduled} aqui.</b> Cada {@code POST} é uma execução do
 * job que, em produção, o EventBridge dispararia por horário; aqui quem dispara
 * é quem quer olhar para o passo — ver {@code docs/steps/step-10.md}.
 */
@SpringBootApplication
public class AplicacaoHttp {

    public static void main(String[] args) {
        SpringApplication.run(AplicacaoHttp.class, args);
    }
}
