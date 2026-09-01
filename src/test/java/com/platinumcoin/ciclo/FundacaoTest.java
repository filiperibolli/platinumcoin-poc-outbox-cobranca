package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-01: o chão. Prova que o ambiente sobe sozinho e que a fronteira
 * arquitetural existe de fato — antes de haver qualquer regra de negócio para
 * violá-la.
 */
class FundacaoTest extends AmbienteDeTeste {

    @Test
    @DisplayName("o schema aplicado pelo script de init tem as cinco tabelas do fluxo")
    void schemaCriadoPeloScriptDeInit() throws SQLException {
        List<String> tabelas = new ArrayList<>();
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT table_name FROM information_schema.tables
                      WHERE table_schema = 'public' ORDER BY table_name
                     """)) {
            while (rs.next()) {
                tabelas.add(rs.getString(1));
            }
        }

        assertEquals(
                List.of("arquivo_retorno", "ciclo_cobranca", "fatura", "outbox", "tentativa_debito"),
                tabelas);
    }

    @Test
    @DisplayName("o outbox carrega a invariante 'um lançamento por fatura' no próprio schema")
    void outboxTemUniquePorFatura() throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) FROM pg_constraint
                      WHERE conname = 'outbox_um_lancamento_por_fatura' AND contype = 'u'
                     """)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                    "a rede de proteção sob o UPDATE condicional do step-03 precisa existir no banco");
        }
    }

    @Test
    @DisplayName("o ciclo carrega a invariante 'um ciclo por banco e data' no próprio schema")
    void cicloTemUniquePorBancoEData() throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) FROM pg_constraint
                      WHERE conname = 'ciclo_um_por_banco_e_data' AND contype = 'u'
                     """)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                    "a reexecução da montagem precisa ser segura por construção, não por consulta prévia");
        }
    }

    @Test
    @DisplayName("chave e hash da remessa existem juntos, por constraint")
    void cicloTemCheckDeChaveEHashJuntos() throws SQLException {
        try (Connection conexao = novaConexao();
             Statement stmt = conexao.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) FROM pg_constraint
                      WHERE conname = 'ciclo_remessa_chave_com_hash' AND contype = 'c'
                     """)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1),
                    "uma chave sem hash não responde a pergunta que o hash existe para responder");
        }
    }

    @Test
    @DisplayName("apenas o desfecho PAGO autoriza um lançamento contábil")
    void somentePagoGeraLancamento() {
        List<TentativaDebito.Status> geram = Arrays.stream(TentativaDebito.Status.values())
                .filter(TentativaDebito.Status::geraLancamentoContabil)
                .toList();

        assertEquals(List.of(TentativaDebito.Status.PAGO), geram,
                "NAO_PAGO, ERRO e SEM_RETORNO não são pagamentos — o mainframe não tem o que contabilizar");
    }

    @Test
    @DisplayName("a fila que o mainframe consome já existe, sem passo manual")
    void filaCriadaPeloScriptDeInit() {
        String url = urlDaFila();

        assertTrue(url.endsWith("/" + NOME_DA_FILA), "url inesperada: " + url);
    }

    @Test
    @DisplayName("o bucket dos artefatos do ciclo já existe, sem passo manual")
    void bucketCriadoPeloScriptDeInit() {
        List<String> buckets = s3().listBuckets().buckets().stream()
                .map(Bucket::name).toList();

        assertTrue(buckets.contains(NOME_DO_BUCKET), "buckets inesperados: " + buckets);
    }

    @Test
    @DisplayName("o simulador é o ambiente: o sistema não o importa, e ele não fala com o banco")
    void simuladorEhOAmbienteNaoOSistema() throws IOException {
        Path dominio = fonte("domain");
        Path infra = fonte("infra");
        Path simulador = fonte("simulador");

        List<String> conhecemOAmbiente = new ArrayList<>(
                importesQueCasam(dominio, "com.platinumcoin.ciclo.simulador"));
        conhecemOAmbiente.addAll(importesQueCasam(infra, "com.platinumcoin.ciclo.simulador"));

        assertEquals(List.of(), conhecemOAmbiente,
                "o simulador é o ambiente, não o sistema: a seta aponta dele para cá."
                        + " Uma linha do parceiro dentro de domain/ ou infra/ colocaria na"
                        + " primeira leitura do projeto código que não vai para produção");

        assertEquals(List.of(), importesQueCasam(simulador, "java.sql.", "javax.sql."),
                "o retorno é montado a partir da remessa que atravessou o SFTP."
                        + " Consultar o Postgres seria mais simples e destruiria a"
                        + " demonstração: o retorno viraria função do nosso estado");
    }

    private static Path fonte(String pacote) {
        return Path.of("src", "main", "java", "com", "platinumcoin", "ciclo", pacote);
    }

    private static List<String> importesQueCasam(Path raiz, String... proibidos)
            throws IOException {
        List<String> encontrados = new ArrayList<>();
        try (Stream<Path> arquivos = Files.walk(raiz)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                for (String linha : Files.readAllLines(arquivo)) {
                    if (!linha.startsWith("import ")) {
                        continue;
                    }
                    for (String proibido : proibidos) {
                        if (linha.contains(proibido)) {
                            encontrados.add(arquivo.getFileName() + ": " + linha.trim());
                        }
                    }
                }
            }
        }
        return encontrados;
    }

    @Test
    @DisplayName("o domínio não conhece Spring, AWS SDK nem biblioteca SSH")
    void dominioIsolado() throws IOException {
        Path dominio = Path.of("src", "main", "java", "com", "platinumcoin", "ciclo", "domain");
        List<String> proibidos = new ArrayList<>();

        try (Stream<Path> arquivos = Files.walk(dominio)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String linha : Files.readAllLines(arquivo)) {
                    if (!linha.startsWith("import ")) {
                        continue;
                    }
                    boolean permitido = linha.startsWith("import java.")
                            || linha.startsWith("import com.platinumcoin.ciclo.domain.");
                    if (!permitido) {
                        proibidos.add(arquivo.getFileName() + ": " + linha.trim());
                    }
                }
            }
        }

        assertEquals(List.of(), proibidos,
                "api → domain ← infra: o domínio só pode importar java.* e ele mesmo."
                        + " O Spring entrou no step-10 por um motivo único — expor HTTP —"
                        + " e a lista branca abaixo é o que garante que ele parou em api/http"
                        + " e na fiação");
    }
}
