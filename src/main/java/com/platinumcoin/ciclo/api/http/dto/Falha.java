package com.platinumcoin.ciclo.api.http.dto;

/**
 * O efeito quando o efeito é uma recusa.
 *
 * <p>Existe porque a segunda montagem do mesmo recorte é um caso <b>previsto</b>
 * do desenho — o {@code UNIQUE (banco, data_ref)} recusando por construção — e
 * um corpo de erro genérico transformaria essa demonstração num acidente.
 */
public record Falha(String erro, String mensagem) {

    public static Falha de(Exception recusa) {
        return new Falha(recusa.getClass().getSimpleName(), recusa.getMessage());
    }
}
