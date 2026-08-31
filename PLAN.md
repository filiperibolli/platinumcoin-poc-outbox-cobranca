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

- [x] **step-05 — Relay** · [docs/steps/step-05.md](docs/steps/step-05.md)
  `PublicarOutboxUseCase`. Lê `PENDENTE`, publica no SQS, marca `PUBLICADO`.
  Chave de dedup determinística (id da fatura) em atributo da mensagem.
  Testes: `RelayPublicaTest`, `CrashDoRelayTest`.

- [x] **step-06 — Cenário ponta a ponta** · [docs/steps/step-06.md](docs/steps/step-06.md)
  `Main` que roda um ciclo com 4 faturas — retorno duplicado, duas tentativas,
  crash simulado no relay — mais uma tentativa sem retorno, imprimindo cada
  transição. O step-06.md fala em 3 faturas e 4 tentativas, mas a história da
  `F-2` (recusa e reapresentação) já consome duas: a fatura silenciosa é a
  quarta, `F-4`.
  Teste: `CenarioPontaAPontaTest`.

- [x] **step-07 — Remessa no S3** · [docs/steps/step-07.md](docs/steps/step-07.md)
  Separa geração de transmissão, com artefato durável entre as duas.
  `ArmazenamentoArtefato` (put/get/existe) e chave determinística derivada do
  ciclo. Layout posicional de largura fixa: header, N detalhes com
  `id_tentativa` em posição fixa, trailer com a contagem.
  Testes: `RemessaDeterministicaTest`, `RemessaSobreviveAReexecucaoTest`.

- [x] **step-08 — Envio por SFTP** · [docs/steps/step-08.md](docs/steps/step-08.md)
  `EnviarRemessaUseCase` lê o artefato do S3 e transmite por SSH de verdade
  (`atmoz/sftp`). Nome determinístico no destino: reenvio sobrescreve.
  A janela `put` → `COMMIT` fica marcada no código, não escondida.
  Testes: `EnvioChegaNoParceiroTest`, `CrashDepoisDoPutTest`.

- [x] **step-09 — Coleta de retorno** · [docs/steps/step-09.md](docs/steps/step-09.md)
  `ColetarRetornoUseCase`. Sem callback: varredura periódica, quiescência
  (tamanho e mtime iguais em duas leituras) e trailer decidindo completude.
  Arquivo que não fecha é descartado inteiro e reavaliado na próxima passada.
  `LeitorDeRetorno` é a porta que mantém `api → domain` de pé: quem sabe posição
  de campo é `api/ArquivoRetorno`, e o coletor só sabe que alguém responde
  "de que recorte é, se fecha, e o que afirma".
  Testes: `ArquivoIncompletoNaoEhProcessadoTest`,
  `ArquivoEmEscritaNaoEhBaixadoTest`, `RetornoParticionadoTest`,
  `ReenvioDeRetornoTest`.

- [ ] **step-10 — API de operação** · [docs/steps/step-10.md](docs/steps/step-10.md)
  Spring Boot (Web) entra aqui, e só por isto: o projeto passa a expor HTTP.
  Um `POST` por passo do ciclo, cada resposta descrevendo o efeito produzido;
  `GET /estado` com o snapshot das cinco fontes. Cada chamada é uma execução
  do job que o EventBridge dispararia. Zero regra no controller.
  Testes: `EndpointsDevolvemEfeitoTest`, `ControllerNaoDecideTest`.

- [ ] **step-11 — Simulador do parceiro e provocação de falhas** · [docs/steps/step-11.md](docs/steps/step-11.md)
  Pacote `simulador/`, fora de `domain` e `infra`: é o ambiente, não o sistema.
  Processa a remessa lida do SFTP e escreve o retorno — particionado, atrasado,
  truncado, reenviado ou nenhum. Mais os dois crashes provocáveis por `POST`.
  Testes: `SimuladorProduzRetornoAplicavelTest`, `FalhasProvocadasTest`.

