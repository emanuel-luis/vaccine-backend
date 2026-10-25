"""Refresh the vendored snapshots from the public endpoints and rewrite their manifest entries.

Run by hand (`vaccine-elt fetch`), never in CI. Downloads go to a temporary directory; only the
normalized, deterministic snapshot files land in `data/sources/`."""

from __future__ import annotations

import csv
import json
import logging
import tempfile
from datetime import UTC, datetime
from pathlib import Path

from vaccine_elt import csvio
from vaccine_elt.manifest import Manifest, SourceEntry, sha256_file
from vaccine_elt.sources import cnes, ibge
from vaccine_elt.sources.http import download

log = logging.getLogger(__name__)

IBGE_LICENSE_NOTE = (
    "Public data published by IBGE; reuse with attribution to IBGE as the source. IBGE's terms "
    "page could not be retrieved automatically, so the exact license text is still to be "
    "confirmed (see docs/data-sources.md)."
)
CNES_LICENSE_NOTE = (
    "Ministry of Health open data (dadosabertos.saude.gov.br, formerly opendatasus). Third parties "
    "report it as public domain with attribution; the portal's license field could not be "
    "retrieved automatically and is still to be confirmed (see docs/data-sources.md)."
)
CNES_TRANSFORM = (
    "Kept TP_UNIDADE 1 and 2 (primary care) plus units present in earlier snapshots; CO_CNES "
    "zero-padded to 7 digits; street = NO_LOGRADOURO - NU_ENDERECO; is_active = CO_MOTIVO_DESAB "
    "empty; coordinates outside Brazil blanked; phone, e-mail and CNPJ columns dropped."
)


def _now() -> str:
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def _entry(
    directory: Path,
    path: Path,
    columns: list[str],
    rows: int,
    *,
    id: str,
    table: str,
    publisher: str,
    source_url: str,
    documentation_url: str,
    license_note: str,
    citation: str,
    retrieved_at: str,
    snapshot_date: str,
    transform: str,
    source_last_modified: str | None,
) -> SourceEntry:
    return SourceEntry(
        id=id,
        path=path.relative_to(directory).as_posix(),
        table=table,
        publisher=publisher,
        source_url=source_url,
        documentation_url=documentation_url,
        license_note=license_note,
        citation=citation,
        retrieved_at=retrieved_at,
        snapshot_date=snapshot_date,
        transform=transform,
        columns=columns,
        rows=rows,
        sha256=sha256_file(path),
        source_last_modified=source_last_modified,
    )


def fetch_municipalities(directory: Path, manifest: Manifest) -> SourceEntry:
    with tempfile.TemporaryDirectory() as tmp:
        got = download(ibge.MUNICIPALITIES_URL, Path(tmp) / "municipios.json")
        payload = json.loads(got.path.read_text(encoding="utf-8"))
    rows = ibge.normalize_municipalities(payload)
    path = directory / "ibge_municipalities.csv.gz"
    count = csvio.write_snapshot(path, ibge.MUNICIPALITY_COLUMNS, rows)
    retrieved = _now()
    entry = _entry(
        directory,
        path,
        ibge.MUNICIPALITY_COLUMNS,
        count,
        id="ibge_municipalities",
        table="raw.ibge_municipality",
        publisher="IBGE",
        source_url=ibge.MUNICIPALITIES_URL,
        documentation_url=ibge.MUNICIPALITIES_DOCS,
        license_note=IBGE_LICENSE_NOTE,
        citation=f"IBGE, API de Localidades (municípios, view=nivelado), acesso em {retrieved[:10]}.",
        retrieved_at=retrieved,
        snapshot_date=retrieved[:10],
        transform="Flattened view=nivelado; kept municipality, state and region codes and names.",
        source_last_modified=got.last_modified,
    )
    manifest.upsert(entry)
    return entry


