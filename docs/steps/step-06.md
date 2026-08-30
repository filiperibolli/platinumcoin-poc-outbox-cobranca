# step-06 — Cenário ponta a ponta

## Objetivo

Um `main` que torna o ciclo de vida inteiro visível sem ler teste nenhum.

## Entregáveis

- `Main` — roda contra o ambiente do `docker compose`, imprimindo cada transição.

## Cenário

Um ciclo, montado uma vez, com 3 faturas:

| Fatura | História | Esperado |
|---|---|---|
| `F-1` | retorno pago aplicado **duas vezes** | 1 linha no outbox, 1 mensagem |
| `F-2` | tentativa 1 `NAO_PAGO` (saldo), tentativa 2 `PAGO` | 1 linha no outbox, 1 mensagem |
| `F-3` | pago, **crash** entre `send` e `UPDATE`, relay reexecutado | 1 linha no outbox, **2 mensagens, mesma chave** |

Mais o fechamento: uma quarta tentativa sem retorno vira `SEM_RETORNO`, e
**não** gera lançamento.

## Saída esperada (forma)

```
[ciclo]    C-1  MONTADO           4 tentativas ABERTO → SOLICITADO
[remessa]  C-1  4 linhas, sha256=... (regerada: idêntica)
[retorno]  T-1  ENVIADO_PARCEIRO → PAGO   (1 linha afetada)
[fatura]   F-1  ABERTA → PAGA
[outbox]   F-1  + PENDENTE
[retorno]  T-1  duplicado                 (0 linhas afetadas — ignorado)
[retorno]  T-2  ENVIADO_PARCEIRO → NAO_PAGO (SALDO_INSUFICIENTE) — sem outbox
[fecha]    C-1  FECHADO           T-4 → SEM_RETORNO (sem outbox)
[relay]    F-1  PENDENTE → PUBLICADO  chaveDedup=F-1
...
[fila]     4 lançamentos, 3 chaves distintas — F-3 duplicado (at-least-once)
```

## Definition of Done

- [ ] `mvn compile exec:java` (ou `java -cp`) roda o cenário do começo ao fim.
- [ ] A saída mostra os 0 registros afetados do retorno duplicado.
- [ ] A saída mostra `SEM_RETORNO` e `NAO_PAGO` **sem** linha de outbox.
- [ ] A saída termina contando mensagens na fila e explicitando a duplicata da
      `F-3` como comportamento esperado.
- [ ] README com o passo a passo e a saída de exemplo.
- [ ] CHANGELOG + commit `feat(outbox): cenário ponta a ponta no main (step 06)`.
