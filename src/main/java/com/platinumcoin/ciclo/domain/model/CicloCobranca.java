package com.platinumcoin.ciclo.domain.model;

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
 *
 * <p>{@code remessaChave} e {@code remessaSha256} são nulos até a geração
 * commitar, e sempre os dois juntos: são a mesma afirmação — "a remessa deste
 * ciclo é aquele objeto, e o conteúdo dele era este". Uma chave sem hash não
 * responde a pergunta que o hash existe para responder.
 */
public record CicloCobranca(String id, String banco, LocalDate dataRef, Status status,
                            ChaveArtefato remessaChave, String remessaSha256) {

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
        // Espelha a constraint ciclo_remessa_chave_com_hash do schema.
        if ((remessaChave == null) != (remessaSha256 == null)) {
            throw new IllegalArgumentException("ciclo " + id
                    + ": chave e hash da remessa existem juntos ou não existem"
                    + " (chave=" + remessaChave + ", sha256=" + remessaSha256 + ")");
        }
    }

    /** Recém-montado: tentativas atribuídas, remessa ainda não gerada. */
    public static CicloCobranca montado(String id, String banco, LocalDate dataRef) {
        return new CicloCobranca(id, banco, dataRef, Status.MONTADO, null, null);
    }

    /** Se a geração da remessa deste ciclo já commitou. */
    public boolean temRemessa() {
        return remessaChave != null;
    }
}
