package com.platinumcoin.outbox.api.http;

import com.platinumcoin.outbox.api.http.dto.Falha;
import com.platinumcoin.outbox.domain.exception.FalhaDePersistencia;
import com.platinumcoin.outbox.domain.exception.FalhaDePublicacao;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduz a recusa em resposta, pela mesma razão que a resposta de sucesso é o
 * efeito: um corpo genérico não explicaria nada.
 *
 * <p>As recusas interessantes aqui são <b>previstas pelo desenho</b>: a segunda
 * montagem do mesmo recorte esbarra no {@code UNIQUE (banco, data_ref)}, e a
 * transmissão de um ciclo sem remessa gerada esbarra na fronteira que o step-07
 * criou. As duas precisam chegar a quem está olhando com o motivo intacto.
 *
 * <p>Não há decisão de negócio aqui — só o mapeamento de um tipo de exceção
 * para um código. Nenhum {@code catch} novo nasceu no domínio por causa desta
 * classe.
 */
@RestControllerAdvice
public class FalhasComoResposta {

    /** Parâmetro que o domínio recusou: quantidade fora da faixa, ciclo inexistente. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Falha> pedidoInvalido(IllegalArgumentException recusa) {
        return ResponseEntity.badRequest().body(Falha.de(recusa));
    }

    /** Passo pedido fora de ordem — enviar antes de gerar, por exemplo. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Falha> foraDeOrdem(IllegalStateException recusa) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Falha.de(recusa));
    }

    /** O banco recusou a escrita — tipicamente uma constraint fazendo o seu trabalho. */
    @ExceptionHandler(FalhaDePersistencia.class)
    public ResponseEntity<Falha> bancoRecusou(FalhaDePersistencia recusa) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Falha.de(recusa));
    }

    /**
     * O efeito externo falhou — e falhar aqui é ambíguo por natureza: a
     * mensagem pode ter chegado, o arquivo pode ter sido gravado. É por isso
     * que a linha continua {@code PENDENTE} e a próxima passada recomeça.
     */
    @ExceptionHandler(FalhaDePublicacao.class)
    public ResponseEntity<Falha> mundoExternoFalhou(FalhaDePublicacao recusa) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Falha.de(recusa));
    }
}
