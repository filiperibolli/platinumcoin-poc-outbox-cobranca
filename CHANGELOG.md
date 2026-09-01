# CHANGELOG

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Um step por entrada.

## [não versionado] — 2026-09-01 — A aplicação sobe no Compose, e o ciclo aparece no log

Só faltava a aplicação para o `docker compose logs` mostrar o ciclo inteiro num
lugar só. Nenhuma linha de `domain/`, `infra/` ou `simulador/` mudou: o
`Ambiente` já lia `DB_URL`, `SQS_ENDPOINT`, `S3_ENDPOINT`, `SFTP_HOST` e
`SFTP_PORTA`, e a única diferença de dentro da rede do Compose é que o vizinho
atende pelo nome do serviço em vez de por `localhost`.

### Adicionado

- `infra/Dockerfile`: build em duas etapas (`maven:3.9-eclipse-temurin-21` →
  `eclipse-temurin:21-jre`). O `pom.xml` copiado antes do `src` e um cache
  mount em `/root/.m2` para que uma mudança de código não volte à rede.
- `.dockerignore`, para manter `target/` e `.git/` fora do contexto.
- Serviço `app` no `infra/docker-compose.yml`, **atrás do perfil `app`**:
  `up -d` sem perfil continua subindo só o ambiente. Sem isso, o Compose e o
  `mvn spring-boot:run` disputariam a porta 8080 — as duas formas continuam
  valendo, e é a de fora do container que se usa para mexer no código.
- `README.md`, seção "Ver acontecendo": os dois comandos, por que existe o
  perfil e por que o `repackage` mora no Dockerfile.

### Decidido

- **`spring-boot:repackage` chamado no Dockerfile, não ligado ao `package` no
  `pom.xml`.** O comentário do pom diz que o artefato do projeto é um jar comum,
  e isso continua verdade: quem precisa do jar executável é a imagem. A
  alternativa — uma execução do plugin no pom — mudaria o que `mvn package`
  produz para todo mundo, por uma necessidade que é só do container.
- **Testes pulados no build da imagem.** A suíte sobe os próprios containers via
  Testcontainers e não há daemon Docker dentro do `docker build`. `mvn test`
  continua sendo o lugar dela.

### Adicionado — o log

- **`org.slf4j` no domínio**, nos oito use cases, nos pontos de decisão. É a
  regra que mudou: `FundacaoTest.dominioIsolado` só admitia `java.*` e o próprio
  domínio, e passou a admitir `org.slf4j.` também. A regra 3 do `CLAUDE.md`
  nomeia Spring, AWS SDK e biblioteca SSH — **o teste era mais estrito que a
  regra escrita**, e o que se afrouxou foi o teste até ela, não ela. O motivo
  está no [ADR-0005](docs/adr/0005-log-no-dominio-nos-pontos-de-decisao.md): o
  adaptador sabe que um `UPDATE` afetou zero linhas, e só o use case sabe que
  isso significa "já aplicado, e não é erro".
- **`EfeitoNoLog`**, um `ResponseBodyAdvice` em `api/http`: a mesma resposta que
  volta ao chamador, também no log. Um advice e não oito linhas em oito
  controllers. **Fora `GET /estado`** — o painel o relê a cada 2s, e ele afogaria
  a sequência.
- **Linhas nos adaptadores**: `[artefato]` (S3), `[parceiro]` (SFTP, incluindo os
  dois `stat` que **são** a quiescência) e `[sqs]`. E `[crash]` nos dois
  decoradores de falha, no instante exato de cada janela.
- `Main.main` silencia `com.platinumcoin.ciclo` antes de rodar: o cenário do
  console **é** a saída do programa, e as mesmas frases interleavadas contariam
  a história duas vezes.
- `showShortLogName=true` no `simplelogger.properties`, para a mensagem caber na
  tela ao lado do nome da classe.

### Adicionado — a documentação

- [ADR-0004](docs/adr/0004-aplicacao-em-container-atras-de-um-perfil.md) e
  [ADR-0005](docs/adr/0005-log-no-dominio-nos-pontos-de-decisao.md).
- ADR-0001, 0002 e 0003 ganharam um bloco **"Onde isto é observável"** cada um,
  ligando a decisão às linhas de log que a mostram acontecendo. As duas janelas
  deixaram de ser dedutíveis só do teste.
- README: seção **"Testar, do terminal, contra `localhost:8080`"** — os oito
  `curl` em sequência, o trecho de log real que eles produzem, e os dois
  roteiros de falha (retorno repetido e janela B), com a saída observada.
- README: duas linhas novas na tabela "As decisões, com o preço", e a lista de
  arquivos atualizada.

### Verificado

Contra os containers, com a aplicação em `ciclo-app` — os oito passos, na ordem
do painel:

```
gerar-remessa  {"chave":"remessa/341/20260901/C-1.rem","sha256":"303d3071…","detalhes":4,"bytes":233}
coletar        {"vistos":1,"aplicadas":4,"arquivos":[{"nome":"341-20260901-C-1.ret","desfecho":"APLICADO",…}]}
publicar       {"publicados":2,"chavesDedup":["F-20260901-1","F-20260901-4"]}
```

`tentativas {PAGO 2, NAO_PAGO 1, ERRO 1}` e **duas** linhas no outbox: os quatro
sistemas externos respondendo de dentro da rede do Compose, inclusive o SQS —
a url que o LocalStack devolve ao `getQueueUrl` era o risco, e o container a
resolve.

A janela B, provocada de dentro do container, é a mesma:

```
crash-relay → publicar   409   fila 3 mensagens, F-20260902-1 já enviada e ainda PENDENTE
publicar de novo         200   fila 5 mensagens, F-20260902-1 duas vezes
```

O log de um ciclo inteiro foi lido linha a linha contra o que cada uma afirma —
os dois `stat` idênticos da quiescência, a ausência de `[sqs]` entre o
`[outbox] + PENDENTE` e o `COMMIT`, as quatro linhas `(0 linhas afetadas — já
aplicado, ignorado)` de um retorno reprocessado, e o `[crash]` logo depois do
`[fila] … a linha ainda diz PENDENTE`. Os trechos do README saíram desse log,
não de memória.

`mvn test` → **24 classes, 83 testes, 0 falhas**, com a lista branca do domínio
já afrouxada.

AI: est 1h30 / actual 55min / ~90% generated / 2 issues caught in review (jar sem
manifesto na primeira imagem — o `repackage` não estava ligado; e o log de
`atributos` chamando `mtime()` num record cujo componente é `modificadoEm`)

## [não versionado] — 2026-09-01 — O projeto deixa de se chamar outbox

`mini-outbox-cobranca` → **`ciclo-de-cobranca`**, e o pacote
`com.platinumcoin.outbox` → `com.platinumcoin.ciclo` nos 107 arquivos.

O nome antigo descrevia o projeto de quando ele tinha seis steps. O outbox é
**um** dos mecanismos daqui — o que resolve a dificuldade 1, a de duas escritas
em dois sistemas — e nomear o todo pela parte escondia as outras três: a
ausência de callback, o mesmo retorno chegando de quatro formas, e o silêncio
que não é resposta. O nome novo é o do agregado que decide tudo: a montagem do
ciclo é a única escrita que importa, e todo o resto é trabalho derivado dela.

**O que NÃO foi renomeado**, porque ali `outbox` é o nome certo: a tabela
`outbox`, `RepositorioOutbox` e sua implementação, `PublicarOutboxUseCase`, a
rota `POST /outbox/publicar`, o bloco `outbox` do `/estado` e do painel, e o
ADR-0001. O padrão continua se chamando padrão.

### Alterado

- `pom.xml`: `artifactId`, `name`, `description` e os dois `mainClass`. A
  descrição antiga — "Outbox transacional para publicação de lançamento contábil
  sem dual write" — descrevia o step-05, não o projeto.
- `infra/docker-compose.yml`: o `name:` do projeto Compose e os três
  `container_name` (`ciclo-postgres`, `ciclo-localstack`, `ciclo-sftp`).
- `simplelogger.properties`, que silencia um logger pelo nome do nosso pacote.
- `FundacaoTest` e `ControllerNaoDecideTest` montam caminhos de pacote peça a
  peça (`Path.of("com", "platinumcoin", "outbox", …)`) para fiscalizar a
  fronteira arquitetural, e por isso a substituição do literal de pacote não os
  alcança — os dois foram acompanhados à mão. O modo de falha, verificado por
  mutação, é `NoSuchFileException` no `Files.walk`: um rename esquecido aqui
  quebra a suíte em vez de deixá-la verde fiscalizando um diretório que não
  existe. Vale registrar porque a suspeita inicial era a oposta, e um teste de
  fronteira que passasse vazio seria o pior resultado possível.
