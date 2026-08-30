package com.platinumcoin.outbox.domain.exception;

/**
 * Falha de infraestrutura ao ler ou escrever no banco.
 *
 * <p>Existe para que o domínio não precise conhecer {@code SQLException} — a
 * infra traduz. Não é usada para regra de negócio: retorno duplicado e fatura
 * já paga <b>não</b> são exceções, são zero linhas afetadas.
 */
public class FalhaDePersistencia extends RuntimeException {

    public FalhaDePersistencia(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }

    public FalhaDePersistencia(String mensagem) {
        super(mensagem);
    }
}
