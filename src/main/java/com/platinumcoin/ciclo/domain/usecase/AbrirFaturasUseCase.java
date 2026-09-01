package com.platinumcoin.ciclo.domain.usecase;

import com.platinumcoin.ciclo.domain.model.Fatura;
import com.platinumcoin.ciclo.domain.model.TentativaDebito;
import com.platinumcoin.ciclo.domain.port.RepositorioFatura;
import com.platinumcoin.ciclo.domain.port.RepositorioTentativa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abre as faturas de partida de um recorte, cada uma com uma tentativa
 * {@code ABERTO} esperando entrar num ciclo.
 *
 * <p>É o estado inicial do mundo — o que, num sistema de verdade, chegaria da
 * originação. Existe como use case, e não como um punhado de {@code INSERT}s no
 * controller, pela mesma regra que vale para os outros seis: a operação inbound
 * tem um lugar com nome, e o controller não decide id, valor nem recorte.
 *
 * <p>Não abre transação. As duas escritas não são uma só — uma fatura sem
 * tentativa é uma fatura que nenhum ciclo puxa, e não uma invariante quebrada:
 * o {@code UNIQUE (fatura_id, numero)} deixa a reexecução conserta-la. O que
 * <b>é</b> uma escrita só continua sendo a montagem do ciclo.
 */
public final class AbrirFaturasUseCase {

    private static final Logger log = LoggerFactory.getLogger(AbrirFaturasUseCase.class);

    /**
     * O teto de faturas por chamada.
     *
     * <p>Não é gosto: o id da tentativa é {@code F-{dataRef}-{n}-T1} e o layout
     * posicional da remessa reserva 16 posições para ele. Com três dígitos em
     * {@code n} o campo estoura na projeção, longe daqui. O limite mora onde o
     * id nasce.
     */
    public static final int MAXIMO = 99;

    private static final DateTimeFormatter DATA = DateTimeFormatter.BASIC_ISO_DATE;
    private static final BigDecimal PARCELA = new BigDecimal("100.00");

    private final RepositorioFatura faturas;
    private final RepositorioTentativa tentativas;

    public AbrirFaturasUseCase(RepositorioFatura faturas, RepositorioTentativa tentativas) {
        this.faturas = faturas;
        this.tentativas = tentativas;
    }

    /** As faturas abertas e as tentativas que nasceram com elas. */
    public record Resultado(List<Fatura> faturas, List<TentativaDebito> tentativas) {

        public Resultado {
            faturas = List.copyOf(faturas);
            tentativas = List.copyOf(tentativas);
        }
    }

    /**
     * Abre {@code quantidade} faturas no recorte, numeradas a partir de 1.
     *
     * <p>Os ids são derivados da data de referência — {@code F-20260831-1} —, e
     * não sorteados: o mesmo recorte pede as mesmas faturas, e a segunda
     * chamada esbarra na chave primária em vez de encher o banco de faturas
     * parecidas. É a mesma escolha da montagem do ciclo, com a mesma
     * consequência: reexecutar é recusado por construção.
     */
    public Resultado executar(int quantidade, String banco, LocalDate dataRef) {
        if (quantidade < 1 || quantidade > MAXIMO) {
            throw new IllegalArgumentException(
                    "quantidade de faturas fora de 1.." + MAXIMO + ": " + quantidade);
        }

        List<Fatura> abertas = new ArrayList<>();
        List<TentativaDebito> esperando = new ArrayList<>();
        for (int numero = 1; numero <= quantidade; numero++) {
            Fatura fatura = new Fatura(
                    "F-%s-%d".formatted(DATA.format(dataRef), numero),
                    PARCELA.multiply(BigDecimal.valueOf(numero)),
                    Fatura.Status.ABERTA);
            TentativaDebito tentativa = TentativaDebito.aberta(
                    fatura.id() + "-T1", fatura.id(), 1, banco, dataRef);

            faturas.inserir(fatura);
            tentativas.inserir(tentativa);

            abertas.add(fatura);
            esperando.add(tentativa);
        }
        log.info("[fatura]  {} faturas ABERTA, {} tentativas ABERTO (banco {}, {})",
                abertas.size(), esperando.size(), banco, dataRef);
        return new Resultado(abertas, esperando);
    }
}