- `docs/brief.md` **reescrito**. Ele ainda era o brief do step-01: enunciava só
  a dificuldade 1 e prometia "o que este projeto prova" em termos de dual write.
  Agora é o enunciado curto e completo — a pergunta, as quatro restrições, as
  quatro dificuldades, as duas invariantes e a fronteira de responsabilidade —
  e **para antes da solução**, que é assunto do README. Ganhou também o
  parágrafo que faltava: por que o mainframe ser legado fecha a porta do
  exatamente-uma-vez ponta a ponta, e como a pergunta se divide em lançamento
  *decidido* (nosso) e *efetivado* (do consumidor).

### Verificado

- `mvn test` → **24 classes, 83 testes, 0 falhas** depois do rename.
- Nenhuma ocorrência de `mini-outbox`, `com.platinumcoin.outbox`,
  `com/platinumcoin/outbox` ou dos nomes antigos de container sobrou no
  repositório — varrido nas duas formas, com pontos e com barras.
- O que **continua** se chamando outbox foi conferido um a um: a tabela e suas
  duas constraints no schema, `RepositorioOutbox`, `PublicarOutboxUseCase`, a
  rota, o bloco do painel e o ADR-0001.

AI: est 1h / actual 35min / ~95% generated / 2 issues caught in review

<!--
As 2: (1) os dois testes de fronteira montam o caminho do pacote como lista de
literais, e não como string — a substituição global do pacote não os pegaria.
Presumi que ficariam verdes fiscalizando um diretório inexistente; a mutação
mostrou que estouram com NoSuchFileException, o que é o comportamento certo.
(2) a árvore de estrutura do README escreve o pacote com barras
(`com/platinumcoin/outbox/`), forma que o sed do literal com pontos não pegou —
achado só na varredura final por `outbox` restante.

## [step-12] — 2026-09-01 — Painel HTML, e o README como documento de system design

O último step fecha o projeto por onde ele começou: a **pergunta**. O mecanismo
agora pode ser visto acontecendo numa tela, e o README deixa de ser um guia de
uso para virar o que o projeto sempre foi — um problema de system design escrito
por inteiro, com a solução e o preço de cada decisão.

O painel é um arquivo. Botões na ordem do fluxo, com o `parceiro processa` **no
meio** — porque é ali que ele age, e uma lista de rotas que o omite manda o
leitor chamar `coletar` num diretório vazio. Falhas em seção separada, por cor e
por título: operar o sistema e sabotá-lo não podem se parecer.

A demonstração que o painel torna possível, e que nenhum `curl` isolado dava:

```
outbox   F-20260901-1 PENDENTE   F-20260901-3 PENDENTE   F-20260901-4 PENDENTE
fila     mensagens 1   chaveDedup: F-20260901-1
```

A mensagem já está na fila e a linha ainda diz `PENDENTE`. É a janela do relay,
inteira, em duas linhas de tela.

### Adicionado

- **`resources/static/index.html`** — o painel. HTML, CSS e JS puro, tudo inline,
  sem build e sem CDN. Um `npm install` para observar um mecanismo de backend
  seria a contradição óbvia; um `<script src="https://…">` seria pior, porque
  transformaria um demo local numa coisa que só funciona com internet.
- **`PainelEhServidoTest`** — três asserções: a raiz devolve HTML com um botão
  por passo (inclusive o do parceiro), nenhum `src`/`href` aponta para fora, e
  **os blocos que o painel pinta são os que o `/estado` devolve**. A terceira
  existe porque o painel lê seis campos por nome em JavaScript, onde um rename
  no `Retrato` não quebra a compilação — vira um bloco vazio que ninguém
  relaciona com a mudança feita três semanas antes.
- **`ClienteHttp.pagina()`** — busca uma página como um navegador buscaria. O
  `Accept: text/html` não é decoração: a página de boas-vindas do Spring só
  responde a quem pede HTML, e o `Accept: application/json` dos outros métodos
  recebe 404 do mesmo servidor.

### Alterado

- **`EstadoDoMundo` cresceu para servir a tela**, em três pontos e por um motivo
  cada:
  - **outbox vira lista, não contagem.** De tentativas há dezenas e só a
    distribuição importa; do outbox há no máximo uma linha por fatura, e **qual**
    está pendente é a informação. Uma contagem por status esconderia exatamente
    o par "F-1 PENDENTE / F-1 já na fila".
  - **artefatos com tamanho**, que o `listObjectsV2` já devolvia e era descartado.
  - **a fila é espiada, não só contada.** `receiveMessage` com
    `visibilityTimeout=0` — a mensagem volta a ficar visível no mesmo instante.
    Sem isso a duplicata do `crash-relay` seria "o contador subiu de 3 para 4"; o
    que ela tem de demonstrável é a **mesma** `chaveDedup` duas vezes.
- **README reescrito** em torno da pergunta e das **quatro dificuldades** que ela
  esconde: duas escritas em dois sistemas (duas vezes no fluxo), a ausência de
  callback, o mesmo retorno chegando de quatro formas, e o silêncio que não é
  resposta. Ganhou um **teste de mesa** — o ciclo percorrido à mão, com o estado
  das tabelas depois de cada passo e "e se morrer aqui?" nos dois pontos em que
  isso importa — e dois diagramas novos: um `sequenceDiagram` das duas janelas e
  um `stateDiagram` da máquina de estados.
- **Os mecanismos passaram a ser descritos como duas famílias.** Escrita
  transacional, trabalho derivado e transição condicional garantem **correção
  sob falha** e valem para qualquer integração assíncrona. Quiescência, trailer
  e `SEM_RETORNO` garantem outra coisa — que o sistema **não afirme o que
  ninguém disse** — e existem porque o canal é um diretório e o parceiro é mudo.
  A lista antiga dizia "três mecanismos, e de mais nenhum" e deixava as
  dificuldades 2 e 4 sem casa.

### Removido

- **`Fatura.Status.LANCADA`**, do enum e do `CHECK` do schema. Nada nunca o
  escreveu. Quem responde "o lançamento saiu?" é o outbox, que tem
  `UNIQUE (fatura_id)` — uma linha por fatura, por construção. Um estado na
  fatura seria uma segunda cópia do mesmo fato, livre para divergir da primeira:
  o dual write que o ADR-0001 recusa, em escala menor. E marcá-lo exigiria um
  `UPDATE` **depois** do `send`, dentro exatamente da janela que o projeto existe
  para discutir.

### Corrigido

Sete divergências entre o README e o código, encontradas numa auditoria antes
deste step:

- o texto de abertura afirmava que o painel existia, três seções depois de dizer
  que era plano;
- a lista de endpoints omitia `/parceiro/processar` — quem seguia a ordem
  impressa chamava `coletar` num diretório vazio e recebia `{"vistos":0}` sem
  explicação;
- a máquina de estados publicava `ABERTA → PAGA → LANCADA`, uma transição que
  nenhum código fazia;
- a linha do `reenviar-retorno` creditava a segurança ao `sha256`, quando o
  próprio `ColetarRetornoUseCase` diz em comentário que o hash é só um atalho de
  custo e quem garante é o `UPDATE` condicional;
- a tabela `arquivo_retorno` não era mencionada uma única vez, apesar de ser
  onde esse atalho mora;
- a árvore de estrutura omitia `domain/exception`, `Sha256` e três portas —
  entre elas `Transacao`, que é a fronteira transacional, o assunto do projeto;
- `POST /faturas` repetido devolve 409 (os ids derivam da data) e isso não
  estava documentado.

### Verificado

- `mvn test` → **24 classes, 83 testes, 0 falhas**, sem variável de ambiente e
  sem o Compose no ar.
- **O teste de mesa foi conferido contra o código**, e não escrito de memória: os
  282 bytes e o `sha256=588b15cc…` da remessa do cenário foram reproduzidos à
  mão a partir das larguras de campo e batem com a execução real; o SQL de cada
  passo foi copiado dos repositórios, com as guardas de status que a primeira
  versão do texto havia omitido.
- **O ciclo inteiro percorrido pela API com o Compose no ar**, na ordem que o
  README imprime: `bytes:233` e `detalhes:4` conforme documentado,
  `POST /ciclo/coletar` antes do parceiro devolvendo `{"vistos":0,...}` como o
  texto avisa, e o `crash-relay` produzindo 4 mensagens com 3 chaves distintas.
