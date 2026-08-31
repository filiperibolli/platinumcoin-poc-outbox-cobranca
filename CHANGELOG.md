# CHANGELOG

Formato baseado em [Keep a Changelog](https://keepachangelog.com/pt-BR/1.1.0/).
Um step por entrada.

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
  `docker exec outbox-sftp ls -l /home/parceiro/remessa` mostra o arquivo com os
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
