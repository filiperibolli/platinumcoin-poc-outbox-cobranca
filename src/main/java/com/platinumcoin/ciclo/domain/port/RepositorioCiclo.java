package com.platinumcoin.ciclo.domain.port;

import com.platinumcoin.ciclo.domain.model.ChaveArtefato;
import com.platinumcoin.ciclo.domain.model.CicloCobranca;

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
     * Registra no ciclo o artefato de remessa já gravado: onde ele está e que
     * bytes ele tinha.
     *
     * <p>É o {@code COMMIT} que fecha a ordem {@code projeta → put → COMMIT}.
     * Sem guarda de status, e por isso mesmo: a chave é determinística e o
     * conteúdo é função pura do ciclo, então uma segunda geração escreve
     * exatamente os mesmos dois valores. Guardar contra a reexecução seria
     * proteger contra o caso que este step existe para mostrar como inofensivo.
     * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
     */
    void registrarRemessa(Transacao tx, String cicloId, ChaveArtefato chave, String sha256);

    /**
     * Registra que a remessa foi transmitida: as tentativas {@code SOLICITADO}
     * do ciclo viram {@code ENVIADO_PARCEIRO} e o ciclo {@code MONTADO} vira
     * {@code ENVIADO}.
     *
     * <p>Duas transições, uma transação — um arquivo é um evento: ou o parceiro
     * recebeu a remessa, ou não recebeu. As guardas por status são o que torna
     * a retransmissão inócua: quem já está {@code ENVIADO_PARCEIRO} não é
     * tocado, e a segunda passada afeta zero linhas em vez de reabrir estado
     * que o retorno já resolveu.
     *
     * @return quantas tentativas saíram no arquivo.
     */
    int registrarEnvio(Transacao tx, String cicloId);

    /**
     * Fecha o ciclo: o que continua {@code ENVIADO_PARCEIRO} vira
     * {@code SEM_RETORNO}.
     *
     * @return quantas tentativas ficaram sem retorno.
     */
    int fechar(Transacao tx, String cicloId);
}