- A asserção de contrato entre painel e `/estado` foi checada por mutação:
  renomear um `id` no HTML faz `PainelEhServidoTest` falhar.

AI: est 4h / actual 2h05 / ~95% generated / 3 issues caught in review

<!--
As 3: (1) o teste de mesa trazia um layout de remessa inventado e SQL sem as
guardas de status — só apareceu ao conferir campo a campo contra `Remessa.java`
e os repositórios; (2) o parágrafo do painel dizia "uma fatura ainda PENDENTE"
quando o crash deixa três pendentes e uma delas já na fila — só apareceu ao
rodar o ciclo de verdade contra o Compose; (3) a contagem de baseline "24
classes / 86 testes" vinha de relatórios surefire velhos misturados no `target/`;
o número real antes deste step era 23 classes / 80 testes.
-->

## [step-11] — 2026-08-31 — Simulador do parceiro e provocação de falhas

As falhas que o desenho defende deixam de morar em classes de teste e passam a
ser **reproduzíveis por botão**. Quem quiser ver o retorno truncado sendo
descartado, ou o relay morrendo entre o `send` e o `UPDATE`, faz um `POST` — e
vê, entre uma chamada e outra, o estado que a janela produz.

E o arquivo de retorno deixa de ser depositado pela suíte: ele nasce do outro
lado do fio. O simulador **lê a remessa do SFTP** e responde sobre as tentativas
que encontrou nela. É a volta fechando — até aqui o projeto provava que sabia
ler um retorno; agora ele mostra o retorno sendo escrito.

| chamada | o mecanismo que fica visível |
|---|---|
| `POST /parceiro/processar` | o caminho feliz, e que só `PAGO` gera linha no outbox |
| `…?particionar=3` | vários arquivos por ciclo convergindo para o mesmo estado |
| `…?atrasar=true` | o retorno pela metade; o resto continua `ENVIADO_PARCEIRO` |
| `POST /parceiro/reenviar-retorno` | os mesmos bytes reconhecidos pelo `sha256` |
| `POST /parceiro/retorno-truncado` | trailer que não fecha, arquivo descartado inteiro |
| `POST /parceiro/silencio` | `SEM_RETORNO` no fechamento — silêncio não é recusa |
| `POST /falha/crash-relay` | at-least-once: duas mensagens, uma `chaveDedup` |
| `POST /falha/crash-envio` | a janela entre efeito externo e commit |

### Adicionado

- **`simulador/ParceiroSimulado`** — as quatro formas de o parceiro se
  comportar: responder, repetir a resposta, responder pela metade, e não
  responder. Nada é sorteado: o desfecho vem da distribuição pedida, aplicada na
  ordem do arquivo. Uma demonstração que muda a cada execução não prova nada.
- **`simulador/RemessaLida`** — o que o parceiro entende do arquivo que recebeu.
  É o coração da decisão do step: o retorno é montado **a partir daqui**, e não
  de uma consulta ao Postgres.
- **`simulador/LayoutDeRetorno`** — como o parceiro escreve o retorno, incluindo
  o trailer que **não** bate com o conteúdo. Um formatador que sempre conta
  certo não teria como produzir o arquivo truncado.
- **`simulador/DiscoDoParceiro`** — o diretório visto por ele. Conexão SSH
  própria, e não `CanalArquivosSftp`: aquele objeto é o nosso lado do fio.
- **`simulador/http`** — seis rotas (`/parceiro/*` e `/falha/*`) e dois records
  de efeito, mais a `FiacaoDoParceiro`, no próprio pacote — a seta aponta do
  ambiente para o sistema, nunca ao contrário.
- **`infra/falha/FalhasArmadas`** e os decoradores **`MorreAoMarcarPublicado`**
  (porta do outbox) e **`MorreAoRegistrarEnvio`** (porta do ciclo). Armados na
  fiação do servidor; enquanto nada está armado, apenas delegam.
- **`SimuladorProduzRetornoAplicavelTest`** (3) — o retorno produzido pelo
  parceiro é baixado, validado e aplicado; num arquivo, em vários, e em vários
  momentos, sempre para o mesmo estado final.
- **`FalhasProvocadasTest`** (5) — os cinco cenários de falha por HTTP, pelo
  mesmo caminho que o painel do step-12 vai usar.
- **`FundacaoTest.simuladorEhOAmbienteNaoOSistema`** — nada em `domain/` ou
  `infra/` importa o simulador, e o simulador não importa `java.sql`. As duas
  metades da frase "é o ambiente, não o sistema", asseridas.
- **`ServidorDeTeste`** (teste) — subir o servidor apontado para os containers
  virou uma linha. Três classes precisavam dele; o `EndpointsDevolvemEfeitoTest`
  do step-10 passou a usá-lo, sem mudar uma asserção.

### Decisões

- **O simulador vive fora de `domain` e de `infra`.** Ele é o ambiente. Dentro
  de `infra/`, a primeira leitura do projeto passaria a incluir código que não
  vai para produção, e a fronteira `api → domain ← infra` ganharia um quarto
  vértice mal explicado.
- **O simulador só enxerga arquivos.** Consultar o Postgres para montar o
  retorno seria mais simples e destruiria o valor da demonstração: o retorno
  viraria função do nosso estado, e o dia em que a remessa saísse errada o
  parceiro responderia certo assim mesmo.
- **`DiscoDoParceiro` duplica a conexão SSH de propósito.** Reaproveitar
  `CanalArquivosSftp` obrigaria a porta do domínio a receber um diretório de
  destino para servir ao simulador — e a primeira pergunta de quem lesse
  `CanalArquivos` passaria a ser por que ela sabe escrever em `/retorno`.
- **As falhas são decoradores, não `if` no use case.** Um `if (simularCrash)`
  dentro de `PublicarOutboxUseCase` colocaria no código de produção uma linha
  que só existe para a demonstração — e o código de produção é justamente o que
  se quer olhar. Custo: dois arquivos e três linhas de fiação.
- **Armar e disparar são chamadas separadas**, e a falha se desarma ao disparar.
  Um endpoint que provocasse a falha e executasse o passo esconderia o estado
  intermediário, que é o único momento em que a janela existe; e uma falha que
  ficasse armada transformaria a reexecução — a parte que converge — em mais uma
  falha.
- **`atrasar` entrega parte do retorno agora e o resto na chamada seguinte**, em
  vez de simular quiescência. O step-11.md previa que este botão mostrasse "o
  arquivo em escrita não é baixado", e isso exigiria um parceiro escrevendo por
  temporizador: a demonstração passaria a depender de qual das duas coisas
  chegou primeiro. A quiescência continua provada por
  `ArquivoEmEscritaNaoEhBaixadoTest`, onde o crescimento acontece exatamente
  entre as duas leituras; o botão mostra o outro lado da mesma ausência — o que
  o parceiro ainda não disse continua `ENVIADO_PARCEIRO`, e é o fechamento que
  decidiria sobre ele.
- **`reenviar-retorno` reescreve os mesmos bytes, e o `sha256` os
  curto-circuita.** O step-11.md previa aqui o `UPDATE` condicional afetando
  zero linhas, mas com bytes idênticos o atalho do step-09 age antes — dizer o
  contrário seria descrever um mecanismo que não roda. `FalhasProvocadasTest`
  cobre as duas metades: o reenvio idêntico (`REPETIDO`, nada interpretado) e o
  mesmo retorno **reagrupado** em três arquivos, que tem outro hash, passa
  direto pelo atalho e chega ao `UPDATE` — que afeta zero linhas.
- **Nenhum endpoint do ciclo ganhou parâmetro de simulação.** Os oito passos de
  `api/http` estão como o step-10 os deixou; provocar falha é mexer no ambiente,
  e mora ao lado do simulador.

### Verificado

- `mvn test` → 80 testes, 0 falhas (71 dos steps anteriores, 9 deste).
- Os oito botões produzem o efeito que anunciam, asseridos por HTTP contra os
  containers — banco, fila, bucket e diretório do parceiro.
- `FundacaoTest.dominioIsolado` e `ControllerNaoDecideTest` continuam verdes: o
  simulador não vazou para o domínio nem para os controllers do ciclo.
- `CenarioPontaAPontaTest` continua verde sem uma linha alterada no `Main` — os
  decoradores de falha só existem na fiação do servidor.

AI: est 3h / actual 55min / ~100% generated / 2 issues caught in review

<!--
As 2 são divergências entre o step-11.md e o que o código do step-09 já fazia,
e as duas viraram decisão registrada acima em vez de código que finge:

