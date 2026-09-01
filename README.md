# mini-outbox-cobranca

Prova de **um** conceito: como garantir que um evento financeiro seja publicado
numa fila externa **exatamente uma vez em relação à decisão de negócio**, quando
a fila não participa da transação do banco.

Java 21 · Maven · Postgres · SQS e S3 (LocalStack) · SFTP (`atmoz/sftp`) ·
Spring Boot (Web).

Spring Boot entrou no step-10 por um motivo único: o projeto passou a expor
HTTP — um endpoint por passo do ciclo e um painel para observá-lo. O domínio
continua sem framework, e o `main` de console dos steps 01–06 continua rodando.

---

## O problema

Um sistema de débito automático de faturas de cartão. O cliente autoriza o
débito da fatura na conta que mantém num banco parceiro; todo dia, as faturas
que vencem naquela data são apresentadas ao banco para débito. O resultado de
cada tentativa volta e precisa refletir em dois lugares: na fatura do cliente,
que passa a PAGA, e no razão contábil, que recebe um **lançamento contábil** por
fatura paga. O razão é um mainframe legado que consome uma fila e aceita **um
lançamento por fatura**. Uma fatura pode ter N tentativas de débito; no máximo
uma delas paga, e no máximo um lançamento sai. Um lançamento duplicado não
quebra nada tecnicamente, mas gera uma divergência que alguém concilia à mão no
mês seguinte.

Bancos parceiros não atendem por chamada síncrona: trabalham por troca de
arquivos em janelas fixas. A remessa com as faturas do dia sai de madrugada, o
retorno chega em algum momento da noite — sem callback, sem aviso de que chegou,
às vezes particionado em mais de um arquivo, às vezes nunca. Não existe a quem
perguntar o que aconteceu com uma fatura específica antes da janela seguinte.
Isso força o desenho a ser um ciclo diário com fechamento explícito, e não um
fluxo reativo: em algum momento é preciso declarar o dia encerrado e dizer o que
aconteceu com as tentativas sobre as quais o parceiro não falou nada. É por isso
que existe `SEM_RETORNO` — o estado que registra a ausência de resposta sem
inventar uma.

O banco de dados e a fila são sistemas distintos, sem transação distribuída. Só
existem duas ordens possíveis, e as duas têm uma janela de falha. Se o `COMMIT`
vem antes do `send` e o processo morre no meio, a fatura fica PAGA e o lançamento
nunca é publicado — e, pior, **nada no sistema registra que havia algo a
publicar**, então nenhum reprocessamento descobre a perda. Se o `send` vem antes
do `COMMIT` e o processo morre no meio, o mainframe contabiliza um lançamento de
uma fatura que continua ABERTA: o mundo externo passa a conter um fato que o
sistema de registro nega. O mesmo raciocínio se aplica duas vezes no ciclo, e
não uma: na montagem, entre a escrita do ciclo no banco e o artefato de remessa
que sai para o parceiro; e na publicação, entre a escrita do resultado e a
mensagem na fila. Nos dois pontos há uma escrita que decide e um efeito externo
que não commita junto com ela.

A saída é não escolher entre as duas. A decisão de negócio e a **intenção de
publicar** são gravadas juntas, no mesmo `COMMIT` do Postgres — a intenção vira
uma linha na tabela `outbox`. Um **relay** separado lê o outbox e publica no SQS,
fora de qualquer transação. A janela some porque a mensagem só passa a existir
depois do commit que a autoriza. O que sobra de imperfeição fica explícito e
testado: se o relay morre entre o `send` e o `UPDATE outbox`, a mensagem é
republicada — at-least-once, com chave de dedup determinística para o consumidor
resolver.

## O ciclo de vida

A montagem do ciclo é a **única escrita que importa**. Remessa, retorno,
fechamento e publicação são trabalho derivado: dado o ciclo, todos podem ser
refeitos e chegam ao mesmo lugar. Por isso a remessa é uma função pura do ciclo
— mesma entrada, mesmos bytes — e por isso a reexecução da montagem é barrada
por um `UNIQUE (banco, data_ref)`, e não por uma consulta prévia que perderia a
corrida contra outro processo.

