package com.platinumcoin.ciclo.domain.usecase;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.port.ArmazenamentoArtefato;
import com.platinumcoin.ciclo.domain.port.CanalArquivos;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.Transacao;

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
        canal.enviar(ChaveArtefato.nomeDaRemessaNoParceiro(ciclo), artefato);

        try (Transacao tx = transacoes.abrir()) {
            int transmitidas = ciclos.registrarEnvio(tx, cicloId);
            tx.commit();
            return transmitidas;
        }
    }
}
