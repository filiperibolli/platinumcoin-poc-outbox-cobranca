package com.platinumcoin.ciclo.api.http;

import java.time.LocalDate;

/**
 * O recorte que uma chamada opera: um banco numa data.
 *
 * <p>Existe para que os padrões da demonstração morem num lugar só. Sem ele,
 * "banco 341" e "hoje" apareceriam repetidos em dois controllers, e o dia em
 * que divergissem produziria faturas num recorte e um ciclo noutro — o erro
 * mais difícil de enxergar que este projeto pode cometer, porque o ciclo
 * montaria vazio e nada acusaria.
 */
public record Recorte(String banco, LocalDate dataRef) {

    /** O parceiro da demonstração — o mesmo do {@code Main} e do Compose. */
    public static final String BANCO_PADRAO = "341";

    public static Recorte de(String banco, LocalDate dataRef) {
        return new Recorte(
                banco == null || banco.isBlank() ? BANCO_PADRAO : banco,
                dataRef == null ? LocalDate.now() : dataRef);
    }
}
