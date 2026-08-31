package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.ArquivoRetorno;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.outbox.domain.usecase.ColetarRetornoUseCase;
import com.platinumcoin.outbox.infra.canal.CanalArquivosSftp;
import com.platinumcoin.outbox.infra.config.Ambiente;
import com.platinumcoin.outbox.infra.persistence.ArmazenamentoArtefatoS3;
import com.platinumcoin.outbox.infra.persistence.RepositorioArquivoRetornoPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base dos testes: sobe Postgres, LocalStack (SQS e S3) e o SFTP do parceiro,
 * e aplica os MESMOS scripts de init que o {@code docker compose} usa.
 *
 * <p>Reaproveitar os scripts não é economia de digitação — é o que garante que
 * um teste verde signifique alguma coisa. Um schema de teste escrito à parte
 * diverge do de produção sem que nada acuse.
 *
 * <p>Os containers são estáticos e sobem uma vez para toda a suíte; cada teste
 * limpa as tabelas em vez de recriar o banco.
 */
public abstract class AmbienteDeTeste {

    protected static final String NOME_DA_FILA = "lancamentos-contabeis";
    protected static final String NOME_DO_BUCKET = "cobranca-artefatos";
    protected static final String USUARIO_SFTP = "parceiro";
    protected static final String SENHA_SFTP = "parceiro";
    /** O diretório do parceiro onde a remessa é deixada — contrato, não configuração. */
    protected static final String DIRETORIO_REMESSA = "/remessa";
    /** E aquele de onde o retorno é recolhido. */
    protected static final String DIRETORIO_RETORNO = "/retorno";

    /**
     * O intervalo entre as duas leituras de atributos na suíte.
     *
     * <p>Curto porque o teste não espera de verdade: quem faz o arquivo crescer
     * entre as leituras é um decorador do canal, não o relógio. Em produção
     * seriam minutos — e é por isso que o intervalo é parâmetro do use case, não
     * constante dentro dele.
     */
    protected static final Duration QUIESCENCIA = Duration.ofMillis(50);

