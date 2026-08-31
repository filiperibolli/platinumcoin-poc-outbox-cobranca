package com.platinumcoin.outbox.domain.port;

/**
 * O diretório do parceiro, visto pelo domínio: um lugar onde arquivos são
 * deixados com um nome.
 *
 * <p>Nada aqui fala de SSH, de host ou de credencial — isso é
 * {@code CanalArquivosSftp}, na infra. O domínio sabe apenas que existe um
 * outro lado, que ele não controla, e que entregar um arquivo lá é um efeito
 * externo como qualquer outro: acontece <b>fora</b> de {@link Transacao}
 * aberta.
 *
 * <p>O nome do arquivo é derivado do ciclo, e por isso reenviar sobrescreve em
 * vez de acumular — a mesma propriedade que {@link ArmazenamentoArtefato} tem
 * pela chave. Sobrescrever não fecha a janela entre a entrega e o
 * {@code COMMIT}: torna o efeito da reexecução idempotente por conteúdo, e
 * quem absorve o parceiro que leu duas vezes é o {@code UPDATE} condicional do
 * step-03.
 * <br>DECISÃO: put no parceiro antes do COMMIT — ver docs/steps/step-08.md
 *
 * <p>Ganha os métodos de leitura no step-09, quando o retorno passa a ser
 * coletado deste mesmo canal.
 */
public interface CanalArquivos {

    /**
     * Deixa {@code conteudo} no destino sob {@code nome}, sobrescrevendo o que
     * houver lá com esse nome.
     *
     * @throws com.platinumcoin.outbox.domain.exception.FalhaDePublicacao
     *         se a transmissão não completou — e, como em toda entrega a
     *         sistema externo, não se sabe se o arquivo chegou.
     */
    void enviar(String nome, byte[] conteudo);
}