- [ ] **step-12 — Painel HTML** · [docs/steps/step-12.md](docs/steps/step-12.md)
  Um arquivo estático servido pelo Spring — HTML, CSS e JS puro, sem build e
  sem CDN. Botões na ordem do fluxo, falhas em seção separada, estado por
  polling de 2s e log de eventos append-only.
  Teste: `PainelEhServidoTest`.

## O que os steps 07–12 acrescentam

Os steps 01–06 provam o outbox: a fronteira transacional entre banco e fila.
Os steps 07–12 materializam **o outro lado do desenho** — o ciclo de arquivo
contra um parceiro real — para que o mecanismo possa ser **visto acontecendo**
em vez de deduzido de uma suíte verde.

Isso amplia o escopo de propósito. A premissa passa a ser: **compreender o
mecanismo vale mais que manter o projeto pequeno.** O teto de infra sobe para
Postgres + S3 + SQS + SFTP, e o Spring Boot entra por um motivo único — o
projeto passa a expor HTTP.

O que **não** muda: quem decide o estado de uma tentativa continua sendo o
`UPDATE` condicional do step-03, e as duas invariantes abaixo continuam sendo a
razão de o projeto existir. Canal e formato mudam de "fora de escopo" para
"implementados"; nenhuma decisão dos steps 01–06 é revista.

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
                    LancamentoContabil, RegistroOutbox,
                    ChaveArtefato (07), Sha256 (09)
domain/port/        RepositorioFatura, RepositorioCiclo, RepositorioTentativa,
                    RepositorioOutbox, PublicadorLancamento,
                    ArmazenamentoArtefato (07), CanalArquivos (08, ampliada em 09)
                    RepositorioArquivoRetorno (09), LeitorDeRetorno (09)
                    Transacao (fronteira transacional, não é porta de negócio)
domain/usecase/     MontarCicloUseCase (02), GerarRemessaUseCase (02, grava em 07),
                    AplicarRetornoUseCase (03), FecharCicloUseCase (04),
                    PublicarOutboxUseCase (05), EnviarRemessaUseCase (08),
                    ColetarRetornoUseCase (09)
domain/exception/   FalhaDePersistencia, FalhaDePublicacao (05)
api/                LinhaRetorno (03), ArquivoRetorno (09) — parser e trailer
api/http/           um controller por passo (10) + DTOs de efeito
infra/persistence/  TransacaoJdbc, RepositorioFaturaPostgres,
                    RepositorioCicloPostgres, RepositorioTentativaPostgres,
                    RepositorioOutboxPostgres, PublicadorLancamentoSqs (05),
                    Payload (05) — o corpo do lançamento, gravado e publicado
                    ArmazenamentoArtefatoS3 (07), RepositorioArquivoRetornoPostgres (09)
infra/canal/        CanalArquivosSftp (08)
infra/falha/        decoradores das falhas provocáveis (11)
infra/config/       Ambiente (05) — DataSource + SqsClient + S3Client + SFTP
simulador/          ParceiroSimulado e seus endpoints (11) — o ambiente, não o sistema
resources/static/   index.html — o painel (12)
Main                (06) — o cenário de console, que continua existindo
AplicacaoHttp       (10) — o servidor
```

Do step-06 tudo existe: os sete modelos, as seis portas, as duas exceções, os
cinco use cases, a `api/LinhaRetorno`, o `infra/config/Ambiente`, o
`TransacaoJdbc`, os quatro repositórios, o `Payload`, o
`PublicadorLancamentoSqs` e o `Main`, que amarra as peças num cenário só.

O que está marcado com (07) a (12) é plano, não código. A régua para julgá-lo é
a mesma: cada arquivo com uma responsabilidade que dá para nomear sem usar "e".
Se um step apertar, corta-se **escopo** — regra de negócio, canal, formato —
nunca os testes de falha.
