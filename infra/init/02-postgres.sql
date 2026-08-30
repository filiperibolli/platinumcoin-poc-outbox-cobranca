-- Schema do mini-outbox-cobranca.
-- Aplicado pelo docker-compose (via docker-entrypoint-initdb.d) E pelos testes
-- Testcontainers, a partir deste mesmo arquivo. Se os dois divergirem, o teste
-- passa e a produção quebra.

CREATE TABLE fatura (
    id     TEXT           PRIMARY KEY,
    valor  NUMERIC(15, 2) NOT NULL,
    status TEXT           NOT NULL CHECK (status IN ('ABERTA', 'PAGA', 'LANCADA'))
);

-- N tentativas por fatura. O banco reapresenta o débito quando não paga.
CREATE TABLE tentativa_debito (
    id        TEXT    PRIMARY KEY,
    fatura_id TEXT    NOT NULL REFERENCES fatura (id),
    numero    INTEGER NOT NULL,
    status    TEXT    NOT NULL CHECK (status IN ('ENVIADA', 'PAGA', 'NAO_PAGA', 'ERRO')),
    UNIQUE (fatura_id, numero)
);

-- A intenção de publicar, gravada na MESMA transação que decide o pagamento.
CREATE TABLE outbox (
    id           BIGSERIAL   PRIMARY KEY,
    fatura_id    TEXT        NOT NULL REFERENCES fatura (id),
    payload      TEXT        NOT NULL,
    status       TEXT        NOT NULL CHECK (status IN ('PENDENTE', 'PUBLICADO')),
    criado_em    TIMESTAMPTZ NOT NULL DEFAULT now(),
    publicado_em TIMESTAMPTZ,

    -- A invariante do domínio ("no máximo um lançamento por fatura") escrita no
    -- banco, e não só no código. É a rede sob o UPDATE condicional do step-02:
    -- se a guarda de status falhar por qualquer motivo, o INSERT estoura em vez
    -- de gerar um lançamento duplicado silencioso.
    -- DECISÃO: invariante no schema além do código — ver ADR-0001
    CONSTRAINT outbox_um_lancamento_por_fatura UNIQUE (fatura_id)
);

-- O relay varre só o que está PENDENTE.
CREATE INDEX outbox_pendente_idx ON outbox (id) WHERE status = 'PENDENTE';
