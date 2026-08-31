# step-11 — Simulador do parceiro e provocação de falhas

## Objetivo

Tornar **reproduzíveis por botão** as falhas que o desenho defende. Até aqui elas
existem em testes: quem quiser ver o retorno truncado sendo descartado, ou o
relay morrendo entre o `send` e o `UPDATE`, precisa ler uma classe de teste. A
partir daqui é um `POST`.

O simulador **não é o sistema — é o ambiente**. Ele ocupa o lugar do banco
parceiro: escreve arquivos em `/retorno`, lê o que chegou em `/remessa`, e nada
sabe sobre `outbox`, transação ou estado de tentativa que não venha do arquivo
de remessa.

## Entregáveis

- Pacote `simulador/`, irmão de `api`, `domain` e `infra` — **fora** dos dois
  últimos. Ele não é chamado por nenhum use case; só por HTTP.
- `simulador/http/` — os endpoints abaixo.
- `simulador/ParceiroSimulado` — lê a remessa do SFTP, produz o retorno.
- `infra/falha/` — os decoradores que armam as falhas provocadas.
- Testes: `SimuladorProduzRetornoAplicavelTest`, `FalhasProvocadasTest`.

## Os endpoints do parceiro

| Rota | O que faz | Parâmetros |
|---|---|---|
| `POST /parceiro/processar` | lê a remessa do SFTP e escreve o retorno | `resultado` (distribuição `PAGO`/`NAO_PAGO`/`ERRO`), `particionar` (quebra em N arquivos), `atrasar` (escreve só parte agora; o resto numa segunda chamada) |
| `POST /parceiro/reenviar-retorno` | reescreve um retorno **idêntico** já processado | — |
| `POST /parceiro/retorno-truncado` | escreve arquivo cujo trailer não bate com o conteúdo | — |
| `POST /parceiro/silencio` | marca o ciclo como "sem retorno": **nada é escrito** | — |

E as falhas do nosso lado:

| Rota | Onde quebra | O que se observa |
|---|---|---|
| `POST /falha/crash-relay` | entre o `send` ao SQS e o `UPDATE` do outbox | duas mensagens, mesma `chaveDedup` |
| `POST /falha/crash-envio` | entre o `put` no SFTP e o `COMMIT` | um arquivo no parceiro, tentativas ainda `SOLICITADO` |

Cada endpoint arma a falha para a **próxima** execução do passo correspondente e
se desarma depois de disparar. Provocar a falha e executar o passo são duas
chamadas — é o que permite ver, no painel do step-12, o estado entre uma e outra.

## O que cada botão existe para mostrar

| Botão | O mecanismo que fica visível |
|---|---|
| `processar` | o caminho feliz, e a distribuição que só `PAGO` gera outbox |
| `particionar` | vários arquivos por ciclo convergindo para o mesmo estado |
| `atrasar` | quiescência: o arquivo em escrita não é baixado |
| `reenviar-retorno` | `UPDATE` condicional afetando **0 linhas** |
| `retorno-truncado` | trailer que não fecha, arquivo descartado inteiro |
| `silencio` | `SEM_RETORNO` no fechamento — silêncio não é recusa |
| `crash-relay` | at-least-once: a duplicata que o consumidor deduplica |
| `crash-envio` | a janela entre efeito externo e commit, sem transação possível |

## Decisões deste step

- **O simulador vive em pacote próprio, fora de `domain` e `infra`.** Ele é o
  ambiente, não o sistema. Se estivesse em `infra/`, a primeira leitura do
  projeto passaria a incluir código que não vai para produção — e a fronteira
  `api → domain ← infra`, que é o que o projeto ensina, ficaria com um quarto
  vértice mal explicado.
- **O simulador só enxerga arquivos.** Ele lê a remessa do SFTP para saber quais
  tentativas existem, exatamente como o parceiro real faria. Consultar o Postgres
  para montar o retorno seria mais simples e destruiria o valor da demonstração:
  o retorno passaria a ser função do nosso estado, e não do artefato que
  transmitimos.
- **As falhas são decoradores, não `if` no use case.** Mesmo recorte do
  `CrashDoRelayTest`: a falha é injetada na fiação HTTP envolvendo a porta. Um
  `if (simularCrash)` dentro de `PublicarOutboxUseCase` colocaria no código de
  produção uma linha que só existe para a demonstração — e o código de produção
  é justamente o que se quer olhar.
- **Armar e disparar são chamadas separadas.** Um endpoint que provocasse a
  falha e executasse o passo de uma vez esconderia o estado intermediário, que é
  o único momento em que a janela é visível.
- **`silencio` não escreve nada.** É o botão mais barato de implementar e o mais
  importante de ter: a ausência de retorno é o caso que os sistemas reais tratam
  pior, e ele não tem como ser observado sem um botão que produz nada.

## Testes obrigatórios

**`SimuladorProduzRetornoAplicavelTest`** — `processar` gera um retorno que a
coleta do step-09 baixa, valida e aplica: as tentativas da remessa terminam nos
estados pedidos, e só as `PAGO` geram linha no outbox. Cobre também
`particionar` e `atrasar`.

**`FalhasProvocadasTest`** — cada botão de falha reproduz, por HTTP, o que o
teste unitário correspondente já prova:
`retorno-truncado` → nada aplicado, arquivo permanece;
`reenviar-retorno` → 0 linhas afetadas, outbox inalterado;
`silencio` + `fechar` → `SEM_RETORNO`, sem outbox;
`crash-envio` → um arquivo no parceiro, tentativas `SOLICITADO`, reexecução
converge;
`crash-relay` → duas mensagens, mesma `chaveDedup`.

## Definition of Done

- [ ] Os oito endpoints respondem e produzem o efeito descrito.
- [ ] O pacote `simulador/` não é importado por nenhuma classe de `domain/` nem
      de `infra/` — asserido por teste, como a fronteira do domínio.
- [ ] O simulador monta o retorno **a partir da remessa lida do SFTP**, sem
      consultar o Postgres.
- [ ] Nenhum `if` de simulação em código de use case: as falhas são decoradores.
- [ ] `FalhasProvocadasTest` cobre os cinco cenários de falha por HTTP.
- [ ] README deixa explícito que o simulador é o ambiente, e não o sistema.
- [ ] CHANGELOG + commit `feat(outbox): simulador do parceiro e provocação de falhas (step 11)`.
