package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.Fatura;
import com.platinumcoin.outbox.domain.model.LancamentoContabil;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.model.Remessa;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.outbox.domain.port.PublicadorLancamento;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.outbox.domain.usecase.FecharCicloUseCase;
import com.platinumcoin.outbox.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.domain.usecase.PublicarOutboxUseCase;
import com.platinumcoin.outbox.infra.config.Ambiente;
import com.platinumcoin.outbox.infra.persistence.ArmazenamentoArtefatoS3;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * O ciclo inteiro, do ciclo montado à mensagem na fila, com cada transição
 * impressa.
 *
 * <p>Existe para que as três propriedades que o projeto defende sejam visíveis
 * sem ler teste nenhum: o retorno duplicado que afeta <b>zero</b> linhas, a
 * ausência de retorno que vira {@code SEM_RETORNO} sem gerar lançamento, e a
 * duplicata na fila quando o relay morre entre o {@code send} e o
 * {@code UPDATE}. Os testes provam; este arquivo mostra.
 *
 * <p>O cenário, num ciclo só:
 *
 * <pre>
 * F-1  retorno pago aplicado duas vezes        → 1 linha no outbox, 1 mensagem
 * F-2  T-2 NAO_PAGO (saldo), T-3 PAGO          → 1 linha no outbox, 1 mensagem
 * F-3  pago; o relay morre entre send e UPDATE → 1 linha no outbox, 2 mensagens
 * F-4  o parceiro não falou nada               → SEM_RETORNO, nenhum lançamento
 * </pre>
 *
 * <p>A quarta fatura é a diferença entre "o parceiro recusou" e "o parceiro não
 * respondeu": {@code NAO_PAGO} é afirmação e vem com motivo, {@code SEM_RETORNO}
 * é silêncio. Colapsar as duas notificaria o cliente sobre uma falha de débito
 * que ninguém afirmou ter ocorrido — ver step-04.
 *
 * <p>É um demo, não um serviço: começa zerando o banco e a fila, para que a
 * contagem final do fim seja deste cenário e não do anterior. Nenhuma outra
 * parte do projeto apaga dados.
 */
public final class Main {

    private static final String BANCO = "341";
    private static final LocalDate DATA = LocalDate.of(2026, 8, 31);
    private static final String CICLO = "C-1";

    /** O limite de uma passada do relay: maior que o cenário, de propósito. */
    private static final int LOTE = 10;

    private final PrintStream saida;
    private final DataSource dados;
    private final SqsClient sqs;
    private final String urlDaFila;

    private final RepositorioFatura faturas;
    private final RepositorioTentativa tentativas;
    private final RepositorioCiclo ciclos;
    private final RepositorioOutbox outbox;
    private final PublicadorLancamento publicador;
    private final ArmazenamentoArtefato artefatos;

    private final MontarCicloUseCase montar;
    private final GerarRemessaUseCase gerarRemessa;
    private final AplicarRetornoUseCase aplicarRetorno;
    private final FecharCicloUseCase fecharCiclo;

    /**
     * A fiação inteira do programa, num lugar só.
     *
     * <p>Recebe o {@link Ambiente} e a saída em vez de construí-los, porque é
     * isso que permite ao teste rodar este mesmo cenário contra os containers e
     * ler o que ele imprimiu. Um {@code main} que monta a própria fiação lá
     * dentro só é exercitável à mão.
     */
    public Main(Ambiente ambiente, PrintStream saida) {
        this.saida = saida;
        this.dados = ambiente.dados();
        this.sqs = ambiente.sqs();
        this.urlDaFila = ambiente.urlDaFila();

        this.faturas = new RepositorioFaturaPostgres(dados);
        this.tentativas = new RepositorioTentativaPostgres(dados);
        this.ciclos = new RepositorioCicloPostgres(dados);
        this.outbox = new RepositorioOutboxPostgres(dados);
        this.publicador = new PublicadorLancamentoSqs(sqs, urlDaFila);
        this.artefatos = new ArmazenamentoArtefatoS3(ambiente.s3(), ambiente.bucket());

        Transacao.Fabrica transacoes = new TransacaoJdbc.Fabrica(dados);
        this.montar = new MontarCicloUseCase(transacoes, ciclos);
        this.gerarRemessa = new GerarRemessaUseCase(
                transacoes, ciclos, tentativas, faturas, artefatos);
        this.aplicarRetorno = new AplicarRetornoUseCase(transacoes, tentativas, faturas, outbox);
        this.fecharCiclo = new FecharCicloUseCase(transacoes, ciclos);
    }

    public static void main(String[] args) {
        new Main(Ambiente.doProcesso(), System.out).executar();
    }

