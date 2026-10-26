"""Geometry snapshots: normalization (pure) and the contract of the vendored files (no network)."""

from __future__ import annotations

import gzip
import json
from pathlib import Path
from typing import Any

from vaccine_elt import config, csvio
from vaccine_elt.manifest import Manifest, SourceEntry, read_manifest, verify, write_manifest
from vaccine_elt.sources import ibge

# IBGE municipality meshes can carry the two lagoons of Rio Grande do Sul as extra features. The
# 2026-10-25 snapshot has neither; they stay allowed so a refresh that brings them still passes.
LAGOON_CODES = {"4300001", "4300002"}
# Created after Censo 2022: it is in the Localidades API but has no census rows and no polygon in
# the malhas v3 mesh yet (checked at `minima` and `intermediaria` quality on 2026-10-25).
MUNICIPALITIES_WITHOUT_GEOMETRY = {"5101837"}  # Boa Esperança do Norte, MT


def feature(code: object, coordinates: Any) -> dict[str, Any]:
    return {
        "type": "Feature",
        "properties": {"codarea": code, "extra": "dropped"},
        "geometry": {"type": "Polygon", "coordinates": coordinates},
    }


def test_normalization_is_deterministic_and_sorted() -> None:
    ring = [[[-41.909123, -13.311449], [-41.8694, -13.3474], [-41.9091, -13.3114]]]
    forward = {"type": "FeatureCollection", "features": [feature("2", ring), feature("1", ring)]}
    backward = {"type": "FeatureCollection", "features": forward["features"][::-1]}
    first, count = ibge.normalize_geojson(forward)
    assert count == 2
    assert first == ibge.normalize_geojson(backward)[0]
    document = json.loads(first)
    assert [f["properties"] for f in document["features"]] == [{"codarea": "1"}, {"codarea": "2"}]
    assert b" " not in first  # compact separators


def test_properties_are_stripped_and_codarea_is_a_string() -> None:
    data, _ = ibge.normalize_geojson(
        {"features": [feature(2900108, [[[0.1, 0.2], [0.3, 0.4], [0.1, 0.2]]])]}
    )
    (only,) = json.loads(data)["features"]
    assert only["properties"] == {"codarea": "2900108"}
    assert set(only) == {"type", "properties", "geometry"}


def test_coordinates_are_rounded_and_negative_zero_is_zero() -> None:
    data, _ = ibge.normalize_geojson(
        {"features": [feature("1", [[[-41.909123, -13.311449], [-0.00001, 0.00004]]])]}
    )
    assert b"[[[-41.9091,-13.3114],[0.0,0.0]]]" in data
    data3, _ = ibge.normalize_geojson(
        {"features": [feature("1", [[[-41.909123, -13.311449]]])]}, decimals=3
    )
    assert b"[[[-41.909,-13.311]]]" in data3


def test_gzip_is_byte_deterministic(tmp_path: Path) -> None:
    data, _ = ibge.normalize_geojson({"features": [feature("1", [[[1.5, 2.5]]])]})
    first, second = tmp_path / "a.geojson.gz", tmp_path / "b.geojson.gz"
    csvio.write_deterministic(first, data)
    csvio.write_deterministic(second, data)
    assert first.read_bytes() == second.read_bytes()
    assert gzip.decompress(first.read_bytes()) == data


def test_license_status_is_written_only_when_set(tmp_path: Path) -> None:
    def entry(entry_id: str, status: Any) -> SourceEntry:
        return SourceEntry(
            id=entry_id,
            path="x.gz",
            table=None,
            publisher="test",
            source_url="https://example.org",
            documentation_url="https://example.org",
            license_note="n",
            citation="c",
            retrieved_at="2026-10-25T00:00:00Z",
            snapshot_date="2026-10-25",
            transform="none",
            columns=["codarea", "geometry"],
            rows=1,
            sha256="0" * 64,
            license_status=status,
        )

    manifest = Manifest(sources=[entry("a", None), entry("b", "to_be_confirmed")])
    write_manifest(tmp_path, manifest)
    items = json.loads((tmp_path / "manifest.json").read_text(encoding="utf-8"))["sources"]
    assert "license_status" not in items[0]
    assert items[1]["license_status"] == "to_be_confirmed"
    assert read_manifest(tmp_path) == manifest


# --- contract of the vendored files -------------------------------------------------------------


def _features(path: Path) -> list[dict[str, Any]]:
    return json.loads(gzip.decompress(path.read_bytes()))["features"]  # type: ignore[no-any-return]


def test_vendored_geometries_match_their_manifest_and_ibge_municipalities() -> None:
    directory = config.sources_dir()
    manifest = read_manifest(directory)
    verify(directory, manifest)  # every sha256, geometries included

    geo = [e for e in manifest.sources if e.path.startswith("geo/")]
    assert all(e.table is None and e.license_status == "to_be_confirmed" for e in geo)
    assert all(e.columns == ["codarea", "geometry"] for e in geo)
    for e in geo:
        assert e.rows == len(_features(directory / e.path)), e.id

    _, rows = csvio.read_snapshot(directory / manifest.entry("ibge_municipalities").path)
    municipalities = list(rows)
    states = {row["state_code"]: row["state_abbr"].lower() for _, row in municipalities}
    assert len(states) == 27
    expected_ids = {"ibge_malha_uf"} | {f"ibge_malha_municipios_{a}" for a in states.values()}
    assert {e.id for e in geo} == expected_ids

    uf = _features(directory / manifest.entry("ibge_malha_uf").path)
    assert sorted(f["properties"]["codarea"] for f in uf) == sorted(states)
    assert len(uf) == 27

    codes: set[str] = set()
    for state_code, abbr in states.items():
        path = directory / manifest.entry(f"ibge_malha_municipios_{abbr}").path
        state_codes = [f["properties"]["codarea"] for f in _features(path)]
        assert state_codes == sorted(state_codes)
        assert len(state_codes) == len(set(state_codes))
        assert all(c.startswith(state_code) or c in LAGOON_CODES for c in state_codes), abbr
        codes.update(state_codes)
        assert len(gzip.decompress(path.read_bytes())) <= 600_000, f"{abbr} over 600 KB"

    expected = {row["municipality_code"] for _, row in municipalities}
    assert codes - LAGOON_CODES == expected - MUNICIPALITIES_WITHOUT_GEOMETRY
    assert sum((directory / e.path).stat().st_size for e in geo) <= 2_000_000
