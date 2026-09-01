package com.platinumcoin.ciclo.infra.falha;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * O que está armado para quebrar na próxima execução de um passo.
 *
 * <p>Existe para que as duas janelas do projeto — as duas em que um efeito
 * externo acontece antes do {@code COMMIT} que o registra — possam ser
 * provocadas por botão, e não apenas lidas numa classe de teste.
 *
 * <p><b>Armar e disparar são chamadas separadas.</b> Um endpoint que provocasse
 * a falha e executasse o passo de uma vez esconderia o estado intermediário, e
 * o estado intermediário é o único momento em que a janela é visível: o arquivo
 * no parceiro com as tentativas ainda {@code SOLICITADO}, a mensagem na fila com
 * a linha ainda {@code PENDENTE}.
 *
 * <p>A falha se desarma ao disparar. Uma falha que ficasse armada transformaria
 * a demonstração seguinte — a reexecução que converge — em mais uma falha, e é
 * justamente a convergência que o botão existe para mostrar.
 */
public final class FalhasArmadas {

    /** As duas janelas, e o passo em que cada uma se abre. */
    public enum Falha {

        /** Entre o {@code send} ao SQS e o {@code UPDATE} do outbox — step-05. */
        CRASH_RELAY("POST /outbox/publicar", "o relay morreu entre o send e o UPDATE"),

        /** Entre o {@code put} no SFTP e o {@code COMMIT} do envio — step-08. */
        CRASH_ENVIO("POST /ciclo/enviar", "o processo morreu entre o put e o COMMIT");

        private final String passo;
        private final String mensagem;

        Falha(String passo, String mensagem) {
            this.passo = passo;
            this.mensagem = mensagem;
        }

        /** A chamada que dispara esta falha, para a resposta dizer o que esperar. */
        public String passo() {
            return passo;
        }

        /** O que a exceção dirá quando o passo for executado. */
        public String mensagem() {
            return mensagem;
        }
    }

    private final Set<Falha> armadas = ConcurrentHashMap.newKeySet();

    public void armar(Falha falha) {
        armadas.add(falha);
    }

    /**
     * Se esta falha estava armada — e, neste caso, a desarma.
     *
     * <p>Um teste-e-limpa atômico, e não uma consulta seguida de uma remoção: o
     * servidor atende em várias threads, e uma falha armada uma vez precisa
     * derrubar exatamente uma execução.
     */
    public boolean dispara(Falha falha) {
        return armadas.remove(falha);
    }

    /** O que continua armado — o que o painel do step-12 mostra ao lado do botão. */
    public List<String> armadas() {
        return armadas.stream().map(Falha::name).sorted().toList();
    }
}
