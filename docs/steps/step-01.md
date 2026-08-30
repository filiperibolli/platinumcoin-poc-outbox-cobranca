# step-01 — Fundação

## Objetivo

Ter um projeto que compila, um ambiente que sobe com um comando e um teste
Testcontainers verde. Nenhuma regra de negócio ainda — só o chão onde os steps
02–04 vão pisar.

## Entregáveis

- `pom.xml` — Java 21, JUnit 5, Testcontainers (Postgres + LocalStack),
  driver Postgres, AWS SDK v2 (só SQS).
- `infra/docker-compose.yml` — Postgres + LocalStack, com init automático.
- `infra/init/02-postgres.sql` — schema: `fatura`, `ciclo_cobranca`,
  `tentativa_debito`, `outbox`.
- `infra/init/01-localstack.sh` — cria a fila `lancamentos-contabeis`.
- `domain/model/` — `Fatura`, `TentativaDebito`, `LancamentoContabil`,
  `RegistroOutbox` (records; enums aninhados).
- `domain/port/` — `RepositorioFatura`, `RepositorioOutbox`,
  `PublicadorLancamento` (interfaces, ainda sem implementação), mais `Transacao`
  — que não é uma porta de negócio, e sim o tipo que deixa o use case dizer
  "estas duas escritas são uma só" sem saber que existe JDBC.
- `domain/exception/FalhaDePersistencia`.
- `src/test/.../AmbienteDeTeste` — base da suíte: sobe Postgres + LocalStack e
  aplica os **mesmos** scripts de `infra/init/`.
- `src/test/.../FundacaoTest` — as quatro tabelas existem, os `UNIQUE` do outbox
  e do ciclo existem, só `PAGO` gera lançamento, a fila existe no SQS, e o
  domínio não importa framework nem AWS.

## Decisões deste step

- **Records + enums aninhados.** O modelo é de dados, não de comportamento; o
  comportamento vive nos use cases. Enums aninhados (`Fatura.Status`) evitam
  quatro arquivos de uma linha.
- **O mesmo `.sql` serve Compose e Testcontainers.** Uma única fonte de verdade
  para o schema; se divergirem, o teste passa e a produção quebra.
- **`outbox.fatura_id` com `UNIQUE`.** A invariante "no máximo um lançamento por
  fatura" fica no banco, não só no código. É a rede de proteção sob o `UPDATE`
  condicional do step-03.
- **Portas sem implementação neste step.** Definir a fronteira antes de ter
  infra é o que impede o domínio de nascer acoplado ao JDBC.
- **O isolamento do domínio é um teste, não uma convenção.** `dominioIsolado`
  varre os imports de `domain/` e falha se aparecer algo que não seja `java.*`.
  Regra de arquitetura que ninguém executa é decoração.
- **`api.version` fixado no surefire.** O docker-java negocia uma versão de API
  que daemons recentes (Docker 25+) recusam com HTTP 400. Sem isso, `mvn test`
  só rodaria com variável de ambiente — e a DoD diz que não precisa.

## Definition of Done

- [x] `mvn -q compile` sem erro.
- [x] `mvn test` verde, subindo Postgres e LocalStack via Testcontainers, sem
      `docker compose` rodando.
- [x] `FundacaoTest.dominioIsolado` verde: nenhum import fora de `java.*` em
      `domain/`.
- [x] `docker compose -f infra/docker-compose.yml up` deixa banco e fila
      prontos, sem passo manual.
- [x] `PLAN.md` com step-01 marcado, `CHANGELOG.md` atualizado, commit
      `feat(outbox): fundação, schema e ambiente de teste (step 01)`.

## Nota — realinhamento posterior

Depois deste step, antes do step-02, o projeto foi realinhado ao desenho de
referência: a máquina de estados de `TentativaDebito` passou a ser
`ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO | SEM_RETORNO`,
e o schema ganhou `ciclo_cobranca` mais as colunas `banco`, `data_ref`,
`ciclo_id` e `motivo` em `tentativa_debito`. Os steps foram reordenados para
seguir o ciclo de vida real — ver `CHANGELOG.md`. A DoD acima continua válida e
verificada.
