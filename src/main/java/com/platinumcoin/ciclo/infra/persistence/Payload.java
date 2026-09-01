package com.platinumcoin.ciclo.infra.persistence;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.model.LancamentoContabil;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O corpo do lançamento, como o consumidor o lê.
 *
 * <p>JSON escrito à mão, pela mesma razão do SQL: são dois campos, e uma
 * biblioteca de serialização seria dependência sem ganho.
 *
 * <p>Tem os dois lados juntos e é usado pelos dois lados do relay — quem grava a
 * linha do outbox ({@link RepositorioOutboxPostgres}) e quem a envia
 * ({@link PublicadorLancamentoSqs}). É por isso que o corpo da mensagem publicada
 * é byte a byte o que está na coluna {@code payload}: ler e reescrever devolve o
 * mesmo texto. Duas cópias deste formato — uma para o banco, outra para a fila —
 * seriam duas versões livres para divergirem sem que nada acusasse.
 */
final class Payload {

    private static final Pattern CAMPO = Pattern.compile("\"(\\w+)\":\"([^\"]*)\"");

    private Payload() {
    }

    static String escrever(LancamentoContabil lancamento) {
        // Os dois campos são controlados pelo domínio — id de fatura e
        // decimal. Um id com aspas escaparia do formato calado, então o
        // caso é recusado aqui, e não descoberto na leitura.
        if (lancamento.faturaId().matches(".*[\"\\\\].*")) {
            throw new FalhaDePersistencia(
                    "id de fatura inválido para o payload: " + lancamento.faturaId());
        }
        return "{\"faturaId\":\"" + lancamento.faturaId()
                + "\",\"valor\":\"" + lancamento.valor().toPlainString() + "\"}";
    }

    static LancamentoContabil ler(String payload) {
        String faturaId = null;
        BigDecimal valor = null;
        Matcher campo = CAMPO.matcher(payload);
        while (campo.find()) {
            switch (campo.group(1)) {
                case "faturaId" -> faturaId = campo.group(2);
                case "valor" -> valor = new BigDecimal(campo.group(2));
                default -> { }
            }
        }
        if (faturaId == null || valor == null) {
            throw new FalhaDePersistencia("payload de outbox ilegível: " + payload);
        }
        return new LancamentoContabil(faturaId, valor);
    }
}
