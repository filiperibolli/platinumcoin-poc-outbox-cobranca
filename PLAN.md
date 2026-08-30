# PLAN.md — mini-outbox-cobranca

Um step por sessão. O primeiro step **não marcado** é o próximo a executar.

A ordem segue o ciclo de vida real da cobrança: monta, envia, recebe retorno,
fecha, publica. Não a ordem em que as peças foram pensadas.

## Steps

- [x] **step-01 — Fundação** · [docs/steps/step-01.md](docs/steps/step-01.md)
  Scaffold Maven, docker-compose (Postgres + LocalStack), modelo de domínio,
  portas, schema (`fatura`, `ciclo_cobranca`, `tentativa_debito`, `outbox`) e
  Testcontainers verde.

- [ ] **step-02 — Montagem de ciclo** · [docs/steps/step-02.md](docs/steps/step-02.md)
  `MontarCicloUseCase`. Em **uma** transação: `INSERT` no ciclo e
  `UPDATE tentativa_debito SET ciclo_id, status='SOLICITADO' WHERE status='ABERTO'`.
  `UNIQUE (banco, data_ref)` torna a reexecução segura **por construção**.
  A remessa é função pura do ciclo.
  Testes: `MontagemDeterministicaTest`, `TrabalhoDerivadoDeterministicoTest`.

- [ ] **step-03 — Aplicar retorno** · [docs/steps/step-03.md](docs/steps/step-03.md)
  `AplicarRetornoUseCase`. Transição por UPDATE condicional
  (`WHERE id = ? AND status = 'ENVIADO_PARCEIRO'`). Na **mesma** transação:
  fatura vira `PAGA` e uma linha entra no `outbox` — **só quando o desfecho é
  `PAGO`**. Duas escritas, um banco, uma transação.
  Testes: `RetornoAplicadoTest`, `RetornoDuplicadoTest`, `MultiplasTentativasTest`,
  `DualWriteEvitadoTest`.

- [ ] **step-04 — Fechamento de ciclo** · [docs/steps/step-04.md](docs/steps/step-04.md)
  `FecharCicloUseCase`. Quem continua `ENVIADO_PARCEIRO` vira `SEM_RETORNO` —
  nunca `NAO_PAGO`. Silêncio não é resposta.
  Teste: `FechamentoNaoInventaResultadoTest`.

- [ ] **step-05 — Relay** · [docs/steps/step-05.md](docs/steps/step-05.md)
  `PublicarOutboxUseCase`. Lê `PENDENTE`, publica no SQS, marca `PUBLICADO`.
  Chave de dedup determinística (id da fatura) em atributo da mensagem.
  Testes: `RelayPublicaTest`, `CrashDoRelayTest`.

- [ ] **step-06 — Cenário ponta a ponta** · [docs/steps/step-06.md](docs/steps/step-06.md)
  `Main` que roda um ciclo com 3 faturas — retorno duplicado, duas tentativas,
  crash simulado no relay — mais uma tentativa sem retorno, imprimindo cada
  transição.

## As duas invariantes que o projeto existe para provar

> **1.** No máximo **um** `LancamentoContabil` por fatura, qualquer que seja o
> número de tentativas de débito ou de reprocessamentos do arquivo de retorno.

> **2.** A montagem do ciclo é a **única** escrita que importa. Remessa, retorno,
> fechamento e publicação são trabalho derivado, re-executável a partir dela.

O que o projeto **não** promete: exatamente-uma-vez na fila. Promete
at-least-once com chave de dedup estável — ver `docs/adr/0002-*`.

## Máquina de estados

```
TentativaDebito
  ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO
                                         → SEM_RETORNO (via fechamento)

CicloCobranca   MONTADO → ENVIADO → FECHADO
Fatura          ABERTA → PAGA → LANCADA
```

Só `PAGO` gera linha no outbox — a pergunta é
`TentativaDebito.Status.geraLancamentoContabil()`, e ela mora no enum
justamente para que um estado novo obrigue a respondê-la.

## Orçamento de arquivos (teto: 24 de produção)

O teto conta `src/main` + `pom.xml` + `infra/`. Os testes ficam fora: as cinco
provas de falha são intocáveis por definição, e sozinhas já estourariam
qualquer teto. Se apertar, corta-se regra de negócio.

| Onde | Arquivos | Quais |
|---|---|---|
| `domain/model` | 5 | Fatura, TentativaDebito, LancamentoContabil, RegistroOutbox, **CicloCobranca** (02) |
| `domain/port` | 4 | Transacao, RepositorioFatura, RepositorioOutbox, PublicadorLancamento |
| `domain/exception` | 1 | FalhaDePersistencia |
| `domain/usecase` | 4 | MontarCiclo (02), AplicarRetorno (03), FecharCiclo (04), PublicarOutbox (05) |
| `api` | 1 | LinhaRetorno (03) |
| `infra/persistence` | 3 | RepositorioFaturaPostgres, RepositorioOutboxPostgres, PublicadorLancamentoSqs |
| `infra/config` | 1 | Ambiente (05) |
| raiz | 1 | Main (06) |
| build/infra | 4 | pom.xml, docker-compose.yml, 01-localstack.sh, 02-postgres.sql |
| **Total** | **24** | 15 já existem |

Duas consolidações já embutidas para caber: a projeção da remessa é um método
estático de `CicloCobranca` (em vez de um `GerarRemessaUseCase`), e as operações
de ciclo ficam em `RepositorioFatura` — que é o repositório do agregado de
cobrança, não só da tabela `fatura`.

## Mapa de arquivos

```
domain/model/       Fatura, TentativaDebito, LancamentoContabil, RegistroOutbox,
                    CicloCobranca (+ remessa: função pura do ciclo)
domain/port/        RepositorioFatura, RepositorioOutbox, PublicadorLancamento
                    Transacao (fronteira transacional, não é porta de negócio)
domain/usecase/     MontarCicloUseCase (02), AplicarRetornoUseCase (03),
                    FecharCicloUseCase (04), PublicarOutboxUseCase (05)
domain/exception/   FalhaDePersistencia
api/                LinhaRetorno (03)
infra/persistence/  RepositorioFaturaPostgres, RepositorioOutboxPostgres,
                    PublicadorLancamentoSqs (05)
infra/config/       Ambiente (05) — DataSource + SqsClient
Main                (06)
```
