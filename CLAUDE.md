# CLAUDE.md — mini-outbox-cobranca

## O propósito, acima de qualquer outra regra deste arquivo

Este projeto existe para ser **a pergunta de um system design e a sua solução.**
O objetivo é descrever um problema de system design e resolvê-lo em código.

Disso decorre a única prioridade que vence as outras: **o `README.md` é o
entregável principal.** Ele precisa conter o problema e a solução por escrito,
com **teste de mesa** — o ciclo percorrido à mão, com o estado depois de cada
passo — e o **desenho em Mermaid**. O código é a prova de que o documento é
verdadeiro, não o contrário.

Consequências práticas, quando houver conflito com o resto deste arquivo:

1. **Nenhuma mudança de código está pronta enquanto o README ainda descrever o
   comportamento antigo.** Um teste verde com README desatualizado é um step
   incompleto.
2. **Toda afirmação do README precisa ser conferível.** Bytes, `sha256`, SQL e
   corpos de resposta saem do código ou de uma execução real — nunca de memória.
   Se um número aparece no README, ele foi calculado ou observado.
3. **A pergunta e as quatro dificuldades são a espinha do documento.** Um
   mecanismo que não puder ser ligado de volta a uma delas está sobrando; uma
   dificuldade sem mecanismo é um buraco.

## A pergunta

> Como fechar um ciclo diário de cobrança contra um parceiro que só fala por
> arquivo, não avisa quando responde, às vezes responde pela metade, às vezes
> reenvia o mesmo arquivo, e às vezes não responde — garantindo que cada fatura
> paga gere exatamente um lançamento no razão contábil?

### As quatro dificuldades que ela esconde

1. **Duas escritas, dois sistemas, nenhuma transação — duas vezes.** Na montagem
   (Postgres + artefato no S3) e na publicação (Postgres + fila). Qualquer ordem
   tem uma janela de morte que produz perda ou duplicidade. Resposta: uma escrita
   transacional que decide, e todo o resto vira trabalho derivado do commitado.
2. **Não existe callback.** Força varredura periódica, e duas decisões que nada
   mais no sistema exige: **quiescência** (parou de crescer) e **trailer**
   (a contagem fecha). Sem elas, processa-se arquivo pela metade.
3. **O mesmo retorno chega de várias formas.** Reenvio idêntico, partição com
   sobreposição, linha duplicada, reprocessamento manual. Quatro problemas, uma
   solução: `UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'`. A idempotência não
   vem de tabela de dedup nem de hash — vem de a transição ser condicional.
4. **O silêncio não é uma resposta.** Ausência vira `SEM_RETORNO`, nunca
   `NAO_PAGO`: marcar não-pago dispararia notificação ao cliente com base num
   fato que ninguém afirmou.

Os mecanismos são de **duas famílias**, e misturá-las esconde o desenho. As três
primeiras — escrita transacional, trabalho derivado, transição condicional —
garantem **correção sob falha**. Quiescência, trailer e `SEM_RETORNO` garantem
**honestidade**: que o sistema não afirme o que ninguém disse.

## O que este projeto é

Uma prova de conceito com testes que demonstram os trade-offs. Não é um
framework, não é um produto, não é uma referência de arquitetura completa.

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
10. **A `Fatura` para em `PAGA`.** Quem responde "o lançamento já saiu?" é o
    `outbox`, que tem `UNIQUE (fatura_id)`. Um estado `LANCADA` na fatura seria
    uma segunda cópia do mesmo fato — o dual write do ADR-0001 em escala menor —
    e exigiria um `UPDATE` depois do `send`, dentro da janela do relay.

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

## Workflow

**Os doze steps estão escritos.** O `PLAN.md` registra o que cada um entregou;
não há step pendente. O que vier agora é manutenção ou um step novo, e os dois
seguem o mesmo ciclo:

1. Escrever o teste **antes ou junto** da implementação.
2. Implementar.
3. Rodar `mvn test`.
4. **Atualizar o `README.md`** — ver "O propósito" no topo deste arquivo. Uma
   mudança de comportamento com README velho é trabalho pela metade, e toda
   afirmação nova precisa ser conferida contra o código ou contra uma execução,
   nunca escrita de memória.
5. Atualizar o `CHANGELOG.md` (com a linha de métricas AI) e, se for um step
   novo, marcá-lo no `PLAN.md`.
6. Commit convencional: `feat(outbox): <o que>`.
7. **PARAR.** Não emendar o próximo assunto.

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
