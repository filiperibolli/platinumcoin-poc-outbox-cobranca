package com.platinumcoin.ciclo.infra.falha;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.model.LancamentoContabil;
import com.platinumcoin.ciclo.domain.model.RegistroOutbox;
import com.platinumcoin.ciclo.domain.port.RepositorioOutbox;
import com.platinumcoin.ciclo.domain.port.Transacao;

import java.util.List;

/**
 * O outbox que morre no instante exato em que a duplicata nasce: depois do
 * {@code send}, antes do {@code UPDATE}.
 *
 * <p>É o mesmo recorte do decorador de {@code CrashDoRelayTest}, promovido de
 * teste a botão. Um decorador, e não um {@code if (simularCrash)} dentro de
 * {@code PublicarOutboxUseCase}: o use case é justamente o código que se quer
 * olhar, e uma linha lá dentro que só existe para a demonstração estragaria a
 * leitura.
 * <br>DECISÃO: as falhas provocadas são decoradores da porta — ver docs/steps/step-11.md
 *
 * <p>Só entra na fiação do <b>servidor</b> ({@code infra/config/Fiacao}). O
 * {@code Main} de console continua recebendo o repositório nu, e o cenário do
 * step-06 continua provocando o seu crash com o decorador dele.
 */
public final class MorreAoMarcarPublicado implements RepositorioOutbox {

    private final RepositorioOutbox real;
    private final FalhasArmadas falhas;

    public MorreAoMarcarPublicado(RepositorioOutbox real, FalhasArmadas falhas) {
        this.real = real;
        this.falhas = falhas;
    }

    @Override
    public void inserir(Transacao tx, LancamentoContabil lancamento) {
        real.inserir(tx, lancamento);
    }

    @Override
    public List<RegistroOutbox> pendentes(int limite) {
        return real.pendentes(limite);
    }

    @Override
    public int marcarPublicado(long registroId) {
        if (falhas.dispara(FalhasArmadas.Falha.CRASH_RELAY)) {
            // A mensagem já saiu — quem chama este método é o relay, depois do
            // send. A linha continua PENDENTE, e é isso que autoriza a próxima
            // passada a republicar a mesma chaveDedup.
            throw new FalhaDePersistencia(FalhasArmadas.Falha.CRASH_RELAY.mensagem());
        }
        return real.marcarPublicado(registroId);
    }
}
