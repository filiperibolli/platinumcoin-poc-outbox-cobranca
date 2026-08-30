package com.platinumcoin.outbox.domain.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * O arquivo transmitido ao banco parceiro: uma linha posicional por tentativa
 * do ciclo.
 *
 * <p>É <b>trabalho derivado</b>. O ciclo é o sistema de registro; a remessa é
 * uma projeção dele, e {@link #de} é uma função pura — mesmo ciclo, mesmas
 * tentativas, mesmos bytes. Isso é o que permite regerar e retransmitir sem
 * medo depois de qualquer falha: um artefato derivado que muda a cada geração
 * vira um segundo sistema de registro, e aí duas cópias do mesmo arquivo
 * passam a discordar sem que ninguém saiba qual vale.
 *
 * <p>O formato é posicional e trivial — três campos, sem cabeçalho nem
 * trailer. CNAB 240 de verdade é I/O e formato, não desenho: a única
 * propriedade do formato que este projeto usa como argumento é ser
 * determinístico — ver README, "fora de escopo".
 */
public record Remessa(String cicloId, String conteudo) {

    private static final int LARGURA_ID = 20;
    private static final int LARGURA_NUMERO = 3;

    /**
     * Fim de linha fixo, e não {@code System.lineSeparator()}: a remessa gerada
     * no Windows e a gerada no Linux precisam ser o mesmo arquivo, byte a byte.
     * <br>DECISÃO: a remessa é função pura do ciclo — ver step-02
     */
    private static final String FIM_DE_LINHA = "\n";

    public Remessa {
        if (cicloId == null || cicloId.isBlank()) {
            throw new IllegalArgumentException("remessa sem ciclo");
        }
        if (conteudo == null) {
            throw new IllegalArgumentException("remessa do ciclo " + cicloId + " sem conteúdo");
        }
    }

    /**
     * Projeta o ciclo e suas tentativas no arquivo. Não lê relógio, nem sorteio,
     * nem nada além dos argumentos.
     *
     * <p>A ordenação por id acontece aqui, e não só no {@code ORDER BY} da
     * consulta: assim a igualdade byte a byte é propriedade da projeção, não de
     * quem a alimenta.
     */
    public static Remessa de(CicloCobranca ciclo, List<TentativaDebito> tentativas) {
        if (ciclo == null) {
            throw new IllegalArgumentException("remessa sem ciclo");
        }
        tentativas.forEach(tentativa -> exigirDoCiclo(ciclo, tentativa));
        String conteudo = tentativas.stream()
                .sorted(Comparator.comparing(TentativaDebito::id))
                .map(Remessa::linha)
                .collect(Collectors.joining());
        return new Remessa(ciclo.id(), conteudo);
    }

    private static void exigirDoCiclo(CicloCobranca ciclo, TentativaDebito tentativa) {
        if (!ciclo.id().equals(tentativa.cicloId())) {
            throw new IllegalArgumentException("tentativa " + tentativa.id() + " não é do ciclo "
                    + ciclo.id() + " (ciclo=" + tentativa.cicloId() + ")");
        }
    }

    private static String linha(TentativaDebito tentativa) {
        return campo(tentativa.id())
                + campo(tentativa.faturaId())
                + String.format(Locale.ROOT, "%0" + LARGURA_NUMERO + "d", tentativa.numero())
                + FIM_DE_LINHA;
    }

    /** Campo alfanumérico à esquerda, preenchido com espaços. */
    private static String campo(String valor) {
        if (valor.length() > LARGURA_ID) {
            throw new IllegalArgumentException(
                    "campo não cabe em " + LARGURA_ID + " posições: " + valor);
        }
        // Locale.ROOT: em locales com dígitos próprios, formatar é uma decisão
        // sobre bytes — e a remessa precisa ser a mesma em qualquer máquina.
        return String.format(Locale.ROOT, "%-" + LARGURA_ID + "s", valor);
    }
}
