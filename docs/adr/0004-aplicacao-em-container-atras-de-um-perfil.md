# ADR-0004 — A aplicação em container, atrás de um perfil do Compose

- **Status:** aceito
- **Data:** 2026-09-01
- **Contexto:** depois do step-12 (o painel) — manutenção, não step novo

## Contexto

Até aqui, "subir o projeto" eram dois comandos em dois lugares: o Compose com os
três containers do ambiente, e o `mvn spring-boot:run` num terminal separado. O
ambiente e a aplicação viviam em mundos diferentes, e a consequência prática era
a leitura da execução: os logs do Postgres, do LocalStack e do parceiro saíam por
`docker compose logs`, e os da aplicação — os únicos que contam o ciclo — saíam
no terminal do Maven.

Isso importa mais neste projeto do que importaria em outro. O step-12 existe para
que o mecanismo possa ser **visto acontecendo** em vez de deduzido de uma suíte
verde; um painel que mostra o *agora* e um log que mostra a *sequência* são as
duas metades disso, e a segunda estava fora do lugar onde se olha.

## Decisão

**A aplicação é um serviço do Compose como os outros três, atrás do perfil
`app`.**

```bash
docker compose -f infra/docker-compose.yml up -d                      # só o ambiente
docker compose -f infra/docker-compose.yml --profile app up -d --build  # tudo
docker compose -f infra/docker-compose.yml logs -f app                # a sequência
```

O perfil não é um detalhe de configuração — é a decisão. Sem ele, `up -d`
passaria a subir a aplicação também, e as duas formas de rodar disputariam a
porta 8080. Com ele, o comando que já estava documentado continua fazendo
exatamente o que fazia, e quem está mexendo no código continua com o ciclo de
edição do Maven, que nenhum container melhora.

A imagem é construída em duas etapas (`maven:3.9-eclipse-temurin-21` →
`eclipse-temurin:21-jre`), e nada no código mudou por causa dela: `Ambiente` já
era o único lugar que lia configuração, e já lia `DB_URL`, `SQS_ENDPOINT`,
`S3_ENDPOINT`, `SFTP_HOST` e `SFTP_PORTA` com os padrões do Compose. A única
diferença de dentro da rede é que o vizinho atende pelo nome do serviço em vez de
por `localhost` — cinco variáveis no `docker-compose.yml`, zero linhas de Java.

**Que isso tenha custado zero código é o resultado, não a sorte.** Um `Ambiente`
que lesse `System.getenv` espalhado pelos adaptadores teria custado uma varredura;
a porta única de configuração é o que fez o container ser um arquivo de infra.

## `spring-boot:repackage` no Dockerfile, não no `pom.xml`

O `pom.xml` declara o plugin do Spring Boot **sem** execução de `repackage`, com
um comentário dizendo que o artefato do projeto continua sendo um jar comum. A
primeira imagem subiu com `no main manifest attribute` justamente por isso.

Havia duas saídas, e a escolha diz o que se está protegendo:

- ligar `repackage` ao `package` no pom — muda o que `mvn package` produz para
  todo mundo, por uma necessidade que é só da imagem;
- chamar `spring-boot:repackage` no Dockerfile — quem precisa do jar executável
  pede por ele, no lugar em que precisa.

A segunda. É o mesmo raciocínio do resto do projeto: a necessidade de um
consumidor não vira propriedade global do sistema.

**Os testes são pulados no build da imagem**, e não por pressa: a suíte sobe os
próprios containers via Testcontainers, e não há daemon Docker dentro do
`docker build`. `mvn test` continua sendo o lugar dela — a regra 7 do
`CLAUDE.md` não mudou.

## Alternativas descartadas

**A. A aplicação no Compose sem perfil, sempre subindo.** Um comando a menos para
lembrar. Descartada pela porta 8080: `up -d` seguido de `mvn spring-boot:run` —
a sequência documentada no README desde o step-10 — passaria a falhar com um erro
de bind que não explica nada a quem o encontra.

**B. Um `docker-compose.app.yml` separado, aplicado com dois `-f`.** Também
resolve a disputa de porta. Descartada porque a linha de comando fica mais longa
que a do perfil e o ambiente passa a ter duas descrições em vez de uma — e a
razão de o `docker-compose.yml` deste projeto ser um arquivo só é que ele é
legível de cima a baixo numa tela.

**C. Montar o `target/` da máquina dentro de um container com JRE.** Build
instantâneo. Descartada porque a imagem passaria a depender de um `mvn package`
que alguém rodou antes, com a versão de Java que estivesse na máquina —
exatamente o acoplamento que subir em container existe para remover.

**D. Deixar como estava, dois terminais.** Descartada pelo motivo do topo: a
sequência é metade da demonstração, e ela estava no terminal errado.

## Consequências

- **Duas formas de rodar, e as duas precisam continuar funcionando.** É custo
  real de manutenção: uma variável nova de ambiente agora precisa entrar no
  serviço `app` além de ter padrão no `Ambiente`. O padrão continua sendo o do
  `localhost`, então esquecer disso quebra o container e não o `spring-boot:run`
  — a falha aparece de um lado só, que é o modo de falha mais fácil de não notar.
- **O primeiro build é lento.** Duas etapas, dependências baixadas dentro da
  imagem. O `pom.xml` é copiado antes do `src` e há cache mount em `/root/.m2`,
  então builds seguintes de código não voltam à rede.
- **O teto de infra não subiu.** Postgres + LocalStack + SFTP continuam sendo o
  ambiente; o quarto container é a aplicação, que já existia como processo. A
  regra 2 do `CLAUDE.md` fala de infra de terceiros, e ela não foi tocada.
- **A imagem não é de produção.** Sem usuário não-root, sem healthcheck, sem
  limite de heap, sem tag versionada — `ciclo-de-cobranca:local` é o nome
  inteiro. Fica registrado como limite conhecido, ao lado do expurgo do
  [ADR-0003](0003-artefato-duravel-no-s3-em-vez-de-geracao-em-memoria.md): o
  objetivo aqui é ver o mecanismo acontecendo, não empacotar um serviço.