(1) `atrasar` não tem como mostrar quiescência sem temporizador — duas chamadas
separadas nunca produzem um arquivo que cresce **entre** as duas leituras de
atributos.

(2) `reenviar-retorno` byte-idêntico é interceptado pelo `sha256` do step-09
antes de chegar ao `UPDATE` condicional. Forçar o caminho do `UPDATE` para um
reenvio idêntico exigiria enfraquecer o atalho — trocar uma otimização correta
por uma demonstração bonita.

O `ParceiroSimulado` guarda as partes pendentes por ciclo em memória. É estado
do ambiente, não do sistema: reiniciar o servidor esquece o que o parceiro ainda
ia entregar, e a chamada seguinte recalcula tudo a partir da remessa.
-->

## [step-10] — 2026-08-31 — API de operação do ciclo

O mecanismo deixa de ser deduzido de uma suíte verde e passa a ser **disparável
passo a passo**. Sete `POST` e um `GET`: cada chamada executa o job que, em
produção, o EventBridge dispararia por horário, e cada resposta diz o que
aconteceu com o mundo.

É o step em que o Spring Boot entra, e o motivo é único e declarado: **o projeto
passa a expor HTTP.** Nenhuma outra parte do desenho mudou por causa dele — o
domínio continua sem framework, os use cases continuam os mesmos, e o `Main` de
console dos steps 01–06 continua rodando sem uma linha alterada.

| chamada | dispara | a resposta afirma |
|---|---|---|
| `POST /faturas` | — | as faturas que passaram a existir |
| `POST /ciclo/montar` | `MontarCiclo` | o ciclo e quantas tentativas ele puxou |
| `POST /ciclo/gerar-remessa` | `GerarRemessa` | chave, `sha256` e a contagem do trailer |
| `POST /ciclo/enviar` | `EnviarRemessa` | o nome do arquivo no parceiro |
| `POST /ciclo/coletar` | `ColetarRetorno` | um item por arquivo **visto**, com o desfecho |
| `POST /ciclo/fechar` | `FecharCiclo` | quantas viraram `SEM_RETORNO` |
| `POST /outbox/publicar` | `PublicarOutbox` | as chaves de dedup que foram para a fila |
| `GET /estado` | — | o retrato das cinco fontes |

### Adicionado

- **Spring Boot (Web) no `pom.xml`** — só `spring-boot-starter-web`, e sem o
  starter de logging: o binding do projeto continua sendo o `slf4j-simple` do
  step-08, e dois bindings no classpath é um aviso a cada execução. Sem starter
  de dados: a persistência continua JDBC puro, sem pool e sem ORM.
- **`AplicacaoHttp`** — o `main` do servidor. Um processo, todos os endpoints,
  inclusive os do simulador do step-11 quando ele chegar.
- **`infra/config/Fiacao`** — a continuação do `Ambiente`: ele responde "com que
  banco, com que fila, com que bucket e com que parceiro", e ela responde "que
  objetos falam com eles". É o único arquivo de `infra/` que conhece o Spring, e
  não decide nada — sem `@Value`, sem perfil, sem condicional.
- **Oito controllers em `api/http`**, um por passo. Cada um desserializa, chama
  **um** use case e serializa o efeito.
- **`api/http/dto`** — sete records de resposta. Cada campo é uma afirmação sobre
  o mundo depois da chamada, não sobre a chamada.
- **`AbrirFaturasUseCase`** — o estado inicial, que num sistema de verdade viria
  da originação. Existe como use case, e não como `INSERT` dentro do controller,
  pela regra que vale para os outros sete: operação inbound tem um lugar com
  nome. Os ids saem da data de referência (`F-20260901-1`), e o teto de 99 mora
  onde o id nasce — o campo posicional da remessa reserva 16 posições para o da
  tentativa.
- **`api/http/Recorte`** — os padrões da demonstração num lugar só. Espalhados
  por dois controllers, o dia em que divergissem produziria faturas num recorte e
  um ciclo noutro: o ciclo montaria vazio e nada acusaria.
- **`api/http/ChavesPublicadas`** — o publicador anotando o que saiu. É o mesmo
  recurso que o `Main` usa para narrar o `send`.
- **`api/http/FalhasComoResposta`** — a recusa também é efeito. A segunda
  montagem do mesmo recorte devolve `409` com o motivo intacto, em vez de um
  corpo genérico que esconderia a constraint fazendo o seu trabalho.
- **`infra/consulta/EstadoDoMundo`** — banco, outbox, diretório do parceiro,
  bucket e fila num retrato só.
- **`<parameters>true</parameters>` no compilador** — os nomes dos parâmetros no
  bytecode. As rotas nomeiam os seus à mão de qualquer forma; a linha é o cinto
  além do suspensório.
- **`EndpointsDevolvemEfeitoTest`** (9) — o ciclo inteiro por HTTP de verdade,
  na ordem, contra os containers. Cada contagem que a API devolve é conferida
  contra o banco, contra o diretório do parceiro ou contra a fila.
- **`ControllerNaoDecideTest`** (4) e **`ClienteHttp`** (teste) — a fronteira e o
  cliente que fala por soquete, sem atalho de framework.

### Decisões

- **Todos `POST`, inclusive os que parecem leitura.** Cada chamada executa um
  job e tem efeito. Um `GET` que muda estado é uma armadilha para qualquer coisa
  que pré-busque links; `/estado` é o único `GET`, e é o único sem efeito.
- **A resposta é o efeito, não `200`.** "Montou" não é informação; "montou C-1 e
  moveu 3 tentativas" é. É o que torna o `curl` sozinho suficiente para entender
  o que aconteceu, e o que vai permitir ao painel do step-12 ter um log de
  eventos sem inventar texto.
- **O que se proíbe no controller é o *acesso*, não a intenção.** A erosão não
  começa com uma decisão grande: começa com um `PreparedStatement` "só para
  conferir uma coisa antes de chamar o use case". Sem `java.sql` e sem SDK, o
  controller não tem com o que decidir — e é isso que `ControllerNaoDecideTest`
  cobra.
- **O `/estado` não passa pelo domínio.** É leitura de operação: nenhuma regra
  deste projeto pergunta quantos objetos há no bucket. Acrescentar `listar()` ao
  `ArmazenamentoArtefato` para servir uma tela colocaria no domínio uma pergunta
  que nenhuma decisão faz — e a porta do step-07 nasceu sem `list` e sem
  `delete` exatamente para não convidar esse acréscimo. O parceiro é a exceção:
  ali a leitura já é porta desde o step-09, e abrir uma segunda conexão SSH só
  para a tela seria um caminho paralelo ao que a coleta usa.
- **O relay é montado por passada, e é o único.** As chaves que foram para a
  fila só são conhecidas por quem publica. Montá-las a partir do outbox diria o
  que estava pendente *antes* da passada — outra afirmação, e mentirosa
  justamente na passada em que o relay morre no meio.
- **A coleta não recebe ciclo.** Ela varre o diretório; de que recorte é cada
  arquivo quem diz é o header dele. Pedir o ciclo seria supor que o parceiro
  respeita a nossa contagem, e o step-09 existe porque ele não respeita.
- **Os ids das faturas saem da data, não de um sorteio.** A segunda chamada no
  mesmo recorte esbarra na chave primária, como a segunda montagem esbarra no
  `UNIQUE (banco, data_ref)`. Reexecutar é recusado por construção nos dois
  lugares, e pela mesma razão.
- **Um único Spring Boot para tudo.** Os endpoints do simulador do step-11
  ficarão num pacote separado e no mesmo processo: dois processos exigiriam
  orquestração para uma demonstração que cabe numa tela.
- **O `Main` não foi tocado.** Console e HTTP entram pelo mesmo `Ambiente` e
  montam os mesmos objetos; o cenário do step-06 continua sendo a narrativa
  escrita, e os endpoints são o mesmo ciclo sob controle manual.

### Verificado

- `mvn test` → 71 testes, 0 falhas (58 dos steps anteriores, 13 deste).
- `CenarioPontaAPontaTest` continua verde **sem uma linha alterada** no `Main` —
  é a prova de que o console não mudou de comportamento.
- `FundacaoTest.dominioIsolado` continua verde com o Spring no classpath: a
  lista branca do domínio (`java.*` e ele mesmo) já proibia
  `org.springframework`, e agora a mensagem diz isso em voz alta.
- `ControllerNaoDecideTest` confere as duas fronteiras do step e mais duas: um
  use case por controller, e as oito rotas publicadas.
- O ciclo inteiro por HTTP roda em ~22s na suíte, quiescência incluída.

