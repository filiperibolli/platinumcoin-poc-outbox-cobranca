package com.platinumcoin.outbox.infra.config;

import org.postgresql.ds.PGSimpleDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.sql.DataSource;
import java.net.URI;
import java.util.function.UnaryOperator;

/**
 * Onde o programa descobre com que banco, com que fila, com que bucket e com
 * que parceiro ele fala.
 *
 * <p>É o único lugar que lê configuração. Os repositórios recebem um
 * {@link DataSource} pronto, o publicador recebe um {@link SqsClient} pronto e
 * o armazenamento recebe um {@link S3Client} pronto — nenhum deles sabe de
 * variável de ambiente, e é por isso que os testes montam os mesmos objetos
 * apontando para os containers.
 *
 * <p>Os padrões são os do {@code infra/docker-compose.yml}: subir o Compose e
 * rodar o {@code Main} sem exportar nada é o caminho normal.
 */
public final class Ambiente {

    private final DataSource dados;
    private final SqsClient sqs;
    private final S3Client s3;
    private final String nomeDaFila;
    private final String bucket;
    private final ServidorSftp sftp;
    private String urlDaFila;

    private Ambiente(DataSource dados, SqsClient sqs, S3Client s3,
                     String nomeDaFila, String bucket, ServidorSftp sftp) {
        this.dados = dados;
        this.sqs = sqs;
        this.s3 = s3;
        this.nomeDaFila = nomeDaFila;
        this.bucket = bucket;
        this.sftp = sftp;
    }

    /**
     * Com que servidor SFTP o canal fala.
     *
     * <p>Uma descrição de servidor, e não um cliente pronto como o do SQS e o
     * do S3: a sessão SSH é aberta e fechada a cada transmissão, então não há
     * objeto de longa vida para montar aqui.
     */
    public record ServidorSftp(String host, int porta, String usuario, String senha) {
    }

    /** O ambiente do processo — o que o {@code Main} usa. */
    public static Ambiente doProcesso() {
        return de(System::getenv);
    }

    /**
     * O ambiente descrito por uma tabela de variáveis.
     *
     * <p>Recebe a consulta em vez de ler {@code System.getenv} direto para que o
     * teste possa apontar esta mesma montagem para os containers, em vez de
     * repetir a fiação à mão e deixá-la divergir do que o {@code Main} faz.
     */
    public static Ambiente de(UnaryOperator<String> variaveis) {
        String url = valor(variaveis, "DB_URL", "jdbc:postgresql://localhost:5432/cobranca");
        String usuario = valor(variaveis, "DB_USUARIO", "cobranca");
        String senha = valor(variaveis, "DB_SENHA", "cobranca");

        PGSimpleDataSource fonte = new PGSimpleDataSource();
        fonte.setUrl(url);
        fonte.setUser(usuario);
        fonte.setPassword(senha);

        // Credenciais estáticas porque o destino é o LocalStack, aqui e no
        // Compose. Contra uma conta AWS de verdade, isto viraria a cadeia de
        // credenciais padrão — e é a única linha que mudaria.
        String regiao = valor(variaveis, "AWS_REGIAO", "us-east-1");
        StaticCredentialsProvider credenciais = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(
                        valor(variaveis, "AWS_CHAVE", "test"),
                        valor(variaveis, "AWS_SEGREDO", "test")));

        SqsClient sqs = SqsClient.builder()
                .endpointOverride(URI.create(valor(variaveis, "SQS_ENDPOINT", "http://localhost:4566")))
                .region(Region.of(regiao))
                .credentialsProvider(credenciais)
                .build();

        S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(valor(variaveis, "S3_ENDPOINT", "http://localhost:4566")))
                .region(Region.of(regiao))
                .credentialsProvider(credenciais)
                // Endereçamento por caminho: o LocalStack não resolve o bucket
                // como subdomínio, e virtual-hosted-style é o padrão do SDK.
                // Contra a AWS de verdade esta linha sairia junto com o
                // endpointOverride.
                .forcePathStyle(true)
                .build();

        ServidorSftp parceiro = new ServidorSftp(
                valor(variaveis, "SFTP_HOST", "localhost"),
                Integer.parseInt(valor(variaveis, "SFTP_PORTA", "2222")),
                valor(variaveis, "SFTP_USUARIO", "parceiro"),
                valor(variaveis, "SFTP_SENHA", "parceiro"));

        return new Ambiente(fonte, sqs, s3,
                valor(variaveis, "FILA", "lancamentos-contabeis"),
                valor(variaveis, "BUCKET", "cobranca-artefatos"),
                parceiro);
    }

    public DataSource dados() {
        return dados;
    }

    public SqsClient sqs() {
        return sqs;
    }

    public S3Client s3() {
        return s3;
    }

    /** O bucket dos artefatos do ciclo: remessas e, no step-09, retornos. */
    public String bucket() {
        return bucket;
    }

    /** O parceiro que recebe a remessa e devolve o retorno. */
    public ServidorSftp sftp() {
        return sftp;
    }

    /**
     * A url da fila, resolvida pelo nome na primeira chamada.
     *
     * <p>Tarde, e não na montagem: pedir a url é uma ida à rede, e um objeto de
     * configuração que só é construído com o SQS no ar seria uma dependência
     * escondida no construtor.
     */
    public String urlDaFila() {
        if (urlDaFila == null) {
            urlDaFila = sqs.getQueueUrl(fila -> fila.queueName(nomeDaFila)).queueUrl();
        }
        return urlDaFila;
    }

    private static String valor(UnaryOperator<String> variaveis, String nome, String padrao) {
        String definido = variaveis.apply(nome);
        return definido == null || definido.isBlank() ? padrao : definido;
    }
}
