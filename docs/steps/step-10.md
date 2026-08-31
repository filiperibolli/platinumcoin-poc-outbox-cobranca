# step-10 — API de operação

## Objetivo

Dar **controle manual sobre cada passo do ciclo**, para que o mecanismo possa ser
visto acontecendo em vez de deduzido de uma suíte verde. Cada endpoint é uma
execução do job que, em produção, o EventBridge dispararia por horário.

Este é o step em que o Spring Boot entra no projeto — e o motivo é único e
declarado: o projeto passa a expor HTTP. Nenhuma outra parte do desenho muda por
causa dele.

## Entregáveis

- Spring Boot (Web) no `pom.xml`; `AplicacaoHttp` com o `main` do servidor.
- `api/http/` — um controller por passo. Nenhuma regra de negócio dentro.
- `api/http/dto/` — os records de resposta: o **efeito produzido**, não `200`.
- `infra/config/Ambiente` continua sendo o único lugar que lê configuração; a
  fiação Spring monta os use cases a partir dele.
- `Main` (console, step-06) **continua existindo** e continua rodando por
  `mvn compile exec:java`.
- Testes: `EndpointsDevolvemEfeitoTest`, `ControllerNaoDecideTest`.

## Os endpoints

| Método e rota | Dispara | Devolve |
|---|---|---|
| `POST /faturas` | — | as N faturas criadas com tentativas `ABERTO` |
| `POST /ciclo/montar` | `MontarCiclo` | cicloId, tentativas `ABERTO → SOLICITADO` |
| `POST /ciclo/gerar-remessa` | `GerarRemessa` | chave no S3, sha256, contagem do trailer |
| `POST /ciclo/enviar` | `EnviarRemessa` | nome no parceiro, transições `SOLICITADO → ENVIADO_PARCEIRO` |
| `POST /ciclo/coletar` | `ColetarRetorno` | arquivos vistos, baixados, descartados e por quê; linhas aplicadas |
| `POST /ciclo/fechar` | `FecharCiclo` | tentativas `ENVIADO_PARCEIRO → SEM_RETORNO` |
| `POST /outbox/publicar` | `PublicarOutbox` | linhas `PENDENTE → PUBLICADO`, chaves de dedup enviadas |
| `GET /estado` | — | o snapshot inteiro |

O snapshot de `/estado`: ciclos e seus status, tentativas agrupadas por status,
linhas do outbox por status, arquivos presentes no SFTP (`/remessa` e
`/retorno`), objetos no S3 e mensagens na fila.

## Decisões deste step

- **Todos `POST`, inclusive os que parecem leitura.** Cada chamada **executa um
  job** e tem efeito. Um `GET` que muda estado é uma armadilha para qualquer
  coisa que pré-busque links. `/estado` é o único `GET`, e é o único sem efeito.
- **A resposta é o efeito, não `200`.** "Montou" não é informação; "montou C-1 e
  moveu 5 tentativas" é. É o que permite ao painel do step-12 ter um log de
  eventos sem inventar texto, e é o que torna o `curl` sozinho suficiente para
  entender o que aconteceu.
- **Os endpoints substituem o cron.** Não há `@Scheduled` neste projeto. Em
  produção quem chama é o EventBridge, nos horários do diagrama do README; aqui
  quem chama é um botão. A vantagem de ter tirado o horário do código é
  exatamente esta: dá para executar o passo quando se quer olhar para ele.
- **Zero regra no controller.** Ele desserializa, chama **um** use case e
  serializa o efeito. Um `if` de negócio dentro de um controller é o começo da
  erosão da fronteira que o projeto inteiro existe para mostrar —
  `ControllerNaoDecideTest` falha se aparecer um.
- **Spring não entra em `domain/`.** `FundacaoTest.dominioIsolado` passa a
  proibir também `org.springframework` no domínio, com a mesma força com que já
  proíbe o AWS SDK. O framework fica em `api/http` e na fiação.
- **Um único Spring Boot para tudo.** Um serviço, todos os endpoints — incluindo
  os do simulador do step-11, que ficam num pacote separado mas no mesmo
  processo. Dois processos exigiriam orquestração para uma demonstração que cabe
  numa tela.

## Testes obrigatórios

**`EndpointsDevolvemEfeitoTest`** — o ciclo inteiro por HTTP, na ordem, contra os
containers: cria faturas, monta, gera, envia, coleta, fecha, publica. Assere que
cada resposta traz as contagens e transições reais (conferidas contra o banco), e
não um corpo vazio.

**`ControllerNaoDecideTest`** — teste de fronteira: nenhuma classe de `api/http`
importa `java.sql`, e o domínio não importa `org.springframework`.

## Definition of Done

- [ ] Os sete `POST` e o `GET /estado` respondem, e cada resposta descreve o
      efeito produzido.
- [ ] `EndpointsDevolvemEfeitoTest` roda o ciclo ponta a ponta por HTTP.
- [ ] `/estado` mostra as cinco fontes: banco, outbox, SFTP, S3 e fila.
- [ ] `FundacaoTest.dominioIsolado` proíbe `org.springframework` em `domain/`.
- [ ] O `Main` de console continua funcionando sem alteração de comportamento.
- [ ] README explica que cada endpoint é uma execução do job e que, em produção,
      quem chama é o EventBridge.
- [ ] CHANGELOG + commit `feat(outbox): api de operação do ciclo (step 10)`.
