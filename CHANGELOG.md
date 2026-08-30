# CHANGELOG

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Um step por entrada.

## [step-02] — 2026-08-30 — Montagem de ciclo

A escrita que importa. Tudo o que vem depois — remessa, retorno, fechamento,
publicação — passa a ser trabalho derivado dela.

### Adicionado

- **`MontarCicloUseCase`** — numa transação, `INSERT` no ciclo mais
  `UPDATE tentativa_debito SET ciclo_id, status = 'SOLICITADO'` no recorte
  (banco + data de referência). Ou existe o ciclo com as tentativas dentro, ou
  não existe nada.
- **`domain/model/Remessa`** — o artefato e seu formato: uma função pura do
  ciclo e de suas tentativas, três campos posicionais por linha
  (id da tentativa, id da fatura, número), `\n` fixo e `Locale.ROOT`.
- **`GerarRemessaUseCase`** — lê o ciclo pela porta e delega a projeção à
  `Remessa`. Separado da montagem porque promete o oposto dela: a montagem
  acontece uma vez, a geração acontece quantas vezes for preciso com resultado
  idêntico.
- **`infra/persistence/TransacaoJdbc`** — a `Transacao` do domínio encarnada
  numa conexão com `autoCommit` desligado, mais a `Fabrica` que a abre. É o
  único ponto do projeto que sabe que "uma transação" e "uma conexão" são a
  mesma coisa — e é o que faz escritas de repositórios diferentes caírem no
  mesmo `COMMIT`.
- **`RepositorioCicloPostgres`** e **`RepositorioTentativaPostgres`** — JDBC
  puro. Os métodos de retorno (step-03) e de fechamento (step-04) ficam
  declarados e não implementados: SQL sem teste nasce parecendo pronto.
- **`Cenario`** nos testes — os dados de partida (fatura aberta com tentativa
  `ABERTO`), fora do `AmbienteDeTeste`, que cuida dos containers.
- **`AmbienteDeTeste.dados()`** — um `DataSource`, o mesmo tipo que a infra
  recebe em produção, em vez de conexões passadas à mão.

### Decisões

- **Idempotência por constraint, não por consulta prévia.** A segunda montagem
  do mesmo banco e data estoura no `UNIQUE (banco, data_ref)`. Consultar antes
  seria uma corrida: dois processos leem "não existe" e ambos inserem.
- **A remessa é ordenada dentro da projeção, e não só no `ORDER BY`.** Assim a
  igualdade byte a byte é propriedade da `Remessa`, e não de quem a alimenta.
- **`\n` fixo em vez de `System.lineSeparator()`.** A remessa gerada no Windows
  e a gerada no Linux precisam ser o mesmo arquivo.
- **`close()` da transação não lança.** Chamado pelo `try-with-resources`, um
  erro ali esconderia a exceção que de fato abortou o use case — e é ela que
  diz por que a transação está sendo desfeita.

### Verificado

- `mvn test` → 11 testes, 0 falhas (6 de fundação, 3 de montagem, 2 de trabalho
  derivado).
- `MontagemDeterministicaTest`: o recorte é respeitado (outro banco e outra data
  continuam `ABERTO`); falha entre o `INSERT` e o `UPDATE` não deixa ciclo órfão
  nem tentativa meio-atribuída, e o recorte continua montável; a segunda
  montagem não cria um segundo ciclo.
- `TrabalhoDerivadoDeterministicoTest`: duas gerações comparadas como Strings
  inteiras, e o formato posicional conferido linha a linha com as tentativas
  inseridas fora de ordem.

AI: est 1h30 / actual 25min / ~95% generated / 2 issues caught in review

<!--
As 2: (1) a validação "a tentativa é deste ciclo" estava num `peek` antes do
`sorted` — efeito colateral pendurado num estágio preguiçoso, virou um `forEach`
explícito; (2) `TransacaoJdbc.close()` lançava `FalhaDePersistencia`, o que
contraria o contrato "não lança" da porta e mascararia a exceção original dentro
do `try-with-resources`.
-->

## [não versionado] — 2026-08-30 — Fim do teto de arquivos

O teto de 24 arquivos saiu. Ele estava fazendo o projeto escolher a fronteira
errada para caber numa contagem — que é o oposto de enxuto.

### Removido

