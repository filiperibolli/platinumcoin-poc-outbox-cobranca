package com.platinumcoin.outbox.domain.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * O endereço determinístico de um artefato, derivado do ciclo que o produziu:
 * a chave no armazenamento de objetos e o nome no diretório do parceiro.
 *
 * <p>Existe como tipo, e não como {@code String} montada em cada chamador,
 * porque a chave é o que torna o {@code put} seguro de repetir: dois lugares
 * montando o mesmo endereço com um separador diferente transformariam a
 * segunda gravação num objeto novo, e o bucket num log de tentativas.
 *
 * <p>Nada de {@code now()} nem de sorteio aqui dentro: mesmo ciclo, mesma
 * chave, hoje e depois do incidente.
 * <br>DECISÃO: chave determinística derivada do ciclo — ver ADR-0003
 */
public record ChaveArtefato(String valor) {

    /**
     * {@code yyyyMMdd}, o mesmo formato de data que vai no header da remessa —
     * o projeto escreve data de uma maneira só, no arquivo e no endereço dele.
     */
    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;

    public ChaveArtefato {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("chave de artefato vazia");
        }
    }

    /** {@code remessa/{banco}/{dataRef}/{cicloId}.rem} — a chave no armazenamento. */
    public static ChaveArtefato daRemessa(CicloCobranca ciclo) {
        if (ciclo == null) {
            throw new IllegalArgumentException("chave de remessa sem ciclo");
        }
        return new ChaveArtefato("remessa/%s/%s/%s.rem".formatted(
                ciclo.banco(), DATA.format(ciclo.dataRef()), ciclo.id()));
    }

    /**
     * {@code retorno/{banco}/{dataRef}/{nome}} — onde o arquivo que <b>voltou</b>
     * do parceiro é arquivado.
     *
     * <p>Termina no nome que o parceiro escolheu, e não num id nosso, porque o
     * arquivo é dele: um ciclo pode receber vários, e o nome é a única coisa que
     * os distingue. As duas primeiras pastas são nossas — o recorte que o
     * cabeçalho declara — e é o que torna o arquivamento navegável quando alguém
     * for procurar o que o parceiro mandou naquele dia.
     *
     * <p>O arquivamento acontece <b>antes</b> da validação do trailer: o arquivo
     * que não fecha é justamente o que alguém vai querer olhar.
     */
    public static ChaveArtefato doRetorno(String banco, LocalDate dataRef, String nome) {
        if (banco == null || banco.isBlank() || dataRef == null || nome == null || nome.isBlank()) {
            throw new IllegalArgumentException(
                    "chave de retorno incompleta (banco=" + banco + ", data=" + dataRef
                            + ", nome=" + nome + ")");
        }
        return new ChaveArtefato("retorno/%s/%s/%s".formatted(banco, DATA.format(dataRef), nome));
    }

    /**
     * {@code {banco}-{dataRef}-{cicloId}.rem} — o nome no diretório do parceiro.
     *
     * <p>Plano, sem barras: o destino é um diretório combinado, não uma árvore
     * nossa. É a mesma derivação da chave aplicada ao outro lado do fio, e mora
     * aqui pelo mesmo motivo — dois lugares montando o nome com um separador
     * diferente fariam do reenvio um segundo arquivo, e caberia ao parceiro
     * decidir qual dos dois vale.
     * <br>DECISÃO: nome determinístico no parceiro — ver docs/steps/step-08.md
     */
    public static String nomeDaRemessaNoParceiro(CicloCobranca ciclo) {
        if (ciclo == null) {
            throw new IllegalArgumentException("nome de remessa sem ciclo");
        }
        return "%s-%s-%s.rem".formatted(
                ciclo.banco(), DATA.format(ciclo.dataRef()), ciclo.id());
    }

    @Override
    public String toString() {
        return valor;
    }
}
