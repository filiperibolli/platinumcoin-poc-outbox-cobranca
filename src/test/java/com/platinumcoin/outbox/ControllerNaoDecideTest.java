package com.platinumcoin.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * step-10: a fronteira que o Spring poderia apagar.
 *
 * <p>Um {@code if} de negócio dentro de um controller é o começo da erosão que
 * este projeto inteiro existe para mostrar, e ela não começa com uma decisão
 * grande — começa com um {@code PreparedStatement} "só para conferir uma coisa
 * antes de chamar o use case". Por isso o que se proíbe aqui é o <b>acesso</b>,
 * e não a intenção: sem SQL e sem SDK, o controller não tem com o que decidir.
 *
 * <p>Não sobe container nenhum: é um teste sobre o código-fonte, e ele falha na
 * máquina de quem escreveu a linha errada antes de qualquer coisa subir.
 */
class ControllerNaoDecideTest {

    private static final Path API_HTTP =
            Path.of("src", "main", "java", "com", "platinumcoin", "outbox", "api", "http");
    private static final Path DOMINIO =
            Path.of("src", "main", "java", "com", "platinumcoin", "outbox", "domain");

    @Test
    @DisplayName("nenhuma classe de api/http fala com o banco ou com a AWS")
    void controllerNaoAlcancaOMundo() throws IOException {
        assertEquals(List.of(), importesQueCasam(API_HTTP,
                        "java.sql.", "javax.sql.", "software.amazon.awssdk.", "net.schmizz."),
                "o controller desserializa, chama UM use case e serializa o efeito"
                        + " — quem fala com o mundo é infra");
    }

    @Test
    @DisplayName("o domínio não conhece o Spring")
    void dominioNaoConheceOFramework() throws IOException {
        assertEquals(List.of(), importesQueCasam(DOMINIO, "org.springframework."),
                "o framework entrou por um motivo único — expor HTTP — e o domínio"
                        + " não é esse motivo");
    }

    @Test
    @DisplayName("cada controller depende de um use case só")
    void umUseCasePorController() throws IOException {
        List<String> comMaisDeUm = new ArrayList<>();
        for (Path controller : arquivos(API_HTTP)) {
            if (!controller.getFileName().toString().endsWith("Controller.java")) {
                continue;
            }
            long useCases = Files.readAllLines(controller).stream()
                    .filter(linha -> linha.startsWith("import "))
                    .filter(linha -> linha.contains(".domain.usecase."))
                    .count();
            if (useCases > 1) {
                comMaisDeUm.add(controller.getFileName() + ": " + useCases + " use cases");
            }
        }

        assertEquals(List.of(), comMaisDeUm,
                "um controller que orquestra dois use cases já está decidindo a ordem"
                        + " — e ordem de passos é desenho, não roteamento");
    }

    @Test
    @DisplayName("existe um controller para cada passo do ciclo")
    void osOitoPassosEstaoPublicados() throws IOException {
        List<String> rotas = new ArrayList<>();
        for (Path arquivo : arquivos(API_HTTP)) {
            for (String linha : Files.readAllLines(arquivo)) {
                if (linha.contains("@PostMapping(\"") || linha.contains("@GetMapping(\"")) {
                    rotas.add(linha.substring(linha.indexOf('"') + 1, linha.lastIndexOf('"')));
                }
            }
        }

        assertTrue(rotas.containsAll(List.of(
                        "/faturas", "/ciclo/montar", "/ciclo/gerar-remessa", "/ciclo/enviar",
                        "/ciclo/coletar", "/ciclo/fechar", "/outbox/publicar", "/estado")),
                "rotas publicadas: " + rotas);
    }

    private static List<String> importesQueCasam(Path raiz, String... proibidos)
            throws IOException {
        List<String> encontrados = new ArrayList<>();
        for (Path arquivo : arquivos(raiz)) {
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
        return encontrados;
    }

    private static List<Path> arquivos(Path raiz) throws IOException {
        try (Stream<Path> caminhos = Files.walk(raiz)) {
            return caminhos.filter(caminho -> caminho.toString().endsWith(".java")).sorted().toList();
        }
    }
}
