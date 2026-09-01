package com.platinumcoin.ciclo.domain.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * O sha256 de um conteúdo, em hexadecimal.
 *
 * <p>Existe num lugar só porque o projeto guarda a mesma afirmação — "estes eram
 * os bytes" — em duas tabelas: {@code ciclo_cobranca.remessa_sha256}, sobre o
 * arquivo que saiu, e {@code arquivo_retorno.sha256}, sobre o que voltou. Duas
 * implementações do mesmo hash seriam duas chances de a comparação entre elas
 * deixar de fazer sentido.
 *
 * <p>Não é controle de integridade de transporte, nem em um caso nem no outro.
 * Na remessa é asserção sobre o nosso próprio determinismo; no retorno é atalho
 * para não reprocessar bytes idênticos.
 */
public final class Sha256 {

    private Sha256() {
    }

    public static String de(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE sem SHA-256", e);
        }
    }
}