    /** O ciclo de vida na ordem em que ele acontece. */
    public void executar() {
        zerarOMundo();
        abrirFaturas();
        montarOCiclo();
        projetarARemessa();
        transmitirAoParceiro();
        aplicarOsRetornos();
        fecharAJanela();
        publicarOOutbox();
        conferirAFila();
    }

    private void zerarOMundo() {
        executar("TRUNCATE outbox, tentativa_debito, ciclo_cobranca, fatura RESTART IDENTITY CASCADE");
        int descartadas = drenarFila().size();
        linha("limpeza", "banco zerado, %d mensagem(ns) de execuções anteriores descartadas"
                .formatted(descartadas));
    }

    private void abrirFaturas() {
        abrir("F-1", "100.00", tentativa("T-1", "F-1", 1));
        abrir("F-2", "250.50", tentativa("T-2", "F-2", 1), tentativa("T-3", "F-2", 2));
        abrir("F-3", "89.90", tentativa("T-4", "F-3", 1));
        abrir("F-4", "42.00", tentativa("T-5", "F-4", 1));
    }

    private void montarOCiclo() {
        // A ÚNICA escrita que importa. Tudo daqui para baixo é trabalho
        // derivado dela: remessa, retorno, fechamento e publicação podem ser
        // refeitos a partir do ciclo e chegam ao mesmo lugar.
        MontarCicloUseCase.Resultado montado = montar.executar(CICLO, BANCO, DATA);

        linha("ciclo", "%s %s — %d tentativas ABERTO → SOLICITADO (banco %s, %s)".formatted(
                montado.ciclo().id(), montado.ciclo().status(), montado.tentativas(), BANCO, DATA));
    }

    /**
     * Gera a remessa duas vezes de propósito: a segunda passada é o que torna
     * visível que o artefato é endereçável — mesma chave, mesmos bytes, uma
     * sobrescrita que ninguém paga. É a diferença entre este efeito externo e o
     * da fila, onde a segunda passada custa uma duplicata.
     */
    private void projetarARemessa() {
        Remessa remessa = gerarRemessa.executar(CICLO);
        Remessa regerada = gerarRemessa.executar(CICLO);
        byte[] noArmazenamento = artefatos.get(remessa.chave());

        linha("remessa", "%s %d detalhes, sha256=%s".formatted(
                remessa.cicloId(), remessa.quantidadeDeDetalhes(), remessa.sha256()));
        linha("artefato", "%s — %d bytes no S3 (regerada: %s, objeto: %s)".formatted(
                remessa.chave(),
                noArmazenamento.length,
                remessa.equals(regerada) ? "idêntica" : "DIVERGIU",
                Arrays.equals(noArmazenamento, remessa.bytes()) ? "idêntico" : "DIVERGIU"));
    }

    /**
     * O que a transmissão deixa para trás: tentativas esperando retorno.
     *
     * <p>{@code EnviarRemessa} não tem classe neste repositório — SFTP e CNAB
     * 240 são I/O e formato, e estão fora de escopo (ver README). O cenário
     * começa a interessar depois que o arquivo já foi entregue, e é por isso
     * que estas duas linhas são {@code UPDATE} direto em vez de use case.
     */
    private void transmitirAoParceiro() {
        int enviadas = executar("""
                UPDATE tentativa_debito SET status = 'ENVIADO_PARCEIRO'
                 WHERE ciclo_id = ? AND status = 'SOLICITADO'
                """, CICLO);
        executar("UPDATE ciclo_cobranca SET status = 'ENVIADO' WHERE id = ?", CICLO);

        linha("envia", "%s %s — %d tentativas SOLICITADO → ENVIADO_PARCEIRO (transporte fora de escopo)"
                .formatted(CICLO, CicloCobranca.Status.ENVIADO, enviadas));
    }

    private void aplicarOsRetornos() {
        // F-1: o mesmo arquivo processado duas vezes. A segunda passada não
        // encontra a tentativa em ENVIADO_PARCEIRO e afeta zero linhas — e zero
        // linhas não é erro, é o caso normal de um reprocessamento.
        retorno(LinhaRetorno.paga("T-1"));
        retorno(LinhaRetorno.paga("T-1"));

        // F-2: recusa com motivo, depois reapresentação que paga. Duas
        // tentativas, um lançamento.
        retorno(LinhaRetorno.naoPaga("T-2", TentativaDebito.MotivoNaoPago.SALDO_INSUFICIENTE));
        retorno(LinhaRetorno.paga("T-3"));

        // F-3: paga; a duplicata dela nasce mais tarde, no relay.
        retorno(LinhaRetorno.paga("T-4"));

        // F-4 não aparece: o parceiro não falou nada sobre ela. É o fechamento
        // que decide o que fazer com o silêncio.
    }

