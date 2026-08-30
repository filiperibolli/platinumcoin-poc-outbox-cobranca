package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.model.CicloCobranca;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Acesso ao ciclo de cobrança — a escrita que importa.
 *
 * <p>Tudo o que vem depois do ciclo (remessa, retorno, fechamento, publicação) é
 * trabalho derivado, reconstruível a partir dele. Por isso a montagem tem porta
 * própria, e não é um método perdido no meio das operações de retorno.
 */
public interface RepositorioCiclo {

    /**
     * Cria o ciclo. Falha com {@code FalhaDePersistencia} se já existir um ciclo
     * para o mesmo banco e data — a constraint {@code ciclo_um_por_banco_e_data}
     * é o que torna a reexecução segura por construção, em vez de depender de
     * uma consulta prévia que perderia a corrida contra outro processo.
     * <br>DECISÃO: idempotência por constraint, não por consulta prévia — ver step-02
     */
    void criar(Transacao tx, CicloCobranca ciclo);

    Optional<CicloCobranca> buscar(String cicloId);

    Optional<CicloCobranca> buscarPor(String banco, LocalDate dataRef);

    /**
     * Atribui ao ciclo todas as tentativas {@code ABERTO} do recorte
     * (banco + data de referência), levando-as a {@code SOLICITADO}.
     *
     * @return quantas tentativas entraram no ciclo.
     */
    int atribuirTentativasAbertas(Transacao tx, CicloCobranca ciclo);

    /**
     * Fecha o ciclo: o que continua {@code ENVIADO_PARCEIRO} vira
     * {@code SEM_RETORNO}.
     *
     * @return quantas tentativas ficaram sem retorno.
     */
    int fechar(Transacao tx, String cicloId);
}
