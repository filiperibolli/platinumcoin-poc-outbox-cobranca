package com.platinumcoin.ciclo.api.http;

import com.platinumcoin.ciclo.api.http.dto.OutboxPublicado;
import com.platinumcoin.ciclo.domain.port.PublicadorLancamento;
import com.platinumcoin.ciclo.domain.port.RepositorioOutbox;
import com.platinumcoin.ciclo.domain.usecase.PublicarOutboxUseCase;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /outbox/publicar} — uma passada do relay.
 *
 * <p>É o único controller que monta o seu use case por chamada, e o motivo é a
 * resposta: as chaves que foram para a fila só são conhecidas por quem publica,
 * então o publicador vai anotado por {@link ChavesPublicadas}. Montar é fiação,
 * não decisão — o que publicar, em que ordem e o que marcar continua inteiro
 * dentro de {@link PublicarOutboxUseCase}.
 */
@RestController
public class PublicarOutboxController {

    private final RepositorioOutbox outbox;
    private final PublicadorLancamento publicador;

    public PublicarOutboxController(RepositorioOutbox outbox, PublicadorLancamento publicador) {
        this.outbox = outbox;
        this.publicador = publicador;
    }

    @PostMapping("/outbox/publicar")
    public OutboxPublicado publicar(@RequestParam(name = "limite", defaultValue = "50") int limite) {
        ChavesPublicadas enviadas = new ChavesPublicadas(publicador);
        int publicados = new PublicarOutboxUseCase(outbox, enviadas).executar(limite);
        return new OutboxPublicado(publicados, enviadas.chaves());
    }
}
