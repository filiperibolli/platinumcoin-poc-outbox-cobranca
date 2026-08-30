package com.platinumcoin.outbox.domain.model;

import java.time.LocalDate;

/**
 * O conjunto de tentativas transmitido a um banco parceiro numa data.
 *
 * <p>É a <b>única escrita que importa</b>. Remessa, retorno, fechamento e
 * publicação são trabalho derivado: dado o ciclo, todos podem ser refeitos e
 * chegam ao mesmo lugar. Errar na montagem é o único erro que não se conserta
 * reprocessando.
 *
 * <p>A identidade de negócio é {@code (banco, dataRef)}, e o banco a garante com
 * {@code UNIQUE} — é o que faz a reexecução da montagem ser segura por
 * construção, e não por alguém ter conferido antes se o ciclo já existia.
 */
public record CicloCobranca(String id, String banco, LocalDate dataRef, Status status) {

    public enum Status {
        /** Montado; tentativas atribuídas, remessa ainda não transmitida. */
        MONTADO,
        /** Remessa transmitida ao parceiro; aguardando retornos. */
        ENVIADO,
        /** Encerrado; quem não respondeu virou SEM_RETORNO. */
        FECHADO
    }

    public CicloCobranca {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("ciclo sem id");
        }
        if (banco == null || banco.isBlank()) {
            throw new IllegalArgumentException("ciclo " + id + " sem banco");
        }
        if (dataRef == null) {
            throw new IllegalArgumentException("ciclo " + id + " sem data de referência");
        }
    }

    public static CicloCobranca montado(String id, String banco, LocalDate dataRef) {
        return new CicloCobranca(id, banco, dataRef, Status.MONTADO);
    }
}
