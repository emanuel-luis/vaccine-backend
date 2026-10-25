-- The 2023 operational tables (V1) are replaced by the warehouse: reference data lands in `raw`,
-- dbt builds `marts`, and the API reads the marts. The 2023 health-unit list is preserved as the
-- vendored snapshot data/sources/cnes_primary_care_2023-06-02.csv.gz.

DROP TABLE public.aplicacao_vacina;
DROP TABLE public.recebimento_vacina;
DROP TABLE public.unidade_basica;
DROP TABLE public.municipio;
DROP TABLE public.estado;
DROP TABLE public.regiao;
