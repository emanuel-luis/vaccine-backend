"""IBGE sources: Localidades (municipalities with state and region) and Censo 2022 population by
municipality, sex and single year of age 0-6 (SIDRA table 9514)."""

from __future__ import annotations

import re
from collections.abc import Iterable, Mapping
from typing import Any

from vaccine_elt.csvio import Row

MUNICIPALITIES_URL = "https://servicodados.ibge.gov.br/api/v1/localidades/municipios?view=nivelado"
MUNICIPALITIES_DOCS = "https://servicodados.ibge.gov.br/api/docs/localidades"
MUNICIPALITY_COLUMNS = [
    "municipality_code",
    "municipality_name",
    "state_code",
    "state_abbr",
    "state_name",
    "region_code",
    "region_abbr",
    "region_name",
]

CENSUS_YEAR = 2022
# Age categories 6557-6563 = "Menos de 1 ano" .. "6 anos"; c286/113635 = all ways of declaring age.
CENSUS_URL_TEMPLATE = (
    "https://apisidra.ibge.gov.br/values/t/9514/n6/all/v/93/p/2022/c2/{sex_code}"
    "/c287/6557,6558,6559,6560,6561,6562,6563/c286/113635"
)
CENSUS_DOCS = "https://sidra.ibge.gov.br/tabela/9514"
CENSUS_SEX_CODES = {"M": 4, "F": 5}  # SIDRA classification 2 (Sexo): 4 = Homens, 5 = Mulheres
CENSUS_COLUMNS = ["census_year", "municipality_code", "sex", "age_years", "population"]

_AGE_PATTERN = re.compile(r"^(\d+) anos?$")


def normalize_municipalities(payload: Iterable[Mapping[str, Any]]) -> list[Row]:
    """Flatten the `view=nivelado` payload into snapshot rows sorted by municipality code."""
    rows = [
        {
            "municipality_code": str(item["municipio-id"]),
            "municipality_name": str(item["municipio-nome"]),
            "state_code": str(item["UF-id"]),
            "state_abbr": str(item["UF-sigla"]),
            "state_name": str(item["UF-nome"]),
            "region_code": str(item["regiao-id"]),
            "region_abbr": str(item["regiao-sigla"]),
            "region_name": str(item["regiao-nome"]),
        }
        for item in payload
    ]
    return sorted(rows, key=lambda row: int(row["municipality_code"]))


def parse_age(label: str) -> int:
    """Map a SIDRA age label ("Menos de 1 ano", "1 ano", "6 anos") to completed years."""
    if label == "Menos de 1 ano":
        return 0
    match = _AGE_PATTERN.match(label)
    if not match:
        raise ValueError(f"unexpected SIDRA age label {label!r}")
    return int(match.group(1))


def parse_population(value: str) -> int:
    """SIDRA uses "-" for a true zero; "..." (not applicable) and "X" (suppressed) are errors."""
    if value == "-":
        return 0
    if not value.isdigit():
        raise ValueError(f"unexpected SIDRA value {value!r}")
    return int(value)


def normalize_census(payload: list[Mapping[str, str]], sex: str) -> list[Row]:
    """Turn one SIDRA response (header row first) into snapshot rows for one sex."""
    if sex not in CENSUS_SEX_CODES:
        raise ValueError(f"sex must be one of {sorted(CENSUS_SEX_CODES)}")
    header, *records = payload
    keys = {label: key for key, label in header.items()}
    municipality_key = keys["Município (Código)"]
    age_key = keys["Idade"]
    year_key = keys["Ano"]
    rows = []
    for record in records:
        if int(record[year_key]) != CENSUS_YEAR:
            raise ValueError(f"unexpected census year {record[year_key]!r}")
        rows.append(
            {
                "census_year": str(CENSUS_YEAR),
                "municipality_code": record[municipality_key],
                "sex": sex,
                "age_years": str(parse_age(record[age_key])),
                "population": str(parse_population(record["V"])),
            }
        )
    return rows


def sort_census(rows: Iterable[Row]) -> list[Row]:
    return sorted(rows, key=lambda r: (int(r["municipality_code"]), r["sex"], int(r["age_years"])))
