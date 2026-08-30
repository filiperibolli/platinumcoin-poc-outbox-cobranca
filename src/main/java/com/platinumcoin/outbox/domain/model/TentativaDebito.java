package com.platinumcoin.outbox.domain.model;

/**
 * Uma tentativa de débito enviada ao banco parceiro. Uma fatura tem N — o banco
 * reapresenta o débito quando não há saldo — mas no máximo uma delas paga.
 *
 * <p>O status é a chave de idempotência do processamento do retorno: só uma
 * tentativa {@code ENVIADA} aceita um resultado, e o {@code UPDATE} condicional
 * do step-02 usa isso para ignorar retornos repetidos sem tabela auxiliar.
 */
public record TentativaDebito(String id, String faturaId, int numero, Status status) {

    public enum Status {
        ENVIADA,
        PAGA,
        NAO_PAGA,
        ERRO
    }

    public TentativaDebito {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tentativa sem id");
        }
        if (faturaId == null || faturaId.isBlank()) {
            throw new IllegalArgumentException("tentativa " + id + " sem fatura");
        }
        if (numero < 1) {
            throw new IllegalArgumentException("tentativa " + id + " com número inválido: " + numero);
        }
    }
}
