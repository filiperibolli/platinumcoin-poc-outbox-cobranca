package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.model.Remessa;
import com.platinumcoin.ciclo.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.ciclo.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.ciclo.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static com.platinumcoin.ciclo.Cenario.DATA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-07: o artefato é função pura do ciclo, e o endereço dele também.
 *
 * <p>É o que faz o {@code put} antes do {@code COMMIT} não custar nada: gerar
 * de novo escreve os mesmos bytes na mesma chave, então a segunda gravação é
 * uma sobrescrita idêntica e não um objeto novo. Sem isso, o bucket viraria um
 * log de tentativas e duas cópias do mesmo arquivo passariam a discordar sem
 * que ninguém soubesse qual o parceiro processou.
 *
 * <p>A comparação é dos <b>bytes inteiros</b>, e não do tamanho nem do hash: é
 * o arquivo que precisa ser o mesmo, não um resumo dele.
 * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
 */
class RemessaDeterministicaTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-DET";

    private RepositorioCiclo ciclos;
    private ArmazenamentoArtefato artefatos;
    private GerarRemessaUseCase gerar;

    @BeforeEach
    void cicloMontado() throws SQLException {
        limparTabelas();
        ciclos = new RepositorioCicloPostgres(dados());
        artefatos = artefatos();

        // Inseridas fora de ordem, e com valores diferentes, de propósito: a
        // remessa não pode depender de quem chegou primeiro na tabela, e um
        // cenário de valor único não distinguiria o campo do preenchimento.
        Cenario.tentativaAberta("FAT-C", "89.90");
        Cenario.tentativaAberta("FAT-A", "100.00");
        Cenario.tentativaAberta("FAT-B", "250.50");
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar(CICLO, BANCO, DATA);

        gerar = new GerarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()),
                ciclos,
                new RepositorioTentativaPostgres(dados()),
                new RepositorioFaturaPostgres(dados()),
                artefatos);
    }

    @Test
    @DisplayName("duas gerações do mesmo ciclo: mesmos bytes, mesma chave, mesmo objeto no S3")
    void duasGeracoesProduzemOMesmoArtefato() {
        Remessa primeira = gerar.executar(CICLO);
        byte[] depoisDoPrimeiroPut = artefatos.get(primeira.chave());

        Remessa segunda = gerar.executar(CICLO);
        byte[] depoisDoSegundoPut = artefatos.get(segunda.chave());

        assertEquals(primeira.chave(), segunda.chave(),
                "chave derivada do ciclo: a segunda geração endereça o mesmo objeto");
        assertArrayEquals(primeira.bytes(), segunda.bytes(),
                "os bytes inteiros, não o tamanho nem o hash");
        assertArrayEquals(depoisDoPrimeiroPut, depoisDoSegundoPut,
                "o segundo put é uma sobrescrita idêntica — é isso que o torna barato");
        assertArrayEquals(primeira.bytes(), depoisDoSegundoPut,
                "e o que está no S3 é o que a projeção produziu");
    }

    @Test
    @DisplayName("a chave é derivada do ciclo: banco, data e id — nada de uuid nem de relógio")
    void chaveDerivadaDoCiclo() {
        assertEquals(new ChaveArtefato("remessa/341/20260830/CICLO-DET.rem"),
                gerar.executar(CICLO).chave());
    }

    @Test
    @DisplayName("o layout é posicional: header, detalhes ordenados por id, trailer")
    void layoutPosicionalDeLarguraFixa() {
        Remessa remessa = gerar.executar(CICLO);

        // O \s no fim da primeira linha é um espaço: text block corta espaço
        // final, e o preenchimento do cicloId faz parte do arquivo.
        assertEquals("""
                034120260830CICLO-DET      \s
                1FAT-A-T1        FAT-A           000000000010000
                1FAT-B-T1        FAT-B           000000000025050
                1FAT-C-T1        FAT-C           000000000008990
                9000003
                """, remessa.conteudo());
    }

    @Test
    @DisplayName("o trailer traz a contagem dos registros de detalhe, e ela confere com as linhas")
    void trailerContaOsRegistrosDeDetalhe() {
        List<String> linhas = gerar.executar(CICLO).conteudo().lines().toList();

        long detalhes = linhas.stream().filter(linha -> linha.startsWith("1")).count();
        String trailer = linhas.get(linhas.size() - 1);

        assertEquals(3, detalhes, "três tentativas no ciclo, três linhas de detalhe");
        assertEquals(detalhes, Long.parseLong(trailer.substring(1, 7)),
                "é o trailer que deixa o parceiro decidir se o arquivo chegou inteiro");
    }

    @Test
    @DisplayName("o ciclo guarda a chave e o sha256 do que foi gravado")
    void cicloGuardaChaveEHashDoArtefato() {
        Remessa remessa = gerar.executar(CICLO);

        CicloCobranca ciclo = ciclos.buscar(CICLO).orElseThrow();

        assertEquals(remessa.chave(), ciclo.remessaChave());
        assertEquals(remessa.sha256(), ciclo.remessaSha256());
        // Conferido contra o objeto de fato gravado, e não só contra o que a
        // projeção diz ter produzido: é essa comparação que responde "o
        // artefato mudou?" em produção, sem baixar nada.
        assertEquals(sha256(artefatos.get(remessa.chave())), ciclo.remessaSha256());
    }

    private static String sha256(byte[] conteudo) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(conteudo));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JRE sem SHA-256", e);
        }
    }
}
