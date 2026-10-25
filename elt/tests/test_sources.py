from __future__ import annotations

import pytest

from vaccine_elt.sources import cnes, ibge

SIDRA_HEADER = {
    "NC": "Nível Territorial (Código)",
    "V": "Valor",
    "D1C": "Município (Código)",
    "D3C": "Ano (Código)",
    "D3N": "Ano",
    "D5N": "Idade",
}


def sidra(municipality: str, age: str, value: str) -> dict[str, str]:
    return {"NC": "6", "V": value, "D1C": municipality, "D3C": "2022", "D3N": "2022", "D5N": age}


def test_municipalities_are_flattened_and_sorted() -> None:
    payload = [
        {
            "municipio-id": 5300108,
            "municipio-nome": "Brasília",
            "UF-id": 53,
            "UF-sigla": "DF",
            "UF-nome": "Distrito Federal",
            "regiao-id": 5,
            "regiao-sigla": "CO",
            "regiao-nome": "Centro-Oeste",
        },
        {
            "municipio-id": 1100015,
            "municipio-nome": "Alta Floresta D'Oeste",
            "UF-id": 11,
            "UF-sigla": "RO",
            "UF-nome": "Rondônia",
            "regiao-id": 1,
            "regiao-sigla": "N",
            "regiao-nome": "Norte",
        },
    ]
    rows = ibge.normalize_municipalities(payload)
    assert [r["municipality_code"] for r in rows] == ["1100015", "5300108"]
    assert list(rows[0]) == ibge.MUNICIPALITY_COLUMNS
    assert rows[1]["region_name"] == "Centro-Oeste"


@pytest.mark.parametrize(("label", "age"), [("Menos de 1 ano", 0), ("1 ano", 1), ("6 anos", 6)])
def test_age_labels(label: str, age: int) -> None:
    assert ibge.parse_age(label) == age


def test_unknown_age_label_fails() -> None:
    with pytest.raises(ValueError, match="age label"):
        ibge.parse_age("7 a 9 anos")


def test_dash_is_zero_and_suppressed_values_fail() -> None:
    assert ibge.parse_population("-") == 0
    assert ibge.parse_population("153") == 153
    with pytest.raises(ValueError):
        ibge.parse_population("X")


def test_census_rows_use_the_header_mapping() -> None:
    payload = [
        SIDRA_HEADER,
        sidra("1100015", "Menos de 1 ano", "153"),
        sidra("1100015", "2 anos", "-"),
    ]
    rows = ibge.normalize_census(payload, "M")
    assert rows == [
        {
            "census_year": "2022",
            "municipality_code": "1100015",
            "sex": "M",
            "age_years": "0",
            "population": "153",
        },
        {
            "census_year": "2022",
            "municipality_code": "1100015",
            "sex": "M",
            "age_years": "2",
            "population": "0",
        },
    ]


def test_census_rejects_unknown_sex() -> None:
    with pytest.raises(ValueError, match="sex"):
        ibge.normalize_census([SIDRA_HEADER], "X")


def extract_record(code: str, unit_type: str, **overrides: str) -> dict[str, str]:
    record = {
        "CO_CNES": code,
        "CO_IBGE": "260290",
        "NO_FANTASIA": " USF CENTRO ",
        "NO_BAIRRO": "CENTRO",
        "NO_LOGRADOURO": "RUA A",
        "NU_ENDERECO": "10",
        "TP_UNIDADE": unit_type,
        "CO_MOTIVO_DESAB": "",
        "NU_LATITUDE": "-8.23166",
        "NU_LONGITUDE": "-34.9695991",
        "NU_TELEFONE": "(81)0000-0000",
        "NO_EMAIL": "x@example.org",
    }
    record.update(overrides)
    return record


def test_extract_keeps_primary_care_and_known_units_only() -> None:
    records = [
        extract_record("19", "4"),  # hospital, unknown: dropped
        extract_record("27", "5"),  # hospital, known from an earlier snapshot: kept
        extract_record("108", "2"),  # primary care: kept
        extract_record("116", "1", CO_MOTIVO_DESAB="01", NU_LATITUDE="0.0", NU_LONGITUDE=""),
    ]
    rows = cnes.normalize_extract(records, "2026-10-24", frozenset({"0000027"}))
    assert [r["cnes_code"] for r in rows] == ["0000027", "0000108", "0000116"]
    first = rows[1]
    assert first["unit_name"] == "USF CENTRO"
    assert first["street"] == "RUA A - 10"
    assert first["is_active"] == "true"
    assert first["latitude"] == "-8.231660"
    assert set(first) == set(cnes.COLUMNS)  # phone and e-mail are not carried over
    assert rows[2]["is_active"] == "false"
    assert rows[2]["latitude"] == "" and rows[2]["longitude"] == ""


def test_legacy_rows_are_padded_and_active() -> None:
    rows = cnes.normalize_legacy(
        [
            {
                "id": "4030",
                "id_municipio": "292740",
                "nome_fantasia": "UBS X",
                "bairro": "B",
                "logradouro": "RUA - S/N",
            }
        ],
        "2023-06-02",
    )
    assert rows[0]["cnes_code"] == "0004030"
    assert rows[0]["is_active"] == "true"
    assert rows[0]["unit_type_code"] == ""


@pytest.mark.parametrize("code", ["", "12345678", "12a"])
def test_invalid_cnes_codes_fail(code: str) -> None:
    with pytest.raises(ValueError):
        cnes.pad_cnes(code)
