package com.platinumcoin.ciclo;

import com.platinumcoin.ciclo.domain.model.Remessa;
import com.platinumcoin.ciclo.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.ciclo.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.ciclo.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.ciclo.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.ciclo.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static com.platinumcoin.ciclo.Cenario.BANCO;
import static com.platinumcoin.ciclo.Cenario.DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-02: a remessa é trabalho derivado do ciclo. Mesmo ciclo, mesmos bytes —
 * gerada agora, amanhã, ou depois de uma falha de transmissão.
 *
 * <p>Sem isso, retransmitir vira uma decisão de risco: duas cópias do mesmo
 * arquivo discordam e ninguém sabe qual o parceiro processou. A comparação é
 * das Strings inteiras, e não de tamanho ou hash — é o arquivo que precisa ser
 * o mesmo, não um resumo dele.
 *
 * <p>Aqui a asserção é sobre a <b>projeção</b>: o que o ciclo produz, com a
 * ordem vindo do repositório. O que a gravação faz com ela — chave
 * determinística, sobrescrita idêntica, órfão inofensivo — é o step-07, em
 * {@code RemessaDeterministicaTest} e {@code RemessaSobreviveAReexecucaoTest}.
 */
class TrabalhoDerivadoDeterministicoTest extends AmbienteDeTeste {

    private GerarRemessaUseCase gerar;

    @BeforeEach
    void montarUmCiclo() throws SQLException {
        limparTabelas();
        RepositorioCicloPostgres ciclos = new RepositorioCicloPostgres(dados());
        RepositorioTentativaPostgres tentativas = new RepositorioTentativaPostgres(dados());
        RepositorioFaturaPostgres faturas = new RepositorioFaturaPostgres(dados());

        // Inseridas fora de ordem de propósito: a remessa não pode depender de
        // quem chegou primeiro na tabela.
        Cenario.tentativaAberta("FAT-C");
        Cenario.tentativaAberta("FAT-A");
        Cenario.tentativaAberta("FAT-B");
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar("CICLO-1", BANCO, DATA);

        gerar = new GerarRemessaUseCase(
                new TransacaoJdbc.Fabrica(dados()), ciclos, tentativas, faturas, artefatos());
    }

    @Test
    @DisplayName("duas gerações do mesmo ciclo produzem exatamente os mesmos bytes")
    void duasGeracoesSaoIdenticas() {
        Remessa primeira = gerar.executar("CICLO-1");
        Remessa segunda = gerar.executar("CICLO-1");

        assertEquals(primeira.conteudo(), segunda.conteudo());
        assertEquals("CICLO-1", primeira.cicloId());
    }

    @Test
    @DisplayName("a remessa sai ordenada por id, entre o header e o trailer")
    void formatoPosicionalOrdenadoPorId() {
        Remessa remessa = gerar.executar("CICLO-1");

        // O \s no fim da primeira linha é um espaço: text block corta espaço
        // final, e o preenchimento do cicloId faz parte do arquivo.
        assertEquals("""
                034120260830CICLO-1        \s
                1FAT-A-T1        FAT-A           000000000010000
                1FAT-B-T1        FAT-B           000000000010000
                1FAT-C-T1        FAT-C           000000000010000
                9000003
                """, remessa.conteudo());
    }
}
