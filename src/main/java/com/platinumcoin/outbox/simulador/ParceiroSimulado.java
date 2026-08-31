package com.platinumcoin.outbox.simulador;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.TentativaDebito;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O banco parceiro, do outro lado do fio: lê a remessa que chegou em
 * {@code /remessa} e escreve o retorno em {@code /retorno}.
 *
 * <p><b>Não é o sistema — é o ambiente.</b> Vive fora de {@code domain} e de
 * {@code infra}, nenhum use case o chama, e ele não conhece {@code outbox},
 * transação nem estado de tentativa que não venha do arquivo que recebeu. O que
 * ele sabe do nosso mundo é o que atravessou o SFTP.
 *
 * <p>Nada aqui é sorteado. O desfecho de cada tentativa vem da distribuição
 * pedida, aplicada na ordem do arquivo — mesma remessa e mesmos parâmetros,
 * mesmos bytes. Um parceiro aleatório tornaria a demonstração impossível de
 * repetir, e uma demonstração que muda a cada execução não prova nada.
 *
 * <p>As quatro operações são as quatro formas de o parceiro se comportar que o
 * projeto precisa mostrar: responder, repetir a resposta, responder pela
 * metade, e não responder.
 */
public final class ParceiroSimulado {

    /** Os dois diretórios combinados — contrato, e não configuração. */
    private static final String DIRETORIO_REMESSA = "/remessa";
    private static final String DIRETORIO_RETORNO = "/retorno";

    /**
     * O motivo que acompanha um {@code NAO_PAGO}.
     *
     * <p>Fixo porque a escolha entre os motivos não muda nada do mecanismo: o
     * que o projeto precisa é que {@code NAO_PAGO} tenha motivo — a regra do
     * {@code TentativaDebito.exigirMotivoCoerente} — e não que ele varie.
     */
    private static final TentativaDebito.MotivoNaoPago MOTIVO =
            TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE;

    private final DiscoDoParceiro disco;

    /**
     * As partes que o parceiro ainda não escreveu, por ciclo.
     *
     * <p>É o estado de quem entrega o retorno em mais de um momento — e um
     * parceiro de verdade tem esse estado. Fica aqui, no ambiente, e não
     * atravessa nenhuma porta: o nosso lado continua descobrindo o que chegou
     * varrendo o diretório, como faria com o banco real.
     */
    private final Map<String, Deque<Parte>> pendentes = new ConcurrentHashMap<>();

    public ParceiroSimulado(DiscoDoParceiro disco) {
        this.disco = disco;
    }

    /** O que o parceiro fez nesta chamada. */
    public record Resultado(int tentativasLidas, int linhasEscritas,
                            List<Arquivo> arquivos, int partesPendentes) {

        public Resultado {
            arquivos = List.copyOf(arquivos);
        }
    }

    /** Um arquivo que o parceiro deixou no diretório de retorno. */
    public record Arquivo(String nome, int linhas, int bytes) {
    }

    /** Uma parte já montada, esperando a vez de ser escrita. */
    private record Parte(String nome, byte[] conteudo, int linhas) {
    }

    /**
     * Processa as remessas recebidas e escreve o retorno.
     *
     * @param distribuicao desfechos separados por vírgula, aplicados em ciclo
     *        sobre as tentativas na ordem do arquivo — {@code PAGO,NAO_PAGO}
     *        alterna entre os dois.
     * @param particionar em quantos arquivos o retorno do ciclo é quebrado. O
     *        parceiro não promete um arquivo por ciclo, e o step-09 existe
     *        porque ele não promete.
     * @param atrasar escreve só a próxima parte agora; o resto fica pendente
     *        para uma segunda chamada. Entre uma e outra, o ciclo tem metade do
     *        retorno aplicada e a outra metade ainda em {@code ENVIADO_PARCEIRO}
     *        — que é o estado que o fechamento transformaria em
     *        {@code SEM_RETORNO}.
     */
    public Resultado processar(String distribuicao, int particionar, boolean atrasar) {
        List<TentativaDebito.Status> desfechos = desfechos(distribuicao);
        if (particionar < 1) {
            throw new IllegalArgumentException("particionar precisa ser ao menos 1: " + particionar);
        }

        Escrita escrita = new Escrita();
        for (RemessaLida remessa : remessas()) {
            escrita.leu(remessa);
            Deque<Parte> fila = pendentes.computeIfAbsent(
                    remessa.cicloId(), ciclo -> new ArrayDeque<>());
            if (fila.isEmpty()) {
                fila.addAll(partir(remessa, desfechos, atrasar ? Math.max(particionar, 2) : particionar));
            }

            // Com atrasar, uma parte por chamada. Sem, o que estiver pendente
            // sai de uma vez — inclusive o resto de um atraso anterior.
            do {
                escrita.escreveu(fila.poll());
            } while (!atrasar && !fila.isEmpty());
            escrita.pendentes(fila.size());
        }
        return escrita.resultado();
    }