AI: est 2h30 / actual 40min / ~100% generated / 2 issues caught in review

<!--
O 1: o teste registrava o `Ambiente` dos containers com
`registerSingleton("ambiente", ...)` antes do refresh. O `@Bean ambiente()` da
`Fiacao` tem o mesmo nome, e `registerBeanDefinition` chama `resetBeanDefinition`
— que **apaga** o singleton manual. O servidor subiu apontado para
`localhost:4566` e o teste morreu com "Connection refused" na criação do
publicador. A correção é um `registerBean` primário com nome próprio, que é o
seam que o Spring oferece de verdade.

O 2: sem `-parameters`, o Spring não descobre o nome de `@RequestParam int
quantidade` e recusa a chamada com `IllegalArgumentException` — que
`FalhasComoResposta` traduziu em `400`, e o teste só afirmava o status. Oito
falhas idênticas e nenhuma pista. Duas correções: a flag no compilador e o corpo
da resposta na mensagem da asserção, para que a próxima recusa se explique
sozinha.

O `ColetarRetornoController` usa quiescência de 1s, vinda de uma constante da
`Fiacao` e não do `Ambiente`: é um valor de desenho da demonstração, não
configuração — quem clica o botão é gente. Se um dia precisar variar por
ambiente, muda de lugar.
-->

## [step-09] — 2026-08-31 — Coleta de retorno com quiescência e trailer

"Sem callback" deixa de ser uma frase sobre o parceiro e vira três mecanismos no
código. O parceiro não avisa que o arquivo chegou, não avisa que terminou de
escrevê-lo e não garante um arquivo por ciclo — e cada uma dessas três ausências
tem agora um teste que a prova cara:

| ausência | mecanismo | o que custa não ter |
|---|---|---|
| não avisa que chegou | varredura periódica | nada acontece até alguém olhar |
| não avisa que terminou | quiescência (2 leituras) | aplica-se um arquivo cortado ao meio |
| não garante um por ciclo | trailer + aplicação incremental | metade do dia vira `SEM_RETORNO` |

O que **não** mudou: quem decide o estado de uma tentativa continua sendo o
`UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'` do step-03. A coleta só entrega
linhas a ele — e é justamente por isso que o `sha256` deste step pode ser só um
atalho de custo.

### Adicionado

- **`ColetarRetornoUseCase`** — uma passada: lista, aplica quiescência, baixa,
  arquiva, valida o trailer, aplica linha a linha, registra o hash. **Nada é
  marcado como "em processamento"**: o que não passa numa passada é reavaliado
  na seguinte, a partir do mesmo estado. Não há estado intermediário para vazar
  quando o processo morre no meio.
- **`LeitorDeRetorno`** — a porta que mantém `api → domain` de pé. O coletor
  mora no domínio e o parser mora em `api/`; sem esta porta a dependência
  apontaria para o lado errado, e é `FundacaoTest.dominioIsolado` quem cobra.
  Ela responde três perguntas sobre um punhado de bytes: de que recorte é, se
  fecha, e o que afirma.
- **`api/ArquivoRetorno`** — o parser do layout posicional (header, N detalhes,
  trailer), implementando `LeitorDeRetorno.Retorno`. Aplica cada linha por
  `LinhaRetorno.aplicarCom`, que é o adaptador que o step-03 desenhou e que aqui
  só ganhou quem o alimente.
- **`CanalArquivos.listar/atributos/baixar`** — a porta do step-08 ganha a
  leitura. `Atributos` traz tamanho **e** mtime; `atributos` devolve
  `Optional`, porque sumir entre a listagem e a pergunta é resposta, não falha.
  Continua **sem `remover`**: o diretório é do parceiro.
- **Tabela `arquivo_retorno`** — `nome`, `sha256`, `ciclo_id`, `linhas`,
  `baixado_em`, com `UNIQUE (sha256)` e sem chave substituta: a identidade da
  linha **são** os bytes. O mesmo nome pode voltar com outro conteúdo, e as duas
  vezes são história legítima.
- **`RepositorioArquivoRetorno`** + **`RepositorioArquivoRetornoPostgres`** —
  duas operações, "já vi estes bytes?" e "vi estes bytes". Nenhuma consulta por
  nome, ciclo ou data, porque nenhuma decisão do domínio depende disso.
- **`ChaveArtefato.doRetorno`** — `retorno/{banco}/{dataRef}/{nome}`. Termina no
  nome que o parceiro escolheu, e não num id nosso: o arquivo é dele, um ciclo
  pode receber vários, e o nome é a única coisa que os distingue.
- **`Sha256`** — o mesmo hash num lugar só. O projeto guarda a mesma afirmação
  ("estes eram os bytes") em duas tabelas, e duas implementações seriam duas
  chances de a comparação entre elas deixar de fazer sentido. `Remessa.sha256()`
  passou a delegar.
- **`ArquivoIncompletoNaoEhProcessadoTest`** (2), **`ArquivoEmEscritaNaoEhBaixadoTest`**
  (1), **`RetornoParticionadoTest`** (1) e **`ReenvioDeRetornoTest`** (2) — os
  três obrigatórios do step mais o par que a Definition of Done pede sobre o
  hash.
- **`RetornoDoParceiro`** (teste) — o parceiro escrevendo. Fica do lado do teste
  porque **este projeto não escreve arquivos de retorno, ele os lê**; quem
  escreve é o outro lado do fio, e no step-11 vira o `simulador/`. O layout está
  montado à mão ali de propósito: se o parser divergir do contrato, é este
  arquivo que deixa de bater com ele — como aconteceria com o parceiro de
  verdade.

### Decisões

- **Descartar o arquivo incompleto inteiro, em vez de aplicar o que dá.** Meia
  aplicação é indistinguível de um retorno legítimo menor, e o fechamento do
  ciclo transformaria o resto em `SEM_RETORNO` — afirmando silêncio onde havia
  ruído. Um retorno pela metade aplicado é pior que nenhum, porque nada no banco
  registra que ele estava pela metade.
- **Quiescência por tamanho *e* mtime.** Só o tamanho não pega o arquivo
  reescrito no lugar com o mesmo comprimento; só o mtime não pega o arquivo que
  cresce dentro do mesmo segundo — a resolução que o protocolo dá.
- **Intervalo configurável, não constante.** Milissegundos na suíte, minutos em
  produção. Uma constante forçaria o teste a esperar de verdade, e um teste que
  dorme minutos é um teste que ninguém roda.
- **O hash é gravado *depois* de aplicar, nunca antes.** Antes, o processo que
  morresse no meio da aplicação deixaria o atalho gravado e o trabalho pela
  metade — e a próxima passada curto-circuitaria justamente o arquivo que ainda
  tinha o que aplicar. É a mesma ordem do relay pela mesma razão.
- **O hash é atalho de custo, não garantia de idempotência.** Ele cobre
  "exatamente os mesmos bytes". Um reenvio com uma linha a mais passa direto — e
  está **certo** que passe, porque é o `UPDATE` condicional que sabe o que já foi
  aplicado. `ReenvioDeRetornoTest` prova as duas metades dessa frase.
- **Nada é apagado do diretório do parceiro.** O arquivo já aplicado reaparece em
  toda varredura, e quem o reconhece são os bytes, não o desaparecimento. Uma
  coleta que apagasse trocaria uma pergunta barata por um efeito destrutivo
  irreversível num diretório que não é nosso.
- **O crescimento do arquivo em escrita vem de um decorador, não de uma
  thread.** O crescimento tem que acontecer *depois* da primeira leitura e
  *antes* da segunda; uma thread acertaria esse instante às vezes, e um teste que
  falha sozinho deixa de ser lido.
- **`RetornoParticionadoTest` compara o estado inteiro, não uma contagem.**
  Contar acertaria mesmo se as partes tivessem se aplicado à tentativa errada.
  Fora do retrato ficam o id e o `criado_em` do outbox (são de quando a linha
  nasceu, não do que ela diz) e a `arquivo_retorno` — ela registra quantos
  arquivos chegaram, que é a única coisa que os dois caminhos não têm em comum.
- **A coleta não fecha o ciclo.** Ela não sabe se o parceiro terminou o dia —
  ninguém sabe. Quem declara o dia encerrado continua sendo `FecharCicloUseCase`,
  por horário.
- **O `Main` não mudou.** Ele aplica linhas de retorno construídas à mão porque
  não há quem escreva o arquivo do lado do parceiro: isso é o `simulador/` do
  step-11. Emendar aqui um parceiro de mentira seria fazer o step seguinte.

### Verificado

