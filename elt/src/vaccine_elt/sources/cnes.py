"""CNES (Cadastro Nacional de Estabelecimentos de Saúde) primary-care units as dated snapshots.

The national open-data extract is a `;`-separated Latin-1 CSV inside a zip. We keep primary-care
units (`TP_UNIDADE` 1 and 2) plus every unit already present in an earlier snapshot, so a unit
whose type changed still shows up in the SCD2 history. Phone, e-mail and CNPJ columns are dropped
(data minimization)."""

from __future__ import annotations

import csv
import io
import zipfile
from collections.abc import Iterable, Iterator, Mapping
from pathlib import Path

from vaccine_elt.csvio import Row

EXTRACT_URL = (
    "https://s3.sa-east-1.amazonaws.com/ckan.saude.gov.br/CNES/cnes_estabelecimentos_csv.zip"
)
# Dataset page as documented before the portal moved; it now redirects to dadosabertos.saude.gov.br.
DATASET_URL = (
    "https://opendatasus.saude.gov.br/dataset/cadastro-nacional-de-estabelecimentos-de-saude-cnes"
)
PRIMARY_CARE_TYPES = frozenset({1, 2})
COLUMNS = [
    "snapshot_date",
    "cnes_code",
    "municipality_code6",
    "unit_name",
    "neighborhood",
    "street",
    "unit_type_code",
    "is_active",
    "latitude",
    "longitude",
]


def pad_cnes(code: str) -> str:
    code = code.strip()
    if not code.isdigit() or len(code) > 7:
        raise ValueError(f"invalid CNES code {code!r}")
    return code.zfill(7)


def _coordinate(value: str, low: float, high: float) -> str:
    try:
        number = float(value)
    except ValueError:
        return ""
    return f"{number:.6f}" if low <= number <= high and number != 0.0 else ""


def _street(name: str, number: str) -> str:
    name, number = name.strip(), number.strip()
    return f"{name} - {number}" if name and number else name


def iter_extract_rows(zip_path: Path) -> Iterator[dict[str, str]]:
    """Yield the raw CSV records of the national extract without extracting it to disk."""
    with zipfile.ZipFile(zip_path) as archive:
        (member,) = [name for name in archive.namelist() if name.lower().endswith(".csv")]
        with archive.open(member) as raw:
            text = io.TextIOWrapper(raw, encoding="latin-1", newline="")
            yield from csv.DictReader(text, delimiter=";")


def normalize_extract(
    records: Iterable[Mapping[str, str]], snapshot_date: str, keep_codes: frozenset[str]
) -> list[Row]:
    """Primary-care units plus units listed in `keep_codes`, sorted by CNES code."""
    rows: dict[str, Row] = {}
    for record in records:
        unit_type = int(record["TP_UNIDADE"]) if record["TP_UNIDADE"].strip() else None
        code = pad_cnes(record["CO_CNES"])
        if unit_type not in PRIMARY_CARE_TYPES and code not in keep_codes:
            continue
        rows[code] = {
            "snapshot_date": snapshot_date,
            "cnes_code": code,
            "municipality_code6": record["CO_IBGE"].strip(),
            "unit_name": record["NO_FANTASIA"].strip(),
            "neighborhood": record["NO_BAIRRO"].strip(),
            "street": _street(record["NO_LOGRADOURO"], record["NU_ENDERECO"]),
            "unit_type_code": "" if unit_type is None else str(unit_type),
            "is_active": "false" if record["CO_MOTIVO_DESAB"].strip() else "true",
            "latitude": _coordinate(record["NU_LATITUDE"], -34.0, 6.0),
            "longitude": _coordinate(record["NU_LONGITUDE"], -74.0, -28.0),
        }
    return [rows[code] for code in sorted(rows)]


def normalize_legacy(records: Iterable[Mapping[str, str]], snapshot_date: str) -> list[Row]:
    """Convert the 2023 `unidade_basica.csv` (id, id_municipio, nome_fantasia, bairro,
    logradouro) into snapshot rows. It carries no type or coordinates; every unit was active."""
    rows = {
        pad_cnes(record["id"]): {
            "snapshot_date": snapshot_date,
            "cnes_code": pad_cnes(record["id"]),
            "municipality_code6": record["id_municipio"].strip(),
            "unit_name": record["nome_fantasia"].strip(),
            "neighborhood": record["bairro"].strip(),
            "street": record["logradouro"].strip(),
            "unit_type_code": "",
            "is_active": "true",
            "latitude": "",
            "longitude": "",
        }
        for record in records
    }
    return [rows[code] for code in sorted(rows)]