```mermaid
flowchart TB
    EB["EventBridge · crons"]

    subgraph montagem["05:00 · UMA transação Postgres"]
        direction LR
        M["MontarCicloUseCase"] --> CC[("ciclo_cobranca<br/>MONTADO")]
        M --> T1[("tentativa_debito<br/>ABERTO → SOLICITADO")]
    end

    REM["GerarRemessa<br/>função pura do ciclo · regerável byte a byte"]
    S3R[("S3 · remessa/{banco}/{dataRef}/{cicloId}.rem<br/>chave determinística · sobrescrita idêntica")]
    ENV["EnviarRemessa · até 05:30<br/>SOLICITADO → ENVIADO_PARCEIRO<br/>janela de duplicidade: put antes do commit"]

    EB --> montagem
    montagem --> REM --> S3R --> ENV

    subgraph parceiro["Fora do nosso controle"]
        direction LR
        PREC["recebe a remessa"] --> PDEB["processa o débito"] --> PRET["devolve o retorno<br/>— ou não devolve"]
    end

    ENV --> parceiro

    COL["ColetarRetorno · 18h–23h<br/>sem callback: varredura periódica<br/>quiescência decide se baixa<br/>trailer decide se está completo"]
    parceiro -.->|"arquivo aparece em algum momento"| COL

    S3T[("S3 · retorno/{banco}/{dataRef}/{nome}<br/>arquivado ANTES de validar o trailer")]
    COL --> S3T

    subgraph retorno["UMA transação Postgres"]
        direction LR
        AR["AplicarRetornoUseCase<br/>UPDATE ... WHERE status = ENVIADO_PARCEIRO"]
        AR --> TD[("tentativa → PAGO / NAO_PAGO / ERRO<br/>fatura ABERTA → PAGA")]
        AR --> OB[("outbox PENDENTE<br/>só quando PAGO")]
    end

    S3T --> retorno

    FC["FecharCiclo · 00:30<br/>ENVIADO_PARCEIRO → SEM_RETORNO<br/>nunca NAO_PAGO"]
    EB -.-> FC
    FC --> TD
    FC --> CC

    RL["PublicarOutbox · relay"]
    OB -.->|"SELECT PENDENTE"| RL
    RL -->|"send + chaveDedup"| Q(["SQS · lancamentos-contabeis"])
    RL -.->|"UPDATE PUBLICADO"| OB
    Q --> MF["Mainframe legado<br/>deduplica por chaveDedup"]
```

Os horários no diagrama são o desenho de produção, onde quem dispara cada passo é
o EventBridge. Neste repositório não há cron nem `@Scheduled`: **cada passo é um
endpoint**, e cada chamada é uma execução do job — ver "Operar o ciclo pela API".

## Os três mecanismos

A correção deste desenho não vem de vigilância nem de retentativa. Vem de três
mecanismos, e de mais nenhum.

**Uma escrita transacional por passo.** Cada passo do ciclo tem exatamente uma
escrita que decide algo, num único banco, num único `COMMIT`. Nenhum sistema
externo participa dela.

**Trabalho derivado re-executável.** Tudo que vem depois da decisão é função do
estado commitado: a remessa é função pura do ciclo, a publicação é função do
outbox. Reexecutar é seguro por construção, e não por verificação prévia.

**Transição condicional de estado.** Toda aplicação de resultado externo é
`UPDATE ... WHERE status = <esperado>`. Zero linhas afetadas significa "já
processado" — e é isso que torna idempotente, de uma vez só, o arquivo reenviado,
o arquivo parcial, a linha duplicada e o reprocessamento manual.

Qualquer parte do desenho que não se apoie num destes três é um ponto de
fragilidade. Existem exatamente duas, e as duas têm a mesma forma — um efeito
externo antes do `COMMIT` que o registra: a janela entre o `send` e o
`UPDATE PUBLICADO` do relay, e a janela entre o `put` no SFTP e o `COMMIT` das
transições do envio (step-08). Estão documentadas e testadas, em vez de
escondidas.

A diferença entre as duas é o que se paga por reexecutar. O arquivo no parceiro
e o objeto no S3 têm nome determinístico: a segunda gravação **sobrescreve** com
os mesmos bytes. A mensagem no SQS não tem nome: a segunda **duplica**. É por
isso que só a fila obriga o consumidor a cooperar — ver
[ADR-0003](docs/adr/0003-artefato-duravel-no-s3-em-vez-de-geracao-em-memoria.md).

