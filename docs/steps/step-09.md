# step-09 — Coleta de retorno

## Objetivo

Provar que **"sem callback" tem consequência de desenho**. O parceiro não avisa
que o retorno chegou, não avisa que terminou de escrever, e não garante um
arquivo por ciclo. Cada uma dessas três ausências vira um mecanismo concreto
aqui: varredura periódica, quiescência e validação de trailer.

O que este step **não** muda: quem decide o estado da tentativa continua sendo
`AplicarRetornoUseCase`, com o mesmo `UPDATE` condicional do step-03. A coleta
só entrega linhas a ele.

## Entregáveis

- `domain/port/CanalArquivos` — ganha `listar(diretorio)`,
  `atributos(nome)` (tamanho e mtime) e `baixar(nome)`.
- `domain/usecase/ColetarRetornoUseCase` — lista, aplica quiescência, baixa,
  arquiva no S3, valida o trailer, parseia e delega ao aplicador.
- `api/ArquivoRetorno` — o parser do layout posicional: valida o trailer contra
  a contagem de linhas e projeta `LinhaRetorno`. Fica em `api/` pelo mesmo
  motivo que `LinhaRetorno`: é adaptador de entrada, `api → domain`.
- Schema: tabela `arquivo_retorno` (`nome`, `sha256`, `ciclo_id`, `linhas`,
  `baixado_em`), com `UNIQUE (sha256)`.
- Testes: `ArquivoIncompletoNaoEhProcessadoTest`,
  `ArquivoEmEscritaNaoEhBaixadoTest`, `RetornoParticionadoTest`.

## Os três mecanismos da coleta

**Varredura periódica.** Não há callback: o use case lista `/retorno` e decide.
Cada execução é uma passada; o que não passa numa passada é reavaliado na
seguinte. Nada é marcado como "em processamento" — não há estado intermediário
para vazar.

**Quiescência.** Um arquivo que o parceiro ainda está escrevendo aparece na
listagem. Baixá-lo dá um arquivo válido do ponto de vista do SFTP e truncado do
ponto de vista do negócio. A regra: **tamanho e mtime iguais em duas leituras
separadas por um intervalo configurável** (curto nos testes, minutos em
produção). Quem cresce entre as leituras não é baixado.

**Trailer.** Quiescência não prova completude — o parceiro pode ter morrido no
meio da escrita e o arquivo parado ali para sempre. O trailer diz quantos
registros de detalhe deveriam existir; se a contagem não bater, o arquivo é
**descartado sem aplicar nada** e reavaliado na próxima passada. Um arquivo
parcialmente aplicado seria pior que nenhum: a metade aplicada é indistinguível
de um retorno legítimo.

```
lista  →  quiescência (2 leituras)  →  baixa  →  arquiva no S3  →  trailer
                                                                     ├─ fecha  → aplica linha a linha
                                                                     └─ não fecha → descarta, próxima passada
```

O arquivamento no S3 (`retorno/{banco}/{dataRef}/{nome}`) acontece **antes** da
validação: o arquivo que não fechou é justamente o que alguém vai querer olhar.

## O hash, e o que ele não é

O `sha256` do conteúdo é gravado em `arquivo_retorno` com `UNIQUE`. Reenvio
byte-idêntico é reconhecido e curto-circuitado sem reprocessar as linhas.

**Isto é um atalho de custo, não a garantia de idempotência.** A garantia é, e
continua sendo, o `UPDATE ... WHERE status = 'ENVIADO_PARCEIRO'` do step-03. O
hash só cobre o caso "exatamente os mesmos bytes"; um reenvio com uma linha a
mais, ou com as linhas em outra ordem, tem hash diferente e passa direto — e
está certo que passe, porque é o `UPDATE` condicional que sabe o que já foi
aplicado. Um projeto que confundisse os dois trocaria uma garantia por uma
otimização e não perceberia até o dia em que o parceiro mudasse o espaçamento.

## Decisões deste step

- **Descartar o arquivo incompleto inteiro, em vez de aplicar o que dá.** Aplicar
  parcialmente é indistinguível de um retorno legítimo menor — e o fechamento do
  ciclo transformaria o resto em `SEM_RETORNO`, afirmando silêncio onde havia
  ruído.
- **Quiescência por tamanho **e** mtime.** Só o tamanho não pega o arquivo
  reescrito no lugar com o mesmo tamanho; só o mtime não pega o servidor cujo
  relógio anda de segundo em segundo.
- **Intervalo configurável, não constante.** Milissegundos no teste, minutos em
  produção. Uma constante forçaria o teste a esperar de verdade, e um teste que
  dorme minutos é um teste que ninguém roda.
- **Vários arquivos por ciclo é o caso normal, não a exceção.** O parceiro
  particiona quando quer. Cada arquivo é aplicado incrementalmente; o estado
  final é o mesmo de um arquivo único, e é isso que `RetornoParticionadoTest`
  assere.
- **A coleta não fecha o ciclo.** Ela não sabe se o parceiro terminou — ninguém
  sabe. Quem declara o dia encerrado é `FecharCicloUseCase`, por horário.

## Testes obrigatórios

**`ArquivoIncompletoNaoEhProcessadoTest`** — trailer diz 10, arquivo tem 7
detalhes. Assere: nenhuma tentativa mudou de estado, nenhuma linha no outbox, o
arquivo **continua** em `/retorno` para a próxima passada, e o arquivo está
arquivado no S3.

**`ArquivoEmEscritaNaoEhBaixadoTest`** — arquivo que cresce entre as duas
leituras não passa na quiescência e não é baixado. Depois de parar de crescer,
a passada seguinte o processa normalmente.

**`RetornoParticionadoTest`** — o mesmo ciclo em dois arquivos parciais, cada um
com trailer coerente, aplicados em passadas diferentes. Assere que o estado
final (tentativas, faturas, outbox) é **idêntico** ao produzido pelo arquivo
único equivalente.

## Definition of Done

- [ ] Os três testes passam, contra o container SFTP.
- [ ] Arquivo com trailer divergente não altera **nenhuma** linha do banco e
      permanece no diretório remoto.
- [ ] Arquivo em escrita não é baixado, e é baixado na passada seguinte.
- [ ] Dois arquivos parciais convergem para o mesmo estado de um arquivo único —
      asserido comparando o estado inteiro, não uma contagem.
- [ ] O reenvio byte-idêntico é curto-circuitado pelo hash, e há teste que prova
      que o reenvio **não** byte-idêntico continua sendo tratado pelo `UPDATE`
      condicional.
- [ ] `RetornoDuplicadoTest` e `MultiplasTentativasTest` continuam verdes sem
      alteração — a coleta não mexeu na decisão.
- [ ] CHANGELOG + commit `feat(outbox): coleta de retorno com quiescência e trailer (step 09)`.
