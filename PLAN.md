# PLAN.md — mini-outbox-cobranca

Um step por sessão. O primeiro step **não marcado** é o próximo a executar.

A ordem segue o ciclo de vida real da cobrança: monta, envia, recebe retorno,
fecha, publica. Não a ordem em que as peças foram pensadas.

## Steps

- [x] **step-01 — Fundação** · [docs/steps/step-01.md](docs/steps/step-01.md)
  Scaffold Maven, docker-compose (Postgres + LocalStack), modelo de domínio,
  portas, schema (`fatura`, `ciclo_cobranca`, `tentativa_debito`, `outbox`) e
  Testcontainers verde.

- [x] **step-02 — Montagem de ciclo** · [docs/steps/step-02.md](docs/steps/step-02.md)
  `MontarCicloUseCase`. Em **uma** transação: `INSERT` no ciclo e
  `UPDATE tentativa_debito SET ciclo_id, status='SOLICITADO' WHERE status='ABERTO'`.
  `UNIQUE (banco, data_ref)` torna a reexecução segura **por construção**.
  `GerarRemessaUseCase` projeta a remessa — função pura do ciclo.
  Testes: `MontagemDeterministicaTest`, `TrabalhoDerivadoDeterministicoTest`.

- [x] **step-03 — Aplicar retorno** · [docs/steps/step-03.md](docs/steps/step-03.md)
  `AplicarRetornoUseCase`. Transição por UPDATE condicional
  (`WHERE id = ? AND status = 'ENVIADO_PARCEIRO'`). Na **mesma** transação:
  fatura vira `PAGA` e uma linha entra no `outbox` — **só quando o desfecho é
  `PAGO`**. Duas escritas, um banco, uma transação.
  Testes: `RetornoAplicadoTest`, `RetornoDuplicadoTest`, `MultiplasTentativasTest`,
  `DualWriteEvitadoTest`.

- [x] **step-04 — Fechamento de ciclo** · [docs/steps/step-04.md](docs/steps/step-04.md)
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

## Enxuto por desenho, não por cota

Não há teto de arquivos. A régua é: **cada arquivo carrega uma responsabilidade
que dá para nomear sem usar "e"**. Um arquivo a mais que deixa a fronteira mais
nítida é ganho; um arquivo a mais que só muda código de lugar é custo.

Quando o escopo apertar, corta-se **escopo** — regra de negócio, canal, formato —
e não a estrutura. Nunca os testes de falha. Um projeto que perde a porta certa
para caber numa contagem não ficou enxuto, ficou mal desenhado com menos
arquivos.

## Mapa de arquivos

Uma porta por agregado, um use case por operação inbound.

```
domain/model/       Fatura, CicloCobranca, TentativaDebito, Remessa (02),
                    LancamentoContabil, RegistroOutbox
domain/port/        RepositorioFatura, RepositorioCiclo, RepositorioTentativa,
                    RepositorioOutbox, PublicadorLancamento
                    Transacao (fronteira transacional, não é porta de negócio)
domain/usecase/     MontarCicloUseCase (02), GerarRemessaUseCase (02),
                    AplicarRetornoUseCase (03), FecharCicloUseCase (04),
                    PublicarOutboxUseCase (05)
domain/exception/   FalhaDePersistencia
api/                LinhaRetorno (03)
infra/persistence/  TransacaoJdbc, RepositorioFaturaPostgres,
                    RepositorioCicloPostgres, RepositorioTentativaPostgres,
                    RepositorioOutboxPostgres, PublicadorLancamentoSqs (05)
infra/config/       Ambiente (05) — DataSource + SqsClient
Main                (06)
```

Já existem: os sete modelos, as seis portas, a exceção, os use cases de
montagem, de geração de remessa, de aplicação de retorno e de fechamento de
ciclo, a `api/LinhaRetorno` e, em `infra/persistence`, o `TransacaoJdbc` mais os
quatro repositórios. Falta implementar `RepositorioOutbox.marcarPublicado`
(step-05) — ele chega junto com o teste que o prova.
