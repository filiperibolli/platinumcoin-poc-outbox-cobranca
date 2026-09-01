package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.infra.consulta.EstadoDoMundo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /estado} — o retrato das cinco fontes: banco, outbox, diretório do
 * parceiro, bucket e fila.
 *
 * <p>É o <b>único</b> {@code GET} do projeto, e é o único sem efeito. Os outros
 * sete passos são {@code POST} mesmo quando parecem leitura, porque cada um
 * executa um job — e um {@code GET} que muda estado é uma armadilha para
 * qualquer coisa que pré-busque links.
 *
 * <p>Fala com {@link EstadoDoMundo}, que é infra, e não com as portas do
 * domínio: o retrato é leitura de operação. Nenhuma regra deste projeto
 * pergunta quantos objetos há no bucket — ver a decisão no próprio
 * {@code EstadoDoMundo}.
 */
@RestController
public class EstadoController {

    private final EstadoDoMundo estadoDoMundo;

    public EstadoController(EstadoDoMundo estadoDoMundo) {
        this.estadoDoMundo = estadoDoMundo;
    }

    @GetMapping("/estado")
    public EstadoDoMundo.Retrato estado() {
        return estadoDoMundo.ler();
    }
}