Duas coisas o diagrama torna óbvias. A primeira: o subgraph do parceiro é o que
não controlamos — ele pode processar e não responder, e é por isso que existe
`SEM_RETORNO`. A segunda: entre o `send` e o `UPDATE PUBLICADO` (as linhas
tracejadas do relay) não há transação possível. É ali que mora o trade-off do
projeto inteiro.

## Como rodar

```bash
# testes — sobem Postgres, LocalStack e o SFTP sozinhos (Testcontainers), sem o Compose
mvn test

# ambiente local — schema, fila e bucket prontos, zero passo manual
docker compose -f infra/docker-compose.yml up
```

Só é preciso ter Docker rodando. `mvn test` **não** depende do Compose e o
Compose não depende dos testes; os dois aplicam os mesmos scripts de
`infra/init/` e sobem o mesmo servidor SFTP, com as mesmas credenciais.

### Ver o ciclo inteiro rodar

Com o Compose no ar, o cenário ponta a ponta imprime cada transição:

```bash
docker compose -f infra/docker-compose.yml up -d
mvn compile exec:java
```

Um ciclo, quatro faturas, e as três situações que o projeto existe para mostrar:
o retorno duplicado que afeta **zero** linhas, o silêncio que vira
`SEM_RETORNO` sem gerar lançamento, e o relay que morre entre o `send` e o
`UPDATE` — publicando `F-3` duas vezes com a mesma chave.

```
[limpeza]  banco zerado, 0 mensagem(ns) de execuções anteriores descartadas
[dados]    F-1 100.00 ABERTA · T-1
[dados]    F-2 250.50 ABERTA · T-2, T-3
[dados]    F-3 89.90 ABERTA · T-4
[dados]    F-4 42.00 ABERTA · T-5
[ciclo]    C-1 MONTADO — 5 tentativas ABERTO → SOLICITADO (banco 341, 2026-08-31)
[remessa]  C-1 5 detalhes, sha256=588b15cc7805f50a6fa343f92ebc12edcb3aafd6117820c60197322549643988
[artefato] remessa/341/20260831/C-1.rem — 282 bytes no S3 (regerada: idêntica, objeto: idêntico)
[envia]    C-1 ENVIADO — 5 tentativas SOLICITADO → ENVIADO_PARCEIRO (341-20260831-C-1.rem no SFTP do parceiro)
[retorno]  T-1 ENVIADO_PARCEIRO → PAGO  (1 linha afetada)
[fatura]   F-1 ABERTA → PAGA
[outbox]   F-1 + PENDENTE (chaveDedup=F-1) — na MESMA transação da fatura
[retorno]  T-1 PAGO → PAGO  (0 linhas afetadas — ignorado, o retorno já havia sido aplicado)
[retorno]  T-2 ENVIADO_PARCEIRO → NAO_PAGO (SALDO_INSUFICIENTE)  (1 linha afetada, sem outbox — não é pagamento, ou a fatura já pagou)
[retorno]  T-3 ENVIADO_PARCEIRO → PAGO  (1 linha afetada)
[fatura]   F-2 ABERTA → PAGA
[outbox]   F-2 + PENDENTE (chaveDedup=F-2) — na MESMA transação da fatura
[retorno]  T-4 ENVIADO_PARCEIRO → PAGO  (1 linha afetada)
[fatura]   F-3 ABERTA → PAGA
[outbox]   F-3 + PENDENTE (chaveDedup=F-3) — na MESMA transação da fatura
[fecha]    C-1 FECHADO — 1 tentativa(s) ENVIADO_PARCEIRO → SEM_RETORNO: T-5 (F-4)
[outbox]   3 linhas PENDENTE — nem NAO_PAGO nem SEM_RETORNO geram lançamento
[relay]    primeira passada — o processo vai morrer entre o send de F-3 e o UPDATE
[send]     F-1 → SQS  chaveDedup=F-1  msg=821c054e-b554-47d2-b0aa-d31c03dbc7c8
[send]     F-2 → SQS  chaveDedup=F-2  msg=be279b91-8866-4d5a-9565-9c1021059bcb
[send]     F-3 → SQS  chaveDedup=F-3  msg=6aa4934c-a670-4bfe-b0c4-c9d65e3435e8
[crash]    o relay morreu entre o send de F-3 e o UPDATE
[outbox]   ainda PENDENTE: F-3 — a mensagem saiu, a linha não foi marcada
[send]     F-3 → SQS  chaveDedup=F-3  msg=b96ab415-d49c-46b2-b376-44524d58cc30
[relay]    segunda passada — 1 linha(s) PENDENTE → PUBLICADO
[outbox]   3 PUBLICADO, 0 PENDENTE
[fila]     chaveDedup=F-1  {"faturaId":"F-1","valor":"100.00"}
[fila]     chaveDedup=F-2  {"faturaId":"F-2","valor":"250.50"}
[fila]     chaveDedup=F-3  {"faturaId":"F-3","valor":"89.90"}
[fila]     chaveDedup=F-3  {"faturaId":"F-3","valor":"89.90"}
[fila]     4 lançamentos, 3 chaves distintas — F-3 duplicada porque o relay morreu
[fila]     at-least-once é o contrato: quem desduplica pela chaveDedup é o consumidor — ver ADR-0002
```

