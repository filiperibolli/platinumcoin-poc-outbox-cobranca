package com.platinumcoin.outbox;

import com.platinumcoin.outbox.api.LinhaRetorno;
import com.platinumcoin.outbox.domain.model.CicloCobranca;
import com.platinumcoin.outbox.domain.model.RegistroOutbox;
import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.usecase.ColetarRetornoUseCase;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import static com.platinumcoin.outbox.Cenario.BANCO;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * step-09: o que o hash cobre, e o que continua sendo trabalho do
 * {@code UPDATE} condicional.
 *
 * <p>O {@code sha256} do conteúdo é gravado com {@code UNIQUE}, e um reenvio
 * byte-idêntico é reconhecido e curto-circuitado sem baixar sentido nenhum do
 * arquivo. <b>Isto é um atalho de custo, não a garantia de idempotência.</b>
 *
 * <p>A garantia é, e continua sendo, o {@code UPDATE ... WHERE status =
 * 'ENVIADO_PARCEIRO'} do step-03. O hash só cobre "exatamente os mesmos bytes";
 * um reenvio com uma linha a mais tem hash diferente e passa direto — e está
 * <b>certo</b> que passe, porque é o {@code UPDATE} que sabe o que já foi
 * aplicado. Um projeto que confundisse os dois trocaria uma garantia por uma
 * otimização, e só descobriria no dia em que o parceiro mudasse o espaçamento.
 *
 * <p>Os dois testes abaixo são as duas metades dessa frase.
 */
class ReenvioDeRetornoTest extends AmbienteDeTeste {

    private static final String CICLO = "CICLO-REENVIO";
    /** Data própria: o nome e a chave do arquivo incluem a data. */
    private static final LocalDate DATA = LocalDate.of(2026, 10, 26);

    private DiretorioDoParceiro parceiro;
    private RepositorioTentativa tentativas;
    private CicloCobranca ciclo;
    private String caminho;

    @BeforeEach
    void primeiraParteJaAplicada() throws SQLException {
        limparTabelas();
        parceiro = new DiretorioDoParceiro(ambiente().sftp());
        parceiro.limpar(DIRETORIO_RETORNO);
        tentativas = new RepositorioTentativaPostgres(dados());

        Cenario.tentativaAberta("FAT-1", 1, BANCO, DATA);
        Cenario.tentativaAberta("FAT-2", 1, BANCO, DATA);
        Cenario.cicloTransmitido(CICLO, DATA);
        ciclo = new RepositorioCicloPostgres(dados()).buscar(CICLO).orElseThrow();
        caminho = DIRETORIO_RETORNO + "/" + RetornoDoParceiro.nome(ciclo);

        parceiro.escrever(caminho,
                RetornoDoParceiro.arquivo(ciclo, List.of(LinhaRetorno.paga("FAT-1-T1"))));
        assertEquals(ColetarRetornoUseCase.Desfecho.APLICADO, umaPassada().desfecho());
    }

    @Test
    @DisplayName("o mesmo arquivo na passada seguinte é reconhecido pelos bytes e não é reprocessado")
    void reenvioByteIdenticoEhCurtoCircuitado() {
        // Nada foi apagado do diretório do parceiro — de propósito. Toda passada
        // vê o arquivo de novo, e é o hash que o reconhece.
        ColetarRetornoUseCase.Resultado segunda = umaPassada();

        assertEquals(ColetarRetornoUseCase.Desfecho.REPETIDO, segunda.desfecho());
        assertEquals(0, segunda.linhas(),
                "curto-circuito de verdade: o arquivo nem chegou a ser interpretado");
        assertEquals(1, lancamentos().size(), "e o outbox continua com a linha de FAT-1, só ela");
    }

    @Test
    @DisplayName("reenvio com uma linha a mais tem outro hash, passa direto, e quem decide é o UPDATE")
    void reenvioComLinhaAMaisEhDecididoPeloUpdateCondicional() {
        // O parceiro reenviou o arquivo do dia, agora com as duas tentativas.
        // Mesmo nome, outro conteúdo — e por isso outro hash.
        parceiro.escrever(caminho, RetornoDoParceiro.arquivo(ciclo,
                List.of(LinhaRetorno.paga("FAT-1-T1"), LinhaRetorno.paga("FAT-2-T1"))));

        ColetarRetornoUseCase.Resultado segunda = umaPassada();

        assertEquals(ColetarRetornoUseCase.Desfecho.APLICADO, segunda.desfecho(),
                "o hash não reconhece este arquivo, e está certo que não reconheça");
        assertEquals(2, segunda.linhas());
        assertEquals(1, segunda.aplicadas(),
                "das duas linhas, uma mudou estado: FAT-1 já estava PAGO e o UPDATE"
                        + " condicional afetou zero linhas — que é o caso normal de um"
                        + " reprocessamento, não erro");

        assertEquals(List.of(TentativaDebito.Status.PAGO, TentativaDebito.Status.PAGO),
                tentativas.doCiclo(CICLO).stream().map(TentativaDebito::status).toList());
        assertEquals(List.of("FAT-1", "FAT-2"), lancamentos().stream()
                        .map(registro -> registro.lancamento().faturaId()).sorted().toList(),
                "um lançamento por fatura, e nenhum a mais: a invariante do projeto"
                        + " não depende do hash para valer");
    }

    private ColetarRetornoUseCase.Resultado umaPassada() {
        List<ColetarRetornoUseCase.Resultado> passada = coletaCom(canal()).executar();

        assertEquals(1, passada.size(), "um arquivo no diretório, um resultado");
        return passada.get(0);
    }

    private List<RegistroOutbox> lancamentos() {
        return new RepositorioOutboxPostgres(dados()).pendentes(10);
    }
}
