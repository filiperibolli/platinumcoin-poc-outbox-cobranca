# step-08 — Envio por SFTP

## Objetivo

Exercitar a transmissão **de verdade** — conexão SSH, arquivo aparecendo no
diretório de outro host — e expor a janela de duplicidade que o README descreve
desde o começo mas que nenhum código deste repositório havia ainda vivido: o
`put` no parceiro acontece antes do `COMMIT` que registra que ele aconteceu, e
não existe transação que una os dois.

Até o step-06 a transmissão era um `UPDATE` solto com um comentário dizendo
"transporte fora de escopo". Deixa de ser.

## Entregáveis

- Container `atmoz/sftp` no `infra/docker-compose.yml`, com os diretórios
  `/remessa` e `/retorno` e um usuário de teste.
- `domain/port/CanalArquivos` — `enviar(nome, bytes)`. Ganha os métodos de
  leitura no step-09.
- `domain/usecase/EnviarRemessaUseCase` — lê o artefato do S3 pela chave gravada
  no ciclo, transmite, e **então** commita as transições.
- `infra/canal/CanalArquivosSftp` — cliente SSH real (`com.hierynomus:sshj`).
- `infra/config/Ambiente` — ganha as credenciais e o host do SFTP.
- Testes: `EnvioChegaNoParceiroTest`, `CrashDepoisDoPutTest`.

## Ordem obrigatória

```
s3.get (artefato)  →  sftp.put  →  COMMIT (tentativas e ciclo)
```

Em **uma** transação, depois do `put`:

```sql
UPDATE tentativa_debito SET status = 'ENVIADO_PARCEIRO'
 WHERE ciclo_id = ? AND status = 'SOLICITADO';

UPDATE ciclo_cobranca SET status = 'ENVIADO'
 WHERE id = ? AND status = 'MONTADO';
```

Nome de destino determinístico — `/remessa/{banco}-{dataRef}-{cicloId}.rem` —
para que o reenvio **sobrescreva** em vez de acumular. É a mesma propriedade da
chave do S3, aplicada ao outro lado do fio.

## A janela, marcada no código

Comentário obrigatório no `EnviarRemessaUseCase`, exatamente no ponto:

```java
// DECISÃO: put no parceiro antes do COMMIT — a janela é conhecida e testada,
// ver docs/steps/step-08.md e CrashDepoisDoPutTest
```

É a segunda ocorrência da mesma forma do relay, e a comparação é o que este step
ensina:

| | relay (step-05) | envio (step-08) | remessa (step-07) |
|---|---|---|---|
| efeito externo | mensagem na fila | arquivo no parceiro | objeto no S3 |
| reexecutar produz | **duplicata** | **sobrescrita** | **sobrescrita** |
| quem paga | consumidor, via `chaveDedup` | ninguém — o nome é o mesmo | ninguém |

O nome determinístico não fecha a janela: ele torna o efeito da reexecução
**idempotente por conteúdo**. O parceiro que já leu o arquivo antes da
sobrescrita processa duas vezes — e é o `UPDATE` condicional do step-03 que
absorve isso, não o SFTP.

## Decisões deste step

- **SFTP de verdade, não um `FileSystem` fingindo de canal.** Um `Path` local
  não tem latência, não tem arquivo aparecendo pela metade, não tem conexão que
  cai no meio do `put`. O container custa segundos no `mvn test` e é o que torna
  os testes do step-09 (quiescência) possíveis.
- **Nome determinístico em vez de sufixo de tentativa.** A alternativa —
  `...-1.rem`, `...-2.rem` — deixa o parceiro decidir qual é o bom, e transfere
  para fora um problema que é nosso.
- **O use case lê do S3, não regenera.** Regenerar aqui seria correto (a remessa
  é função pura) e ainda assim errado: apagaria a fronteira que o step-07 acabou
  de criar, e o dia em que a geração mudar, o que foi enviado e o que foi
  arquivado divergiriam sem que nada acuse.
- **A transição é do ciclo inteiro, num `UPDATE` só.** Um arquivo é um evento:
  ou o parceiro recebeu a remessa, ou não recebeu.

## Testes obrigatórios

**`EnvioChegaNoParceiroTest`** — com o container SFTP no ar: envia, conecta,
lista `/remessa`, baixa e compara os bytes com o artefato do S3. Assere também
as transições (`SOLICITADO → ENVIADO_PARCEIRO`, `MONTADO → ENVIADO`).

**`CrashDepoisDoPutTest`** — força exceção entre o `put` e o `COMMIT`. Assere:
o arquivo **está** no parceiro, as tentativas continuam `SOLICITADO`, o ciclo
continua `MONTADO`. Reexecuta e assere: **um** arquivo no diretório remoto (não
dois), bytes idênticos, e os estados convergidos. Este teste **documenta** a
janela; não a elimina.

## Definition of Done

- [ ] Os dois testes passam, contra o container `atmoz/sftp` subido por
      Testcontainers — sem depender do Compose.
- [ ] `CrashDepoisDoPutTest` assere explicitamente a contagem de arquivos no
      destino **igual a 1** depois da reexecução.
- [ ] O comentário `// DECISÃO:` está no ponto exato da janela.
- [ ] Zero import de biblioteca SSH em `domain/`.
- [ ] `mvn test` continua subindo tudo sozinho.
- [ ] CHANGELOG + commit `feat(outbox): envio da remessa por sftp (step 08)`.
