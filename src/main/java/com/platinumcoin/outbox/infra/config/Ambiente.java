package com.platinumcoin.outbox.infra.config;

import org.postgresql.ds.PGSimpleDataSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import javax.sql.DataSource;
import java.net.URI;
import java.util.function.UnaryOperator;

/**
 * Onde o programa descobre com que banco e com que fila ele fala.
 *
 * <p>É o único lugar que lê configuração. Os repositórios recebem um
 * {@link DataSource} pronto e o publicador recebe um {@link SqsClient} pronto —
 * nenhum deles sabe de variável de ambiente, e é por isso que os testes montam
 * os mesmos objetos apontando para os containers.
 *
 * <p>Os padrões são os do {@code infra/docker-compose.yml}: subir o Compose e
 * rodar o {@code Main} sem exportar nada é o caminho normal.
 */
public final class Ambiente {

    private final DataSource dados;
    private final SqsClient sqs;
    private final String nomeDaFila;
    private String urlDaFila;

    private Ambiente(DataSource dados, SqsClient sqs, String nomeDaFila) {
        this.dados = dados;
        this.sqs = sqs;
        this.nomeDaFila = nomeDaFila;
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
        SqsClient sqs = SqsClient.builder()
                .endpointOverride(URI.create(valor(variaveis, "SQS_ENDPOINT", "http://localhost:4566")))
                .region(Region.of(valor(variaveis, "AWS_REGIAO", "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        valor(variaveis, "AWS_CHAVE", "test"),
                        valor(variaveis, "AWS_SEGREDO", "test"))))
                .build();

        return new Ambiente(fonte, sqs, valor(variaveis, "FILA", "lancamentos-contabeis"));
    }

    public DataSource dados() {
        return dados;
    }

    public SqsClient sqs() {
        return sqs;
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
