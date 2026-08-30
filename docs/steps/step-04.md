# step-04 — Cenário ponta a ponta

## Objetivo

Um `main` que torna o comportamento visível sem ler teste nenhum.

## Entregáveis

- `Main` — roda contra o ambiente do `docker compose`, imprimindo cada transição.

## Cenário

| Fatura | História | Esperado |
|---|---|---|
| `F-1` | retorno pago aplicado **duas vezes** | 1 linha no outbox, 1 mensagem |
| `F-2` | tentativa 1 `NAO_PAGA`, tentativa 2 `PAGA` | 1 linha no outbox, 1 mensagem |
| `F-3` | pago, **crash** entre `send` e `UPDATE`, relay reexecutado | 1 linha no outbox, **2 mensagens, mesma chave** |

## Saída esperada (forma)

```
[retorno]  T-1  ENVIADA → PAGA        (1 linha afetada)
[fatura]   F-1  ABERTA  → PAGA
[outbox]   F-1  + PENDENTE
[retorno]  T-1  duplicado             (0 linhas afetadas — ignorado)
[relay]    F-1  PENDENTE → PUBLICADO  chaveDedup=F-1
...
[fila]     3 lançamentos, 2 chaves distintas — F-3 duplicado (at-least-once)
```

## Definition of Done

- [ ] `mvn compile exec:java` (ou `java -cp`) roda o cenário do começo ao fim.
- [ ] A saída mostra os 0 registros afetados do retorno duplicado.
- [ ] A saída termina contando mensagens na fila e explicitando a duplicata da
      `F-3` como comportamento esperado.
- [ ] README com o passo a passo e a saída de exemplo.
- [ ] CHANGELOG + commit `feat(outbox): cenário ponta a ponta no main (step 04)`.