    /**
     * Reescreve, byte a byte, o retorno que já entregou.
     *
     * <p>É o parceiro que reenvia o arquivo do dia — acontece, e não é erro
     * dele. Do nosso lado, os mesmos bytes são reconhecidos pelo {@code sha256}
     * e curto-circuitados sem serem interpretados de novo. O atalho vale para
     * <b>exatamente</b> os mesmos bytes; um reenvio reagrupado em outro número
     * de arquivos passa direto por ele, e aí quem decide é o {@code UPDATE}
     * condicional do step-03, afetando zero linhas.
     */
    public Resultado reenviar() {
        Escrita escrita = new Escrita();
        for (String caminho : disco.listar(DIRETORIO_RETORNO)) {
            byte[] conteudo = disco.ler(caminho);
            escrita.escreveu(new Parte(
                    caminho.substring(caminho.lastIndexOf('/') + 1),
                    conteudo,
                    LayoutDeRetorno.detalhes(conteudo)));
        }
        return escrita.resultado();
    }

    /**
     * Escreve um retorno cujo trailer não bate com o conteúdo.
     *
     * <p>O parceiro escreveu o cabeçalho, parte das linhas e o trailer que
     * pretendia cumprir, e parou. Do nosso lado o arquivo é descartado inteiro:
     * aplicar o que dá seria indistinguível de um retorno legítimo menor, e o
     * fechamento transformaria o resto em {@code SEM_RETORNO} — afirmando
     * silêncio onde havia ruído.
     */
    public Resultado truncar() {
        Escrita escrita = new Escrita();
        for (RemessaLida remessa : remessas()) {
            escrita.leu(remessa);
            pendentes.remove(remessa.cicloId());

            List<LinhaRetorno> todas = linhas(remessa, List.of(TentativaDebito.Status.PAGO));
            List<LinhaRetorno> cortadas = todas.subList(0, Math.max(todas.size() - 1, 0));
            escrita.escreveu(new Parte(
                    LayoutDeRetorno.nome(remessa),
                    LayoutDeRetorno.arquivoDeclarando(remessa, cortadas, todas.size()),
                    cortadas.size()));
            escrita.pendentes(0);
        }
        return escrita.resultado();
    }

    /**
     * Lê a remessa e não escreve nada.
     *
     * <p>É o botão mais barato de implementar e o mais importante de ter: a
     * ausência de retorno é o caso que os sistemas reais tratam pior, e não há
     * como observá-lo sem um botão que produz nada. Quem o interpreta é o
     * fechamento do ciclo, e ele conclui {@code SEM_RETORNO} — nunca
     * {@code NAO_PAGO}.
     */
    public Resultado silenciar() {
        Escrita escrita = new Escrita();
        for (RemessaLida remessa : remessas()) {
            escrita.leu(remessa);
            pendentes.remove(remessa.cicloId());
            escrita.pendentes(0);
        }
        return escrita.resultado();
    }

