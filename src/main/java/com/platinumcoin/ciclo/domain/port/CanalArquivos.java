package com.platinumcoin.ciclo.domain.port;

import java.util.List;
import java.util.Optional;

/**
 * O diretório do parceiro, visto pelo domínio: um lugar onde arquivos são
 * deixados com um nome — e de onde arquivos são recolhidos.
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
 * <p><b>Não há {@code remover}.</b> Pela mesma razão que
 * {@link ArmazenamentoArtefato} não tem {@code delete}: o diretório é do
 * parceiro, e apagar o que ele deixou lá seria decidir por ele quando o arquivo
 * já cumpriu seu papel. O arquivo já aplicado continua aparecendo em toda
 * varredura, e quem o reconhece pelos bytes é o hash — não o desaparecimento.
 *
 * <p>Os três métodos de leitura são {@code listar} → {@code atributos} →
 * {@code baixar}, nessa ordem, e existem separados porque a decisão do step-09
 * mora entre o segundo e o terceiro: um arquivo que aparece na listagem pode
 * estar sendo escrito neste instante, e perguntar seus atributos duas vezes é o
 * que distingue o que parou do que ainda cresce.
 */
public interface CanalArquivos {

    /**
     * Deixa {@code conteudo} no destino sob {@code nome}, sobrescrevendo o que
     * houver lá com esse nome.
     *
     * @throws com.platinumcoin.ciclo.domain.exception.FalhaDePublicacao
     *         se a transmissão não completou — e, como em toda entrega a
     *         sistema externo, não se sabe se o arquivo chegou.
     */
    void enviar(String nome, byte[] conteudo);

    /**
     * Os caminhos dos arquivos de {@code diretorio}, em ordem estável.
     *
     * <p>Caminhos completos, e não nomes soltos: é o que {@link #atributos} e
     * {@link #baixar} recebem de volta, sem que o chamador precise recompor o
     * endereço com um separador que pode divergir do de quem listou.
     */
    List<String> listar(String diretorio);

    /**
     * Tamanho e instante da última modificação de {@code caminho}, ou vazio se
     * ele não existe mais.
     *
     * <p>Vazio em vez de exceção porque sumir <b>é</b> uma resposta: entre a
     * listagem e a pergunta o parceiro pode ter movido o arquivo, e isso não é
     * falha de ninguém — é a próxima passada que decide de novo.
     */
    Optional<Atributos> atributos(String caminho);

    /**
     * O conteúdo do arquivo.
     *
     * @throws com.platinumcoin.ciclo.domain.exception.FalhaDePersistencia
     *         se não deu para ler.
     */
    byte[] baixar(String caminho);

    /**
     * O que se sabe de um arquivo remoto sem abri-lo.
     *
     * <p>Os dois campos juntos, e não um só, porque cada um sozinho tem um
     * ponto cego: o tamanho não muda quando o arquivo é reescrito no lugar com o
     * mesmo comprimento, e o {@code mtime} tem resolução de segundos no
     * protocolo — um arquivo que cresce dentro do mesmo segundo passaria por
     * parado. Os dois iguais em duas leituras é a definição de quiescência que
     * este projeto usa.
     * <br>DECISÃO: quiescência por tamanho E mtime — ver docs/steps/step-09.md
     *
     * @param tamanho      bytes
     * @param modificadoEm segundos desde a época, como o protocolo informa
     */
    record Atributos(long tamanho, long modificadoEm) {
    }
}
