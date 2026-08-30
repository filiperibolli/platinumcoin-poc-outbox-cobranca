# PLAN.md — mini-outbox-cobranca

Um step por sessão. O primeiro step **não marcado** é o próximo a executar.

## Steps

- [x] **step-01 — Fundação** · [docs/steps/step-01.md](docs/steps/step-01.md)
  Scaffold Maven, docker-compose (Postgres + LocalStack), modelo de domínio,
  portas, schema (`fatura`, `tentativa_debito`, `outbox`) e Testcontainers
  verde com um teste de conexão.

- [ ] **step-02 — Aplicar retorno (a transação)** · [docs/steps/step-02.md](docs/steps/step-02.md)
  `AplicarRetornoUseCase`. Transição por UPDATE condicional
  (`WHERE id = ? AND status = 'ENVIADA'`). Na **mesma** transação: fatura vira
  `PAGA` e uma linha entra no `outbox`. Duas escritas, um banco, uma transação.
  Testes: `RetornoAplicadoTest`, `RetornoDuplicadoTest`, `MultiplasTentativasTest`,
  `DualWriteEvitadoTest`.

- [ ] **step-03 — Relay (a publicação)** · [docs/steps/step-03.md](docs/steps/step-03.md)
  `PublicarOutboxUseCase`. Lê `PENDENTE`, publica no SQS, marca `PUBLICADO`.
  Chave de dedup determinística (id da fatura) em atributo da mensagem.
  Testes: `RelayPublicaTest`, `CrashDoRelayTest`.

- [ ] **step-04 — Cenário ponta a ponta** · [docs/steps/step-04.md](docs/steps/step-04.md)
  `Main` que roda 3 faturas — uma com retorno duplicado, uma com duas
  tentativas, uma com crash simulado no relay — imprimindo cada transição.

## Mapa de arquivos previsto

```
domain/model/       Fatura, TentativaDebito, LancamentoContabil, RegistroOutbox
domain/port/        RepositorioFatura, RepositorioOutbox, PublicadorLancamento
                    Transacao (fronteira transacional, não é porta de negócio)
domain/usecase/     AplicarRetornoUseCase (02), PublicarOutboxUseCase (03)
domain/exception/   FalhaDePersistencia
api/                LinhaRetorno (02)
infra/persistence/  RepositorioFaturaPostgres (02), RepositorioOutboxPostgres (02/03)
infra/config/       Ambiente (03) — DataSource + SqsClient
Main                (04)
```

## Invariante que o projeto existe para provar

> No máximo **um** `LancamentoContabil` por fatura, qualquer que seja o número
> de tentativas de débito ou de reprocessamentos do arquivo de retorno.

O que o projeto **não** promete: exatamente-uma-vez na fila. Promete
at-least-once com chave de dedup estável — ver `docs/adr/0002-*`.