- **Teto de arquivos de produção** e a seção "Orçamento de arquivos" do
  `PLAN.md`. No lugar, um critério: cada arquivo carrega uma responsabilidade
  que dá para nomear sem usar "e"; corta-se escopo, não estrutura.

### Alterado

- **`RepositorioFatura` deixou de cuidar também de tentativas.** Eram sete
  métodos sobre duas entidades. Virou `RepositorioFatura` (a fatura, que só muda
  no retorno) e **`RepositorioTentativa`** (a tentativa, escrita por três
  operações: montagem, retorno e fechamento).
- **A remessa deixou de ser um método estático em `CicloCobranca`.** Vira
  `domain/model/Remessa` (o artefato e seu formato) mais
  `domain/usecase/GerarRemessaUseCase` (a operação). Montar e gerar têm
  garantias opostas — a montagem não pode se repetir, a geração precisa poder
  repetir-se com resultado idêntico — e um método só escondia a segunda.

### Adicionado

- **`RepositorioCiclo`** — porta própria para a escrita que importa, em vez de
  métodos de ciclo pendurados no repositório de faturas.
- **`CicloCobranca`** — o modelo que faltava para a tabela `ciclo_cobranca`, já
  criada no realinhamento anterior.

AI: est 45min / actual 20min / ~95% generated / 1 issue caught in review

<!--
A 1: RepositorioCiclo importava CicloCobranca, que ainda não existia — as portas
novas não compilariam sem o modelo.
-->

## [não versionado] — 2026-08-30 — Realinhamento ao desenho de referência

Ajuste de rumo antes do step-02, sem implementar regra nova. O projeto passou a
refletir o ciclo de vida real da cobrança em vez de só a metade do retorno.

### Alterado

- **Máquina de estados de `TentativaDebito`** — de `ENVIADA | PAGA | NAO_PAGA |
  ERRO` para
  `ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO | SEM_RETORNO`.
  O `UPDATE` condicional do retorno passa a guardar por `ENVIADO_PARCEIRO`.
- **Ordem dos steps**, para seguir o ciclo de vida e não a ordem em que as peças
  foram pensadas: montagem (02) → retorno (03) → fechamento (04) → relay (05) →
  cenário ponta a ponta (06). Arquivos de `docs/steps/` renomeados e todos os
  links corrigidos.
- **Diagrama do README** — trocado por um que mostra o ciclo inteiro, com o
  banco parceiro num subgraph próprio para deixar óbvio o que está fora do nosso
  controle. Os `style` com cores fixas escuras saíram: quebravam em tema claro, e
  o Mermaid já usa o tema do leitor.

### Adicionado

- **`ciclo_cobranca`** `(id, banco, data_ref, status: MONTADO | ENVIADO |
  FECHADO)` com `UNIQUE (banco, data_ref)`, e as colunas `banco`, `data_ref`,
  `ciclo_id` (nullable, FK) e `motivo` em `tentativa_debito`.
- **`TentativaDebito.Status.geraLancamentoContabil()`** — a regra "só `PAGO`
  gera lançamento" vira uma pergunta ao enum, e não um `if` espalhado pelos use
  cases. Coberta por `FundacaoTest.somentePagoGeraLancamento`, que falha se um
  estado novo passar a gerar lançamento sem alguém decidir isso.
- **`TentativaDebito.MotivoNaoPago`** (`SALDO_INSUFICIENTE`, `CONTA_ENCERRADA`,
  `AUTORIZACAO_REVOGADA`) e a constraint
  `CHECK ((status = 'NAO_PAGO') = (motivo IS NOT NULL))`: motivo existe se, e
  somente se, houve recusa. A ausência de retorno não tem motivo porque não
  houve fato — é o que separa `SEM_RETORNO` de `NAO_PAGO`.
- **step-02 (montagem de ciclo)** e **step-04 (fechamento de ciclo)**, com os
  testes obrigatórios `MontagemDeterministicaTest`,
  `TrabalhoDerivadoDeterministicoTest` e `FechamentoNaoInventaResultadoTest`.
- **Orçamento de arquivos** no `PLAN.md`: teto de 24 arquivos de produção, com
  as duas consolidações já decididas para caber.
- **Fora de escopo** no README, com justificativa item a item: SFTP e CNAB 240,
  canal síncrono com throttle e rate limiter distribuído, conciliação D+1,
  ciclo de vida da autorização de débito, e política de retentativa com
  classificação transitório × permanente.