Três detalhes que valem o olho:

- **`[retorno] T-1 PAGO → PAGO (0 linhas afetadas)`** — o mesmo retorno aplicado
  duas vezes. A segunda não encontra a tentativa em `ENVIADO_PARCEIRO`, e zero
  linhas não é erro: é o caso normal de um arquivo reprocessado.
- **`[fecha] ... T-5 (F-4)`** — o parceiro não falou nada sobre a `F-4`. Ela vira
  `SEM_RETORNO`, e não `NAO_PAGO`: silêncio não é recusa, e nenhum dos dois gera
  lançamento.
- **`[fila] 4 lançamentos, 3 chaves distintas`** — o preço da ordem
  `send` → `UPDATE`. A duplicata é visível, tem a mesma `chaveDedup` e o mesmo
  corpo; a alternativa (marcar antes de enviar) trocaria essa duplicata por uma
  mensagem perdida que ninguém procuraria.

O cenário **zera o banco e drena a fila** antes de começar — é um demo, e a
contagem final precisa ser dele. É o único lugar do projeto que apaga dados.

### Operar o ciclo pela API

Com o Compose no ar, o servidor sobe e cada passo do ciclo vira uma chamada:

```bash
docker compose -f infra/docker-compose.yml up -d
mvn spring-boot:run
```

**Cada chamada é uma execução do job.** Não há cron nem `@Scheduled` neste
projeto: em produção quem chama é o EventBridge, nos horários do diagrama acima;
aqui quem chama é um botão. Ter tirado o horário do código é o que permite
executar um passo justamente quando se quer olhar para ele.

```
POST /faturas?quantidade=3&banco=341&data=2026-09-01  faturas com tentativas ABERTO
POST /ciclo/montar?ciclo=C-1&banco=341&data=...       MontarCiclo
POST /ciclo/gerar-remessa?ciclo=C-1                   GerarRemessa   → artefato no S3
POST /ciclo/enviar?ciclo=C-1                          EnviarRemessa  → arquivo no SFTP
POST /ciclo/coletar                                   ColetarRetorno → quiescência + trailer
POST /ciclo/fechar?ciclo=C-1                          FecharCiclo    → SEM_RETORNO
POST /outbox/publicar?limite=50                       PublicarOutbox → relay
GET  /estado                                          banco, outbox, SFTP, S3 e fila
```

Tudo é `POST`, inclusive o que parece leitura: cada chamada **executa um job** e
tem efeito, e um `GET` que muda estado é uma armadilha para qualquer coisa que
pré-busque links. `/estado` é o único `GET`, e é o único sem efeito.

A coleta não recebe o ciclo de propósito: ela varre o diretório do parceiro, e
de que recorte é cada arquivo quem diz é o header dele — o parceiro não garante
um arquivo por ciclo.

Cada resposta traz **o efeito produzido** — contagens e transições —, não um
`200` vazio. "Montou C-1 e moveu 3 tentativas" é informação; "montou" não é:

