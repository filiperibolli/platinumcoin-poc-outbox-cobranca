# step-02 — Aplicar retorno (a transação)

## Objetivo

O coração do projeto: a transação única que decide **e** registra a intenção de
publicar.

## Entregáveis

- `api/LinhaRetorno` — record da linha do arquivo de retorno
  (`tentativaId`, `resultado`).
- `domain/usecase/AplicarRetornoUseCase` — recebe linhas, faz a transição.
- `infra/persistence/RepositorioFaturaPostgres`,
  `infra/persistence/RepositorioOutboxPostgres` — JDBC puro.
- Testes: `RetornoAplicadoTest`, `RetornoDuplicadoTest`,
  `MultiplasTentativasTest`, `DualWriteEvitadoTest`.

## Regra

```sql
UPDATE tentativa_debito SET status = 'PAGA'
 WHERE id = ? AND status = 'ENVIADA'
```

Se afetar **0 linhas**, a linha de retorno já foi aplicada (ou a tentativa não
está num estado que aceita retorno) — ignora e segue. Se afetar 1 linha, na
**mesma transação**:

```sql
UPDATE fatura SET status = 'PAGA' WHERE id = ? AND status = 'ABERTA';
INSERT INTO outbox (fatura_id, payload, status) VALUES (?, ?, 'PENDENTE');
```

Duas escritas, um banco, um `COMMIT`. Nenhuma chamada externa dentro da
transação.

## Decisões deste step

- **UPDATE condicional em vez de tabela de dedup.** O estado atual da tentativa
  já é a chave de idempotência; uma tabela separada seria um segundo lugar para
  a mesma verdade ficar desatualizada.
- **A guarda `AND status = 'ABERTA'` no update da fatura** cobre o caso de duas
  tentativas da mesma fatura pagarem: a segunda não gera outbox.

## Definition of Done

- [ ] Os quatro testes passam.
- [ ] `RetornoDuplicadoTest` prova **1** linha no outbox para 2 aplicações.
- [ ] `MultiplasTentativasTest` prova **1** linha no outbox para 2 tentativas.
- [ ] `DualWriteEvitadoTest` prova que, com rollback forçado, o outbox está
      vazio, a fatura está `ABERTA` e a reexecução processa normal.
- [ ] `PublicadorLancamento` **não** é chamado por este use case.
- [ ] CHANGELOG + commit `feat(outbox): aplicar retorno em transação única (step 02)`.