    private static final Path SCHEMA_SQL = Path.of("infra", "init", "02-postgres.sql");
    private static final Path INIT_LOCALSTACK = Path.of("infra", "init", "01-localstack.sh");

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("cobranca")
                    .withUsername("cobranca")
                    .withPassword("cobranca");

    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(LocalStackContainer.Service.SQS, LocalStackContainer.Service.S3)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(INIT_LOCALSTACK, 0755),
                            "/etc/localstack/init/ready.d/01-localstack.sh")
                    // Espera o log do PRÓPRIO script de init, na ÚLTIMA linha
                    // dele: se a fila e o bucket não foram criados pelo script,
                    // o teste nem começa.
                    .waitingFor(Wait.forLogMessage(".*bucket " + NOME_DO_BUCKET + " criado.*\\n", 1));

    /**
     * O parceiro: um servidor SSH de verdade, com os mesmos diretórios e as
     * mesmas credenciais do Compose.
     *
     * <p>A sintaxe {@code usuario:senha:::dirs} do {@code atmoz/sftp} cria
     * {@code /remessa} e {@code /retorno} dentro do chroot do usuário.
     */
    protected static final GenericContainer<?> SFTP =
            new GenericContainer<>(DockerImageName.parse("atmoz/sftp:alpine"))
                    .withExposedPorts(22)
                    .withCommand(USUARIO_SFTP + ":" + SENHA_SFTP + ":::remessa,retorno")
                    .waitingFor(Wait.forLogMessage(".*Server listening on 0\\.0\\.0\\.0 port 22.*\\n", 1));

    private static Ambiente ambiente;

    @BeforeAll
    static void subirAmbiente() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
            aplicarSchema();
        }
        if (!LOCALSTACK.isRunning()) {
            LOCALSTACK.start();
        }
        if (!SFTP.isRunning()) {
            SFTP.start();
        }
    }

    private static void aplicarSchema() throws IOException, SQLException {
        String ddl = Files.readString(SCHEMA_SQL);
        try (Connection conexao = novaConexao(); Statement stmt = conexao.createStatement()) {
            stmt.execute(ddl);
        }
    }

    protected static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /**
     * A MESMA montagem que o {@code Main} usa, apontada para os containers.
     *
     * <p>Pela razão dos scripts de init: uma fiação de teste escrita à parte
     * diverge da de produção sem que nada acuse. É também o que o
     * {@code CenarioPontaAPontaTest} entrega ao {@code Main}: o cenário do
     * step-06 roda contra os containers sem uma segunda montagem.
     */
    protected static Ambiente ambiente() {
        if (ambiente == null) {
            ambiente = Ambiente.de(Map.ofEntries(
                    Map.entry("DB_URL", POSTGRES.getJdbcUrl()),
                    Map.entry("DB_USUARIO", POSTGRES.getUsername()),
                    Map.entry("DB_SENHA", POSTGRES.getPassword()),
                    Map.entry("SQS_ENDPOINT", LOCALSTACK.getEndpoint().toString()),
                    Map.entry("S3_ENDPOINT", LOCALSTACK.getEndpoint().toString()),
                    Map.entry("AWS_REGIAO", LOCALSTACK.getRegion()),
                    Map.entry("AWS_CHAVE", LOCALSTACK.getAccessKey()),
                    Map.entry("AWS_SEGREDO", LOCALSTACK.getSecretKey()),
                    Map.entry("FILA", NOME_DA_FILA),
                    Map.entry("BUCKET", NOME_DO_BUCKET),
                    Map.entry("SFTP_HOST", SFTP.getHost()),
                    Map.entry("SFTP_PORTA", String.valueOf(SFTP.getMappedPort(22))),
                    Map.entry("SFTP_USUARIO", USUARIO_SFTP),
                    Map.entry("SFTP_SENHA", SENHA_SFTP))::get);
        }
        return ambiente;
    }

    /**
     * O {@link DataSource} que a infra recebe — o mesmo tipo que o {@code Main}
     * usará, e não uma conexão de teste passada à mão.
     */
    protected static DataSource dados() {
        return ambiente().dados();
    }

    protected static SqsClient sqs() {
        return ambiente().sqs();
    }

    protected static S3Client s3() {
        return ambiente().s3();
    }

    /** O armazenamento que a infra recebe — o mesmo que o {@code Main} usa. */
    protected static ArmazenamentoArtefato artefatos() {
        return new ArmazenamentoArtefatoS3(s3(), ambiente().bucket());
    }

    /** O canal que a infra recebe — o mesmo que o {@code Main} usa. */
    protected static CanalArquivos canal() {
        return new CanalArquivosSftp(ambiente().sftp());
    }

    /**
     * A coleta inteira, fiada como em produção, sobre o canal informado.
     *
     * <p>Recebe o canal em vez de construí-lo porque é ali que os testes do
     * step-09 intervêm: o arquivo que cresce entre as duas leituras nasce de um
     * decorador de {@link CanalArquivos}, e não de uma espera cronometrada.
     *
     * <p>O aplicador é o {@code AplicarRetornoUseCase} de verdade — a coleta não
     * decide estado de tentativa nenhuma, e um aplicador de mentira aqui
     * esconderia justamente a parte que o step-03 provou.
     */
    protected static ColetarRetornoUseCase coletaCom(CanalArquivos canal) {
        Transacao.Fabrica transacoes = new TransacaoJdbc.Fabrica(dados());
        return new ColetarRetornoUseCase(
                canal,
                artefatos(),
                new RepositorioArquivoRetornoPostgres(dados()),
                ArquivoRetorno::de,
                new AplicarRetornoUseCase(transacoes,
                        new RepositorioTentativaPostgres(dados()),
                        new RepositorioFaturaPostgres(dados()),
                        new RepositorioOutboxPostgres(dados())),
                QUIESCENCIA);
    }

    protected static String urlDaFila() {
        return ambiente().urlDaFila();
    }

    /**
     * Tira da fila o que estiver lá, esperando por {@code esperadas} mensagens
     * antes de desistir.
     *
     * <p>Consumir é o que torna a asserção honesta: o teste afirma o que o
     * mainframe receberia, e não o que o LocalStack tem guardado de um teste
     * anterior. Pedir mais do que se espera é de propósito — {@code
     * drenarFila(0)} é como se assere que <b>nada</b> foi publicado.
     */
    protected static List<Message> drenarFila(int esperadas) {
        List<Message> recebidas = new ArrayList<>();
        int passadasVazias = 0;
        // Só para depois de uma passada vazia: "nenhuma mensagem além destas" é
        // uma afirmação que precisa de uma leitura a mais para valer. Três
        // vazias seguidas é desistência — a mensagem esperada não vem, e o
        // assert de quantidade é quem conta a história.
        while (passadasVazias < 1 || recebidas.size() < esperadas) {
            List<Message> lote = sqs().receiveMessage(pedido -> pedido
                    .queueUrl(urlDaFila())
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(1)
                    .messageAttributeNames("All")).messages();
            if (lote.isEmpty()) {
                if (++passadasVazias > 2) {
                    break;
                }
                continue;
            }
            lote.forEach(mensagem -> sqs().deleteMessage(exclusao -> exclusao
                    .queueUrl(urlDaFila())
                    .receiptHandle(mensagem.receiptHandle())));
            recebidas.addAll(lote);
        }
        return List.copyOf(recebidas);
    }

    /** Zera o estado entre testes sem derrubar os containers. */
    protected static void limparTabelas() throws SQLException {
        try (Connection conexao = novaConexao(); Statement stmt = conexao.createStatement()) {
            stmt.execute("TRUNCATE arquivo_retorno, outbox, tentativa_debito, ciclo_cobranca,"
                    + " fatura RESTART IDENTITY CASCADE");
        }
    }
}