```bash
curl -sX POST 'localhost:8080/ciclo/montar?ciclo=C-1&banco=341&data=2026-09-01'
{"cicloId":"C-1","banco":"341","dataRef":"2026-09-01","status":"MONTADO","solicitadas":3}

curl -sX POST 'localhost:8080/ciclo/gerar-remessa?ciclo=C-1'
{"cicloId":"C-1","chave":"remessa/341/20260901/C-1.rem","sha256":"7f1e...","detalhes":3,"bytes":168}

curl -sX POST 'localhost:8080/outbox/publicar'
{"publicados":1,"chavesDedup":["F-20260901-1"]}
```

A recusa também é efeito: montar o mesmo recorte duas vezes devolve `409` com o
motivo intacto — o `UNIQUE (banco, data_ref)` fazendo o seu trabalho, e não um
`if` no controller.

### Mexer no ambiente: o parceiro e as falhas provocadas

Do outro lado do fio está o **simulador do parceiro**, que lê a remessa do SFTP
e escreve o retorno:

```
POST /parceiro/processar?resultado=PAGO,NAO_PAGO,ERRO   desfechos em ciclo, na ordem do arquivo
POST /parceiro/processar?particionar=3                  o mesmo retorno em três arquivos
POST /parceiro/processar?atrasar=true                   uma parte agora, o resto na próxima chamada
POST /parceiro/reenviar-retorno                         reescreve, byte a byte, o que já entregou
POST /parceiro/retorno-truncado                         trailer que não bate com o conteúdo
POST /parceiro/silencio                                 não escreve nada — o caso do SEM_RETORNO
POST /falha/crash-relay                                 arma a morte entre o send ao SQS e o UPDATE
POST /falha/crash-envio                                 arma a morte entre o put no SFTP e o COMMIT
```

**O simulador não é o sistema — é o ambiente.** Ele vive num pacote `simulador/`,
fora de `domain` e de `infra`, não é chamado por nenhum use case, e monta o
retorno a partir da remessa que leu do SFTP, como o banco parceiro faria.
Consultar o Postgres para produzir o retorno seria mais simples e destruiria o
valor da demonstração: o retorno viraria função do nosso estado, e o dia em que
a remessa saísse errada o parceiro responderia certo assim mesmo. Há um teste
que falha se uma linha de `domain/` ou de `infra/` passar a conhecê-lo
(`FundacaoTest.simuladorEhOAmbienteNaoOSistema`).

As duas falhas do **nosso** lado são decoradores das portas, montados na fiação
do servidor — não há `if` de simulação dentro de use case nenhum. Armar e
disparar são chamadas separadas, e é isso que torna a janela visível: entre uma
e outra existe o estado que o desenho produz.

```bash
curl -sX POST 'localhost:8080/falha/crash-envio'
{"falha":"CRASH_ENVIO","dispara":"POST /ciclo/enviar","mensagem":"o processo morreu entre o put e o COMMIT","armadas":["CRASH_ENVIO"]}

curl -sX POST 'localhost:8080/ciclo/enviar?ciclo=C-1'      # 409 — e o arquivo já está no parceiro
curl -sX POST 'localhost:8080/ciclo/enviar?ciclo=C-1'      # converge: UM arquivo, tentativas ENVIADO_PARCEIRO
```

Cada botão existe para mostrar um mecanismo, e só ele:

| botão | o que fica visível |
|---|---|
| `processar` | o caminho feliz, e que só `PAGO` gera linha no outbox |
| `particionar` | vários arquivos por ciclo convergindo para o mesmo estado |
| `atrasar` | o retorno que chega pela metade; o resto continua `ENVIADO_PARCEIRO` |
| `reenviar-retorno` | os mesmos bytes reconhecidos pelo `sha256`, sem reprocessamento |
| `retorno-truncado` | trailer que não fecha, arquivo descartado inteiro |
| `silencio` | `SEM_RETORNO` no fechamento — silêncio não é recusa |
| `crash-relay` | at-least-once: a duplicata que o consumidor deduplica |
| `crash-envio` | a janela entre efeito externo e commit, sem transação possível |

A **quiescência** — o arquivo que cresce entre as duas leituras de atributos e
por isso não é baixado — não tem botão, e não por esquecimento: provocá-la
exigiria um parceiro escrevendo por temporizador, e a demonstração passaria a
depender de qual das duas coisas chegou primeiro. Ela continua provada por
`ArquivoEmEscritaNaoEhBaixadoTest`, onde o crescimento acontece exatamente entre
as duas leituras.

