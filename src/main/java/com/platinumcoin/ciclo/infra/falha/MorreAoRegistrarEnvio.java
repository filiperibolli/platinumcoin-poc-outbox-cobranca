package com.platinumcoin.ciclo.infra.falha;

import com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia;
import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;
import com.platinumcoin.ciclo.domain.port.RepositorioCiclo;
import com.platinumcoin.ciclo.domain.port.Transacao;

import java.time.LocalDate;
import java.util.Optional;

/**
 * O ciclo que morre entre o {@code put} no parceiro e o {@code COMMIT} que
 * registra que ele aconteceu.
 *
 * <p>O arquivo já está do outro lado do fio quando este método é chamado — é o
 * que torna a janela do step-08 visível: o parceiro tem a remessa, e para o
 * banco o ciclo continua {@code MONTADO} com as tentativas em
 * {@code SOLICITADO}. A reexecução transmite de novo, e o nome determinístico
 * faz a segunda entrega sobrescrever a primeira em vez de duplicá-la.
 *
 * <p>Decorador pelo mesmo motivo de {@link MorreAoMarcarPublicado}, e com o
 * mesmo recorte do decorador de {@code CrashDepoisDoPutTest}.
 */
public final class MorreAoRegistrarEnvio implements RepositorioCiclo {

    private final RepositorioCiclo real;
    private final FalhasArmadas falhas;

    public MorreAoRegistrarEnvio(RepositorioCiclo real, FalhasArmadas falhas) {
        this.real = real;
        this.falhas = falhas;
    }

    @Override
    public void criar(Transacao tx, CicloCobranca ciclo) {
        real.criar(tx, ciclo);
    }

    @Override
    public Optional<CicloCobranca> buscar(String cicloId) {
        return real.buscar(cicloId);
    }

    @Override
    public Optional<CicloCobranca> buscarPor(String banco, LocalDate dataRef) {
        return real.buscarPor(banco, dataRef);
    }

    @Override
    public int atribuirTentativasAbertas(Transacao tx, CicloCobranca ciclo) {
        return real.atribuirTentativasAbertas(tx, ciclo);
    }

    @Override
    public void registrarRemessa(Transacao tx, String cicloId,
                                 ChaveArtefato chave, String sha256) {
        real.registrarRemessa(tx, cicloId, chave, sha256);
    }

    @Override
    public int registrarEnvio(Transacao tx, String cicloId) {
        if (falhas.dispara(FalhasArmadas.Falha.CRASH_ENVIO)) {
            throw new FalhaDePersistencia(FalhasArmadas.Falha.CRASH_ENVIO.mensagem());
        }
        return real.registrarEnvio(tx, cicloId);
    }

    @Override
    public int fechar(Transacao tx, String cicloId) {
        return real.fechar(tx, cicloId);
    }
}
