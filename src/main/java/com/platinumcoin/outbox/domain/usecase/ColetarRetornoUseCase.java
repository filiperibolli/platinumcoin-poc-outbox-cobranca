package com.platinumcoin.outbox.domain.usecase;

import com.platinumcoin.outbox.domain.model.ChaveArtefato;
import com.platinumcoin.outbox.domain.model.Sha256;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.domain.port.LeitorDeRetorno;
import com.platinumcoin.outbox.domain.port.RepositorioArquivoRetorno;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Uma passada pelo diretório de retorno do parceiro.
 *
 * <p>O parceiro não avisa que o arquivo chegou, não avisa que terminou de
 * escrevê-lo e não garante um arquivo por ciclo. Cada uma dessas três ausências
 * virou um mecanismo aqui:
 *
 * <pre>
 * lista → quiescência (2 leituras) → baixa → arquiva → trailer
 *                                                        ├─ fecha     → aplica linha a linha
 *                                                        └─ não fecha → descarta, próxima passada
 * </pre>
 *
 * <p><b>Nada é marcado como "em processamento".</b> Cada execução é uma passada
 * inteira, e o que não passa numa é reavaliado na seguinte a partir do mesmo
 * estado — não há estado intermediário para vazar quando o processo morre no
 * meio. É a mesma propriedade do relay: a próxima passada recomeça do mundo, não
 * de um marcador.
 *
 * <p><b>Este use case não decide o estado de tentativa nenhuma.</b> Ele entrega
 * linhas a {@link AplicarRetornoUseCase}, e o {@code UPDATE} condicional do
 * step-03 continua sendo o dono da decisão — inclusive de reconhecer o que já
 * foi aplicado. É por isso que reprocessar um arquivo inteiro é seguro, e é por
 * isso que o hash pode ser <b>só</b> um atalho de custo.
 * <br>DECISÃO: hash curto-circuita o reprocessamento, não o substitui — ver step-09
 *
 * <p><b>A coleta não fecha o ciclo.</b> Ela não sabe se o parceiro terminou o
 * dia — ninguém sabe. Quem declara o dia encerrado é {@link FecharCicloUseCase},
 * por horário.
 */
public final class ColetarRetornoUseCase {

    /** O diretório combinado com o parceiro — contrato, como as posições dos campos. */
    private static final String DIRETORIO_RETORNO = "/retorno";

    private final CanalArquivos canal;
    private final ArmazenamentoArtefato artefatos;
    private final RepositorioArquivoRetorno arquivos;
    private final LeitorDeRetorno leitor;
    private final AplicarRetornoUseCase aplicar;
    private final Duration intervaloDeQuiescencia;

    /**
     * @param intervaloDeQuiescencia quanto se espera entre as duas leituras de
     *        atributos. É parâmetro, e não constante: milissegundos no teste,
     *        minutos em produção. Uma constante forçaria o teste a esperar de
     *        verdade, e um teste que dorme minutos é um teste que ninguém roda.
     */
    public ColetarRetornoUseCase(CanalArquivos canal,
                                 ArmazenamentoArtefato artefatos,
                                 RepositorioArquivoRetorno arquivos,
                                 LeitorDeRetorno leitor,
                                 AplicarRetornoUseCase aplicar,
                                 Duration intervaloDeQuiescencia) {
        this.canal = canal;
        this.artefatos = artefatos;
        this.arquivos = arquivos;
        this.leitor = leitor;
        this.aplicar = aplicar;
        this.intervaloDeQuiescencia = intervaloDeQuiescencia;
    }

    /** O que aconteceu com um dos arquivos vistos nesta passada. */
    public record Resultado(String nome, Desfecho desfecho, int linhas, int aplicadas) {
    }

    public enum Desfecho {
        /** Cresceu entre as duas leituras: o parceiro ainda está escrevendo. */
        EM_ESCRITA,
        /** Bytes idênticos aos de um arquivo já aplicado: nada a reprocessar. */
        REPETIDO,
        /** Parou de crescer, mas o trailer não bate: descartado inteiro. */
        INCOMPLETO,
        /** Fechou, e cada linha foi entregue ao aplicador. */
        APLICADO
    }

    /** Uma passada. Um resultado por arquivo visto — inclusive os que não passaram. */
    public List<Resultado> executar() {
        List<Resultado> passada = new ArrayList<>();
        for (String caminho : canal.listar(DIRETORIO_RETORNO)) {
            passada.add(coletar(caminho));
        }
        return List.copyOf(passada);
    }

    private Resultado coletar(String caminho) {
        String nome = caminho.substring(caminho.lastIndexOf('/') + 1);
        if (!quiesceu(caminho)) {
            return new Resultado(nome, Desfecho.EM_ESCRITA, 0, 0);
        }

        byte[] conteudo = canal.baixar(caminho);
        String sha256 = Sha256.de(conteudo);
        if (arquivos.jaAplicado(sha256)) {
            // Exatamente os mesmos bytes de um arquivo já aplicado: não há o que
            // arquivar de novo, nem o que parsear, nem o que aplicar. É o atalho
            // inteiro — e ele só cobre este caso. Um reenvio com uma linha a
            // mais tem hash diferente e passa direto daqui, porque quem sabe o
            // que já foi aplicado é o UPDATE condicional, não esta tabela.
            return new Resultado(nome, Desfecho.REPETIDO, 0, 0);
        }

        LeitorDeRetorno.Retorno retorno = leitor.ler(nome, conteudo);

        // ANTES da validação, de propósito: o arquivo que não fecha é justamente
        // o que alguém vai querer olhar.
        artefatos.put(
                ChaveArtefato.doRetorno(retorno.banco(), retorno.dataRef(), nome), conteudo);

        if (!retorno.completo()) {
            // DECISÃO: descartar o arquivo incompleto INTEIRO, em vez de aplicar
            // o que dá — meia aplicação é indistinguível de um retorno legítimo
            // menor, e o fechamento do ciclo transformaria o resto em
            // SEM_RETORNO, afirmando silêncio onde havia ruído. Ver step-09.
            return new Resultado(nome, Desfecho.INCOMPLETO, retorno.quantidadeDeLinhas(), 0);
        }

        int aplicadas = retorno.aplicarCom(aplicar);

        // DEPOIS de aplicar, e não antes: gravar o hash primeiro transformaria
        // uma morte no meio da aplicação em trabalho perdido, porque a próxima
        // passada curto-circuitaria o arquivo que ainda tinha o que aplicar.
        arquivos.registrar(nome, sha256, retorno.cicloId(), retorno.quantidadeDeLinhas());

        return new Resultado(nome, Desfecho.APLICADO, retorno.quantidadeDeLinhas(), aplicadas);
    }

    /**
     * Tamanho e mtime iguais em duas leituras separadas pelo intervalo.
     *
     * <p>Quem cresce entre elas não é baixado. Baixá-lo daria um arquivo válido
     * do ponto de vista do SFTP e cortado do ponto de vista do negócio — e o
     * trailer o descartaria de qualquer forma, só que depois de uma
     * transferência inteira. A quiescência é o que evita pagar por ela.
     * <br>DECISÃO: quiescência por tamanho E mtime — ver docs/steps/step-09.md
     */
    private boolean quiesceu(String caminho) {
        Optional<CanalArquivos.Atributos> antes = canal.atributos(caminho);
        if (antes.isEmpty()) {
            return false;
        }
        esperar();
        return antes.equals(canal.atributos(caminho));
    }

    private void esperar() {
        try {
            Thread.sleep(intervaloDeQuiescencia);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("coleta interrompida durante a quiescência", e);
        }
    }
}
