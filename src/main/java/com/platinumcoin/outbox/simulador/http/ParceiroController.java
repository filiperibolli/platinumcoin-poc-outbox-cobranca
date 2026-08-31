package com.platinumcoin.outbox.simulador.http;

import com.platinumcoin.outbox.simulador.ParceiroSimulado;
import com.platinumcoin.outbox.simulador.http.dto.RetornoEscrito;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Os quatro comportamentos do parceiro, um {@code POST} cada.
 *
 * <p>Estas rotas <b>não fazem parte do sistema</b>. Elas movem o ambiente: é o
 * banco do outro lado do fio processando o que recebeu. Em produção não existe
 * endpoint nenhum aqui — existe um parceiro, e o que ele faz não é decisão
 * nossa. Por isso o prefixo é {@code /parceiro} e não {@code /ciclo}: no painel
 * do step-12 e no {@code curl} do README, a diferença entre operar o sistema e
 * mexer no ambiente precisa ser visível antes de a chamada ser feita.
 *
 * <p>Todos {@code POST}, como os do ciclo, e pelo mesmo motivo: cada um escreve
 * arquivo no diretório do parceiro.
 */
@RestController
public class ParceiroController {

    private final ParceiroSimulado parceiro;

    public ParceiroController(ParceiroSimulado parceiro) {
        this.parceiro = parceiro;
    }

    @PostMapping("/parceiro/processar")
    public RetornoEscrito processar(
            @RequestParam(name = "resultado", defaultValue = "PAGO") String resultado,
            @RequestParam(name = "particionar", defaultValue = "1") int particionar,
            @RequestParam(name = "atrasar", defaultValue = "false") boolean atrasar) {
        return RetornoEscrito.de("processar",
                parceiro.processar(resultado, particionar, atrasar));
    }

    @PostMapping("/parceiro/reenviar-retorno")
    public RetornoEscrito reenviar() {
        return RetornoEscrito.de("reenviar-retorno", parceiro.reenviar());
    }

    @PostMapping("/parceiro/retorno-truncado")
    public RetornoEscrito truncado() {
        return RetornoEscrito.de("retorno-truncado", parceiro.truncar());
    }

    @PostMapping("/parceiro/silencio")
    public RetornoEscrito silencio() {
        return RetornoEscrito.de("silencio", parceiro.silenciar());
    }
}
