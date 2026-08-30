# step-04 — Fechamento de ciclo

## Objetivo

Encerrar o ciclo sem inventar resultado para quem não respondeu.

## Entregáveis

- `domain/usecase/FecharCicloUseCase`.
- Teste: `FechamentoNaoInventaResultadoTest`.

## Regra

Ao fechar o ciclo, tudo que continua `ENVIADO_PARCEIRO` vira `SEM_RETORNO`.
**Nunca `NAO_PAGO`.**

```sql
UPDATE tentativa_debito
   SET status = 'SEM_RETORNO'
 WHERE ciclo_id = ? AND status = 'ENVIADO_PARCEIRO';

UPDATE ciclo_cobranca SET status = 'FECHADO' WHERE id = ?;
```

O comentário no código precisa dizer o porquê, porque a alternativa errada é
tentadora e parece mais simples:

```java
// DECISÃO: ausência de retorno vira SEM_RETORNO, não NAO_PAGO — marcar
// como não pago dispararia notificação de falha ao cliente com base em
// um fato que não ocorreu. SEM_RETORNO é exceção operacional. Ver README.
```

## Decisões deste step

- **Silêncio não é resposta.** `NAO_PAGO` é uma afirmação do parceiro, e vem com
  motivo (`SALDO_INSUFICIENTE`, `CONTA_ENCERRADA`, `AUTORIZACAO_REVOGADA`). A
  ausência de retorno não tem motivo porque não houve fato. O schema força isso:
  `CHECK ((status = 'NAO_PAGO') = (motivo IS NOT NULL))`.
- **A diferença é visível para o cliente.** `NAO_PAGO` dispara notificação de
  falha de débito; `SEM_RETORNO` é exceção operacional que alguém investiga.
  Colapsar os dois é escolher mentir para o cliente para simplificar um `UPDATE`.
- **Nenhum dos dois gera lançamento.** `Status.geraLancamentoContabil()` só é
  verdadeiro para `PAGO` — o fechamento não pode produzir linha no outbox.

## Teste obrigatório

**`FechamentoNaoInventaResultadoTest`** — ciclo com 3 tentativas
`ENVIADO_PARCEIRO`, retorno chega para 1. Depois do fechamento:

| Esperado | Valor |
|---|---|
| `PAGO` | 1 |
| `SEM_RETORNO` | 2 |
| `NAO_PAGO` | **0** |
| linhas no outbox | **1** |

## Definition of Done

- [ ] O teste passa, incluindo a assertiva de **zero** `NAO_PAGO`.
- [ ] O outbox tem exatamente 1 linha.
- [ ] O comentário `// DECISÃO:` está no código, com a justificativa completa.
- [ ] Fechar um ciclo já fechado não altera nada (0 linhas afetadas).
- [ ] CHANGELOG + commit `feat(outbox): fechamento de ciclo sem inventar resultado (step 04)`.