- Testes de fundação para o `UNIQUE` do ciclo e para a regra do lançamento —
  a suíte foi de 4 para 6.

### Decisões

- **Idempotência da montagem por constraint, não por consulta prévia.**
  Verificação prévia é uma corrida entre dois processos; constraint é um fato no
  momento da escrita.
- **A montagem é a única escrita que importa.** Remessa, retorno, fechamento e
  publicação são trabalho derivado, refazíveis a partir do ciclo. Daí a remessa
  ser função pura, comparada byte a byte no teste.
- **Silêncio não é recusa.** Marcar como `NAO_PAGO` quem não respondeu
  dispararia notificação de falha ao cliente com base em fato que não ocorreu.

### Verificado

- `mvn test` → 6 testes, 0 falhas, com o schema novo.
- Todos os links internos de markdown resolvem.

AI: est 2h / actual 35min / ~95% generated / 3 issues caught in review

<!--
As 3: (1) o UPDATE de montagem filtra tentativa_debito por banco e data_ref, que
não existiam na tabela — o schema teria quebrado no step-02; (2) o teto de 24
arquivos não fecha se contar os testes (as classes nomeadas já somam 11), daí o
orçamento explícito no PLAN.md contando só produção; (3) FundacaoTest e o
TRUNCATE do AmbienteDeTeste ficariam vermelhos com ciclo_cobranca no schema.
-->

## [step-01] — 2026-08-30 — Fundação

### Adicionado

- Scaffold Maven para Java 21, sem Spring: JUnit 5, Testcontainers
  (Postgres + LocalStack), driver Postgres e AWS SDK v2 restrito ao SQS.
- `infra/docker-compose.yml` com Postgres e LocalStack, e scripts de init que
  criam o schema e a fila `lancamentos-contabeis` — `docker compose up` deixa o
  ambiente pronto, sem passo manual.
- Schema `fatura`, `tentativa_debito` e `outbox`, com `UNIQUE (fatura_id)` no
  outbox colocando a invariante "no máximo um lançamento por fatura" dentro do
  banco.
- Modelo de domínio (`Fatura`, `TentativaDebito`, `LancamentoContabil`,
  `RegistroOutbox`) e portas (`RepositorioFatura`, `RepositorioOutbox`,
  `PublicadorLancamento`, mais o tipo `Transacao`).
- `AmbienteDeTeste` — base da suíte, que aplica nos containers os **mesmos**
  scripts de `infra/init/` usados pelo Compose.
- `FundacaoTest` — 4 testes: tabelas criadas, `UNIQUE` do outbox presente, fila
  criada pelo script de init, e domínio sem imports de framework ou AWS.
- `README.md`, `docs/brief.md` e os dois ADRs que sustentam o projeto.

### Decisões

- Outbox transacional em vez de dual write — [ADR-0001](docs/adr/0001-outbox-transacional-em-vez-de-dual-write.md).
- At-least-once com chave de dedup em vez de fila FIFO — [ADR-0002](docs/adr/0002-at-least-once-mais-dedup-em-vez-de-fifo.md).
- Fronteira arquitetural verificada por teste (`dominioIsolado`), não por
  convenção.

### Notas de ambiente

- `api.version` fixado no surefire: o docker-java negocia por padrão uma versão
  de API que daemons Docker 25+ recusam com HTTP 400, o que quebrava `mvn test`.
- Perfil Maven `wsl-docker-desktop`, auto-ativado pela existência do socket,
  para WSL2 com Docker Desktop sem integração WSL. Inerte nos demais ambientes.

### Verificado

- `mvn test` → 4 testes, 0 falhas, sem variável de ambiente e sem o Compose no ar.
- `docker compose -f infra/docker-compose.yml up` → containers `healthy`, as três
  tabelas e a fila criadas.

AI: est 3h / actual 1h10 / ~95% generated / 4 issues caught in review

<!--
As 4: (1) erro de digitação no DDL do outbox (`BIGGENERATED`) que teria quebrado
o init; (2) `mvn test` falhando com HTTP 400 do daemon Docker — só apareceu ao
executar de fato, não na leitura; (3) o Compose adotando `infra` como nome de
projeto por herdar o nome do diretório; (4) step-01.md prometendo um
`AmbienteDeTesteTest` que virou `AmbienteDeTeste` + `FundacaoTest`.
-->