- `mvn test` → 58 testes, 0 falhas (52 dos steps anteriores, 6 deste).
- `RetornoDuplicadoTest` e `MultiplasTentativasTest` continuam verdes **sem uma
  linha alterada** — a coleta não mexeu na decisão.
- `FundacaoTest.dominioIsolado` continua verde: `ColetarRetornoUseCase` não
  importa `api`, e é a `LeitorDeRetorno` que paga por isso.
- `FundacaoTest.schemaCriadoPeloScriptDeInit` passou a cobrar **cinco** tabelas.
- `ArquivoEmEscritaNaoEhBaixadoTest` assere `downloads() == 0`: não basta o
  banco não ter mudado, o arquivo não pode nem ter sido transferido.

AI: est 2h30 / actual 55min / ~95% generated / 1 issue caught in review

<!--
O 1: a primeira versão do `ColetarRetornoUseCase` importava `api.ArquivoRetorno`
direto. Compilava, e teria quebrado `FundacaoTest.dominioIsolado` — o teste que
existe exatamente para isso. A correção não foi afrouxar o teste: foi a porta
`LeitorDeRetorno`, que é o desenho que o repo já usava para o S3, para o SQS e
para o SFTP, aplicado ao parser.

O javadoc de `LinhaRetorno` dizia que "o coletor que baixa o arquivo e o
interpreta não tem classe neste repositório". Envelheceu no mesmo commit que o
criou — o mesmo tipo de comentário que o step-08 já tinha corrigido uma vez.

O sshj loga permissões e timestamps de cada download usando o logger da NOSSA
subclasse de `InMemoryDestFile`, e não o dele: a linha `net.schmizz=warn` do
step-08 não alcançava. Três linhas INFO por arquivo baixado, silenciadas com uma
segunda linha no `simplelogger.properties`.
-->

## [step-08] — 2026-08-31 — Envio da remessa por SFTP

A transmissão deixa de ser um `UPDATE` com um comentário dizendo "transporte
fora de escopo" e vira I/O contra outro host: conexão SSH, arquivo aparecendo no
diretório do parceiro. Com ela entra a **segunda janela** do projeto — o `put`
no parceiro acontece antes do `COMMIT` que registra que ele aconteceu, e não
existe transação que una os dois. É a mesma forma do relay, e o step existe para
mostrar o que muda: aqui o efeito tem nome, e reexecutar sobrescreve em vez de
duplicar.

| | relay (05) | envio (08) | remessa (07) |
|---|---|---|---|
| efeito externo | mensagem na fila | arquivo no parceiro | objeto no S3 |
| reexecutar produz | **duplicata** | **sobrescrita** | **sobrescrita** |
| quem paga | consumidor, via `chaveDedup` | ninguém — o nome é o mesmo | ninguém |

### Adicionado

- **`CanalArquivos`** — `enviar(nome, bytes)`. Um método só: o domínio sabe que
  existe um outro lado e que deixar um arquivo lá é efeito externo, fora de
  transação aberta. Ganha a leitura no step-09.
- **`EnviarRemessaUseCase`** — `get` do artefato → `put` no parceiro → `COMMIT`
  das transições. Lê o artefato do S3 em vez de regerar a remessa: regerar seria
  correto, porque a projeção é pura, e ainda assim apagaria a fronteira que o
  step-07 criou — no dia em que a geração mudar, o enviado e o arquivado
  divergiriam sem que nada acusasse.
- **`CanalArquivosSftp`** — `com.hierynomus:sshj`, uma conexão por transmissão.
  Traduz `IOException` em `FalhaDePublicacao` antes de atravessar a porta, como
  o publicador do SQS já fazia.
- **`ChaveArtefato.nomeDaRemessaNoParceiro`** — `{banco}-{dataRef}-{cicloId}.rem`,
  plano e sem barras. Mora ao lado da chave do S3 porque é a mesma derivação
  aplicada ao outro lado do fio.
- **`RepositorioCiclo.registrarEnvio`** — as duas transições numa transação:
  tentativas `SOLICITADO → ENVIADO_PARCEIRO` e ciclo `MONTADO → ENVIADO`. As
  guardas por status são o que torna a retransmissão inócua.
- **`Ambiente.ServidorSftp`** — host, porta, usuário e senha do parceiro, com os
  mesmos padrões do Compose. Uma descrição de servidor, e não um cliente pronto
  como os da AWS: a sessão SSH nasce e morre a cada transmissão.
- **Serviço `sftp` (`atmoz/sftp:alpine`) no Compose**, com `/remessa` e
  `/retorno`, e o mesmo container subido por Testcontainers na suíte.
- **`EnvioChegaNoParceiroTest`** (3 testes) e **`CrashDepoisDoPutTest`**
  (1 teste) — os dois obrigatórios do step. As asserções são feitas do lado do
  parceiro, por `DiretorioDoParceiro`: outra conexão, outro cliente, listando o
  diretório e baixando o que está lá.
- **`simplelogger.properties`** — o handshake do sshj em `warn`. O cenário do
  `Main` é a saída do programa, e uma dezena de linhas INFO por transmissão a
  afogaria.

### Decisões

- **O nome no destino é derivado do ciclo, não sufixado por tentativa.** A
  alternativa (`...-1.rem`, `...-2.rem`) deixaria o parceiro decidir qual dos
  dois arquivos vale, transferindo para fora um problema que é nosso.
- **O nome determinístico não fecha a janela.** Ele torna o efeito da
  reexecução idempotente por conteúdo. O parceiro que já leu o arquivo antes da
  sobrescrita processa duas vezes — e quem absorve isso é o `UPDATE` condicional
  do step-03, não o SFTP.
- **A transição é do ciclo inteiro.** Um arquivo é um evento: ou o parceiro
  recebeu a remessa, ou não recebeu. Não existe meia transmissão a registrar, e
  por isso não há `registrarEnvio` por tentativa.
- **`registrarEnvio` tem guarda de status, ao contrário de `registrarRemessa`.**
  A diferença é o que está sendo escrito: chave e hash de um artefato imutável
  podem ser regravados iguais; estado de tentativa, não — sem a guarda, uma
  retransmissão devolveria a `ENVIADO_PARCEIRO` uma tentativa que o retorno já
  resolveu.
- **SFTP de verdade, não um `Path` local fingindo de canal.** Um diretório local
  não tem latência, não tem arquivo visível pela metade e não tem conexão que
  cai no meio do `put` — e é disso que o step-09 precisa para provar a
  quiescência.
- **`PromiscuousVerifier` no lugar de `known_hosts`.** O outro lado é um
  container que nasce com chave nova a cada execução; verificar o que não existe
  seria teatro. Contra um parceiro real, esta é a linha que mudaria.
- **O `Main` passa a transmitir de verdade.** Ele imprimia
  "transporte fora de escopo", e isso deixou de ser verdade neste step. O
  `Cenario` dos testes de retorno continua com `UPDATE` direto de propósito:
  aqueles testes começam depois da entrega, e fazê-los abrir conexão SSH só para
  chegar ao estado inicial trocaria o sujeito deles.

### Verificado

- `mvn test` → 52 testes, 0 falhas (48 dos steps anteriores, 4 deste).
- `docker compose up -d` + `mvn compile exec:java` → o cenário roda contra o
  Compose e imprime
  `[envia] C-1 ENVIADO — 5 tentativas SOLICITADO → ENVIADO_PARCEIRO (341-20260831-C-1.rem no SFTP do parceiro)`.
  `docker exec ciclo-sftp ls -l /home/parceiro/remessa` mostra o arquivo com os
  282 bytes do artefato do S3.
- `CrashDepoisDoPutTest` assere **1** arquivo no destino depois da reexecução, e
  os mesmos bytes da primeira entrega — a janela está documentada, não
  eliminada.
- `FundacaoTest.dominioIsolado` continua verde: nenhum import de biblioteca SSH
  em `domain/`.

AI: est 2h / actual 40min / ~95% generated / 1 issue caught in review

<!--
O 1: o `Main` e o `Cenario` continuavam com o comentário "transporte fora de
escopo" depois de o transporte existir. Um comentário que descreve um limite do
projeto envelhece junto com o limite, e este envelheceu no mesmo commit que o
criou.

Dois erros de compilação no caminho, os dois do mesmo tipo do step-07: os
decoradores de `RepositorioCiclo` nos testes antigos pararam de compilar quando
a porta ganhou `registrarEnvio`. Uma porta que cresce cobra de todo mundo que a
implementa — e o compilador cobra na hora, que é a diferença entre uma porta e
um mapa de strings.
-->

