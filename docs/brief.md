# Brief — mini-outbox-cobranca

## Contexto (fictício, simplificado de propósito)

Um sistema de débito automático processa **arquivos de retorno** de um banco
parceiro. Cada linha do retorno diz o que aconteceu com uma tentativa de débito:
paga, não paga, erro.

Quando uma fatura é marcada como **PAGA**, um **lançamento contábil** precisa ser
publicado em uma fila consumida por um mainframe legado. O mainframe aceita
**um lançamento por fatura**. Um lançamento duplicado não quebra nada
tecnicamente — gera uma divergência que alguém concilia à mão, no mês seguinte,
lendo dois sistemas.

Uma fatura pode ter **N tentativas de débito** (o banco reapresenta), mas no
máximo **um lançamento**.

## O problema

O banco de dados e a fila são sistemas distintos, sem transação distribuída. Só
existem duas ordens possíveis, e as duas têm uma janela de falha:

| Ordem | Se o processo morrer no meio | Resultado |
|---|---|---|
| `commit` no banco → `send` na fila | morre depois do commit | fatura PAGA, **lançamento nunca publicado** — dinheiro reconhecido, contabilidade cega |
| `send` na fila → `commit` no banco | morre depois do send | lançamento publicado de uma **decisão que não existe** — mainframe contabiliza uma fatura que continua ABERTA |

O segundo caso é o pior: o mundo externo enxerga um fato que o sistema de
registro nega. Não há reprocessamento que conserte, porque não há de onde saber
que aconteceu.

## O que este projeto prova

Que dá para eliminar essa janela sem transação distribuída, movendo a segunda
escrita para **dentro** da transação que já existe:

1. A decisão de negócio e a **intenção de publicar** são gravadas juntas, no
   mesmo `COMMIT` do Postgres (tabelas `fatura` e `outbox`).
2. Um **relay** separado lê o outbox e publica no SQS, fora de qualquer
   transação.

O que sobra de imperfeição fica **explícito e testado**: se o relay morre entre
o `send` e o `UPDATE outbox`, a mensagem é republicada. Isso é at-least-once,
assumido, com chave de dedup determinística para o consumidor resolver.

## Fronteira de responsabilidade

| Garantia | De quem |
|---|---|
| No máximo um lançamento **decidido** por fatura | deste projeto (`UPDATE` condicional + outbox transacional) |
| Todo lançamento decidido é **eventualmente** publicado | deste projeto (relay reprocessa `PENDENTE`) |
| No máximo um lançamento **efetivado** no mainframe | do consumidor (dedup pela chave `faturaId`) |

## Não-objetivos

Throughput, particionamento do outbox, backoff, DLQ, múltiplos publicadores,
ordenação, retenção. Ver a seção "O que foi deliberadamente simplificado" no
README.
