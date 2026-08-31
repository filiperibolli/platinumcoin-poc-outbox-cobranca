package com.platinumcoin.outbox.simulador;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * O que o parceiro entende do arquivo de remessa que recebeu: de que recorte
 * ele é e sobre que tentativas ele terá de falar.
 *
 * <p>É a razão de o simulador existir do jeito que existe. O retorno é montado
 * <b>a partir daqui</b> — do artefato que atravessou o fio —, e não de uma
 * consulta ao Postgres. Consultar o nosso banco seria mais simples e destruiria
 * a demonstração: o retorno passaria a ser função do nosso estado, e o dia em
 * que a remessa saísse errada o parceiro responderia certo assim mesmo.
 * <br>DECISÃO: o simulador só enxerga arquivos — ver docs/steps/step-11.md
 *
 * <p>O layout é o de {@code domain.model.Remessa}, lido à mão e de novo, sem
 * compartilhar constante nenhuma com quem escreve. Se um dos dois lados mudar
 * uma posição, o outro deixa de entender o arquivo — que é exatamente o que
 * aconteceria com o parceiro de verdade.
 *
 * <pre>
 * tipo   posições                 campos
 *   0    1 / 2–4 / 5–12 / 13–28   header:  banco, dataRef (yyyyMMdd), cicloId
 *   1    1 / 2–17 / 18–33 / 34–48 detalhe: idTentativa, faturaId, valor em centavos
 *   9    1 / 2–7                  trailer: quantidade de registros de detalhe
 * </pre>
 */
public record RemessaLida(String nome, String banco, LocalDate dataRef, String cicloId,
                          List<String> tentativas) {

    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    private static final int FIM_BANCO = 4;
    private static final int FIM_DATA = 12;
    private static final int FIM_CICLO = 28;
    private static final int FIM_TENTATIVA = 17;
    private static final int FIM_CONTAGEM = 7;

    public RemessaLida {
        tentativas = List.copyOf(tentativas);
    }

    /**
     * Lê o arquivo inteiro, e recusa o que não fecha.
     *
     * <p>O parceiro confere o trailer pela mesma razão que nós conferimos o
     * dele: um arquivo cortado no meio é indistinguível de uma remessa legítima
     * menor, e processá-lo faria o parceiro debitar um recorte que ninguém
     * pediu.
     */
    public static RemessaLida de(String nome, byte[] conteudo) {
        List<String> registros = new String(conteudo, StandardCharsets.UTF_8).lines().toList();
        if (registros.isEmpty() || registros.get(0).charAt(0) != '0') {
            throw new IllegalArgumentException("remessa sem header: " + nome);
        }

        String header = registros.get(0);
        List<String> tentativas = new ArrayList<>();
        Integer declarado = null;
        for (String registro : registros.subList(1, registros.size())) {
            switch (registro.charAt(0)) {
                case '1' -> tentativas.add(campo(registro, 1, FIM_TENTATIVA));
                case '9' -> declarado = Integer.parseInt(campo(registro, 1, FIM_CONTAGEM));
                default -> throw new IllegalArgumentException(
                        "remessa " + nome + " com registro desconhecido: " + registro.charAt(0));
            }
        }
        if (declarado == null || declarado != tentativas.size()) {
            throw new IllegalArgumentException("remessa " + nome + " não fecha: trailer diz "
                    + declarado + " e há " + tentativas.size() + " detalhes");
        }

        return new RemessaLida(nome,
                campo(header, 1, FIM_BANCO),
                LocalDate.parse(campo(header, FIM_BANCO, FIM_DATA), DATA),
                campo(header, FIM_DATA, FIM_CICLO),
                tentativas);
    }

    /** A data como o nome dos arquivos do parceiro a escreve. */
    public String dataCompacta() {
        return DATA.format(dataRef);
    }

    private static String campo(String registro, int inicio, int fim) {
        if (registro.length() < fim) {
            throw new IllegalArgumentException("registro curto demais para o layout: " + registro);
        }
        return registro.substring(inicio, fim).trim();
    }
}
