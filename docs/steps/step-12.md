# step-12 — Painel HTML

## Objetivo

Ver o estado inteiro **numa tela**, sem `curl`. O ciclo tem cinco passos, cinco
fontes de estado (banco, outbox, SFTP, S3, fila) e oito formas de provocar
falha; acompanhar isso por terminal é possível e é exatamente o tipo de atrito
que faz alguém parar de olhar.

## Entregáveis

- `src/main/resources/static/index.html` — **um arquivo**: HTML, CSS e
  JavaScript puro, tudo inline. Sem build, sem framework, sem CDN.
- Testes: `PainelEhServidoTest`.

## O que a tela tem

**Coluna de passos**, na ordem do fluxo, um botão cada:

```
criar faturas  →  montar  →  gerar remessa  →  enviar  →
   [ parceiro processa ]  →  coletar  →  fechar  →  publicar outbox
```

**Seção "provocar falha"**, visualmente separada — outra cor, outra coluna,
título próprio. Os botões do simulador e os dois de crash. A separação é
funcional, não decorativa: quem abre o painel precisa distinguir num relance o
que é operar o sistema do que é sabotá-lo.

**Painel de estado**, `fetch /estado` a cada 2s:

| Bloco | Conteúdo |
|---|---|
| tentativas | contagem por status |
| ciclos | id, banco, dataRef, status |
| outbox | linhas por status, com `chaveDedup` |
| SFTP | arquivos em `/remessa` e `/retorno`, com tamanho |
| S3 | objetos, com chave e tamanho |
| fila | mensagens, com `chaveDedup` |

**Log de eventos**, append-only: cada resposta de endpoint vira uma linha, com
horário e o efeito que ela devolveu. Ele nunca é limpo por polling — é o
histórico da sessão, e é onde a sequência "provocar falha → executar passo →
reexecutar passo" fica legível depois do fato.

## Decisões deste step

- **Sem build, sem framework, sem CDN.** O painel é acessório do projeto, não um
  segundo projeto. `npm install` para ver um mecanismo de backend seria a
  contradição óbvia; um `<script src="https://...">` seria pior, porque
  transformaria um demo local numa coisa que só funciona com internet.
- **Polling a cada 2s, sem WebSocket.** O estado é lido de cinco lugares e não
  há nada empurrando. Um WebSocket exigiria inverter isso — notificar a partir
  dos use cases — e colocaria no código de produção uma dependência que existe
  só para a tela atualizar sozinha.
- **O log é append-only e vem das respostas dos endpoints.** Ele não é derivado
  do `/estado`: o polling mostra o **agora**, e o log mostra a **sequência**. A
  janela do relay, por exemplo, só aparece na sequência — no `agora` ela já
  passou.
- **A tela não deduz nada.** Ela imprime o que os endpoints devolveram. Um
  painel que calcula "provavelmente aconteceu X" mente na primeira vez que o
  backend mudar.

## Testes obrigatórios

**`PainelEhServidoTest`** — `GET /` devolve 200 e HTML; o corpo não contém
nenhuma referência a host externo (`http://`, `https://`, `//cdn`) em `src` ou
`href`. É a asserção literal de "sem dependência externa".

## Definition of Done

- [ ] `index.html` servido pelo Spring, abre no navegador e funciona com o
      Compose no ar.
- [ ] Um botão por passo do ciclo, na ordem do fluxo.
- [ ] Seção de falhas visualmente separada dos passos.
- [ ] Estado atualizando a cada 2s, com as seis contagens da tabela acima.
- [ ] Log append-only mostrando cada transição conforme acontece.
- [ ] Zero dependência externa — asserido por `PainelEhServidoTest`.
- [ ] README com o passo a passo para abrir o painel.
- [ ] CHANGELOG + commit `feat(outbox): painel html de observação do ciclo (step 12)`.
