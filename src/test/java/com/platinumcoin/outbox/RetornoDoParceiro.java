package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.CicloCobranca;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * O parceiro escrevendo o arquivo de retorno.
 *
 * <p>Fica do lado do teste, e não em {@code main}, porque <b>este projeto não
 * escreve arquivos de retorno</b> — ele os lê. Quem escreve é o outro lado do
 * fio, e no step-11 vira o {@code simulador/}. O layout está aqui montado à mão,
 * de propósito: se {@code api.ArquivoRetorno} um dia divergir do contrato, é
 * este arquivo que deixa de bater com ele — que é exatamente o que aconteceria
 * com o parceiro de verdade.
 *
 * <pre>
 * tipo   posições                 campos
 *   0    1 / 2–4 / 5–12 / 13–28   header:  banco, dataRef (yyyyMMdd), cicloId
 *   1    1 / 2–17 / 18–25 / 26–45 detalhe: idTentativa, resultado, motivo
 *   9    1 / 2–7                  trailer: quantidade de registros de detalhe
 * </pre>
 */
final class RetornoDoParceiro {

    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    private RetornoDoParceiro() {
    }

    /** O nome que o parceiro dá ao arquivo do ciclo. */
    static String nome(CicloCobranca ciclo) {
        return "%s-%s-%s.ret".formatted(ciclo.banco(), DATA.format(ciclo.dataRef()), ciclo.id());
    }

    /** O nome de uma das partes, quando o parceiro decide particionar. */
    static String nome(CicloCobranca ciclo, String parte) {
        return "%s-%s-%s-%s.ret".formatted(
                ciclo.banco(), DATA.format(ciclo.dataRef()), ciclo.id(), parte);
    }

    /** Um arquivo coerente: o trailer conta o que está lá. */
    static byte[] arquivo(CicloCobranca ciclo, List<LinhaRetorno> linhas) {
        return arquivoDeclarando(ciclo, linhas, linhas.size());
    }

    /**
     * Um arquivo cujo trailer promete {@code declarado} detalhes,
     * independentemente de quantos existem.
     *
     * <p>É como um arquivo truncado se apresenta: o parceiro escreveu o
     * cabeçalho, algumas linhas e o trailer que ele <b>pretendia</b> cumprir, e
     * morreu no meio das linhas. O trailer é a única coisa que denuncia.
     */
    static byte[] arquivoDeclarando(CicloCobranca ciclo, List<LinhaRetorno> linhas, int declarado) {
        return (cabecalhoEDetalhes(ciclo, linhas)
                + '9' + String.format(Locale.ROOT, "%06d", declarado) + '\n')
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * O arquivo como ele existe <b>enquanto</b> está sendo escrito: sem trailer
     * nenhum ainda, porque o parceiro escreve direto no caminho final.
     */
    static byte[] arquivoSemTrailer(CicloCobranca ciclo, List<LinhaRetorno> linhas) {
        return cabecalhoEDetalhes(ciclo, linhas).getBytes(StandardCharsets.UTF_8);
    }

    private static String cabecalhoEDetalhes(CicloCobranca ciclo, List<LinhaRetorno> linhas) {
        StringBuilder conteudo = new StringBuilder();
        conteudo.append('0')
                .append(alfanumerico(ciclo.banco(), 3))
                .append(DATA.format(ciclo.dataRef()))
                .append(alfanumerico(ciclo.id(), 16))
                .append('\n');
        for (LinhaRetorno linha : linhas) {
            conteudo.append('1')
                    .append(alfanumerico(linha.tentativaId(), 16))
                    .append(alfanumerico(linha.resultado().name(), 8))
                    .append(alfanumerico(linha.motivo() == null ? "" : linha.motivo().name(), 20))
                    .append('\n');
        }
        return conteudo.toString();
    }

    private static String alfanumerico(String valor, int largura) {
        return String.format(Locale.ROOT, "%-" + largura + "s", valor);
    }
}
