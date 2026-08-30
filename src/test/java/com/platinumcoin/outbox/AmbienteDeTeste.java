package com.platinumcoin.outbox;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base dos testes: sobe Postgres e LocalStack e aplica os MESMOS scripts de
 * init que o {@code docker compose} usa.
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

    private static final Path SCHEMA_SQL = Path.of("infra", "init", "02-postgres.sql");
    private static final Path INIT_LOCALSTACK = Path.of("infra", "init", "01-localstack.sh");

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("cobranca")
                    .withUsername("cobranca")
                    .withPassword("cobranca");

    protected static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                    .withServices(LocalStackContainer.Service.SQS)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(INIT_LOCALSTACK, 0755),
                            "/etc/localstack/init/ready.d/01-localstack.sh")
                    // Espera o log do PRÓPRIO script de init: se a fila não foi
                    // criada pelo script, o teste nem começa.
                    .waitingFor(Wait.forLogMessage(".*fila " + NOME_DA_FILA + " criada.*\\n", 1));

    private static SqsClient sqs;

    @BeforeAll
    static void subirAmbiente() throws Exception {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
            aplicarSchema();
        }
        if (!LOCALSTACK.isRunning()) {
            LOCALSTACK.start();
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

    protected static SqsClient sqs() {
        if (sqs == null) {
            sqs = SqsClient.builder()
                    .endpointOverride(LOCALSTACK.getEndpoint())
                    .region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                    .build();
        }
        return sqs;
    }

    protected static String urlDaFila() {
        return sqs().getQueueUrl(b -> b.queueName(NOME_DA_FILA)).queueUrl();
    }

    /** Zera o estado entre testes sem derrubar os containers. */
    protected static void limparTabelas() throws SQLException {
        try (Connection conexao = novaConexao(); Statement stmt = conexao.createStatement()) {
            stmt.execute("TRUNCATE outbox, tentativa_debito, fatura RESTART IDENTITY CASCADE");
        }
    }
}
