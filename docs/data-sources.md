# Data sources

Reference data comes from public Brazilian government sources and is vendored as small,
normalized, gzipped CSV snapshots in [`data/sources/`](../data/sources/). Every file has an entry in
[`data/sources/manifest.json`](../data/sources/manifest.json) with its source URL, retrieval time,
transformation, row count and sha256; `vaccine-elt load-reference` refuses a file whose bytes do
not match. Vaccination events and vaccine receipts are synthetic (seeded generator, a later phase):
no real person's data is used anywhere.

## Snapshots

| Manifest id | Publisher | Endpoint | Rows | Notes |
| --- | --- | --- | --- | --- |
| `ibge_municipalities` | IBGE | `https://servicodados.ibge.gov.br/api/v1/localidades/municipios?view=nivelado` ([docs](https://servicodados.ibge.gov.br/api/docs/localidades)) | 5,571 | municipality, state and region codes and names |
| `ibge_census2022_population_age0_6` | IBGE | SIDRA table [9514](https://sidra.ibge.gov.br/tabela/9514), one API call per sex (50,000-value cap) | 77,980 | Censo 2022 resident population by municipality, sex and single year of age 0-6; `-` read as 0 |
| `cnes_primary_care_2023-06-02` | Ministério da Saúde | the project's 2023 `unidade_basica.csv` | 39,767 | derived in 2023 from the Ministry's UBS open dataset; exact extraction date unknown (first commit date used) |
| `cnes_primary_care_2026-10-24` | Ministério da Saúde | `https://s3.sa-east-1.amazonaws.com/ckan.saude.gov.br/CNES/cnes_estabelecimentos_csv.zip` | 59,971 | `TP_UNIDADE` 1 and 2 plus units known from 2023; phone, e-mail and CNPJ dropped |

Refresh with `uv run --project elt vaccine-elt fetch` (network; never run in CI). The command
rewrites the snapshots and the manifest deterministically: the same upstream data gives the same
bytes.

## Geometries

Map polygons for the coverage choropleth, in [`data/sources/geo/`](../data/sources/geo/): one file
for the 27 states and one per state with its municipalities (28 files). Each has a manifest entry
with `table: null` (`load-reference` skips it, `verify` still checks its sha256) and
`license_status: to_be_confirmed`.

| Manifest id | Publisher | Endpoint | Features |
| --- | --- | --- | --- |
| `ibge_malha_uf` | IBGE, Malhas territoriais API v3 ([docs](https://servicodados.ibge.gov.br/api/docs/malhas?versao=3)) | `https://servicodados.ibge.gov.br/api/v3/malhas/paises/BR?intrarregiao=UF&qualidade=minima&formato=application/vnd.geo+json` | 27 |
| `ibge_malha_municipios_<uf>` (27 files) | same | `https://servicodados.ibge.gov.br/api/v3/malhas/estados/<2-digit state code>?intrarregiao=municipio&qualidade=minima&formato=application/vnd.geo+json` | 5,570 in total |

- Quality: `minima` (simplified polygons, enough for a choropleth; not for measuring areas).
- Transformation (`vaccine-elt fetch --only geo`): only `codarea` (string: 2-digit state code or
  7-digit municipality code) and the geometry are kept; features sorted by `codarea`; coordinates
  rounded to 4 decimals (about 11 m); compact JSON with sorted keys; gzip with a zero mtime.
- Sizes (budget: 2 MB gzipped in total, 600 KB uncompressed per state file): 845,510 bytes gzipped
  for the 28 files; the largest state file is Minas Gerais at 455,013 bytes uncompressed; the state
  mesh is 28,049 bytes gzipped.
- Codes: every municipality polygon matches a municipality of `ibge_municipalities`, except that
  Boa Esperança do Norte (MT, `5101837`, created after Censo 2022) has no polygon in the IBGE mesh
  yet and no census rows. The two Rio Grande do Sul lagoons (`4300001`, `4300002`) that IBGE
  meshes may carry as extra features are not in the current snapshot; the contract test allows them.
- Attribution: IBGE, Malhas territoriais (API de malhas v3, qualidade mínima).
- License: to be confirmed, the same status as the other IBGE files (see below).

## Citations

- IBGE, API de Localidades (municípios, `view=nivelado`).
- IBGE, Censo Demográfico 2022, SIDRA tabela 9514 (população residente, por sexo e idade).
- IBGE, Malhas territoriais (API de malhas v3, qualidade mínima).
- Ministério da Saúde, CNES - Cadastro Nacional de Estabelecimentos de Saúde, extração de dados
  abertos de 2026-10-24.
- Ministério da Saúde, Unidades Básicas de Saúde (UBS), dados abertos (2023 snapshot).

## Terms of use: still to be confirmed

IBGE publishes these data openly and asks to be cited as the source; the Ministry of Health
publishes CNES on its open-data portal (`dadosabertos.saude.gov.br`, formerly `opendatasus`).
Third parties describe CNES as public domain with attribution. On 2026-10-25 neither IBGE's terms
page nor the portal's license field could be retrieved automatically (HTTP 403 and connection
resets), so the manifest records attribution and marks the exact license text as still to be
confirmed. Check these before reusing the snapshots elsewhere:

- IBGE terms of use: <https://www.ibge.gov.br/> (institutional "termos de uso").
- CNES dataset page:
  <https://opendatasus.saude.gov.br/dataset/cadastro-nacional-de-estabelecimentos-de-saude-cnes>
  (redirects to the current portal).

## Vaccination schedule

[`dbt/seeds/pni_child_schedule.csv`](../dbt/seeds/pni_child_schedule.csv) encodes the 2026 national
childhood calendar (Programa Nacional de Imunizações) for ages 0-6: recommended ages from the
Ministry of Health calendar page and Nota Técnica 87/2026 (rectified by NT 91/2026), the maximum
ages those notes document, and the coverage-indicator doses of their three monitoring panels
(under 1 year, 1 year, 4 years). Minimum ages and minimum intervals are mostly not documented
there; where the `window_source` column says "modeling assumption", the window is this project's
choice for the synthetic generator, not an official rule. COVID-19 and influenza are left out
(not part of the routine coverage indicators).

- Calendar: <https://www.gov.br/saude/pt-br/vacinacao/calendario>
- NT 87/2026 and NT 91/2026 (CGICI/DPNI/SVSA/MS), as republished by the Porto Alegre health
  department: <https://prefeitura.poa.br/sites/default/files/usu_doc/hotsites/sms/vigilancia-em-saude/Nota%20T%C3%A9cnica%2087.2026%20Regras%20CV.pdf>,
  <https://prefeitura.poa.br/sites/default/files/usu_doc/hotsites/sms/vigilancia-em-saude/NOTA%20T%C3%89CNICA%20N%C2%BA%2091.pdf>

## CNES unit types

[`dbt/seeds/cnes_unit_types.csv`](../dbt/seeds/cnes_unit_types.csv) labels `TP_UNIDADE` 1 ("Posto
de saúde") and 2 ("Centro de saúde / unidade básica"). Label 2 is confirmed by a municipal CNES
filling guide; label 1 is the commonly used name, not yet checked against a DATASUS domain table.
Other types appear as "Other (CNES type N)".