    /** As remessas que estão no diretório do parceiro, na ordem do disco. */
    private List<RemessaLida> remessas() {
        List<RemessaLida> recebidas = new ArrayList<>();
        for (String caminho : disco.listar(DIRETORIO_REMESSA)) {
            if (!caminho.endsWith(".rem")) {
                continue;
            }
            recebidas.add(RemessaLida.de(
                    caminho.substring(caminho.lastIndexOf('/') + 1), disco.ler(caminho)));
        }
        if (recebidas.isEmpty()) {
            throw new IllegalStateException("nenhuma remessa em " + DIRETORIO_REMESSA
                    + ": o parceiro não tem sobre o que falar");
        }
        return recebidas;
    }

    /** As partes do retorno de um ciclo, já montadas e nomeadas. */
    private static List<Parte> partir(RemessaLida remessa,
                                      List<TentativaDebito.Status> desfechos, int particionar) {
        List<LinhaRetorno> linhas = linhas(remessa, desfechos);
        int partes = Math.min(particionar, Math.max(linhas.size(), 1));
        int porParte = (linhas.size() + partes - 1) / partes;

        List<Parte> montadas = new ArrayList<>();
        for (int parte = 0; parte < partes; parte++) {
            List<LinhaRetorno> daParte = linhas.subList(
                    Math.min(parte * porParte, linhas.size()),
                    Math.min((parte + 1) * porParte, linhas.size()));
            montadas.add(new Parte(
                    partes == 1 ? LayoutDeRetorno.nome(remessa)
                            : LayoutDeRetorno.nome(remessa, parte + 1),
                    LayoutDeRetorno.arquivo(remessa, daParte),
                    daParte.size()));
        }
        return montadas;
    }

    /** Uma afirmação por tentativa da remessa, na ordem em que ela veio. */
    private static List<LinhaRetorno> linhas(RemessaLida remessa,
                                             List<TentativaDebito.Status> desfechos) {
        List<LinhaRetorno> linhas = new ArrayList<>();
        List<String> tentativas = remessa.tentativas();
        for (int posicao = 0; posicao < tentativas.size(); posicao++) {
            TentativaDebito.Status desfecho = desfechos.get(posicao % desfechos.size());
            linhas.add(new LinhaRetorno(tentativas.get(posicao), desfecho,
                    desfecho == TentativaDebito.Status.NAO_PAGO ? MOTIVO : null));
        }
        return linhas;
    }

    private static List<TentativaDebito.Status> desfechos(String distribuicao) {
        if (distribuicao == null || distribuicao.isBlank()) {
            return List.of(TentativaDebito.Status.PAGO);
        }
        List<TentativaDebito.Status> pedidos = Arrays.stream(distribuicao.split(","))
                .map(String::trim)
                .filter(pedido -> !pedido.isEmpty())
                .map(pedido -> TentativaDebito.Status.valueOf(pedido.toUpperCase(Locale.ROOT)))
                .toList();
        pedidos.forEach(pedido -> {
            if (!pedido.vemDoRetorno()) {
                // A pergunta mora no enum desde o step-03, e é ela que impede o
                // simulador de afirmar SEM_RETORNO — que não é afirmação de
                // ninguém, é o que o fechamento conclui do silêncio.
                throw new IllegalArgumentException(
                        "o parceiro não informa este desfecho num arquivo de retorno: " + pedido);
            }
        });
        return pedidos.isEmpty() ? List.of(TentativaDebito.Status.PAGO) : pedidos;
    }

    /** O que a chamada foi acumulando, e o {@link Resultado} que ela devolve. */
    private final class Escrita {

        private final List<Arquivo> arquivos = new ArrayList<>();
        private int lidas;
        private int linhas;
        private int aguardando;

        private void leu(RemessaLida remessa) {
            lidas += remessa.tentativas().size();
        }

        private void escreveu(Parte parte) {
            disco.escrever(DIRETORIO_RETORNO + "/" + parte.nome(), parte.conteudo());
            arquivos.add(new Arquivo(parte.nome(), parte.linhas(), parte.conteudo().length));
            linhas += parte.linhas();
        }

        private void pendentes(int quantas) {
            aguardando += quantas;
        }

        private Resultado resultado() {
            return new Resultado(lidas, linhas, arquivos, aguardando);
        }
    }
}
