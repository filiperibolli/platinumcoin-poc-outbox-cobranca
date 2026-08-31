package com.platinumcoin.outbox.domain.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * O arquivo transmitido ao banco parceiro: header, uma linha por tentativa do
 * ciclo, trailer com a contagem.
 *
 * <p>É <b>trabalho derivado</b>. O ciclo é o sistema de registro; a remessa é
 * uma projeção dele, e {@link #de} é uma função pura — mesmo ciclo, mesmas
 * tentativas, mesmos bytes. Isso é o que permite regerar e retransmitir sem
 * medo depois de qualquer falha: um artefato derivado que muda a cada geração
 * vira um segundo sistema de registro, e aí duas cópias do mesmo arquivo
 * passam a discordar sem que ninguém saiba qual vale.
 *
 * <p>O layout mora aqui, no domínio, e não na infra que transmite: posição de
 * campo é regra de contrato com o parceiro. O que é infra é o {@code put}.
 *
 * <p><b>Não é CNAB 240.</b> A propriedade do formato que este projeto usa como
 * argumento é uma só — completude verificável pelo trailer, sem depender de o
 * transporte avisar que terminou.
 *
 * <pre>
 * tipo   posições                campos
 *   0    1 / 2–4 / 5–12 / 13–28  header:  banco, dataRef (yyyyMMdd), cicloId
 *   1    1 / 2–17 / 18–33 / 34–48 detalhe: idTentativa, faturaId, valor em centavos
 *   9    1 / 2–7                 trailer: quantidade de registros de detalhe
 * </pre>
 *
 * <p>O {@code idTentativa} em posição fixa é a <b>correlation key</b>: é o
 * campo que volta no retorno e liga a linha do parceiro à tentativa no banco.
 */
public record Remessa(String cicloId, ChaveArtefato chave, String conteudo) {

    private static final char TIPO_HEADER = '0';
    private static final char TIPO_DETALHE = '1';
    private static final char TIPO_TRAILER = '9';

    private static final int LARGURA_BANCO = 3;
    private static final int LARGURA_CICLO = 16;
    private static final int LARGURA_TENTATIVA = 16;
    private static final int LARGURA_FATURA = 16;
    private static final int LARGURA_VALOR = 15;
    private static final int LARGURA_CONTAGEM = 6;

    /** {@code yyyyMMdd} sem separador, como o resto do arquivo: só posição. */
    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * Fim de linha fixo, e não {@code System.lineSeparator()}: a remessa gerada
     * no Windows e a gerada no Linux precisam ser o mesmo arquivo, byte a byte.
     * <br>DECISÃO: a remessa é função pura do ciclo — ver step-02
     */
    private static final String FIM_DE_LINHA = "\n";

    /**
     * Centavos, inteiro, com zeros à esquerda. Decimal com vírgula faria o
     * mesmo ciclo gerar bytes diferentes conforme o locale da JVM.
     */
    private static final BigDecimal CENTAVOS = BigDecimal.valueOf(100);

    public Remessa {
        if (cicloId == null || cicloId.isBlank()) {
            throw new IllegalArgumentException("remessa sem ciclo");
        }
        if (chave == null) {
            throw new IllegalArgumentException("remessa do ciclo " + cicloId + " sem chave");
        }
        if (conteudo == null) {
            throw new IllegalArgumentException("remessa do ciclo " + cicloId + " sem conteúdo");
        }
    }

    /**
     * Projeta o ciclo, suas tentativas e as faturas correspondentes no arquivo.
     * Não lê relógio, nem sorteio, nem nada além dos argumentos.
     *
     * <p>A ordenação por id acontece aqui, e não só no {@code ORDER BY} da
     * consulta: assim a igualdade byte a byte é propriedade da projeção, não de
     * quem a alimenta.
     */
    public static Remessa de(CicloCobranca ciclo, List<TentativaDebito> tentativas,
                             List<Fatura> faturas) {
        if (ciclo == null) {
            throw new IllegalArgumentException("remessa sem ciclo");
        }
        Map<String, Fatura> porId = new HashMap<>();
        faturas.forEach(fatura -> porId.put(fatura.id(), fatura));

        List<TentativaDebito> emOrdem = tentativas.stream()
                .sorted(Comparator.comparing(TentativaDebito::id))
                .toList();
        emOrdem.forEach(tentativa -> exigirDoCiclo(ciclo, tentativa));

        String conteudo = header(ciclo)
                + emOrdem.stream().map(tentativa -> detalhe(tentativa, valorDe(porId, tentativa)))
                        .collect(Collectors.joining())
                + trailer(emOrdem.size());

        return new Remessa(ciclo.id(), ChaveArtefato.daRemessa(ciclo), conteudo);
    }

    /** O conteúdo como bytes — o que vai para o armazenamento, sem reencode. */
    public byte[] bytes() {
        return conteudo.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * O sha256 do conteúdo, em hexadecimal.
     *
     * <p>Gravado ao lado da chave no ciclo, é o que torna o determinismo
     * verificável fora de teste: regerar e comparar o hash responde "o artefato
     * mudou?" sem baixar nada. Não é controle de integridade do armazenamento —
     * é asserção sobre o nosso próprio código.
     */
    public String sha256() {
        return Sha256.de(bytes());
    }

    /** Quantas linhas de detalhe o trailer promete — a contagem que o parceiro confere. */
    public int quantidadeDeDetalhes() {
        return (int) conteudo.lines().filter(linha -> linha.charAt(0) == TIPO_DETALHE).count();
    }

    private static void exigirDoCiclo(CicloCobranca ciclo, TentativaDebito tentativa) {
        if (!ciclo.id().equals(tentativa.cicloId())) {
            throw new IllegalArgumentException("tentativa " + tentativa.id() + " não é do ciclo "
                    + ciclo.id() + " (ciclo=" + tentativa.cicloId() + ")");
        }
    }

    private static BigDecimal valorDe(Map<String, Fatura> porId, TentativaDebito tentativa) {
        Fatura fatura = porId.get(tentativa.faturaId());
        if (fatura == null) {
            throw new IllegalArgumentException("tentativa " + tentativa.id()
                    + " sem a fatura " + tentativa.faturaId() + " para projetar o valor");
        }
        return fatura.valor();
    }

    private static String header(CicloCobranca ciclo) {
        return TIPO_HEADER
                + alfanumerico(ciclo.banco(), LARGURA_BANCO)
                + DATA.format(ciclo.dataRef())
                + alfanumerico(ciclo.id(), LARGURA_CICLO)
                + FIM_DE_LINHA;
    }

    private static String detalhe(TentativaDebito tentativa, BigDecimal valor) {
        return TIPO_DETALHE
                + alfanumerico(tentativa.id(), LARGURA_TENTATIVA)
                + alfanumerico(tentativa.faturaId(), LARGURA_FATURA)
                + numerico(centavos(valor), LARGURA_VALOR)
                + FIM_DE_LINHA;
    }

    private static String trailer(int detalhes) {
        return TIPO_TRAILER + numerico(detalhes, LARGURA_CONTAGEM) + FIM_DE_LINHA;
    }

    private static long centavos(BigDecimal valor) {
        // longValueExact e não setScale com arredondamento: um valor que não
        // couber em centavos inteiros é erro de dados, não caso de arredondar.
        return valor.multiply(CENTAVOS).longValueExact();
    }

    /** Campo alfanumérico à esquerda, preenchido com espaços. */
    private static String alfanumerico(String valor, int largura) {
        if (valor.length() > largura) {
            throw new IllegalArgumentException(
                    "campo não cabe em " + largura + " posições: " + valor);
        }
        // Locale.ROOT: em locales com dígitos próprios, formatar é uma decisão
        // sobre bytes — e a remessa precisa ser a mesma em qualquer máquina.
        return String.format(Locale.ROOT, "%-" + largura + "s", valor);
    }

    /** Campo numérico à direita, preenchido com zeros. */
    private static String numerico(long valor, int largura) {
        String formatado = String.format(Locale.ROOT, "%0" + largura + "d", valor);
        if (formatado.length() > largura) {
            throw new IllegalArgumentException(
                    "número não cabe em " + largura + " posições: " + valor);
        }
        return formatado;
    }
}
