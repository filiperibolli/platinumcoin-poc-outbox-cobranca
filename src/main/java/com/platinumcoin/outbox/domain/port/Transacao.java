package com.platinumcoin.outbox.domain.port;

/**
 * A fronteira transacional do banco, vista pelo domínio.
 *
 * <p>Não é uma porta de negócio — é o tipo que permite ao use case dizer
 * "estas duas escritas são uma só" sem saber que existe JDBC. É o que torna
 * possível a regra central do projeto: {@code UPDATE fatura} e
 * {@code INSERT outbox} no mesmo {@code COMMIT}.
 *
 * <p>O {@code close()} sem {@code commit()} desfaz tudo. Um use case que
 * estoura no meio não deixa nada meio-escrito — é o que
 * {@code DualWriteEvitadoTest} exercita.
 *
 * <p>Nenhum sistema externo pode ser chamado enquanto uma destas estiver
 * aberta.
 * <br>DECISÃO: outbox na mesma transação, publicação fora — ver ADR-0001
 */
public interface Transacao extends AutoCloseable {

    /** Confirma tudo o que foi escrito nesta transação. */
    void commit();

    /** Encerra a transação; desfaz o que não foi confirmado. Não lança. */
    @Override
    void close();

    /** Quem sabe abrir uma transação. Implementado pela infra. */
    interface Fabrica {
        Transacao abrir();
    }
}
