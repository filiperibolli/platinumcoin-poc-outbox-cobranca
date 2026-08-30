-- Schema do mini-outbox-cobranca.
-- Aplicado pelo docker-compose (via docker-entrypoint-initdb.d) E pelos testes
-- Testcontainers, a partir deste mesmo arquivo. Se os dois divergirem, o teste
-- passa e a produção quebra.

CREATE TABLE fatura (
    id     TEXT           PRIMARY KEY,
    valor  NUMERIC(15, 2) NOT NULL,
    status TEXT           NOT NULL CHECK (status IN ('ABERTA', 'PAGA', 'LANCADA'))
);

-- O ciclo de cobrança: o conjunto de tentativas transmitido a um banco parceiro
-- numa data. É a ÚNICA escrita que realmente importa — remessa, retorno,
-- fechamento e publicação são trabalho derivado, re-executável a partir dele.
CREATE TABLE ciclo_cobranca (
    id       TEXT PRIMARY KEY,
    banco    TEXT NOT NULL,
    data_ref DATE NOT NULL,
    status   TEXT NOT NULL CHECK (status IN ('MONTADO', 'ENVIADO', 'FECHADO')),

    -- Reexecutar a montagem é seguro POR CONSTRUÇÃO: a segunda tentativa
    -- esbarra aqui, em vez de depender de alguém ter conferido antes se o ciclo
    -- já existia. Verificação prévia é uma corrida; constraint é um fato.
    -- DECISÃO: idempotência por constraint, não por consulta prévia — ver step-02
    CONSTRAINT ciclo_um_por_banco_e_data UNIQUE (banco, data_ref)
);

-- N tentativas por fatura. O banco reapresenta o débito quando não paga.
--
-- Máquina de estados:
--   ABERTO → SOLICITADO → ENVIADO_PARCEIRO → PAGO | NAO_PAGO | ERRO
--                                          → SEM_RETORNO (via fechamento)
CREATE TABLE tentativa_debito (
    id        TEXT    PRIMARY KEY,
    fatura_id TEXT    NOT NULL REFERENCES fatura (id),
    numero    INTEGER NOT NULL,
    banco     TEXT    NOT NULL,
    data_ref  DATE    NOT NULL,

    -- Nulo enquanto ABERTO: a tentativa ainda não foi atribuída a um ciclo.
    ciclo_id  TEXT    REFERENCES ciclo_cobranca (id),

    status    TEXT    NOT NULL CHECK (status IN (
                  'ABERTO', 'SOLICITADO', 'ENVIADO_PARCEIRO',
                  'PAGO', 'NAO_PAGO', 'ERRO', 'SEM_RETORNO')),

    -- Só NAO_PAGO tem motivo: houve um fato, e o parceiro disse qual. A ausência
    -- de retorno (SEM_RETORNO) não tem motivo porque não houve fato nenhum.
    motivo    TEXT    CHECK (motivo IN (
                  'SALDO_INSUFICIENTE', 'CONTA_ENCERRADA', 'AUTORIZACAO_REVOGADA')),

    UNIQUE (fatura_id, numero),
    CONSTRAINT tentativa_motivo_so_com_nao_pago
        CHECK ((status = 'NAO_PAGO') = (motivo IS NOT NULL))
);

CREATE INDEX tentativa_montagem_idx
    ON tentativa_debito (banco, data_ref) WHERE status = 'ABERTO';

-- A intenção de publicar, gravada na MESMA transação que decide o pagamento.
CREATE TABLE outbox (
    id           BIGSERIAL   PRIMARY KEY,
    fatura_id    TEXT        NOT NULL REFERENCES fatura (id),
    payload      TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('PENDENTE', 'PUBLICADO')),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    publicado_em TIMESTAMPTZ,

    -- A invariante do domínio ("no máximo um lançamento por fatura") escrita no
    -- banco, e não só no código. É a rede sob o UPDATE condicional do step-03:
    -- se a guarda de status falhar por qualquer motivo, o INSERT estoura em vez
    -- de gerar um lançamento duplicado silencioso.
    -- DECISÃO: invariante no schema além do código — ver ADR-0001
    CONSTRAINT outbox_um_lancamento_por_fatura UNIQUE (fatura_id)
);

-- O relay varre só o que está PENDENTE.
CREATE INDEX outbox_pendente_idx ON outbox (id) WHERE status = 'PENDENTE';
