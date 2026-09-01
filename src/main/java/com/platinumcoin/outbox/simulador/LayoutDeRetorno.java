package com.platinumcoin.outbox.simulador;

import com.platinumcoin.outbox.api.LinhaRetorno;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Como o parceiro escreve o arquivo de retorno: header, uma linha por
 * afirmação, trailer com a contagem.
 *
 * <p>É o espelho de {@code api.ArquivoRetorno}, que lê. Espelho escrito à mão,
 * sem constante compartilhada: são dois lados de um contrato, e um contrato que
 * as duas pontas leem do mesmo lugar não é um contrato — é uma variável.
 *
 * <p>{@link #arquivoDeclarando} permite escrever um trailer que <b>não</b> bate
 * com o conteúdo. Não é um erro do simulador: é o arquivo truncado, o caso que
 * o step-09 existe para descartar inteiro, e ele não teria como ser produzido
 * por um formatador que sempre conta certo.
 *
 * <pre>
 * tipo   posições                 campos
 *   0    1 / 2–4 / 5–12 / 13–28   header:  banco, dataRef (yyyyMMdd), cicloId
 *   1    1 / 2–17 / 18–25 / 26–45 detalhe: idTentativa, resultado, motivo
 *   9    1 / 2–7                  trailer: quantidade de registros de detalhe
 * </pre>
 */
final class LayoutDeRetorno {

    private LayoutDeRetorno() {
    }

    /** O nome do arquivo do ciclo, quando o parceiro responde num arquivo só. */
    static String nome(RemessaLida remessa) {
        return "%s-%s-%s.ret".formatted(
                remessa.banco(), remessa.dataCompacta(), remessa.cicloId());
    }

    /** O nome de uma das partes, quando o parceiro decide particionar. */
    static String nome(RemessaLida remessa, int parte) {
        return "%s-%s-%s-%d.ret".formatted(
                remessa.banco(), remessa.dataCompacta(), remessa.cicloId(), parte);
    }

    /** Um arquivo coerente: o trailer conta o que está lá. */
    static byte[] arquivo(RemessaLida remessa, List<LinhaRetorno> linhas) {
        return arquivoDeclarando(remessa, linhas, linhas.size());
    }

    /**
     * Um arquivo cujo trailer promete {@code declarado} detalhes,
     * independentemente de quantos existem.
     *
     * <p>É como um arquivo truncado se apresenta: o parceiro escreveu o
     * cabeçalho, algumas linhas e o trailer que ele <b>pretendia</b> cumprir.
     * O trailer é a única coisa que denuncia.
     */
    static byte[] arquivoDeclarando(RemessaLida remessa, List<LinhaRetorno> linhas, int declarado) {
        StringBuilder conteudo = new StringBuilder();
        conteudo.append('0')
                .append(alfanumerico(remessa.banco(), 3))
                .append(remessa.dataCompacta())
                .append(alfanumerico(remessa.cicloId(), 16))
                .append('\n');
        for (LinhaRetorno linha : linhas) {
            conteudo.append('1')
                    .append(alfanumerico(linha.tentativaId(), 16))
                    .append(alfanumerico(linha.resultado().name(), 8))
                    .append(alfanumerico(linha.motivo() == null ? "" : linha.motivo().name(), 20))
                    .append('\n');
        }
        conteudo.append('9').append(String.format(Locale.ROOT, "%06d", declarado)).append('\n');
        return conteudo.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Quantos registros de detalhe um arquivo já escrito tem. */
    static int detalhes(byte[] conteudo) {
        return (int) new String(conteudo, StandardCharsets.UTF_8).lines()
                .filter(registro -> !registro.isEmpty() && registro.charAt(0) == '1')
                .count();
    }

    private static String alfanumerico(String valor, int largura) {
        // Locale.ROOT pelo mesmo motivo da remessa: formatar é uma decisão
        // sobre bytes, e o arquivo precisa ser o mesmo em qualquer máquina.
        return String.format(Locale.ROOT, "%-" + largura + "s", valor);
    }
}
