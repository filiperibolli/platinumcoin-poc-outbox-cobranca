package com.platinumcoin.outbox.domain.port;

/**
 * Os arquivos de retorno já baixados e aplicados por inteiro, identificados
 * pelos bytes.
 *
 * <p>É um <b>atalho de custo</b>, e a porta diz isso no formato: só duas
 * operações, "já vi estes bytes?" e "vi estes bytes". Não há consulta por nome,
 * por ciclo ou por data, porque nenhuma decisão do domínio depende disso —
 * quem decide o estado de uma tentativa é o {@code UPDATE} condicional do
 * step-03, e ele não pergunta nada a esta tabela.
 * <br>DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
 *
 * <p>Os parâmetros de {@link #registrar} são escalares, e não um objeto, porque
 * o objeto que os carrega é {@code api.ArquivoRetorno} — adaptador de entrada,
 * do lado de fora da seta {@code api → domain}. Uma porta do domínio que o
 * recebesse inverteria a dependência que o projeto existe para manter.
 */
public interface RepositorioArquivoRetorno {

    /** Se estes bytes exatos já foram aplicados numa passada anterior. */
    boolean jaAplicado(String sha256);

    /**
     * Registra que o arquivo foi aplicado por inteiro.
     *
     * <p>Chamado <b>depois</b> de as linhas terem sido aplicadas. Antes, o
     * processo que morresse no meio da aplicação deixaria o hash gravado e o
     * trabalho pela metade — e a próxima passada curto-circuitaria justamente o
     * arquivo que ainda tinha o que aplicar.
     */
    void registrar(String nome, String sha256, String cicloId, int linhas);
}
