# CLAUDE.md — mini-outbox-cobranca

## O que este projeto é

Um mini-projeto que prova **um único conceito**: como garantir que um evento
financeiro seja publicado em uma fila externa **exatamente uma vez em relação à
decisão de negócio**, quando a fila **não participa** da transação do banco.

Não é um framework, não é um produto, não é uma referência de arquitetura
completa. É uma prova de conceito com testes que demonstram os trade-offs.

## Regras não-negociáveis

1. **Java 21 + Maven.** Steps 01–06: aplicação de console com `main`, sem
   Spring. A partir do step-10 há **um único** Spring Boot (Web), e por um
   motivo só: o projeto passa a expor HTTP. O `main` de console continua
   existindo e continua funcionando.
2. **Teto de infra: Postgres + LocalStack (SQS e S3) + SFTP (`atmoz/sftp`).**
   Nada além. O teto subiu no ciclo dos steps 07–12 — ver "Ampliação de escopo"
   abaixo — e não sobe de novo sem ADR.
3. **O domínio não importa framework nem AWS SDK.** `api → domain ← infra`.
   Se um import de `software.amazon.awssdk`, `org.springframework` ou de
   biblioteca SSH aparecer em `domain/`, está errado. O pacote `simulador/` é
   o ambiente, não o sistema: nada em `domain/` ou `infra/` pode importá-lo.
4. **Um `<Verbo><Substantivo>UseCase` por operação inbound.**
5. **Nenhum sistema externo dentro da transação do banco.** A transação escreve
   em `fatura` e em `outbox`; o SQS é chamado depois, por outro componente.
6. **Os testes de falha nunca são cortados.** Se o escopo apertar, corta-se
   regra de negócio. Os cinco intocáveis, porque são a prova de que os
   trade-offs defendidos funcionam:
   `RetornoDuplicadoTest`, `MultiplasTentativasTest`, `CrashDoRelayTest`,
   `MontagemDeterministicaTest`, `FechamentoNaoInventaResultadoTest`.
   Os steps 07–12 acrescentam à lista, com a mesma proteção:
   `RemessaSobreviveAReexecucaoTest`, `CrashDepoisDoPutTest`,
   `ArquivoIncompletoNaoEhProcessadoTest`, `ArquivoEmEscritaNaoEhBaixadoTest`,
   `RetornoParticionadoTest`.
7. **`mvn test` sobe tudo sozinho** via Testcontainers, sem depender do Compose.
8. **Enxuto é propriedade do desenho, não cota de arquivos.** Não há teto.
   A régua é outra: cada arquivo carrega uma responsabilidade que dá para
   nomear sem usar "e". Um arquivo a mais que deixa a fronteira mais nítida é
   ganho; um arquivo a mais que só muda código de lugar é custo. O que se corta
   quando aperta é **escopo** — regra de negócio, canal, formato — nunca a
   clareza da fronteira e nunca os testes de falha.
9. **Só `PAGO` gera lançamento contábil.** `NAO_PAGO`, `ERRO` e `SEM_RETORNO`
   nunca. A regra mora em `TentativaDebito.Status.geraLancamentoContabil()`.

## Ampliação de escopo (steps 07–12)

Os steps 01–06 provam o outbox: a fronteira transacional entre banco e fila. Os
steps 07–12 materializam o outro lado do desenho — o ciclo de arquivo contra um
parceiro real, com API e painel — para que o mecanismo possa ser **visto
acontecendo** em vez de deduzido de uma suíte verde.

A premissa passou a ser: **compreender o mecanismo vale mais que manter o
projeto pequeno.** Canal e formato saíram de "fora de escopo" e viraram código.
Nenhuma decisão dos steps 01–06 foi revista: quem decide o estado de uma
tentativa continua sendo o `UPDATE` condicional do step-03, e as duas
invariantes do `PLAN.md` continuam sendo a razão de o projeto existir.

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
