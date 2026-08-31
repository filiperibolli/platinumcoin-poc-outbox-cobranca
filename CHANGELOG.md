# CHANGELOG

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Um step por entrada.

## [step-04] — 2026-08-31 — Fechamento de ciclo

O passo que fecha a janela de retorno sem inventar resposta para quem não
respondeu. É o único lugar do projeto onde o estado de uma tentativa muda sem
que ninguém tenha afirmado nada — e por isso o estado que ele escreve tem nome
próprio.

### Adicionado

- **`FecharCicloUseCase`** — numa transação, leva a `SEM_RETORNO` tudo o que
  continua `ENVIADO_PARCEIRO` no ciclo e marca o ciclo como `FECHADO`. Devolve
  quantas tentativas ficaram sem retorno; zero num ciclo já fechado.
- **`RepositorioCicloPostgres.fechar`** — os dois `UPDATE`, com a guarda
  `AND status = 'ENVIADO_PARCEIRO'` no primeiro. Era o último método declarado
  e não implementado do repositório de ciclo.
- **`FechamentoNaoInventaResultadoTest`** — 4 testes: o fechamento com um
  pagamento e dois silêncios, a ausência de motivo em `SEM_RETORNO`, o retorno
  atrasado que chega depois do fechamento, e o refechamento inócuo.

### Decisões

- **Silêncio não é resposta.** `NAO_PAGO` é uma afirmação do parceiro e vem com
  motivo; a ausência de retorno não tem motivo porque não houve fato. A
  diferença é visível para o cliente: `NAO_PAGO` dispara notificação de falha de
  débito, `SEM_RETORNO` é exceção operacional que alguém investiga. Colapsar os
  dois economiza um estado e mente para o cliente.
- **A idempotência do fechamento é a mesma do retorno.** A guarda está no
  `UPDATE` das tentativas — `AND status = 'ENVIADO_PARCEIRO'` — e não no
  `UPDATE` do ciclo. Guardar o ciclo por `status = 'ENVIADO'` faria o
  refechamento parecer seguro pelo motivo errado e deixaria um ciclo ainda
  `MONTADO` impossível de fechar. Quem já tem desfecho não é tocado, venha o
  segundo fechamento de onde vier.
- **O fechamento não toca no outbox, e nem precisa de um `if` para isso.**
  Nenhum dos estados que ele escreve é um pagamento, e a pergunta
  `geraLancamentoContabil()` continua com um dono só — por isso
  `RepositorioOutbox` não aparece no construtor do use case.
- **`executar` devolve um `int`, e não um record de resultado.** "Quantas
  ficaram sem retorno" é a resposta inteira; um record de um campo só seria um
  arquivo a mais que muda código de lugar.

### Verificado

- `mvn test` → 27 testes, 0 falhas (23 dos steps anteriores, 4 deste).
- `FechamentoNaoInventaResultadoTest`: com 3 tentativas transmitidas e 1 retorno
  `PAGO`, o fechamento deixa **1** `PAGO`, **2** `SEM_RETORNO`, **0**
  `NAO_PAGO` e **1** linha no outbox, com o ciclo em `FECHADO`; nenhuma
  tentativa `SEM_RETORNO` ganha motivo; o retorno que chega depois do
  fechamento é `IGNORADO` e não reabre a tentativa; o segundo fechamento devolve
  0 e não altera nada.
- **Teste de mutação manual**, duas vezes. Sem a guarda
  `AND status = 'ENVIADO_PARCEIRO'`, o fechamento sobrescreve o `PAGO` e 2 dos 4
  testes falham. Trocando `SEM_RETORNO` por `NAO_PAGO`, os 4 estouram na
  constraint `tentativa_motivo_so_com_nao_pago`: a recusa inventada é recusada
  pelo schema antes de chegar ao teste.

AI: est 1h / actual 20min / ~95% generated / 1 issue caught in review

<!--
A 1: o step-04.md escreve o `UPDATE ciclo_cobranca ... WHERE id = ?` sem guarda
e, na Definition of Done, pede que refechar não altere nada — o que só fecha
porque a guarda mora no `UPDATE` das tentativas. A leitura oposta (guardar o
ciclo por `status = 'ENVIADO'`) passaria no teste de refechamento pelo motivo
errado e deixaria um ciclo ainda `MONTADO` sem como fechar.
-->

## [step-03] — 2026-08-31 — Aplicar retorno

A transação que decide **e** registra a intenção de publicar. É o step que o
projeto existe para mostrar: três escritas, um banco, um `COMMIT`, e nenhuma
chamada externa lá dentro.

### Adicionado

- **`AplicarRetornoUseCase`** — aplica a uma tentativa o desfecho que o parceiro
  informou. Quando o desfecho é `PAGO`, na mesma transação a fatura vai a `PAGA`
  e a intenção de publicar entra no `outbox`. `NAO_PAGO` e `ERRO` movem a
  tentativa e param aí. Devolve um `Resultado`
  (`IGNORADO`, `APLICADO`, `APLICADO_COM_LANCAMENTO`) — o bastante para explicar
  a chamada num log sem consultar o banco.
