# CLAUDE.md — mini-outbox-cobranca

## O que este projeto é

Um mini-projeto que prova **um único conceito**: como garantir que um evento
financeiro seja publicado em uma fila externa **exatamente uma vez em relação à
decisão de negócio**, quando a fila **não participa** da transação do banco.

Não é um framework, não é um produto, não é uma referência de arquitetura
completa. É uma prova de conceito com testes que demonstram os trade-offs.

## Regras não-negociáveis

1. **Java 21 + Maven. Sem Spring Boot.** Aplicação simples com `main`.
2. **Teto de infra: Postgres + 1 serviço AWS (SQS via LocalStack).** Nada além.
3. **O domínio não importa framework nem AWS SDK.** `api → domain ← infra`.
   Se um import de `software.amazon.awssdk` aparecer em `domain/`, está errado.
4. **Um `<Verbo><Substantivo>UseCase` por operação inbound.**
5. **Nenhum sistema externo dentro da transação do banco.** A transação escreve
   em `fatura` e em `outbox`; o SQS é chamado depois, por outro componente.
6. **Os testes de falha nunca são cortados.** Se o escopo apertar, corta-se
   regra de negócio. `CrashDoRelayTest` e `DualWriteEvitadoTest` são a razão de
   o projeto existir.
7. **`mvn test` sobe tudo sozinho** via Testcontainers, sem depender do Compose.

## Convenções

- Nomes de domínio em português (`Fatura`, `TentativaDebito`, `LancamentoContabil`).
- Pontos de decisão marcados no código no formato:
  ```java
  // DECISÃO: outbox na mesma transação, publicação fora — ver ADR-0001
  // DECISÃO: UPDATE condicional em vez de tabela de dedup — ver README
  ```
  Um comentário desses marca uma escolha com alternativa real descartada.
  Não usar para explicar o que o código já diz.
- Records para modelo de domínio; enums aninhados no record a que pertencem.
- SQL escrito à mão com JDBC puro. Sem ORM.

## Workflow (um step por sessão)

1. Ler `PLAN.md` e achar o primeiro step **não marcado**.
2. Ler `docs/steps/step-NN.md`.
3. Escrever o teste **antes ou junto** da implementação.
4. Implementar.
5. Rodar `mvn test` e conferir a **Definition of Done** do step.
6. Marcar o step no `PLAN.md`, atualizar o `CHANGELOG.md` (com a linha de
   métricas AI).
7. Commit convencional: `feat(outbox): <o que> (step NN)`.
8. **PARAR.** Não emendar o step seguinte.

## Ambiente

`mvn test` roda sem nenhuma variável de ambiente. Duas coisas no `pom.xml`
cuidam disso, e é bom saber que existem antes de estranhar:

- **`docker.api.version` (1.44) fixado no surefire.** O docker-java negocia uma
  versão de API que daemons Docker 25+ recusam com HTTP 400. Daemon antigo:
  `mvn test -Ddocker.api.version=1.41`.
- **Perfil `wsl-docker-desktop`**, ativado sozinho quando
  `/mnt/wsl/docker-desktop-bind-mounts/Ubuntu/docker.sock` existe — WSL2 com
  Docker Desktop sem integração WSL. Inerte em Linux nativo, macOS ou WSL com
  integração ligada.