## [step-07] — 2026-08-31 — Remessa durável no S3

Gerar e transmitir deixam de ser o mesmo instante. A remessa passa a existir
como objeto no S3, com chave derivada do ciclo, e o `put` acontece **antes** do
`COMMIT` que registra a chave — a mesma ordem do relay, e o step existe para
mostrar por que aqui ela não custa nada: o objeto é endereçável, e a segunda
gravação sobrescreve com os mesmos bytes. A comparação entre os três efeitos
externos do projeto (S3, parceiro, fila) é a lição, e agora ela está em teste.

### Adicionado

- **`ArmazenamentoArtefato`** — `put`, `get`, `existe`. Sem `delete` e sem
  `list`: expurgo é operação de infra, e uma porta que o oferecesse convidaria
  regra de negócio a decidir o que apagar.
- **`ChaveArtefato`** — a chave como valor do domínio:
  `remessa/{banco}/{dataRef}/{cicloId}.rem`, montada num lugar só. Data em
  `yyyyMMdd`, o mesmo formato do header — o projeto escreve data de uma maneira
  só, no arquivo e no endereço dele.
- **`ArmazenamentoArtefatoS3`** — AWS SDK v2 com `forcePathStyle`, o que o
  LocalStack exige. Traduz `SdkException` em `FalhaDePersistencia` antes de
  atravessar a porta, como o publicador do SQS já fazia.
- **Layout posicional em `Remessa`** — header (`0`), N detalhes (`1`) e trailer
  (`9`) com a contagem. `idTentativa` nas posições 2–17 é a correlation key do
  step-09; valor em centavos, sem separador.
- **`Remessa.sha256()` e `quantidadeDeDetalhes()`** — o hash saiu do `Main`, que
  o calculava para imprimir, e virou propriedade do artefato.
- **`ciclo_cobranca.remessa_chave` e `remessa_sha256`** — nulos até a geração
  commitar, e sempre juntos, por `CHECK` e pelo construtor do record.
- **`RepositorioFatura.doCiclo`** — uma consulta por ciclo, e não uma por
  tentativa dentro do laço: a remessa leva o valor, e o valor é da fatura.
- **Bucket `cobranca-artefatos` no script de init**, com `SERVICES: sqs,s3`. O
  script ganhou uma linha final própria, e é nela que o Testcontainers espera:
  se o bucket não foi criado, o teste nem começa.
- **`RemessaDeterministicaTest`** (5 testes) e **`RemessaSobreviveAReexecucaoTest`**
  (1 teste) — os dois obrigatórios do step, com a comparação sempre em bytes
  inteiros.
- **Dois testes novos em `FundacaoTest`** — o bucket existe sem passo manual, e
  a constraint que amarra chave e hash existe no banco.

### Decisões

- **A chave é derivada do ciclo, não sorteada.** É o que transforma o `put`
  reexecutado em sobrescrita idêntica. Um UUID ou um `now()` na chave faria de
  cada tentativa um objeto novo, e o bucket viraria um log de tentativas em vez
  do artefato do ciclo — ver ADR-0003.
- **`registrarRemessa` não tem guarda de status**, ao contrário dos `UPDATE` que
  decidem estado de tentativa e de fatura. De propósito: a segunda geração grava
  exatamente os mesmos dois valores. Guardar contra a reexecução seria proteger
  contra o caso que o step existe para mostrar como inofensivo.
- **Chave e hash moram no `CicloCobranca`, não numa tabela de artefatos.** São
  estado do ciclo — "a remessa deste ciclo é aquele objeto" —, e uma tabela à
  parte só acrescentaria um join para responder a mesma pergunta.
- **O sha256 é asserção sobre o nosso código, não integridade do S3.** Regerar e
  comparar com o que está no banco responde "o artefato mudou?" sem baixar nada.
  O teste confere o hash gravado contra o objeto de fato lido, calculando o
  digest por fora — não contra o que a projeção diz ter produzido.
- **`existe` propaga falha de rede em vez de responder `false`.** "Não deu para
  perguntar" não é "não existe": engolir isso faria uma indisponibilidade do S3
  parecer um artefato que sumiu.
- **`TrabalhoDerivadoDeterministicoTest` (step-02) continua existindo.** Ele
  assere a **projeção** — bytes iguais, ordem vinda do repositório; os dois
  testes novos asseram a **gravação**. Fundi-los teria custado a distinção entre
  "a função é pura" e "o artefato é endereçável", que é o assunto inteiro do
  step.
- **O `Main` gera a remessa duas vezes e imprime as duas linhas.** É a segunda
  passada que torna visível a sobrescrita idêntica — o contraste com a segunda
  passada do relay, que custa uma duplicata.

### Verificado

- `mvn test` → 48 testes, 0 falhas (40 dos steps anteriores, 8 deste).
- `docker compose up -d` + `mvn compile exec:java` → o cenário roda contra o
  Compose e imprime
  `[artefato] remessa/341/20260831/C-1.rem — 282 bytes no S3 (regerada: idêntica, objeto: idêntico)`.
  O objeto lido do bucket com `awslocal s3 cp` tem as 7 linhas esperadas —
  header, 5 detalhes, trailer `9000005` — e o ciclo no Postgres traz a chave e o
  hash.
- **Teste de mutação manual**, três vezes. (1) Chave com `nanoTime`: 3 testes
  caem, inclusive o do crash — o órfão deixa de estar onde a reexecução grava.
  (2) `COMMIT` antes do `put`: `RemessaSobreviveAReexecucaoTest` cai, porque a
  janela que o teste descreve deixa de existir. (3) Trailer contando um a mais:
  caem o teste de layout e o de contagem.

AI: est 2h / actual 55min / ~95% generated / 1 issue caught in review

<!--
O 1: o decorador de `RepositorioCiclo` em `MontagemDeterministicaTest` (step-02)
parou de compilar com o método novo da porta — sinal de que uma porta que cresce
cobra de todo mundo que a implementa, inclusive dos testes. Delegar era a
resposta certa ali: aquele teste mata a atribuição, não a remessa.
-->

## [step-06] — 2026-08-31 — Cenário ponta a ponta

O último step não acrescenta regra: torna visível, sem ler teste nenhum, o que
os quatro anteriores provaram. Um `main` roda o ciclo de vida inteiro contra o
ambiente do Compose e imprime cada transição — inclusive as três que costumam
ficar escondidas atrás de uma asserção verde: o retorno duplicado que afeta zero
linhas, o silêncio que vira `SEM_RETORNO`, e a mensagem publicada duas vezes
porque o relay morreu na hora errada.

### Adicionado

- **`Main`** — a fiação inteira num lugar só e o cenário na ordem em que ele
  acontece: zera, abre faturas, monta, projeta a remessa, transmite, aplica os
  retornos, fecha, publica, confere a fila. Quatro faturas, cinco tentativas, um
  ciclo. Recebe `Ambiente` e `PrintStream` no construtor, o que é o que permite
  ao teste rodá-lo contra os containers.
- **`CenarioPontaAPontaTest`** (4 testes) — as linhas da Definition of Done do
  step viradas em asserção, mais o estado do banco no fim.
- **`exec-maven-plugin` fixado no `pom.xml`** — `mvn compile exec:java` roda o
  cenário. A propriedade `exec.mainClass` já existia desde o step-01; faltava a
  versão do plugin, e plugin sem versão é build que muda sozinha.
- **README com o passo a passo e a saída real** — com os três detalhes que valem
  o olho apontados um a um.
- **`AmbienteDeTeste.ambiente()`** deixou de ser privado: é o mesmo objeto de
  configuração que o `Main` recebe.

### Decisões

- **A quarta fatura.** O step-06.md pede 3 faturas, "4 tentativas" e uma quarta
  tentativa sem retorno — e a conta não fecha: a história da `F-2` (recusa e
  reapresentação) já consome duas tentativas, e a quarta é a da `F-3`, que
  precisa pagar. As três histórias da tabela ficaram exatamente como escritas, e
  o silêncio ganhou fatura própria, `F-4`. Isso é mais fiel ao que ele quer
  mostrar: `SEM_RETORNO` é o parceiro que não falou **nada** sobre uma fatura,
  não uma reapresentação a menos.
- **A saída é lida do banco, não narrada de memória.** Cada linha `[retorno]`
  imprime o status que a tentativa tinha e o que ela passou a ter, relidos pelo
  repositório. Um `main` que imprime o que espera ter acontecido é um script de
  slides: continuaria bonito depois de o comportamento mudar.