- **`api/LinhaRetorno`** — a linha do arquivo de retorno, com fábricas `paga`,
  `naoPaga` e `comErro`. É o adaptador de entrada: `aplicarCom(useCase)`
  traduz a linha numa chamada em tipos de domínio.
- **`RepositorioTentativaPostgres.registrarResultado`** — o `UPDATE ... WHERE id
  = ? AND status = 'ENVIADO_PARCEIRO'`. Zero linhas afetadas é a resposta, não
  um erro.
- **`RepositorioFaturaPostgres`** — inclui `marcarPaga`, com a guarda
  `AND status = 'ABERTA'`, e `buscarPorTentativa`, lida pela mesma conexão da
  transação.
- **`RepositorioOutboxPostgres`** — `inserir` (na transação) e `pendentes` (fora
  dela), mais o payload JSON escrito à mão e sua leitura. `marcarPublicado`
  segue declarado e não implementado até o step-05.
- **`TentativaDebito.exigirMotivoCoerente`** e
  **`TentativaDebito.Status.vemDoRetorno()`** — duas regras que passaram a ter
  três chamadores cada, extraídas para ter um dono só.
- **`Cenario.cicloTransmitido`** nos testes — monta o ciclo pelo use case real e
  leva as tentativas a `ENVIADO_PARCEIRO` por SQL direto, porque
  `EnviarRemessa` não tem classe neste repositório.

### Decisões

- **`LinhaRetorno` mora em `api/` e o use case não a importa.** A alternativa
  óbvia — passar o record ao use case — inverteria a seta `api → domain` e
  quebraria `FundacaoTest.dominioIsolado`. Em vez de afrouxar a regra, a linha
  ganhou `aplicarCom`: quem conhece os dois lados é o adaptador, que é o papel
  dele.
- **Um retorno por chamada, uma transação por retorno.** Cada linha do arquivo é
  uma decisão independente: uma linha que estoura não pode desfazer as que já
  foram aplicadas, e reprocessar o arquivo inteiro é seguro porque as que
  passaram viram zero linhas afetadas na segunda vez.
- **A guarda da fatura decide quem grava o lançamento.** Não é um `if` sobre uma
  leitura anterior — é o `UPDATE ... WHERE status = 'ABERTA'`. Quem consegue
  mover a fatura ganha o direito de gravar; quem chega depois lê isso no número
  de linhas afetadas. Ler antes seria uma corrida.
- **`pendentes` chegou no step-03, e não no step-05.** A regra do projeto é que
  cada método nasça junto com o teste que o prova, e são os testes de retorno que
  conferem o que entrou no outbox. Deixá-lo para o step-05 obrigaria os quatro
  testes a ler a tabela com SQL solto.
- **Payload em JSON escrito à mão, com a leitura ao lado da escrita.** Dois
  campos não pagam uma dependência de serialização; o que a gravação escreve, a
  leitura devolve idêntico, e é essa ida e volta que o relay vai precisar.

### Verificado

- `mvn test` → 23 testes, 0 falhas (12 dos steps anteriores, 11 deste).
- `RetornoDuplicadoTest`: a segunda aplicação devolve `IGNORADO` e o outbox
  continua com **1** linha; o arquivo inteiro reprocessado não muda nada; linha
  para tentativa inexistente é ignorada, não é erro.
- `MultiplasTentativasTest`: recusa seguida de pagamento → **1** linha; duas
  tentativas da mesma fatura que **ambas** pagam → **1** linha, com as duas
  tentativas registradas como `PAGO`.
- `DualWriteEvitadoTest`: com o `INSERT` do outbox estourando, o outbox fica
  vazio, a fatura continua `ABERTA` e a tentativa continua `ENVIADO_PARCEIRO`;
  reprocessar a mesma linha depois processa normal.
- `RetornoAplicadoTest`: `NAO_PAGO` grava o motivo e não gera outbox; `ERRO`
  resolve sem motivo e sem outbox; e por reflexão, `PublicadorLancamento` não é
  campo nem parâmetro de construtor do use case.
- **Teste de mutação manual**: removidas as duas guardas
  (`AND status = 'ENVIADO_PARCEIRO'` e `AND status = 'ABERTA'`), a suíte falha
  em 4 casos de `RetornoDuplicadoTest` e `MultiplasTentativasTest`. As guardas
  sustentam os testes de verdade; os testes não passariam sem elas.

AI: est 2h30 / actual 40min / ~95% generated / 2 issues caught in review

<!--
As 2: (1) `api/LinhaRetorno` como parâmetro do use case, como o step-03.md
descrevia — faria `domain` importar `api` e quebraria a regra 3 do CLAUDE.md,
que `FundacaoTest.dominioIsolado` verifica; virou `aplicarCom`, com a tradução
no adaptador; (2) a regra "motivo existe se, e somente se, NAO_PAGO" ia ficar
repetida em `TentativaDebito`, em `LinhaRetorno`, no use case e no schema —
quatro cópias livres para divergirem, colapsadas em `exigirMotivoCoerente`.
-->

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
