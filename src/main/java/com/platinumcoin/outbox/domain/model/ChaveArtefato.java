package com.platinumcoin.outbox.domain.model;

import java.time.format.DateTimeFormatter;

/**
 * O endereço de um artefato no armazenamento de objetos, derivado do ciclo que
 * o produziu.
 *
 * <p>Existe como tipo, e não como {@code String} montada em cada chamador,
 * porque a chave é o que torna o {@code put} seguro de repetir: dois lugares
 * montando o mesmo endereço com um separador diferente transformariam a
 * segunda gravação num objeto novo, e o bucket num log de tentativas.
 *
 * <p>Nada de {@code now()} nem de sorteio aqui dentro: mesmo ciclo, mesma
 * chave, hoje e depois do incidente.
 * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
 */
public record ChaveArtefato(String valor) {

    /**
     * {@code yyyyMMdd}, o mesmo formato de data que vai no header da remessa —
     * o projeto escreve data de uma maneira só, no arquivo e no endereço dele.
     */
    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    public ChaveArtefato {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("chave de artefato vazia");
        }
    }

    /** {@code remessa/{banco}/{dataRef}/{cicloId}.rem} */
    public static ChaveArtefato daRemessa(CicloCobranca ciclo) {
        if (ciclo == null) {
            throw new IllegalArgumentException("chave de remessa sem ciclo");
        }
        return new ChaveArtefato("remessa/%s/%s/%s.rem".formatted(
                ciclo.banco(), DATA.format(ciclo.dataRef()), ciclo.id()));
    }

    @Override
    public String toString() {
        return valor;
    }
}