### Abrir o painel

```bash
docker compose -f infra/docker-compose.yml up -d
mvn spring-boot:run
# abra localhost:8080 no navegador
```

`GET /` é o painel: **um arquivo** (`src/main/resources/static/index.html`),
HTML, CSS e JavaScript inline, sem build, sem framework e sem CDN. Ele tem três
colunas:

- **operar o ciclo** — um botão por passo, na ordem do fluxo, mais o recorte
  (ciclo, banco, data, quantidade de faturas, distribuição de desfechos);
- **estado** — o `GET /estado` relido de dois em dois segundos: tentativas por
  status, ciclos, linhas do outbox com a `chaveDedup`, os arquivos no diretório
  do parceiro, os objetos no bucket e as chaves que estão na fila;
- **log de eventos** — append-only, uma linha por resposta de endpoint, com
  horário, código e o corpo exato que voltou.

O log **não** é derivado do estado, e essa é a decisão que faz o painel valer:
o polling mostra o **agora**, o log mostra a **sequência**. A janela do relay só
aparece na sequência — no agora ela já passou.

Um roteiro de dois minutos, e o que olhar em cada passo:

```
criar faturas → montar ciclo → gerar remessa → enviar ao parceiro
   ↳ o arquivo aparece no bloco SFTP, e o mesmo objeto aparece no bloco S3

o parceiro processa → coletar retorno
   ↳ tentativas viram PAGO / NAO_PAGO / ERRO, e o outbox ganha UMA linha:
     só PAGO gera lançamento

armar crash do relay → publicar outbox   (409 no log, linha ainda PENDENTE)
publicar outbox                          (agora sim, PUBLICADO)
   ↳ o bloco fila mostra a MESMA chaveDedup duas vezes: at-least-once
     acontecendo na tela
```

A tela **não deduz nada**: ela imprime o que os endpoints devolveram. Um painel
que calculasse "provavelmente aconteceu X" mentiria na primeira vez que o
backend mudasse — e o que este projeto tem para mostrar é justamente o que
acontece de fato entre uma chamada e outra.

### Inspecionar a fila enquanto roda

```bash
alias awslocal='docker compose -f infra/docker-compose.yml exec localstack awslocal'

# a fila existe?
awslocal sqs list-queues

# quantas mensagens estão lá?
awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/lancamentos-contabeis \
  --attribute-names ApproximateNumberOfMessages

# ler sem consumir de vez (visibility 0 devolve a mensagem à fila)
awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/lancamentos-contabeis \
  --max-number-of-messages 10 --visibility-timeout 0 \
  --message-attribute-names All
```

E o outbox, do outro lado:

```bash
docker compose -f infra/docker-compose.yml exec postgres \
  psql -U cobranca -d cobranca -c "SELECT id, fatura_id, status FROM outbox ORDER BY id"
```

## As decisões, com o preço