    private void fecharAJanela() {
        int semRetorno = fecharCiclo.executar(CICLO);
        String silenciosas = tentativas.doCiclo(CICLO).stream()
                .filter(tentativa -> tentativa.status() == TentativaDebito.Status.SEM_RETORNO)
                .map(tentativa -> tentativa.id() + " (" + tentativa.faturaId() + ")")
                .collect(Collectors.joining(", "));

        linha("fecha", "%s %s — %d tentativa(s) ENVIADO_PARCEIRO → SEM_RETORNO: %s".formatted(
                CICLO, CicloCobranca.Status.FECHADO, semRetorno, silenciosas));
        linha("outbox", "%d linhas PENDENTE — nem NAO_PAGO nem SEM_RETORNO geram lançamento"
                .formatted(outbox.pendentes(LOTE).size()));
    }

    /**
     * O relay, duas passadas: uma que morre no pior instante possível e outra
     * que termina o serviço.
     *
     * <p>A primeira passada envia F-1, F-2 e F-3 e morre antes de marcar F-3
     * como publicada. A linha continua {@code PENDENTE}, e é justamente por isso
     * que a segunda passada a republica — duplicata na fila, com a mesma chave
     * de dedup, em vez de uma mensagem perdida que ninguém procuraria.
     * <br>DECISÃO: at-least-once assumido em vez de fila FIFO — ver ADR-0002
     */
    private void publicarOOutbox() {
        linha("relay", "primeira passada — o processo vai morrer entre o send de F-3 e o UPDATE");
        try {
            new PublicarOutboxUseCase(new MorreAoMarcar(outbox, "F-3"), narrando()).executar(LOTE);
        } catch (FalhaDePersistencia morte) {
            linha("crash", morte.getMessage());
        }
        linha("outbox", "ainda PENDENTE: %s — a mensagem saiu, a linha não foi marcada"
                .formatted(pendentes()));

        int publicados = new PublicarOutboxUseCase(outbox, narrando()).executar(LOTE);

        linha("relay", "segunda passada — %d linha(s) PENDENTE → PUBLICADO".formatted(publicados));
        linha("outbox", "%d PUBLICADO, %d PENDENTE".formatted(
                contar("PUBLICADO"), outbox.pendentes(LOTE).size()));
    }

    private void conferirAFila() {
        List<Message> naFila = drenarFila();
        naFila.forEach(mensagem -> linha("fila", "chaveDedup=%s  %s".formatted(
                mensagem.messageAttributes().get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP)
                        .stringValue(),
                mensagem.body())));

        long chaves = naFila.stream()
                .map(mensagem -> mensagem.messageAttributes()
                        .get(PublicadorLancamentoSqs.ATRIBUTO_DEDUP).stringValue())
                .distinct()
                .count();

