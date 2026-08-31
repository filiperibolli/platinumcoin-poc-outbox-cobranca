# ADR-0003 — Artefato durável no S3 em vez de geração em memória

- **Status:** aceito
- **Data:** 2026-08-31
- **Contexto:** step-07 (a remessa) / step-08 (o envio) / step-09 (a coleta)

## Contexto

Até o step-06, `GerarRemessaUseCase` devolvia uma String e quem chamava a
transmitia. Gerar e enviar eram o mesmo instante: não havia como enviar sem
gerar, nem como olhar depois para o que foi enviado. Enquanto o transporte era
um `UPDATE` solto com um comentário dizendo "fora de escopo", isso não custava
nada.

Com SFTP real (step-08) passa a custar. A transmissão falha por motivos que não
têm relação com a geração — conexão recusada, disco cheio do outro lado, timeout
no meio do `put` — e cada uma dessas falhas obrigaria a **regerar** para tentar
de novo. Regerar é seguro aqui, porque a remessa é função pura do ciclo; mas
"seguro porque a função é pura" é uma propriedade do código de hoje, e o que foi
transmitido ao parceiro é um fato permanente. No dia em que a geração mudar — um
campo a mais, um espaçamento diferente, uma correção de arredondamento — o que
está no parceiro e o que o sistema regenera divergem, e nada acusa.

A mesma pergunta aparece do outro lado, na coleta: o arquivo de retorno é
baixado, parseado e aplicado. Se ele só existir em memória durante a passada, o
arquivo que **não** passou na validação de trailer — exatamente o que alguém vai
querer inspecionar — desaparece.

## Decisão

**Geração e transmissão são dois passos independentes, com um artefato durável
entre eles.** A remessa é gravada no S3 com chave determinística derivada do
ciclo (`remessa/{banco}/{dataRef}/{cicloId}.rem`); `EnviarRemessaUseCase` **lê
de lá** e transmite, sem regenerar. Todo retorno baixado é arquivado no S3
(`retorno/{banco}/{dataRef}/{nome}`) **antes** de ser validado.

A porta `ArmazenamentoArtefato` (`put`, `get`, `existe`) mantém o domínio sem
conhecimento de S3, como `PublicadorLancamento` faz com o SQS.

```
GerarRemessa   ── projeta ─ put(S3) ─ COMMIT (chave + sha256) ──▶ artefato
EnviarRemessa  ── get(S3) ─ put(SFTP) ─ COMMIT (transições) ────▶ parceiro
ColetarRetorno ── baixa(SFTP) ─ put(S3) ─ valida trailer ───────▶ aplicação
```

## Por que a chave é determinística

Uma chave com UUID ou timestamp transformaria cada reexecução num objeto novo, e
o bucket viraria um log de tentativas em vez do artefato do ciclo. Com a chave
derivada, o segundo `put` sobrescreve com bytes idênticos — que é a definição
operacional de "reexecutar é seguro", e é o que faz o crash entre o `put` e o
`COMMIT` (`RemessaSobreviveAReexecucaoTest`) não custar nada.

É a mesma propriedade que o nome determinístico no SFTP dá do outro lado, e a
comparação entre os três efeitos externos do projeto é o que ela ensina:

| efeito externo | reexecutar produz | quem paga |
|---|---|---|
| objeto no S3 | sobrescrita idêntica | ninguém |
| arquivo no parceiro | sobrescrita idêntica | ninguém |
| mensagem no SQS | **duplicata** | o consumidor, via `chaveDedup` ([ADR-0002](0002-at-least-once-mais-dedup-em-vez-de-fifo.md)) |

A fila é a única em que o efeito não é endereçável — não há "chave" no SQS que
uma segunda mensagem sobrescreva. É por isso que ela é o único ponto em que o
projeto assume at-least-once, e não porque a fila seja menos confiável que o S3.

## Alternativas descartadas

**A. Regerar a remessa a cada tentativa de envio.** Mais simples e sem serviço
novo: a remessa é função pura, logo regenerar dá o mesmo resultado. Descartada
porque a pureza é uma propriedade do código, não do passado — e o artefato
transmitido é histórico. Além disso, apaga a fronteira entre decidir o conteúdo
e entregá-lo, que é justamente a fronteira que o step-08 existe para exercitar.

**B. Gravar o artefato numa coluna `TEXT` do Postgres.** Não adiciona serviço,
mantém tudo transacional e até simplificaria o `COMMIT` do step-07 — o `put` e o
registro da chave virariam uma escrita só, e a janela sumiria. Descartada por
dois motivos. O primeiro é escala: arquivo de remessa cresce com o volume do
dia, e a tabela que mais cresce já é o outbox. O segundo é honestidade do
exercício: o desenho que se quer mostrar tem um armazenamento de objetos fora do
banco, e resolvê-lo com uma coluna esconderia exatamente a janela entre efeito
externo e commit que o projeto passa o tempo todo apontando.

**C. Escrever no filesystem local.** Zero infra nova. Descartada porque um
`Path` local não sobrevive ao container, não é compartilhável entre réplicas e
não obriga a porta `ArmazenamentoArtefato` a existir — e a porta é metade do
valor do step.

**D. Não arquivar o retorno, só parsear.** Descartada pelo caso que mais importa:
o arquivo com trailer divergente é descartado sem aplicar nada, e é o único
artefato do sistema sobre o qual alguém vai fazer uma pergunta depois.

## Consequências

- **Um serviço a mais no teto de infra.** O projeto passa de "Postgres + 1
  serviço AWS" para "Postgres + 2". É uma ampliação deliberada de escopo: a
  premissa passou a ser que compreender o mecanismo vale mais que manter o
  projeto pequeno.
- **Latência.** O envio ganha um `get` de rede antes do `put`. Irrelevante no
  volume deste projeto, mensurável em produção — e é o preço direto de não
  regerar.
- **Expurgo.** Nada apaga objetos. Remessa e retorno acumulam por ciclo,
  indefinidamente, e a chave determinística garante que reexecuções não
  multipliquem objetos — mas não resolve retenção. Em produção seria uma
  lifecycle policy no bucket, com prazo ditado pela exigência de guarda contábil,
  e não pelo custo de armazenamento. Fica registrado como o limite conhecido,
  ao lado do expurgo do outbox.
- **Mais uma janela entre efeito externo e commit.** A do step-07 (`put` antes
  do `COMMIT` da chave) é inofensiva por determinismo, e o teste que a exercita
  existe para mostrar **por que** ela é inofensiva — em contraste com a do
  step-08, que não é.