def fetch_census(directory: Path, manifest: Manifest) -> SourceEntry:
    rows: list[csvio.Row] = []
    with tempfile.TemporaryDirectory() as tmp:
        for sex, code in sorted(ibge.CENSUS_SEX_CODES.items()):
            url = ibge.CENSUS_URL_TEMPLATE.format(sex_code=code)
            got = download(url, Path(tmp) / f"census_{sex}.json")
            rows.extend(
                ibge.normalize_census(json.loads(got.path.read_text(encoding="utf-8")), sex)
            )
    path = directory / "ibge_census2022_population_age0_6.csv.gz"
    count = csvio.write_snapshot(path, ibge.CENSUS_COLUMNS, ibge.sort_census(rows))
    retrieved = _now()
    entry = _entry(
        directory,
        path,
        ibge.CENSUS_COLUMNS,
        count,
        id="ibge_census2022_population_age0_6",
        table="raw.ibge_census_population",
        publisher="IBGE",
        source_url=ibge.CENSUS_URL_TEMPLATE.format(sex_code="{4,5}"),
        documentation_url=ibge.CENSUS_DOCS,
        license_note=IBGE_LICENSE_NOTE,
        citation="IBGE, Censo Demográfico 2022, SIDRA tabela 9514 (população residente, por sexo "
        "e idade).",
        retrieved_at=retrieved,
        snapshot_date="2022-08-01",
        transform="One SIDRA call per sex (50,000-value cap); ages 0-6; '-' read as 0.",
        source_last_modified=None,
    )
    manifest.upsert(entry)
    return entry


def _known_cnes_codes(directory: Path, manifest: Manifest) -> frozenset[str]:
    codes: set[str] = set()
    for entry in manifest.sources:
        if entry.table == "raw.cnes_unit_snapshot":
            _, rows = csvio.read_snapshot(directory / entry.path)
            codes.update(row["cnes_code"] for _, row in rows)
    return frozenset(codes)


def fetch_cnes(directory: Path, manifest: Manifest) -> SourceEntry:
    keep = _known_cnes_codes(directory, manifest)
    with tempfile.TemporaryDirectory() as tmp:
        got = download(cnes.EXTRACT_URL, Path(tmp) / "cnes.zip")
        snapshot_date = (got.last_modified or _now())[:10]
        rows = cnes.normalize_extract(cnes.iter_extract_rows(got.path), snapshot_date, keep)
    source_id = f"cnes_primary_care_{snapshot_date}"
    path = directory / f"{source_id}.csv.gz"
    count = csvio.write_snapshot(path, cnes.COLUMNS, rows)
    entry = _entry(
        directory,
        path,
        cnes.COLUMNS,
        count,
        id=source_id,
        table="raw.cnes_unit_snapshot",
        publisher="Ministério da Saúde",
        source_url=cnes.EXTRACT_URL,
        documentation_url=cnes.DATASET_URL,
        license_note=CNES_LICENSE_NOTE,
        citation="Ministério da Saúde, CNES - Cadastro Nacional de Estabelecimentos de Saúde, "
        f"extração de dados abertos de {snapshot_date}.",
        retrieved_at=_now(),
        snapshot_date=snapshot_date,
        transform=CNES_TRANSFORM,
        source_last_modified=got.last_modified,
    )
    manifest.upsert(entry)
    return entry


def convert_legacy_cnes(
    directory: Path, manifest: Manifest, legacy_csv: Path, snapshot_date: str
) -> SourceEntry:
    with legacy_csv.open(encoding="utf-8", newline="") as handle:
        rows = cnes.normalize_legacy(csv.DictReader(handle), snapshot_date)
    source_id = f"cnes_primary_care_{snapshot_date}"
    path = directory / f"{source_id}.csv.gz"
    count = csvio.write_snapshot(path, cnes.COLUMNS, rows)
    entry = _entry(
        directory,
        path,
        cnes.COLUMNS,
        count,
        id=source_id,
        table="raw.cnes_unit_snapshot",
        publisher="Ministério da Saúde",
        source_url="https://dados.gov.br/dados/conjuntos-dados/unidades-basicas-de-saude-ubs2",
        documentation_url=cnes.DATASET_URL,
        license_note=CNES_LICENSE_NOTE,
        citation="Ministério da Saúde, Unidades Básicas de Saúde (UBS), dados abertos; extraído "
        "em 2023 para a versão original deste projeto.",
        retrieved_at=_now(),
        snapshot_date=snapshot_date,
        transform="Converted from the project's 2023 unidade_basica.csv (provenance approximate: "
        "the UBS open dataset as of the first commit, 2023-06-02); CNES codes zero-padded; no "
        "unit type or coordinates in the source; every unit active.",
        source_last_modified=None,
    )
    manifest.upsert(entry)
    return entry
