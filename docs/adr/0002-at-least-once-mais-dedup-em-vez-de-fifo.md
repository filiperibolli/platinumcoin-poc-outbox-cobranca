# ADR-0002 — At-least-once com chave de dedup em vez de fila FIFO

- **Status:** aceito
- **Data:** 2026-08-30
- **Contexto:** step-03

## Contexto

O outbox ([ADR-0001](0001-outbox-transacional-em-vez-de-dual-write.md)) garante
que toda decisão de negócio vira uma linha `PENDENTE`. Falta publicar. O relay
faz três coisas em sequência, sem transação que as una:

```
1. SELECT ... WHERE status = 'PENDENTE'
2. sqs.sendMessage(...)          ← rede, sistema externo
3. UPDATE outbox SET status = 'PUBLICADO'
```

Se o processo morrer entre (2) e (3), a linha continua `PENDENTE` e a próxima
passada republica. **Duas mensagens, um lançamento.**

Inverter a ordem — marcar `PUBLICADO` antes de enviar — troca duplicata por
perda, que é estritamente pior: a duplicata é detectável pelo consumidor, a
perda não é detectável por ninguém.

## Decisão

Assumir **at-least-once** e carregar uma **chave de deduplicação determinística**
— o id da fatura — no atributo `chaveDedup` da mensagem. O consumidor
(mainframe) é responsável por ignorar a segunda ocorrência da mesma chave.

A chave é derivada do domínio, não gerada no envio: reenviar a mesma linha do
outbox produz sempre a mesma chave, hoje e daqui a uma semana.

## Alternativas descartadas

**A. SQS FIFO com `MessageDeduplicationId`.** Deduplica de verdade, mas só
dentro de uma **janela de 5 minutos**. O relay que volta depois de um incidente
de 20 minutos republica e o FIFO entrega as duas. Ou seja: a janela do FIFO
resolve o caso que já era barato de resolver e falha exatamente no caso que
importa. Cobra por isso throughput limitado a 300 msg/s por grupo e ordenação
que este domínio não pede.

**B. Confirmação em duas fases com o consumidor.** O relay envia, espera um ack
do mainframe e só então marca `PUBLICADO`. Reintroduz um sistema externo no
caminho da consistência e acopla a disponibilidade do relay à do mainframe —
que é justamente o sistema de que se quer desacoplar.

**C. Tabela de mensagens já publicadas consultada antes do envio.** Não resolve:
a janela apenas se desloca para entre a consulta e o envio. Toda tentativa de
resolver duplicata **do lado do produtor** esbarra no mesmo fato — não existe
`send` e `commit` atômicos.

## Por que exatamente-uma-vez ponta a ponta é impossível aqui

O produtor não sabe se um `send` que não retornou chegou. Ele tem duas opções:
reenviar (risco de duplicata) ou não reenviar (risco de perda). Não existe uma
terceira, e nenhuma configuração de fila cria uma — o que as filas chamam de
"exactly-once" é sempre at-least-once mais dedup em alguma janela finita.

A entrega exatamente-uma-vez ponta a ponta só existe se o **efeito** for
idempotente ou desduplicado no destino. Por isso a responsabilidade é declarada
explicitamente, e não escondida atrás de uma configuração de fila:

| Garantia | De quem |
|---|---|
| No máximo um lançamento **decidido** por fatura | deste projeto |
| Todo lançamento decidido é **eventualmente** publicado | deste projeto |
| No máximo um lançamento **efetivado** | do consumidor, via `chaveDedup` |

## Consequências

- `CrashDoRelayTest` assere **duas mensagens com a mesma chave** na fila. É o
  comportamento correto do sistema, não um bug tolerado — por isso é um teste,
  e não uma nota de rodapé.
- O contrato com o consumidor cresce: `chaveDedup` passa a ser parte da
  interface, com a mesma força de um campo obrigatório do payload.
- Se o mainframe não puder deduplicar, este desenho não serve — seria preciso um
  gateway idempotente na frente dele. Fica registrado como o limite conhecido.