        linha("fila", "%d lançamentos, %d chaves distintas — F-3 duplicada porque o relay morreu"
                .formatted(naFila.size(), chaves));
        linha("fila", "at-least-once é o contrato: quem desduplica pela chaveDedup"
                + " é o consumidor — ver ADR-0002");
    }

    // ---------------------------------------------------------------- narração

    /** Uma transição por linha, com a etiqueta do passo que a produziu. */
    private void linha(String etiqueta, String texto) {
        saida.printf("%-10s %s%n", "[" + etiqueta + "]", texto);
    }

    /**
     * Aplica uma linha do arquivo de retorno e conta o que ela mudou — lendo o
     * estado de volta do banco, e não repetindo o que se espera dele.
     */
    private void retorno(LinhaRetorno linhaDoArquivo) {
        String tentativaId = linhaDoArquivo.tentativaId();
        TentativaDebito antes = tentativas.buscar(tentativaId).orElseThrow();

        AplicarRetornoUseCase.Resultado resultado = linhaDoArquivo.aplicarCom(aplicarRetorno);

        TentativaDebito depois = tentativas.buscar(tentativaId).orElseThrow();
        linha("retorno", "%s %s → %s%s  (%s)".formatted(
                tentativaId, antes.status(), depois.status(),
                depois.motivo() == null ? "" : " (" + depois.motivo() + ")",
                explicar(resultado)));

        if (resultado == AplicarRetornoUseCase.Resultado.APLICADO_COM_LANCAMENTO) {
            Fatura fatura = faturas.buscar(depois.faturaId()).orElseThrow();
            linha("fatura", "%s ABERTA → %s".formatted(fatura.id(), fatura.status()));
            linha("outbox", "%s + PENDENTE (chaveDedup=%s) — na MESMA transação da fatura"
                    .formatted(fatura.id(), fatura.id()));
        }
    }

    private static String explicar(AplicarRetornoUseCase.Resultado resultado) {
        return switch (resultado) {
            case IGNORADO -> "0 linhas afetadas — ignorado, o retorno já havia sido aplicado";
            case APLICADO -> "1 linha afetada, sem outbox — não é pagamento, ou a fatura já pagou";
            case APLICADO_COM_LANCAMENTO -> "1 linha afetada";
        };
    }

    /** O publicador de verdade, dizendo o que sai e quando — o instante do {@code send}. */
    private PublicadorLancamento narrando() {
        return lancamento -> {
            String id = publicador.publicar(lancamento);
            linha("send", "%s → SQS  chaveDedup=%s  msg=%s".formatted(
                    lancamento.faturaId(), lancamento.chaveDedup(), id));
            return id;
        };
    }

    private String pendentes() {
        return outbox.pendentes(LOTE).stream()
                .map(registro -> registro.lancamento().faturaId())
                .collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------ mundo

    private static TentativaDebito tentativa(String id, String faturaId, int numero) {
        return TentativaDebito.aberta(id, faturaId, numero, BANCO, DATA);
    }

    private void abrir(String faturaId, String valor, TentativaDebito... doFatura) {
        faturas.inserir(new Fatura(faturaId, new BigDecimal(valor), Fatura.Status.ABERTA));
        for (TentativaDebito tentativaAberta : doFatura) {
            tentativas.inserir(tentativaAberta);
        }

        linha("dados", "%s %s ABERTA · %s".formatted(faturaId, valor,
                Arrays.stream(doFatura)
                        .map(TentativaDebito::id)
                        .collect(Collectors.joining(", "))));
    }

    /**
     * Tira da fila o que estiver lá. Contar mensagens consumindo-as é o que
     * torna a contagem honesta: é o que o mainframe receberia, e não o que o
     * LocalStack guardou de uma execução anterior.
     */
    private List<Message> drenarFila() {
        List<Message> recebidas = new ArrayList<>();
        int passadasVazias = 0;
        // Uma passada vazia não basta: o SQS entrega por amostragem, e "não há
        // mais nada" é uma afirmação que precisa de mais de uma leitura.
        while (passadasVazias < 3) {
            List<Message> lote = sqs.receiveMessage(pedido -> pedido
                    .queueUrl(urlDaFila)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .messageAttributeNames("All")).messages();
            if (lote.isEmpty()) {
                passadasVazias++;
                continue;
            }
            passadasVazias = 0;
            lote.forEach(mensagem -> sqs.deleteMessage(exclusao -> exclusao
                    .queueUrl(urlDaFila)
                    .receiptHandle(mensagem.receiptHandle())));
            recebidas.addAll(lote);
        }
        return List.copyOf(recebidas);
    }

    private int contar(String status) {
        try (Connection conexao = dados.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(
                     "SELECT count(*) FROM outbox WHERE status = ?")) {
            stmt.setString(1, status);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao contar o outbox " + status, e);
        }
    }

    /** SQL solto do demo: limpeza e transmissão, que não são operação de negócio. */
    private int executar(String sql, String... parametros) {
        try (Connection conexao = dados.getConnection();
             PreparedStatement stmt = conexao.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                stmt.setString(i + 1, parametros[i]);
            }
            return stmt.executeUpdate();
        } catch (SQLException e) {
            throw new FalhaDePersistencia("falha ao executar: " + sql, e);
        }
    }

    /**
     * O relay que morre no instante exato em que a duplicata nasce: depois do
     * {@code send} da fatura escolhida, antes do {@code UPDATE}.
     *
     * <p>O mesmo recorte que {@code CrashDoRelayTest} exercita — aqui para ser
     * visto, lá para ser provado.
     */
    private static final class MorreAoMarcar implements RepositorioOutbox {

        private final RepositorioOutbox real;
        private final String faturaFatal;
        private final Set<Long> linhasFatais = new HashSet<>();

        private MorreAoMarcar(RepositorioOutbox real, String faturaFatal) {
            this.real = real;
            this.faturaFatal = faturaFatal;
        }

        @Override
        public void inserir(Transacao tx, LancamentoContabil lancamento) {
            real.inserir(tx, lancamento);
        }

        @Override
        public List<RegistroOutbox> pendentes(int limite) {
            List<RegistroOutbox> pendentes = real.pendentes(limite);
            pendentes.stream()
                    .filter(registro -> registro.lancamento().faturaId().equals(faturaFatal))
                    .forEach(registro -> linhasFatais.add(registro.id()));
            return pendentes;
        }

        @Override
        public int marcarPublicado(long registroId) {
            if (linhasFatais.contains(registroId)) {
                throw new FalhaDePersistencia(
                        "o relay morreu entre o send de " + faturaFatal + " e o UPDATE");
            }
            return real.marcarPublicado(registroId);
        }
    }
}
