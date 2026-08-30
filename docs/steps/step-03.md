# step-03 — Aplicar retorno (a transação)

## Objetivo

O coração do projeto: a transação única que decide **e** registra a intenção de
publicar.

## Entregáveis

- `api/LinhaRetorno` — record da linha do arquivo de retorno
  (`tentativaId`, `resultado`, `motivo`).
- `domain/usecase/AplicarRetornoUseCase` — recebe linhas, faz a transição.
- `infra/persistence/RepositorioFaturaPostgres`,
  `infra/persistence/RepositorioOutboxPostgres` — JDBC puro
  (`RepositorioTentativaPostgres` vem do step-02).
- Testes: `RetornoAplicadoTest`, `RetornoDuplicadoTest`,
  `MultiplasTentativasTest`, `DualWriteEvitadoTest`.

## Regra

```sql
UPDATE tentativa_debito SET status = ?, motivo = ?
 WHERE id = ? AND status = 'ENVIADO_PARCEIRO'
```

Se afetar **0 linhas**, a linha de retorno já foi aplicada, ou o ciclo já
fechou e a tentativa virou `SEM_RETORNO` — ignora e segue. Se afetar 1 linha
**e o desfecho for `PAGO`**, na **mesma transação**:

```sql
UPDATE fatura SET status = 'PAGA' WHERE id = ? AND status = 'ABERTA';
INSERT INTO outbox (fatura_id, payload, status) VALUES (?, ?, 'PENDENTE');
```

Duas escritas, um banco, um `COMMIT`. Nenhuma chamada externa dentro da
transação.

`NAO_PAGO`, `ERRO` e `SEM_RETORNO` atualizam a tentativa e **param aí** — a
pergunta é `TentativaDebito.Status.geraLancamentoContabil()`, e só `PAGO`
responde sim.

## Decisões deste step

- **UPDATE condicional em vez de tabela de dedup.** O estado atual da tentativa
  já é a chave de idempotência; uma tabela separada seria um segundo lugar para
  a mesma verdade ficar desatualizada.
- **A guarda `AND status = 'ABERTA'` no update da fatura** cobre o caso de duas
  tentativas da mesma fatura pagarem: a segunda não gera outbox.
- **A regra de "quem gera lançamento" mora no enum**, não num `if` do use case.
  Um estado novo obriga a responder a pergunta.

## Definition of Done

- [ ] Os quatro testes passam.
- [ ] `RetornoDuplicadoTest` prova **1** linha no outbox para 2 aplicações.
- [ ] `MultiplasTentativasTest` prova **1** linha no outbox para 2 tentativas.
- [ ] `DualWriteEvitadoTest` prova que, com rollback forçado, o outbox está
      vazio, a fatura está `ABERTA` e a reexecução processa normal.
- [ ] Um retorno `NAO_PAGO` não gera linha no outbox e grava o motivo.
- [ ] `PublicadorLancamento` **não** é chamado por este use case.
- [ ] CHANGELOG + commit `feat(outbox): aplicar retorno em transação única (step 03)`.
