package com.platinumcoin.outbox;

import com.platinumcoin.outbox.domain.model.Remessa;
import com.platinumcoin.outbox.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static com.platinumcoin.outbox.Cenario.DATA;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-02: a remessa é trabalho derivado do ciclo. Mesmo ciclo, mesmos bytes —
 * gerada agora, amanhã, ou depois de uma falha de transmissão.
 *
 * <p>Sem isso, retransmitir vira uma decisão de risco: duas cópias do mesmo
 * arquivo discordam e ninguém sabe qual o parceiro processou. A comparação é
 * das Strings inteiras, e não de tamanho ou hash — é o arquivo que precisa ser
 * o mesmo, não um resumo dele.
 */
class TrabalhoDerivadoDeterministicoTest extends AmbienteDeTeste {

    private GerarRemessaUseCase gerar;

    @BeforeEach
    void montarUmCiclo() throws SQLException {
        limparTabelas();
        RepositorioCicloPostgres ciclos = new RepositorioCicloPostgres(dados());
        RepositorioTentativaPostgres tentativas = new RepositorioTentativaPostgres(dados());

        // Inseridas fora de ordem de propósito: a remessa não pode depender de
        // quem chegou primeiro na tabela.
        Cenario.tentativaAberta("FAT-C");
        Cenario.tentativaAberta("FAT-A");
        Cenario.tentativaAberta("FAT-B");
        new MontarCicloUseCase(new TransacaoJdbc.Fabrica(dados()), ciclos)
                .executar("CICLO-1", BANCO, DATA);

        gerar = new GerarRemessaUseCase(ciclos, tentativas);
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
    @DisplayName("a remessa sai ordenada por id, no formato posicional de três campos")
    void formatoPosicionalOrdenadoPorId() {
        Remessa remessa = gerar.executar("CICLO-1");

        assertEquals("""
                FAT-A-T1            FAT-A               001
                FAT-B-T1            FAT-B               001
                FAT-C-T1            FAT-C               001
                """, remessa.conteudo());
    }
}