| Decisão | Alternativa descartada | O que se paga |
|---|---|---|
| Outbox na mesma transação, publicação fora ([ADR-0001](docs/adr/0001-outbox-transacional-em-vez-de-dual-write.md)) | publicar e depois commitar | latência do relay, uma tabela a mais, escrita dobrada na transação de negócio |
| At-least-once + chave de dedup ([ADR-0002](docs/adr/0002-at-least-once-mais-dedup-em-vez-de-fifo.md)) | SQS FIFO com `MessageDeduplicationId` | duplicata possível na fila; o consumidor precisa cooperar |
| `UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'` | tabela de deduplicação de retornos | a idempotência fica implícita no número de linhas afetadas, e não num registro explícito |
| `UNIQUE (banco, data_ref)` no ciclo | consultar antes se o ciclo já existe | a segunda montagem falha com erro de constraint em vez de ser ignorada em silêncio — mas verificação prévia é uma corrida, e constraint é um fato |
| Ausência de retorno vira `SEM_RETORNO` | colapsar em `NAO_PAGO` | mais um estado para tratar — em troca de não notificar o cliente sobre uma falha de débito que ninguém afirmou que ocorreu |
| Só `PAGO` gera lançamento, e a regra mora no enum | um `if` dentro do use case | um estado novo quebra a compilação do lugar certo, em vez de passar despercebido |
| `UNIQUE (fatura_id)` no outbox | só a guarda no código | o segundo lançamento estoura em vez de ser ignorado silenciosamente |
| JDBC puro no domínio, sem `@Transactional` | Spring Data / `@Transactional` | mais código de encanamento — em troca, a fronteira transacional fica visível no código, que é justamente o que o projeto quer mostrar |
| Artefato durável no S3 entre geração e transmissão ([ADR-0003](docs/adr/0003-artefato-duravel-no-s3-em-vez-de-geracao-em-memoria.md)) | regerar a remessa a cada tentativa de envio | um serviço a mais no teto de infra, latência de um `get` antes do `put`, e expurgo que ninguém faz |
| Simulador em pacote próprio, enxergando só arquivos | montar o retorno consultando o Postgres | um parser de remessa e uma conexão SSH a mais, escritos do lado do parceiro — em troca de o retorno não ser função do nosso próprio estado |
| Falhas provocadas como decoradores de porta | um `if (simularCrash)` dentro do use case | dois arquivos e três linhas de fiação — em troca de o código de produção continuar sendo o que se lê |
| Nome determinístico no SFTP | sufixo por tentativa (`-1`, `-2`) | reenvio sobrescreve em vez de acumular — mas o parceiro que já leu processa duas vezes, e quem absorve é o `UPDATE` condicional |
| Trailer decide completude; arquivo que não fecha é descartado inteiro | aplicar as linhas que deram para ler | uma passada perdida — em troca de não produzir um retorno parcial indistinguível de um legítimo |
| Cada passo é um endpoint, não um cron | `@Scheduled` no próprio serviço | é preciso chamar para acontecer — que é exatamente o ponto: dá para executar o passo quando se quer olhar para ele |

**A fronteira de responsabilidade**, dita em voz alta:

| Garantia | De quem |
|---|---|
| No máximo um lançamento **decidido** por fatura | deste projeto |
| Todo lançamento decidido é **eventualmente** publicado | deste projeto |
| No máximo um lançamento **efetivado** no mainframe | do consumidor, via `chaveDedup` |

Exatamente-uma-vez ponta a ponta não é entregável por este lado: o produtor não
sabe se um `send` que não retornou chegou, e só pode escolher entre reenviar
(duplicata) e não reenviar (perda). Nenhuma configuração de fila cria uma
terceira opção.

## O que foi deliberadamente simplificado

Isto é uma prova de conceito, não um serviço de produção. Ficou de fora, de
propósito:

- **Sem particionamento do outbox** — um único `SELECT ... WHERE status =
  'PENDENTE'`. Em produção, com dois relays, seria `FOR UPDATE SKIP LOCKED` e
  partição por hash da fatura.
- **Sem backoff nem retry** — o relay tenta uma vez por passada. Uma falha de
  rede vira "tenta de novo na próxima", sem espera exponencial.
- **Sem DLQ** — uma mensagem que falha sempre falha para sempre e trava a fila do
  relay. Em produção, N tentativas e desvio para uma fila morta.
- **Um único publicador** — não há eleição de líder nem lock. Dois processos
  rodando o relay publicariam em duplicidade (o que, note, o desenho tolera:
  mesma chave de dedup).
- **Dedup delegada ao consumidor** — este projeto não simula o mainframe. Ele
  garante a chave estável; quem desduplica é o outro lado.
- **Sem expurgo do outbox** — nada apaga linhas `PUBLICADO`. Em produção, é a
  tabela que mais cresce.
- **Sem expurgo do S3** — nada apaga remessas nem retornos arquivados. A chave
  determinística impede que reexecuções multipliquem objetos, mas retenção é
  outro problema: em produção, uma lifecycle policy com prazo ditado pela guarda
  contábil — ver [ADR-0003](docs/adr/0003-artefato-duravel-no-s3-em-vez-de-geracao-em-memoria.md).
- **Sem pool de conexões, sem métricas, sem tracing.**

Do lado do desenho de sistema, **canal e formato deixaram de estar fora**: SFTP
real, artefato durável no S3 e validação de completude por trailer são os steps
07–09. A ampliação é deliberada — a premissa passou a ser que compreender o
mecanismo vale mais que manter o projeto pequeno. O que continua fora, porque
não muda nenhuma das decisões que o projeto defende:

- **CNAB 240 de verdade.** O formato aqui é posicional de largura fixa, com
  header, detalhe e trailer — o suficiente para que a **contagem no trailer**
  seja o mecanismo de completude e para que o `id_tentativa` em posição fixa seja
  a correlation key. O layout do FEBRABAN acrescenta centenas de campos e zero
  decisão de desenho.
- **Canal síncrono com throttle** (o banco que expõe API REST em vez de arquivo)
  e o **rate limiter distribuído** que ele exigiria. Trocaria o ciclo em lote por
  uma tentativa por requisição — muda o transporte e a política de vazão, não a
  fronteira transacional.
- **Conciliação D+1 contra o extrato agregado de liquidação.** É o controle que
  pega o que este desenho deixa passar (o lançamento duplicado que o consumidor
  não deduplicou). Pertence à camada de controle contábil, não ao produtor.
- **Ciclo de vida da autorização de débito e sua revogação** pelos dois caminhos
  — o cliente revogando no app e o parceiro informando `AUTORIZACAO_REVOGADA` no
  retorno. Aqui a revogação aparece só como motivo de `NAO_PAGO`; o agregado de
  autorização e a corrida entre os dois caminhos são um projeto à parte.
- **Política de retentativa** e a classificação **transitório × permanente** dos
  códigos de retorno. `SALDO_INSUFICIENTE` pede reapresentação;
  `CONTA_ENCERRADA` não pede nenhuma. Este projeto registra o motivo e para por
  aí — decidir o que fazer com ele é regra de negócio, e regra de negócio é o
  que se corta primeiro.

## Estrutura

```
docs/brief.md            o contexto e o problema
docs/adr/                as três decisões que sustentam o projeto
docs/steps/              o que cada step entrega e sua Definition of Done
infra/                   docker-compose + scripts de init (schema, fila, bucket)
src/main/java/...
  domain/model           Fatura, CicloCobranca, TentativaDebito, Remessa,
                         LancamentoContabil, RegistroOutbox, ChaveArtefato
  domain/port            RepositorioFatura, RepositorioCiclo, RepositorioTentativa,
                         RepositorioOutbox, PublicadorLancamento,
                         ArmazenamentoArtefato, CanalArquivos
  domain/usecase         AbrirFaturas, MontarCiclo, GerarRemessa,
                         EnviarRemessa, ColetarRetorno, AplicarRetorno,
                         FecharCiclo, PublicarOutbox
  api                    LinhaRetorno, ArquivoRetorno — adaptadores de entrada
  api/http               um controller por passo; nenhuma regra de negócio
  api/http/dto           os records de resposta: o efeito, não `200`
  infra/persistence      JDBC puro, uma implementação por porta
  infra/canal            CanalArquivosSftp — SSH de verdade
  infra/config           Ambiente — o único lugar que lê configuração
                         Fiacao — os mesmos objetos do Main, para o Spring
  infra/consulta         EstadoDoMundo — o retrato das cinco fontes
  infra/falha            decoradores das portas: as duas falhas provocáveis
  simulador/             o banco parceiro: lê a remessa, escreve o retorno
                         — o AMBIENTE, não o sistema
  Main                   o cenário ponta a ponta, com cada transição impressa
  AplicacaoHttp          o servidor
src/main/resources/static/index.html   o painel: um arquivo, sem build
```

Os doze steps estão escritos: o painel é o step-12, o `simulador/` e o
`infra/falha` são o step-11, `api/http` mais o `AplicacaoHttp` são o step-10, a
coleta do retorno é o step-09, e o armazenamento no S3 e a transmissão por SFTP
são os steps 07 e 08. Ver [PLAN.md](PLAN.md) para o que cada um entregou.

Máquina de estados:

```
CicloCobranca    MONTADO → ENVIADO → FECHADO
TentativaDebito  ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO
                                                        → SEM_RETORNO (fechamento)
Fatura           ABERTA → PAGA → LANCADA
```

`api → domain ← infra`. O domínio não importa framework nem AWS SDK — e há um
teste que falha se isso mudar (`FundacaoTest.dominioIsolado`).

## Estado

Ver [PLAN.md](PLAN.md). Um step por sessão.
