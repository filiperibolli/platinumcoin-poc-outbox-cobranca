# step-07 — Remessa no S3

## Objetivo

Separar **geração** de **transmissão**, com um artefato durável entre as duas.
Hoje `GerarRemessaUseCase` devolve uma String e ela morre na memória de quem
chamou: gerar e enviar são o mesmo instante, e não há nada para inspecionar
depois. A partir daqui a remessa existe como objeto no S3, com chave
determinística derivada do ciclo, e a transmissão é outro passo que lê de lá.

## Entregáveis

- `domain/port/ArmazenamentoArtefato` — `put(chave, bytes)`, `get(chave)`,
  `existe(chave)`. Três métodos, nenhuma noção de bucket, região ou SDK.
- `domain/model/ChaveArtefato` — a chave como valor do domínio, não como String
  montada em três lugares: `remessa/{banco}/{dataRef}/{cicloId}.rem`.
- `domain/model/Remessa` — passa a produzir o layout posicional de largura fixa
  (header, detalhe, trailer). Continua função pura do ciclo.
- `domain/usecase/GerarRemessaUseCase` — projeta, faz `put` e **então** commita
  a chave e o sha256 no ciclo.
- `infra/persistence/ArmazenamentoArtefatoS3` — AWS SDK v2, `S3Client`.
- `infra/config/Ambiente` — ganha o `S3Client`.
- Schema: `ciclo_cobranca` ganha `remessa_chave TEXT` e `remessa_sha256 TEXT`,
  nulos enquanto a remessa não foi gerada.
- `infra/init/01-localstack.sh` cria o bucket `cobranca-artefatos`;
  `SERVICES: sqs,s3` no Compose e no LocalStack dos testes.
- Testes: `RemessaDeterministicaTest`, `RemessaSobreviveAReexecucaoTest`.

## O formato

Largura fixa, três tipos de registro, tipo na coluna 1. **Não é CNAB 240** — o
mecanismo que interessa é validação de completude por trailer, não o layout do
FEBRABAN.

```
0 341 20260831 C-1                    header: tipo, banco, dataRef, cicloId
1 T-1              F-1              000000000010000
1 T-2              F-2              000000000025050
9 000002                                    trailer: tipo + contagem de detalhe
```

| Registro | Posições | Campo |
|---|---|---|
| header  | 1 / 2–4 / 5–12 / 13–28 | tipo `0` · banco · dataRef `yyyyMMdd` · cicloId |
| detalhe | 1 / 2–17 / 18–33 / 34–48 | tipo `1` · **idTentativa** · faturaId · valor em centavos |
| trailer | 1 / 2–7 | tipo `9` · quantidade de registros de detalhe |

`idTentativa` em posição fixa é a **correlation key**: é o campo que volta no
retorno e liga a linha do parceiro à tentativa no banco. Valor em centavos,
zero à esquerda, sem separador — a alternativa (decimal com vírgula) faz o
mesmo ciclo gerar bytes diferentes conforme o locale da JVM.

## Ordem obrigatória

```
projeta (função pura)  →  s3.put  →  COMMIT (chave + sha256 no ciclo)
```

O `put` vem antes do commit, como no relay. A diferença — e é ela que este step
existe para mostrar — é que **aqui a janela não custa nada**: a chave é
determinística e o conteúdo é função pura do ciclo, então o `put` reexecutado
sobrescreve os mesmos bytes. Crashar entre o `put` e o `COMMIT` deixa um objeto
órfão idêntico ao que a reexecução vai gravar. No step-08 a mesma ordem custa
uma transmissão a mais, porque lá o efeito externo não é sobrescrevível pelo
próprio conteúdo.

## Decisões deste step

- **Chave determinística derivada do ciclo, não UUID nem timestamp.** Uma chave
  com `now()` transformaria cada reexecução num objeto novo, e o S3 viraria um
  log de tentativas em vez do artefato do ciclo. Com a chave derivada, o segundo
  `put` é uma sobrescrita com bytes idênticos — que é a definição operacional de
  "reexecutar é seguro".
- **Porta com três métodos, sem `delete` e sem `list`.** O que o domínio precisa
  é gravar, ler e perguntar se existe. Expurgo é operação de infra e não passa
  pela porta — ver ADR-0003.
- **`sha256` gravado no ciclo.** É o que torna verificável a promessa de
  determinismo em produção: reexecutar e comparar o hash com o que está no banco
  responde "o artefato mudou?" sem baixar nada. Não é controle de integridade do
  S3 — é asserção sobre o nosso próprio código.
- **O layout mora em `Remessa`, no domínio.** Posição de campo é regra de
  contrato com o parceiro, não detalhe de transporte. O que é infra é o `put`.

## Testes obrigatórios

**`RemessaDeterministicaTest`** — gera duas vezes a partir do mesmo ciclo e
assere: bytes idênticos, mesma chave, e o objeto no S3 depois do segundo `put`
com o mesmo conteúdo do primeiro. Compara os bytes inteiros, não o tamanho nem
só o hash.

**`RemessaSobreviveAReexecucaoTest`** — força exceção **entre o `put` e o
`COMMIT`**; assere que o ciclo continua sem `remessa_chave` e que o objeto já
está lá. Reexecuta e assere que o objeto final é idêntico ao órfão e que o ciclo
agora tem chave e hash. Nenhum artefato divergente em nenhum dos dois momentos.

## Definition of Done

- [ ] Os dois testes passam.
- [ ] `RemessaDeterministicaTest` compara os bytes inteiros dos dois artefatos.
- [ ] `RemessaSobreviveAReexecucaoTest` prova que o crash entre `put` e `COMMIT`
      não produz artefato divergente.
- [ ] O trailer traz a contagem de registros de detalhe, e existe teste que a
      confere contra o número de linhas.
- [ ] Zero import de `software.amazon.awssdk` em `domain/` — `FundacaoTest.dominioIsolado`
      continua verde.
- [ ] O bucket é criado pelo script de init, sem passo manual, nos dois caminhos
      (Compose e Testcontainers).
- [ ] CHANGELOG + commit `feat(outbox): remessa durável no s3 (step 07)`.
