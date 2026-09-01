# ADR-0001 — Outbox transacional em vez de dual write

- **Status:** aceito
- **Data:** 2026-08-30
- **Contexto:** step-03 (a transação) / step-05 (o relay)

## Contexto

Ao aplicar uma linha de retorno, o sistema precisa fazer duas coisas: marcar a
fatura como `PAGA` no Postgres e publicar um lançamento contábil numa fila SQS
consumida por um mainframe. O SQS não participa da transação do Postgres. Não há
XA, não há transação distribuída, e o mainframe não é um recurso transacionável.

O caminho ingênuo é o *dual write*: fazer as duas escritas em sequência e torcer.

## Decisão

A intenção de publicar é gravada como **linha na tabela `outbox`, na mesma
transação** que altera a fatura. A publicação no SQS acontece **fora** da
transação, num componente separado (o relay), que lê `outbox` com status
`PENDENTE`.

```
AplicarRetornoUseCase   ── BEGIN ─ UPDATE fatura ─ INSERT outbox ─ COMMIT ──▶ Postgres
PublicarOutboxUseCase   ── SELECT PENDENTE ─ send ─ UPDATE PUBLICADO ────────▶ SQS
```

Nenhuma chamada de rede a sistema externo acontece com uma transação aberta.

## Alternativas descartadas

**A. `commit` no banco e depois `send` na fila.** Se o processo morrer entre os
dois, a fatura fica `PAGA` e o lançamento nunca é publicado. Silencioso: nada no
sistema registra que havia algo a publicar, então nenhum reprocessamento
descobre a perda. O erro só aparece na conciliação contábil.

**B. `send` na fila e depois `commit` no banco.** Se o processo morrer entre os
dois, o mainframe contabiliza um lançamento de uma fatura que continua `ABERTA`.
É o pior dos dois: o mundo externo passa a conter um fato que o sistema de
registro nega, e não há como saber que aconteceu. É exatamente a janela que
`DualWriteEvitadoTest` cobre.

**C. Transação distribuída (XA / 2PC).** Exigiria que o SQS fosse um recurso XA
— não é — e introduziria um coordenador que vira ponto único de bloqueio. Custo
operacional desproporcional para o problema.

**D. Publicar direto do trigger do banco / CDC (Debezium).** Resolve o mesmo
problema com garantias parecidas, mas adiciona Kafka Connect ao teto de infra do
projeto (Postgres + 1 serviço AWS). Fica registrado como o caminho natural se o
volume crescer.

## Consequências

**A favor:**
- Some a janela em que a mensagem existe e a decisão de negócio não. A mensagem
  só passa a existir depois do `COMMIT` que a autoriza.
- A perda vira atraso: se o relay cair, a linha continua `PENDENTE` e é
  publicada na próxima passada. Falha detectável — dá para alertar em
  `count(*) where status = 'PENDENTE' and idade > X`.
- Publicar deixa de ser parte do caminho crítico da aplicação do retorno.

**Contra (o preço honesto):**
- **Latência extra.** A publicação deixa de ser síncrona e passa a depender do
  intervalo do relay. Em produção isso é um poll de segundos.
- **Uma tabela a mais** para manter, monitorar e limpar. Outbox sem expurgo
  vira a maior tabela do banco.
- **Escritas dobram** na transação de negócio: cada retorno pago agora custa um
  `UPDATE` e um `INSERT`.
- **Não elimina duplicata na fila.** Move o problema para o relay, onde ele é
  tratável — ver [ADR-0002](0002-at-least-once-mais-dedup-em-vez-de-fifo.md).

**Onde isto é observável.** `AplicarRetornoUseCase` emite `[outbox] F-… +
PENDENTE — na MESMA transação da fatura` no ponto exato do `INSERT`, e nenhuma
linha de `[sqs]` aparece entre ele e o `COMMIT`: a ausência de uma linha de log
é a prova de que o efeito externo não está dentro da transação. Ver
[ADR-0005](0005-log-no-dominio-nos-pontos-de-decisao.md).
