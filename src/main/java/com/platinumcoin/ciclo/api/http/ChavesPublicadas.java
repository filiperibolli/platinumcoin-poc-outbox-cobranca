package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.domain.model.LancamentoContabil;
import com.platinumcoin.ciclo.domain.port.PublicadorLancamento;

import java.util.ArrayList;
import java.util.List;

/**
 * O publicador de verdade, anotando as chaves de dedup que passaram por ele.
 *
 * <p>É o mesmo recurso que o {@code Main} usa para narrar o instante do
 * {@code send}: quem sabe o que <b>saiu</b> é quem publica, não quem lê a
 * tabela depois. Uma resposta montada a partir do outbox diria o que estava
 * pendente antes da passada, que é outra afirmação — e mentiria justamente na
 * passada em que o relay morre no meio.
 *
 * <p>A chave é anotada <b>depois</b> do envio, e por isso a lista conta o que a
 * fila recebeu, mesmo quando a linha não chegou a ser marcada.
 */
public final class ChavesPublicadas implements PublicadorLancamento {

    private final PublicadorLancamento real;
    private final List<String> chaves = new ArrayList<>();

    public ChavesPublicadas(PublicadorLancamento real) {
        this.real = real;
    }

    @Override
    public String publicar(LancamentoContabil lancamento) {
        String id = real.publicar(lancamento);
        chaves.add(lancamento.chaveDedup());
        return id;
    }

    public List<String> chaves() {
        return List.copyOf(chaves);
    }
}
