# ADR-0005 — Log no domínio, nos pontos de decisão

- **Status:** aceito
- **Data:** 2026-09-01
- **Contexto:** depois do [ADR-0004](0004-aplicacao-em-container-atras-de-um-perfil.md) — a aplicação em container

## Contexto

Com a aplicação num container, o log passou a ser a superfície principal de
leitura da execução: quem sobe o Compose lê `docker compose logs -f app`, não a
resposta do `curl`. A pergunta virou **o que** logar, e ela tem uma resposta
específica neste projeto.

O que se quer ver acontecendo não é tráfego. É:

- o `UPDATE` condicional afetando **1** linha na primeira aplicação de um
  retorno e **0** na segunda — a dificuldade 3 inteira, numa contagem;
- o `send` ao SQS acontecendo **antes** do `UPDATE`, com a linha ainda dizendo
  `PENDENTE` no instante entre os dois — a janela B, que só existe na sequência;
- a quiescência: dois `stat` do mesmo arquivo, com tamanhos diferentes,
  seguidos da decisão de não baixar;
- o trailer que não fecha e o arquivo descartado inteiro, em vez de aplicado
  pela metade;
- o fechamento marcando `SEM_RETORNO` e não `NAO_PAGO`.

Todas essas frases são ditas **dentro dos use cases**. O adaptador sabe que
executou um `UPDATE` que afetou uma linha; ele não sabe que isso significa "o
retorno já havia sido aplicado, e isso é o caso normal, e não um erro".

## Decisão

**`org.slf4j` é importável no domínio, e os pontos de decisão logam.** A
`FundacaoTest.dominioIsolado` — que até aqui só admitia `java.*` e o próprio
domínio — passou a admitir `org.slf4j.` também, com o motivo escrito no teste.

A regra 3 do `CLAUDE.md` nunca proibiu log: ela nomeia Spring, AWS SDK e
biblioteca SSH. O teste era mais estrito que a regra escrita, e o que se afrouxou
foi o teste **até** a regra, não a regra.

A distinção que sustenta isso: slf4j é uma **API sem implementação**, e é a
implementação que amarra. Importar `SqsClient` no domínio significa que o
domínio só roda contra a AWS; importar `Logger` significa que ele emite frases
para quem quiser escutar — inclusive ninguém, que é o que acontece nos testes.

A prioridade escrita no topo do `CLAUDE.md` decide o empate: **este projeto
existe para ser a pergunta de um system design e a sua solução.** Um mecanismo
que não pode ser visto acontecendo é um mecanismo que precisa ser acreditado.

O vocabulário é o mesmo do cenário de console do step-06 — `[ciclo]`,
`[remessa]`, `[envia]`, `[coleta]`, `[retorno]`, `[fatura]`, `[outbox]`,
`[fila]`, `[fecha]`, `[crash]` —, de propósito: as duas saídas contam a mesma
história, e um leitor que aprendeu uma lê a outra.

## Onde cada camada fala

| camada | o que ela sabe dizer | exemplo |
|---|---|---|
| use case (`domain`) | o que a contagem **significa** | `[retorno] T-1 → PAGO (0 linhas afetadas — já aplicado, ignorado)` |
| adaptador (`infra`) | o que atravessou a fronteira | `[parceiro] stat /retorno/x.ret — 221 bytes` |
| borda HTTP (`api/http`) | o efeito que voltou ao chamador | `[passo] POST /ciclo/fechar?ciclo=C-1 → {"semRetorno":1}` |

As três são necessárias e nenhuma substitui a outra: o adaptador não sabe
interpretar, o use case não sabe quantos bytes trafegaram, e a borda não sabe o
que aconteceu no meio. O `[passo]` é um advice único (`EfeitoNoLog`), e não uma
linha em cada controller, porque o que se quer registrar é "toda resposta" —
repetir isso oito vezes criaria oito lugares para esquecer no nono passo.

**`GET /estado` fica de fora do log.** O painel o relê a cada 2s e cada leitura
carrega as cinco fontes inteiras: logá-lo afogaria a sequência que este log
existe para mostrar.

**O console do step-06 silencia tudo isto.** `Main.main` define
`org.slf4j.simpleLogger.log.com.platinumcoin.ciclo=warn` antes de qualquer coisa:
o cenário do console **é** a saída do programa, e as mesmas frases interleavadas
contariam a história duas vezes.

## Alternativas descartadas

**A. Logar só em `infra`, mantendo o domínio mudo.** Preserva a lista branca
original sem discussão. Descartada porque o adaptador não tem o vocabulário: ele
emitiria `UPDATE afetou 0 linhas`, e "0 linhas aqui significa que o retorno já
havia sido aplicado, e isso não é erro" é conhecimento do use case. A saída
viraria um `tail` de SQL, e quem já entende o desenho não precisa dela — quem não
entende não a decifra.

**B. Uma porta `Diario`/`RegistroDeEventos` em `domain/port`, implementada em
`infra` sobre slf4j.** É a resposta ortodoxa, e mantém a lista branca intacta.
Descartada porque seria uma indireção para não escrever `import org.slf4j.Logger`:
uma interface, uma implementação, mais um parâmetro em oito construtores e em
toda a fiação — e o teste que a fiscaliza continuaria dizendo a mesma coisa. Um
arquivo a mais que só muda código de lugar é custo (regra 8 do `CLAUDE.md`).

**C. Devolver eventos ricos dos use cases e logar na borda.** Sem dependência
nova no domínio, e a borda já loga o efeito. Descartada porque os pontos que mais
importam não são o retorno do método: a janela B acontece **entre** duas linhas
de `PublicarOutboxUseCase`, e o desfecho de cada arquivo da coleta é decidido no
meio de um laço. Modelá-los como retorno significaria criar tipos cuja única
razão de existir é serem impressos.

**D. Elevar o nível dos logs do Spring e do driver.** Sai de graça. Descartada
porque conta a história errada: `SELECT ... WHERE status='PENDENTE'` num log de
JDBC não é a mesma frase que "3 linhas PENDENTE nesta passada".

## Consequências

- **O domínio ganhou uma dependência.** Continua sem Spring, sem AWS SDK e sem
  SSH — a fronteira que o projeto defende —, mas a lista branca não é mais "só
  `java.*`". A fiscalização segue existindo e agora tem uma exceção nomeada, que
  é pior que zero exceções e melhor que uma regra que ninguém consegue seguir.
- **Os logs são parte do contrato de leitura.** Mudar uma frase de log é mudar o
  que o README mostra, e o README é o entregável principal. Elas passaram a ser
  conferidas contra execução real, como qualquer outro número do documento.
- **Volume.** Uma passada da coleta com um arquivo emite cerca de dez linhas —
  `ls`, dois `stat`, `get`, o `put` do arquivamento e uma por linha aplicada.
  É barulhento para produção e é exatamente o ponto aqui: em produção, o nível
  do pacote `infra` desceria para `warn` e os use cases continuariam em `info`,
  que é a divisão que esta decisão já deixa pronta.
- **Nenhum teste depende de log.** Os cinco intocáveis do `CLAUDE.md` e os cinco
  do ciclo 07–12 continuam verificando estado, não saída. Um log que sumisse não
  quebraria a suíte — quebraria o README, e é por isso que ele está escrito lá.
