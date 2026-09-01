# Brief — ciclo-de-cobranca

O enunciado do problema, curto. A solução, o desenho e o teste de mesa estão no
[README](../README.md); aqui fica só o que precisa ser verdade antes de
qualquer linha de código.

## A pergunta

> Como fechar um ciclo diário de cobrança contra um parceiro que só fala por
> arquivo, não avisa quando responde, às vezes responde pela metade, às vezes
> reenvia o mesmo arquivo, e às vezes não responde — garantindo que cada fatura
> paga gere **exatamente um** lançamento no razão contábil?

## Contexto (fictício, simplificado de propósito)

Débito automático de faturas de cartão. O cliente autoriza o débito na conta que
mantém num banco parceiro; todo dia, as faturas que vencem naquela data são
apresentadas ao banco.

O resultado precisa refletir em dois lugares: na **fatura**, que passa a PAGA, e
no **razão contábil**, que recebe um **lançamento** por fatura paga. O razão é um
mainframe legado que consome uma fila e aceita **um lançamento por fatura**. Uma
fatura pode ter **N tentativas** de débito — o banco reapresenta —, no máximo uma
delas paga, e no máximo um lançamento sai. Um lançamento duplicado não quebra
nada tecnicamente; gera uma divergência que alguém concilia à mão, no mês
seguinte, lendo dois sistemas.

As tentativas não vão ao parceiro uma a uma: são agrupadas num **ciclo de
cobrança** — um banco, uma data —, transmitidas em lote e respondidas em lote.

## As restrições

| | |
|---|---|
| **Não há transação distribuída** | Postgres, S3, SFTP e SQS são quatro sistemas. Nenhum commita junto com outro. |
| **O parceiro não é chamável** | não há API, não há callback, não há como perguntar o estado de uma fatura antes da janela seguinte. |
| **O consumidor é legado** | o mainframe não muda por nós. Aceita um lançamento por fatura e deduplica pelo que lhe dermos. |
| **O ciclo é diário e em lote** | um banco, uma data, um arquivo. Não há tentativa avulsa. |

A terceira restrição é a que fecha a porta mais óbvia: como o mainframe não
muda, **exatamente-uma-vez ponta a ponta não é entregável por este lado.** O
produtor não sabe se um `send` que não retornou chegou, e só pode escolher entre
reenviar (duplicata) e não reenviar (perda). A pergunta acima, então, se divide:
garantir um lançamento **decidido** por fatura é nosso; garantir um lançamento
**efetivado** depende do consumidor cooperar com uma chave estável.

## As quatro dificuldades

O problema não é um. São quatro, e a tentação é tratar todas como "erro de
integração" e resolver com retentativa — que é resposta errada para três delas.

**1 · Duas escritas, dois sistemas, nenhuma transação — e isso acontece duas
vezes.** Na montagem: gravar a decisão no Postgres e produzir o artefato de
remessa. Na publicação: marcar o lançamento e enviá-lo à fila. Nos dois casos,
qualquer ordem tem uma janela de morte:

| Ordem | Se o processo morrer no meio | Resultado |
|---|---|---|
| `COMMIT` → efeito externo | depois do commit | a decisão existe, o efeito não — e **nada registra que havia algo a fazer**, então nenhum reprocessamento descobre a perda |
| efeito externo → `COMMIT` | depois do efeito | o mundo externo contém um fato que o sistema de registro **nega** |

O segundo é o pior: o mainframe contabiliza uma fatura que continua ABERTA, e
não há de onde saber que aconteceu.

**2 · Não existe callback.** O retorno aparece num diretório SFTP em algum
momento da noite. Ninguém avisa. Isso força varredura periódica e duas decisões
que nada mais no sistema exige: **quiescência** (o arquivo parou de crescer, dá
para baixar) e **trailer** (a contagem declarada bate com as linhas, está
completo). Sem as duas, processa-se arquivo pela metade e marcam-se faturas com
base em dados truncados.

**3 · O mesmo retorno pode chegar mais de uma vez, de várias formas.** Arquivo
reenviado idêntico, arquivo particionado com sobreposição, linha duplicada
dentro do arquivo, reprocessamento manual do operador. Quatro problemas
diferentes, uma solução só: `UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'`.
Zero linhas afetadas significa "já processado". A idempotência não vem de tabela
de deduplicação nem de hash — vem de a transição de estado ser **condicional**.

**4 · O silêncio não é uma resposta.** Se o ciclo fecha e nada chegou para uma
tentativa, o sistema não pode marcar `NAO_PAGO`: isso dispararia push dizendo ao
cliente que o débito falhou, com base num fato que ninguém afirmou. Vira
`SEM_RETORNO`, que é exceção operacional. É a decisão mais barata de implementar
do projeto inteiro e a que mais separa quem pensou do que quem só codificou.

## As duas invariantes

> **1.** No máximo **um** lançamento contábil por fatura, qualquer que seja o
> número de tentativas de débito ou de reprocessamentos do arquivo de retorno.

> **2.** A montagem do ciclo é a **única** escrita que importa. Remessa, retorno,
> fechamento e publicação são trabalho derivado, re-executável a partir dela.

## Fronteira de responsabilidade

| Garantia | De quem |
|---|---|
| No máximo um lançamento **decidido** por fatura | deste projeto (`UPDATE` condicional + outbox transacional) |
| Todo lançamento decidido é **eventualmente** publicado | deste projeto (relay reprocessa `PENDENTE`) |
| Nenhum resultado é **inventado** para quem não respondeu | deste projeto (`SEM_RETORNO`) |
| No máximo um lançamento **efetivado** no mainframe | do consumidor (dedup pela chave `faturaId`) |

## Não-objetivos

Throughput, particionamento do outbox, backoff, DLQ, múltiplos publicadores,
ordenação, retenção. CNAB 240 de verdade, política de retentativa por código de
retorno, ciclo de vida da autorização de débito, conciliação D+1. Ver "O que
ficou de fora, de propósito" no [README](../README.md), onde cada omissão tem o
motivo escrito.
