# step-05 — Relay (a publicação)

## Objetivo

Publicar o que o outbox acumulou, fora da transação, assumindo at-least-once.

## Entregáveis

- `domain/usecase/PublicarOutboxUseCase` — lê `PENDENTE`, publica, marca
  `PUBLICADO`.
- `infra/persistence/PublicadorLancamentoSqs` — AWS SDK v2, atributo
  `chaveDedup` = id da fatura.
- `infra/config/Ambiente` — `DataSource` + `SqsClient`.
- Testes: `RelayPublicaTest`, `CrashDoRelayTest`.

## Ordem obrigatória

```
SELECT PENDENTE  →  sqs.send  →  UPDATE PUBLICADO
```

Nunca o inverso. Marcar antes de enviar troca duplicata (detectável pelo
consumidor) por perda (detectável por ninguém).

## Decisões deste step

- **Publicador separado do aplicador.** São ciclos de vida distintos: o
  aplicador roda por arquivo de retorno, o relay roda continuamente. Juntá-los
  reintroduziria o SQS no caminho da transação.
- **Chave de dedup determinística (id da fatura).** Derivada do domínio, não
  gerada no envio — reenviar produz a mesma chave.
- **Uma mensagem por vez, um publicador.** Ver README, "simplificações".

## Definition of Done

- [ ] `RelayPublicaTest`: item vira `PUBLICADO` e a mensagem **aparece de fato**
      no SQS do LocalStack (recebida, não mockada).
- [ ] `CrashDoRelayTest`: exceção entre `send` e `UPDATE` → reexecução →
      **2 mensagens na fila com a mesma `chaveDedup`**, asserido explicitamente.
- [ ] Zero import de `software.amazon.awssdk` em `domain/`.
- [ ] CHANGELOG + commit `feat(outbox): relay publica outbox no sqs (step 05)`.
