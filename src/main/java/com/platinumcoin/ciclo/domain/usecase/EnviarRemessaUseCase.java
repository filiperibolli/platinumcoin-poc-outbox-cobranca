package com.platinumcoin.ciclo.domain.usecase;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.ciclo.domain.port.CanalArquivos;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.Transacao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transmite ao parceiro a remessa que o step-07 deixou gravada, e só então
 * registra que ela saiu.
 *
 * <p>A ordem é a decisão inteira:
 *
 * <pre>
 * get do artefato  →  put no parceiro  →  COMMIT (tentativas e ciclo)
 * </pre>
 *
 * <p>Lê o artefato em vez de regerar a remessa. Regerar seria correto — a
 * projeção é função pura do ciclo — e ainda assim errado: apagaria a fronteira
 * que o step-07 criou, e no dia em que a geração mudar, o que foi enviado e o
 * que foi arquivado divergiriam sem que nada acusasse.
 * <br>DECISÃO: artefato durável entre geração e transmissão — ver ADR-0003
 *
 * <p>A transição é do ciclo inteiro, num {@code UPDATE} só, porque um arquivo é
 * um evento: ou o parceiro recebeu a remessa, ou não recebeu. Não existe meia
 * transmissão a registrar.
 */
public final class EnviarRemessaUseCase {

    private static final Logger log = LoggerFactory.getLogger(EnviarRemessaUseCase.class);

    private final Transacao.Fabrica transacoes;
    private final RepositorioCiclo ciclos;
    private final ArmazenamentoArtefato artefatos;
    private final CanalArquivos canal;

    public EnviarRemessaUseCase(Transacao.Fabrica transacoes, RepositorioCiclo ciclos,
                                ArmazenamentoArtefato artefatos, CanalArquivos canal) {
        this.transacoes = transacoes;
        this.ciclos = ciclos;
        this.artefatos = artefatos;
        this.canal = canal;
    }

    /** @return quantas tentativas saíram no arquivo. */
    public int executar(String cicloId) {
        CicloCobranca ciclo = ciclos.buscar(cicloId)
                .orElseThrow(() -> new IllegalArgumentException("ciclo inexistente: " + cicloId));
        if (!ciclo.temRemessa()) {
            // Transmitir sem artefato seria inventar o arquivo aqui dentro, que
            // é exatamente o que a separação entre gerar e enviar impede.
            throw new IllegalStateException("ciclo sem remessa gerada: " + cicloId);
        }

        byte[] artefato = artefatos.get(ciclo.remessaChave());

        // DECISÃO: put no parceiro antes do COMMIT — a janela é conhecida e testada,
        // ver docs/steps/step-08.md e CrashDepoisDoPutTest
        String nomeNoParceiro = ChaveArtefato.nomeDaRemessaNoParceiro(ciclo);
        canal.enviar(nomeNoParceiro, artefato);
        // A janela A está aberta entre esta linha e o COMMIT abaixo: o parceiro
        // já tem o arquivo e o banco ainda não sabe.
        log.info("[envia]   {} {} entregue ao parceiro — {} bytes, ANTES do COMMIT",
                cicloId, nomeNoParceiro, artefato.length);

        try (Transacao tx = transacoes.abrir()) {
            int transmitidas = ciclos.registrarEnvio(tx, cicloId);
            tx.commit();
            log.info("[envia]   {} ENVIADO — {} tentativas SOLICITADO → ENVIADO_PARCEIRO",
                    cicloId, transmitidas);
            return transmitidas;
        }
    }
}
