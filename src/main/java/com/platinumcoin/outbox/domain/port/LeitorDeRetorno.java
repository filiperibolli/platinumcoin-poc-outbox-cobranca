package com.platinumcoin.outbox.domain.port;

import com.platinumcoin.outbox.domain.usecase.AplicarRetornoUseCase;

import java.time.LocalDate;

/**
 * Como bytes viram um arquivo de retorno interpretado.
 *
 * <p>É a porta que mantém a seta {@code api → domain} de pé. Quem sabe posição
 * de campo, largura de coluna e ordem de registro é {@code api.ArquivoRetorno};
 * o coletor sabe apenas que existe alguém capaz de responder três perguntas
 * sobre um punhado de bytes: de que recorte ele é, se fecha, e o que ele afirma.
 *
 * <p>Sem esta porta, {@code ColetarRetornoUseCase} importaria {@code api} e a
 * dependência apontaria para o lado errado — que é exatamente o que
 * {@code FundacaoTest.dominioIsolado} recusa.
 */
@FunctionalInterface
public interface LeitorDeRetorno {

    /**
     * Lê o arquivo.
     *
     * @throws IllegalArgumentException se os bytes não são do formato combinado.
     *         Contagem divergente <b>não</b> é esse caso: ela é resposta, e vem
     *         em {@link Retorno#completo()}.
     */
    Retorno ler(String nome, byte[] conteudo);

    /** Um arquivo de retorno já interpretado, no vocabulário do domínio. */
    interface Retorno {

        /** O recorte que o cabeçalho declara — o endereço do arquivamento. */
        String banco();

        LocalDate dataRef();

        String cicloId();

        /**
         * Se a contagem do trailer bate com o número de detalhes lidos.
         *
         * <p>É a única pergunta que responde "o parceiro terminou de escrever?".
         * Nem a listagem, nem o tamanho parado, nem o download bem-sucedido
         * respondem — os três são verdade sobre um arquivo cortado ao meio.
         */
        boolean completo();

        /** Quantas linhas de detalhe o arquivo tem. */
        int quantidadeDeLinhas();

        /**
         * Entrega cada linha ao aplicador, na ordem do arquivo.
         *
         * <p>Uma transação por linha, como o step-03 desenhou: uma linha que
         * estoura não desfaz as que já passaram, e reprocessar o arquivo inteiro
         * é seguro porque as que passaram viram zero linhas afetadas na segunda
         * vez.
         *
         * @return quantas linhas mudaram o estado de uma tentativa — o resto foi
         *         ignorado pelo {@code UPDATE} condicional, e ser ignorado é o
         *         caso normal de um reprocessamento.
         */
        int aplicarCom(AplicarRetornoUseCase aplicar);
    }
}
