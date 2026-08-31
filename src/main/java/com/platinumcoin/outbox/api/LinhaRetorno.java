package com.platinumcoin.outbox.api;

import com.platinumcoin.outbox.domain.model.TentativaDebito;
import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;

/**
 * Uma linha do arquivo de retorno do parceiro: o que ele afirma sobre uma
 * tentativa.
 *
 * <p>É o <b>adaptador de entrada</b>. A linha sabe se traduzir numa chamada ao
 * use case ({@link #aplicarCom}), e é por isso que o domínio não precisa
 * conhecê-la: a seta é {@code api → domain}, nunca o contrário — a mesma
 * fronteira que {@code FundacaoTest.dominioIsolado} verifica.
 *
 * <p>Quem produz estas linhas — o coletor que baixa o arquivo e o interpreta —
 * não tem classe neste repositório: SFTP e CNAB 240 são I/O e formato, e estão
 * fora de escopo. O step-03 começa depois que o arquivo já foi lido.
 */
public record LinhaRetorno(String tentativaId,
                           TentativaDebito.Status resultado,
                           TentativaDebito.MotivoNaoPago motivo) {

    public LinhaRetorno {
        if (tentativaId == null || tentativaId.isBlank()) {
            throw new IllegalArgumentException("linha de retorno sem tentativa");
        }
        if (resultado == null || !resultado.vemDoRetorno()) {
            throw new IllegalArgumentException(
                    "linha de retorno da tentativa " + tentativaId
                            + " com desfecho que o parceiro não informa: " + resultado);
        }
        TentativaDebito.exigirMotivoCoerente("linha de retorno da tentativa " + tentativaId,
                resultado, motivo);
    }

    /** O parceiro confirmou o débito. */
    public static LinhaRetorno paga(String tentativaId) {
        return new LinhaRetorno(tentativaId, TentativaDebito.Status.PAGO, null);
    }

    /** O parceiro recusou o débito, e disse por quê. */
    public static LinhaRetorno naoPaga(String tentativaId,
                                       TentativaDebito.MotivoNaoPago motivo) {
        return new LinhaRetorno(tentativaId, TentativaDebito.Status.NAO_PAGO, motivo);
    }

    /** A linha veio, mas não deu para processá-la. */
    public static LinhaRetorno comErro(String tentativaId) {
        return new LinhaRetorno(tentativaId, TentativaDebito.Status.ERRO, null);
    }

    /** Entrega a linha ao domínio, em domínio puro. */
    public AplicarRetornoUseCase.Resultado aplicarCom(AplicarRetornoUseCase aplicar) {
        return aplicar.executar(tentativaId, resultado, motivo);
    }
}
