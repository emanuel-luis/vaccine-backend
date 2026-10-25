-- Baseline of the original (2023) operational schema, previously created by
-- src/python/data/init_table.sql with DROP SCHEMA public CASCADE. Flyway now owns the DDL;
-- later migrations replace this model with the dimensional warehouse. Flyway keeps its history in
-- the `ops` schema, so the legacy tables are qualified with `public` explicitly.

CREATE TABLE public.regiao (
    id   BIGINT PRIMARY KEY,
    nome TEXT NOT NULL
);

CREATE TABLE public.estado (
    id        BIGINT PRIMARY KEY,
    id_regiao BIGINT  NOT NULL REFERENCES public.regiao (id),
    nome      TEXT    NOT NULL,
    sigla     CHAR(2) NOT NULL UNIQUE
);
CREATE INDEX ix_estado_id_regiao ON public.estado (id_regiao);

CREATE TABLE public.municipio (
    id        BIGINT PRIMARY KEY,
    id_estado BIGINT NOT NULL REFERENCES public.estado (id),
    nome      TEXT   NOT NULL
);
CREATE INDEX ix_municipio_id_estado ON public.municipio (id_estado);

CREATE TABLE public.unidade_basica (
    id            BIGINT PRIMARY KEY,
    id_municipio  BIGINT NOT NULL REFERENCES public.municipio (id),
    nome_fantasia TEXT,
    bairro        TEXT,
    logradouro    TEXT
);
CREATE INDEX ix_unidade_basica_id_municipio ON public.unidade_basica (id_municipio);

CREATE TABLE public.recebimento_vacina (
    id                   UUID PRIMARY KEY,
    id_unidade_basica    BIGINT    NOT NULL REFERENCES public.unidade_basica (id),
    datahora_recebimento TIMESTAMP NOT NULL,
    quantidade           BIGINT    NOT NULL
);
CREATE INDEX ix_recebimento_vacina_id_unidade_basica ON public.recebimento_vacina (id_unidade_basica);

CREATE TABLE public.aplicacao_vacina (
    id                 UUID PRIMARY KEY,
    data_nascimento    TIMESTAMP NOT NULL,
    sexo               CHAR(1)   NOT NULL,
    id_unidade_basica  BIGINT    NOT NULL REFERENCES public.unidade_basica (id),
    datahora_aplicacao TIMESTAMP NOT NULL
);
CREATE INDEX ix_aplicacao_vacina_id_unidade_basica ON public.aplicacao_vacina (id_unidade_basica);
CREATE INDEX ix_aplicacao_vacina_datahora_aplicacao ON public.aplicacao_vacina (datahora_aplicacao);
