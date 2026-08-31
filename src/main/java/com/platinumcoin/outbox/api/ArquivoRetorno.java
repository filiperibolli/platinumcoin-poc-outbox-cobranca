package com.platinumcoin.outbox.api;

import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.LeitorDeRetorno;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * O arquivo que o parceiro devolveu, lido: o recorte declarado no cabeçalho, as
 * linhas de detalhe e a contagem que o trailer promete.
 *
 * <p>Fica em {@code api/} pelo mesmo motivo que {@link LinhaRetorno}: é
 * adaptador de entrada, e a seta é {@code api → domain}. O domínio não conhece
 * posição de campo nem largura de coluna do parceiro — ele conhece
 * {@link LeitorDeRetorno}, e {@code ArquivoRetorno::de} é quem a preenche.
 *
 * <p>O layout espelha o da remessa — mesma ideia, campos de largura fixa e um
 * trailer com a contagem:
 *
 * <pre>
 * tipo   posições                 campos
 *   0    1 / 2–4 / 5–12 / 13–28   header:  banco, dataRef (yyyyMMdd), cicloId
 *   1    1 / 2–17 / 18–25 / 26–45 detalhe: idTentativa, resultado, motivo
 *   9    1 / 2–7                  trailer: quantidade de registros de detalhe
 * </pre>
 *
 * <p>O {@code idTentativa} é o campo que saiu na remessa e voltou aqui: é a
 * correlation key que liga a afirmação do parceiro à tentativa no banco.
 *
 * <p><b>Contar não é validar.</b> Um arquivo cuja contagem não bate é
 * construído mesmo assim, com {@link #completo()} falso — quem decide o que
 * fazer com ele é o coletor, e a decisão é descartá-lo inteiro. Estourar aqui
 * impediria o passo que existe antes da validação: arquivar no S3 justamente o
 * arquivo que não fechou.
 */
public record ArquivoRetorno(String nome,
                             String banco,
                             LocalDate dataRef,
                             String cicloId,
                             List<LinhaRetorno> linhas,
                             int declaradoNoTrailer) implements LeitorDeRetorno.Retorno {

    private static final char TIPO_HEADER = '0';
    private static final char TIPO_DETALHE = '1';
    private static final char TIPO_TRAILER = '9';

    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    /** As mesmas larguras do header da remessa: banco, data e ciclo. */
    private static final int FIM_BANCO = 4;
    private static final int FIM_DATA = 12;
    private static final int FIM_CICLO = 28;

    /** Detalhe: tentativa, resultado, motivo. */
    private static final int FIM_TENTATIVA = 17;
    private static final int FIM_RESULTADO = 25;
    private static final int FIM_MOTIVO = 45;

    private static final int FIM_CONTAGEM = 7;

    public ArquivoRetorno {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("arquivo de retorno sem nome");
        }
        linhas = List.copyOf(linhas);
    }

    /**
     * Lê o arquivo inteiro.
     *
     * <p>É estrito quanto à estrutura — um registro de tipo desconhecido, um
     * cabeçalho ausente ou um trailer ilegível são erro, porque nada pode ser
     * afirmado sobre um arquivo que não é do formato combinado. E é tolerante
     * quanto à <b>contagem</b>, que é a única divergência que o step-09 sabe
     * tratar: o arquivo existe, dá para arquivá-lo, e é o coletor que decide
     * descartá-lo.
     */
    public static ArquivoRetorno de(String nome, byte[] conteudo) {
        List<String> registros = new String(conteudo, StandardCharsets.UTF_8).lines().toList();
        if (registros.isEmpty() || registros.get(0).charAt(0) != TIPO_HEADER) {
            throw new IllegalArgumentException("arquivo de retorno sem header: " + nome);
        }

        String header = registros.get(0);
        String banco = campo(header, 1, FIM_BANCO, nome);
        LocalDate dataRef = LocalDate.parse(campo(header, FIM_BANCO, FIM_DATA, nome), DATA);
        String cicloId = campo(header, FIM_DATA, FIM_CICLO, nome);

        List<LinhaRetorno> detalhes = new ArrayList<>();
        Integer declarado = null;
        for (String registro : registros.subList(1, registros.size())) {
            switch (registro.charAt(0)) {
                case TIPO_DETALHE -> detalhes.add(detalhe(registro, nome));
                case TIPO_TRAILER -> declarado =
                        Integer.parseInt(campo(registro, 1, FIM_CONTAGEM, nome));
                default -> throw new IllegalArgumentException(
                        "arquivo de retorno " + nome + " com registro de tipo desconhecido: "
                                + registro.charAt(0));
            }
        }
        if (declarado == null) {
            // Sem trailer não há o que conferir, e um arquivo sem nada a
            // conferir é exatamente o caso que o step-09 recusa: seria
            // indistinguível de um retorno legítimo menor.
            throw new IllegalArgumentException("arquivo de retorno sem trailer: " + nome);
        }
        return new ArquivoRetorno(nome, banco, dataRef, cicloId, detalhes, declarado);
    }

    @Override
    public boolean completo() {
        return linhas.size() == declaradoNoTrailer;
    }

    @Override
    public int quantidadeDeLinhas() {
        return linhas.size();
    }

    /**
     * Cada linha entregue ao domínio, na ordem do arquivo, por
     * {@link LinhaRetorno#aplicarCom} — o mesmo adaptador que o step-03
     * desenhou, e que aqui só ganhou quem o alimente.
     */
    @Override
    public int aplicarCom(AplicarRetornoUseCase aplicar) {
        int mudaram = 0;
        for (LinhaRetorno linha : linhas) {
            if (linha.aplicarCom(aplicar) != AplicarRetornoUseCase.Resultado.IGNORADO) {
                mudaram++;
            }
        }
        return mudaram;
    }

    private static LinhaRetorno detalhe(String registro, String nome) {
        String tentativaId = campo(registro, 1, FIM_TENTATIVA, nome);
        TentativaDebito.Status resultado = TentativaDebito.Status.valueOf(
                campo(registro, FIM_TENTATIVA, FIM_RESULTADO, nome));
        String motivo = campo(registro, FIM_RESULTADO, FIM_MOTIVO, nome);

        // O construtor de LinhaRetorno cobra a coerência entre resultado e
        // motivo — a mesma regra que TentativaDebito cobra de quem grava. O
        // parser não a repete: repetida, seria uma segunda frase livre para
        // divergir da primeira.
        return new LinhaRetorno(tentativaId, resultado,
                motivo.isEmpty() ? null : TentativaDebito.MotivoNaoPago.valueOf(motivo));
    }

    /**
     * O campo entre duas posições, sem os espaços de preenchimento.
     *
     * <p>Um registro curto demais para conter o campo é erro de formato, e não
     * um campo vazio: truncar em silêncio faria uma linha cortada ao meio virar
     * uma afirmação sobre a tentativa errada.
     */
    private static String campo(String registro, int inicio, int fim, String nome) {
        if (registro.length() < fim) {
            throw new IllegalArgumentException("arquivo de retorno " + nome
                    + " com registro curto demais (" + registro.length()
                    + " posições, esperado ao menos " + fim + "): " + registro);
        }
        return registro.substring(inicio, fim).trim();
    }
}
