package com.platinumcoin.outbox.infra.config;

import com.platinumcoin.outbox.api.ArquivoRetorno;
import com.platinumcoin.outbox.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.outbox.domain.port.CanalArquivos;
import com.platinumcoin.outbox.domain.port.LeitorDeRetorno;
import com.platinumcoin.outbox.domain.port.PublicadorLancamento;
import com.platinumcoin.outbox.domain.port.RepositorioArquivoRetorno;
import com.platinumcoin.outbox.domain.port.RepositorioCiclo;
import com.platinumcoin.outbox.domain.port.RepositorioFatura;
import com.platinumcoin.outbox.domain.port.RepositorioOutbox;
import com.platinumcoin.outbox.domain.port.RepositorioTentativa;
import com.platinumcoin.outbox.domain.port.Transacao;
import com.platinumcoin.outbox.domain.usecase.AbrirFaturasUseCase;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;
import com.platinumcoin.outbox.domain.usecase.ColetarRetornoUseCase;
import com.platinumcoin.outbox.domain.usecase.EnviarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.FecharCicloUseCase;
import com.platinumcoin.outbox.domain.usecase.GerarRemessaUseCase;
import com.platinumcoin.outbox.domain.usecase.MontarCicloUseCase;
import com.platinumcoin.outbox.infra.canal.CanalArquivosSftp;
import com.platinumcoin.outbox.infra.consulta.EstadoDoMundo;
import com.platinumcoin.outbox.infra.persistence.ArmazenamentoArtefatoS3;
import com.platinumcoin.outbox.infra.persistence.PublicadorLancamentoSqs;
import com.platinumcoin.outbox.infra.persistence.RepositorioArquivoRetornoPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioCicloPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioFaturaPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioOutboxPostgres;
import com.platinumcoin.outbox.infra.persistence.RepositorioTentativaPostgres;
import com.platinumcoin.outbox.infra.persistence.TransacaoJdbc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * A fiação do servidor: as mesmas peças que o {@link com.platinumcoin.outbox.Main}
 * monta no construtor, montadas aqui para que o Spring as entregue aos
 * controllers.
 *
 * <p>Fica ao lado do {@link Ambiente} porque é a continuação dele: o
 * {@code Ambiente} responde "com que banco, com que fila, com que bucket e com
 * que parceiro", e este arquivo responde "que objetos falam com eles". Um
 * segundo lugar lendo variável de ambiente não existe, e é isso que mantém
 * console e HTTP apontando para o mesmo mundo.
 *
 * <p>É o único arquivo de {@code infra/} que conhece o Spring, e ele não decide
 * nada: não há {@code @Value}, não há perfil, não há condicional. Trocar o
 * container de injeção custaria este arquivo.
 */
@Configuration
public class Fiacao {

    /**
     * Quanto a coleta espera entre as duas leituras de atributos.
     *
     * <p>Um segundo porque quem clica o botão é gente: em produção seriam
     * minutos, e na suíte são milissegundos. O valor é do <b>chamador</b> desde
     * o step-09, justamente para que cada um escolha o seu.
     */
    private static final Duration QUIESCENCIA = Duration.ofSeconds(1);

    @Bean
    public Ambiente ambiente() {
        return Ambiente.doProcesso();
    }

    @Bean
    public Transacao.Fabrica transacoes(Ambiente ambiente) {
        return new TransacaoJdbc.Fabrica(ambiente.dados());
    }

    @Bean
    public RepositorioFatura faturas(Ambiente ambiente) {
        return new RepositorioFaturaPostgres(ambiente.dados());
    }

    @Bean
    public RepositorioTentativa tentativas(Ambiente ambiente) {
        return new RepositorioTentativaPostgres(ambiente.dados());
    }

    @Bean
    public RepositorioCiclo ciclos(Ambiente ambiente) {
        return new RepositorioCicloPostgres(ambiente.dados());
    }

    @Bean
    public RepositorioOutbox outbox(Ambiente ambiente) {
        return new RepositorioOutboxPostgres(ambiente.dados());
    }

    @Bean
    public RepositorioArquivoRetorno arquivosDeRetorno(Ambiente ambiente) {
        return new RepositorioArquivoRetornoPostgres(ambiente.dados());
    }

    @Bean
    public PublicadorLancamento publicador(Ambiente ambiente) {
        return new PublicadorLancamentoSqs(ambiente.sqs(), ambiente.urlDaFila());
    }

    @Bean
    public ArmazenamentoArtefato artefatos(Ambiente ambiente) {
        return new ArmazenamentoArtefatoS3(ambiente.s3(), ambiente.bucket());
    }

    @Bean
    public CanalArquivos canal(Ambiente ambiente) {
        return new CanalArquivosSftp(ambiente.sftp());
    }

    /** O parser do layout posicional, entregue ao domínio como porta — step-09. */
    @Bean
    public LeitorDeRetorno leitor() {
        return ArquivoRetorno::de;
    }

    @Bean
    public AbrirFaturasUseCase abrirFaturas(RepositorioFatura faturas,
                                            RepositorioTentativa tentativas) {
        return new AbrirFaturasUseCase(faturas, tentativas);
    }

    @Bean
    public MontarCicloUseCase montarCiclo(Transacao.Fabrica transacoes, RepositorioCiclo ciclos) {
        return new MontarCicloUseCase(transacoes, ciclos);
    }

    @Bean
    public GerarRemessaUseCase gerarRemessa(Transacao.Fabrica transacoes, RepositorioCiclo ciclos,
                                            RepositorioTentativa tentativas,
                                            RepositorioFatura faturas,
                                            ArmazenamentoArtefato artefatos) {
        return new GerarRemessaUseCase(transacoes, ciclos, tentativas, faturas, artefatos);
    }

    @Bean
    public EnviarRemessaUseCase enviarRemessa(Transacao.Fabrica transacoes, RepositorioCiclo ciclos,
                                              ArmazenamentoArtefato artefatos,
                                              CanalArquivos canal) {
        return new EnviarRemessaUseCase(transacoes, ciclos, artefatos, canal);
    }

    @Bean
    public AplicarRetornoUseCase aplicarRetorno(Transacao.Fabrica transacoes,
                                                RepositorioTentativa tentativas,
                                                RepositorioFatura faturas,
                                                RepositorioOutbox outbox) {
        return new AplicarRetornoUseCase(transacoes, tentativas, faturas, outbox);
    }

    @Bean
    public ColetarRetornoUseCase coletarRetorno(CanalArquivos canal,
                                                ArmazenamentoArtefato artefatos,
                                                RepositorioArquivoRetorno arquivosDeRetorno,
                                                LeitorDeRetorno leitor,
                                                AplicarRetornoUseCase aplicarRetorno) {
        return new ColetarRetornoUseCase(
                canal, artefatos, arquivosDeRetorno, leitor, aplicarRetorno, QUIESCENCIA);
    }

    @Bean
    public FecharCicloUseCase fecharCiclo(Transacao.Fabrica transacoes, RepositorioCiclo ciclos) {
        return new FecharCicloUseCase(transacoes, ciclos);
    }

    @Bean
    public EstadoDoMundo estadoDoMundo(Ambiente ambiente, CanalArquivos canal) {
        return new EstadoDoMundo(ambiente, canal);
    }
}
