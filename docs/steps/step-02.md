# step-02 — Montagem de ciclo

## Objetivo

A escrita que importa. Tudo que vem depois — remessa, retorno, fechamento,
publicação — é **trabalho derivado**, reconstruível a partir do ciclo. Errar
aqui é o único erro que não dá para desfazer reprocessando.

## Entregáveis

- `domain/model/Remessa` — o artefato e seu formato, construído por uma função
  pura a partir do ciclo e de suas tentativas.
- `domain/usecase/MontarCicloUseCase` — a transação.
- `domain/usecase/GerarRemessaUseCase` — lê o ciclo pela porta e delega a
  projeção a `Remessa`. Separado da montagem porque é outra operação: pode rodar
  quantas vezes quiser, e é justamente isso que o teste prova.
- `infra/persistence/RepositorioCicloPostgres`,
  `infra/persistence/RepositorioTentativaPostgres` — JDBC puro.
- Testes: `MontagemDeterministicaTest`, `TrabalhoDerivadoDeterministicoTest`.

`domain/model/CicloCobranca` e as portas `RepositorioCiclo` e
`RepositorioTentativa` já existem.

## Regra

Em **uma** transação Postgres:

```sql
INSERT INTO ciclo_cobranca (id, banco, data_ref, status)
VALUES (?, ?, ?, 'MONTADO');

UPDATE tentativa_debito
   SET ciclo_id = ?, status = 'SOLICITADO'
 WHERE status = 'ABERTO' AND banco = ? AND data_ref = ?;
```

`COMMIT`. Nenhum sistema externo participa — nem arquivo, nem SFTP, nem fila.

## Decisões deste step

- **Idempotência por constraint, não por consulta prévia.** `UNIQUE (banco,
  data_ref)` faz a segunda montagem estourar. A alternativa — consultar se o
  ciclo já existe e só então inserir — é uma corrida: dois processos leem "não
  existe" e ambos inserem. Verificação prévia é uma opinião sobre o passado;
  constraint é um fato no momento da escrita.
- **A remessa é função pura do ciclo.** Mesmo `ciclo_id`, mesma String, byte a
  byte. Não é elegância: é o que permite regerar e retransmitir sem medo depois
  de qualquer falha. Um artefato derivado que muda a cada geração vira um
  segundo sistema de registro.
- **Gerar a remessa é um use case separado da montagem.** A montagem acontece
  uma vez e não pode se repetir; a geração acontece quantas vezes for preciso e
  precisa dar sempre o mesmo resultado. São garantias opostas — juntá-las num
  método só esconderia a mais importante das duas.
- **`ciclo_id` nulo enquanto `ABERTO`.** O nulo diz "ainda não pertence a
  nenhum ciclo" — estado real, não dado faltante.
- **Formato posicional trivial, 3 campos.** CNAB 240 de verdade é I/O e formato,
  não design — ver README, "fora de escopo".

## Testes obrigatórios

**`MontagemDeterministicaTest`** — monta o ciclo, força falha, reexecuta.
Assere que **não** surge um segundo ciclo (o `UNIQUE` barra) e que nenhuma
tentativa fica em estado inconsistente: ou todas do recorte estão `SOLICITADO`
com o `ciclo_id` preenchido, ou todas continuam `ABERTO` sem ciclo. Nunca meio a
meio.

**`TrabalhoDerivadoDeterministicoTest`** — a partir do mesmo `ciclo_id`, gera a
remessa duas vezes e assere igualdade **byte a byte** (`ORDER BY id`).

## Definition of Done

- [ ] Os dois testes passam.
- [ ] `MontagemDeterministicaTest` prova 1 ciclo após 2 montagens.
- [ ] `TrabalhoDerivadoDeterministicoTest` compara as duas Strings inteiras, não
      um hash nem o tamanho.
- [ ] A geração da remessa não lê nada além do ciclo e de suas tentativas —
      nada de `now()`, `random`, ou ordem de `HashMap`.
- [ ] CHANGELOG + commit `feat(outbox): montagem determinística de ciclo (step 02)`.
