package com.platinumcoin.outbox.domain.exception;

/**
 * Falha de infraestrutura ao enviar o lançamento ao destino externo.
 *
 * <p>Existe pelo mesmo motivo que {@link FalhaDePersistencia} — traduzir a
 * exceção da tecnologia antes que ela atravesse a porta — e separada dela por
 * um motivo do domínio: as duas falhas têm consequências diferentes para o
 * relay. Uma falha de envio deixa a linha {@code PENDENTE} e não se sabe se a
 * mensagem chegou; uma falha do banco <b>depois</b> do envio garante que a
 * próxima passada vai republicar. Chamar as duas de "falha de persistência"
 * apagaria justamente a distinção que o ADR-0002 usa como argumento.
 */
public class FalhaDePublicacao extends RuntimeException {

    public FalhaDePublicacao(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