- **O `Main` zera banco e fila antes de começar** — e diz isso na primeira
  linha. A contagem final ("4 lançamentos, 3 chaves") só significa alguma coisa
  se for deste cenário. É o único lugar do projeto que apaga dados, e por isso
  está anunciado em vez de escondido no meio.
- **A contagem final consome as mensagens.** Contar por
  `ApproximateNumberOfMessages` seria uma estimativa sobre o que o LocalStack
  acha que tem; receber é o que o mainframe receberia.
- **O crash é um decorador do `RepositorioOutbox`, o mesmo recorte de
  `CrashDoRelayTest`.** Simular a morte com um `if` dentro do use case colocaria
  no código de produção uma linha que só existe para o demo.
- **A transmissão da remessa continua `UPDATE` solto.** `EnviarRemessa` não tem
  classe neste repositório, e inventar uma agora — no step que não acrescenta
  regra — seria escrever SFTP de mentira para o cenário parecer completo.
- **O `Main` tem teste.** Ele seria a única parte do projeto que só quebra na mão
  de quem for demonstrá-la; e a Definition of Done do step é sobre o que a saída
  mostra, o que é exatamente o que dá para asserir.

### Verificado

- `mvn test` → 40 testes, 0 falhas (36 dos steps anteriores, 4 deste).
- `docker compose up -d` + `mvn compile exec:java` → o cenário roda do começo ao
  fim contra o Compose; a saída completa está no README.
- A saída mostra `T-1 PAGO → PAGO (0 linhas afetadas — ignorado)`, o
  `NAO_PAGO (SALDO_INSUFICIENTE)` e o `SEM_RETORNO: T-5 (F-4)` **sem** linha de
  outbox, e termina em `4 lançamentos, 3 chaves distintas`.
- No banco, ao fim: 3 linhas de outbox, todas `PUBLICADO`, nenhuma `PENDENTE` —
  4 mensagens na fila para 3 lançamentos.
- **Teste de mutação manual**, duas vezes. Tirando o decorador que mata o relay,
  o cenário publica 3 mensagens e `aFilaTerminaCom4MensagensE3Chaves` cai —
  o demo deixaria de mostrar o at-least-once sem que nada acusasse. Aplicando
  também um retorno pago à `T-5`, 3 dos 4 testes falham: sem silêncio não há
  `SEM_RETORNO`, e o outbox passa a ter 4 linhas.

AI: est 1h30 / actual 35min / ~95% generated / 2 issues caught in review

<!--
As 2: (1) a contagem do step-06.md não fecha — 3 faturas e "4 tentativas" com a
F-2 tendo duas, sobrando a quarta para ser ao mesmo tempo paga (F-3) e sem
retorno; virou uma quarta fatura silenciosa; (2) a primeira asserção da chave
duplicada contava "chaveDedup=F-3" na saída inteira e pegava 5 ocorrências — a
chave também aparece nas linhas de outbox e de send, e o que o teste quer contar
é o que saiu da fila.
-->

## [step-05] — 2026-08-31 — Relay

A publicação, finalmente — e fora da transação, que é o ponto. Aqui o projeto
paga o preço que vinha defendendo desde o ADR-0002: duas mensagens para um
lançamento quando o processo morre no meio, asserido num teste em vez de
anotado num rodapé.

### Adicionado

- **`PublicarOutboxUseCase`** — `SELECT PENDENTE` → `send` → `UPDATE PUBLICADO`,
  uma mensagem por vez, nessa ordem. Devolve quantas linhas saíram de
  `PENDENTE`. Uma falha interrompe a passada e propaga: sem backoff, sem DLQ,
  sem pular a linha que falhou.
- **`PublicadorLancamentoSqs`** — AWS SDK v2, fila padrão, `chaveDedup` como
  atributo da mensagem. Traduz `SdkException` antes que ela atravesse a porta.
- **`infra/config/Ambiente`** — o único lugar que lê configuração:
  `DataSource` + `SqsClient` + a url da fila, resolvida tarde. Padrões iguais aos
  do `docker-compose.yml`.
- **`domain/exception/FalhaDePublicacao`** — falha de envio separada de falha de
  banco porque as duas têm consequências diferentes para o relay.
- **`RepositorioOutboxPostgres.marcarPublicado`** — o `UPDATE ... WHERE id = ?
  AND status = 'PENDENTE'`, com `publicado_em`. Era o último método declarado e
  não implementado do projeto.
- **`infra/persistence/Payload`** — extraído de dentro do repositório de outbox,
  agora que o publicador precisa do mesmo formato.
- **`RelayPublicaTest`** (6 testes) e **`CrashDoRelayTest`** (3 testes), mais
  `AmbienteDeTeste.drenarFila` e `Cenario.pagamentoPendente`.

### Decisões

- **A ordem é a decisão inteira.** Marcar `PUBLICADO` antes de enviar fecha a
  janela de duplicata e abre a de perda. A duplicata o consumidor descarta pela
  `chaveDedup`; a mensagem que nunca saiu, numa linha que diz que saiu, ninguém
  procura. Os dois lados dessa troca estão em `CrashDoRelayTest`, um teste cada.
- **`Payload` deixou de ser classe interna do repositório.** Se o publicador
  escrevesse o próprio JSON, o contrato com o consumidor teria duas versões
  livres para divergirem. Com um dono só, o corpo publicado é byte a byte o que
  está na coluna `payload` — e o teste assere o texto inteiro, não um `contains`.
- **`FalhaDePublicacao` em vez de reusar `FalhaDePersistencia`.** Não é purismo
  de nomes: falha de envio deixa a linha `PENDENTE` sem saber se a mensagem
  chegou, e falha do banco depois do envio garante republicação. Chamar as duas
  de "falha de persistência" apagaria a distinção que o ADR-0002 usa como
  argumento — e o tipo do AWS SDK atravessaria a porta.
- **O `Ambiente` é a fiação que os testes usam.** Um objeto de configuração
  exercitado só pelo `Main` seria código morto até o step-06, com a suíte
  montando `DataSource` e `SqsClient` à parte — a mesma divergência que o
  `AmbienteDeTeste` evita reaproveitando os scripts de init. Por isso
  `Ambiente.de(consulta)` recebe de onde ler as variáveis, e o teste aponta a
  montagem de produção para os containers.
- **O id que o SQS devolve não vira estado.** É do transporte, não do domínio: o
  que o relay precisa saber é se a linha saiu de `PENDENTE`, e quem responde isso
  é o `UPDATE`.
- **A mensagem é recebida do LocalStack, não conferida num mock.** Um mock
  provaria que o código chamou o método certo; o que precisa ser provado é que o
  outro lado tem o que consumir.

### Verificado

- `mvn test` → 36 testes, 0 falhas (27 dos steps anteriores, 9 deste).
- `RelayPublicaTest`: a linha vira `PUBLICADO` com `publicado_em` preenchido e a
  mensagem **chega na fila** — corpo igual ao da coluna `payload` e atributo
  `chaveDedup` = `FAT-1`; a segunda passada não republica; o limite recorta a
  passada e a mais antiga sai primeiro; marcar de novo afeta **zero** linhas e
  não reescreve `publicado_em`.
- `CrashDoRelayTest`: morrendo entre o `send` e o `UPDATE`, a linha continua
  `PENDENTE` e a passada seguinte republica — **2 mensagens na fila, 1 única
  `chaveDedup`, corpos idênticos**. No caminho oposto, a falha de envio não marca
  nada e não perde nada: fila vazia, linha pendente, e a passada seguinte publica
  uma vez só.
- **Teste de mutação manual**, duas vezes. Invertendo a ordem para
  `UPDATE` antes do `send`, os **3** testes de `CrashDoRelayTest` falham e
  `RelayPublicaTest` continua verde — a prova de que é o teste de falha, e não o
  caminho feliz, que sustenta a ordem. Removendo a guarda
  `AND status = 'PENDENTE'` do `marcarPublicado`, cai
  `segundaMarcacaoAfetaZeroLinhas`.

AI: est 2h / actual 30min / ~95% generated / 3 issues caught in review

<!--
As 3: (1) `Ambiente` nasceria código morto até o step-06, com a suíte montando
a própria fiação em paralelo — virou a montagem que os testes usam; (2) o
`Payload` privado dentro de `RepositorioOutboxPostgres` obrigaria o publicador a
uma segunda cópia do formato, duas versões do contrato com o consumidor; (3)
`Cenario.cicloTransmitido` só montava o recorte padrão, e o teste de duas
pendências esbarraria no `UNIQUE (banco, data_ref)` — falharia por um motivo que
não é o dele.
-->

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
