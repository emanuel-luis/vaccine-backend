"""The data manifest: one entry per vendored snapshot, with provenance, license note and sha256.

`load-reference` refuses to load a file whose bytes do not match its manifest entry."""

from __future__ import annotations

import hashlib
import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

MANIFEST_NAME = "manifest.json"


class ManifestError(Exception):
    """The manifest is missing, malformed, or does not match the files on disk."""


@dataclass(frozen=True)
class SourceEntry:
    id: str
    path: str
    table: str
    publisher: str
    source_url: str
    documentation_url: str
    license_note: str
    citation: str
    retrieved_at: str
    snapshot_date: str
    transform: str
    columns: list[str]
    rows: int
    sha256: str
    source_last_modified: str | None = None


@dataclass
class Manifest:
    version: int = 1
    sources: list[SourceEntry] = field(default_factory=list)

    def entry(self, source_id: str) -> SourceEntry:
        for entry in self.sources:
            if entry.id == source_id:
                return entry
        raise ManifestError(f"manifest has no source {source_id!r}")

    def upsert(self, entry: SourceEntry) -> None:
        self.sources = sorted(
            [existing for existing in self.sources if existing.id != entry.id] + [entry],
            key=lambda item: item.id,
        )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_manifest(directory: Path) -> Manifest:
    path = directory / MANIFEST_NAME
    if not path.exists():
        raise ManifestError(f"{path} not found")
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
        sources = [SourceEntry(**item) for item in payload["sources"]]
        return Manifest(version=int(payload["version"]), sources=sources)
    except (KeyError, TypeError, ValueError) as exc:
        raise ManifestError(f"{path} is malformed: {exc}") from exc


def write_manifest(directory: Path, manifest: Manifest) -> Path:
    path = directory / MANIFEST_NAME
    payload = {
        "version": manifest.version,
        "sources": [asdict(entry) for entry in manifest.sources],
    }
    path.write_text(json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path


def verify(directory: Path, manifest: Manifest) -> None:
    """Raise ManifestError listing every file that is missing or whose sha256 differs."""
    problems = []
    for entry in manifest.sources:
        path = directory / entry.path
        if not path.exists():
            problems.append(f"{entry.id}: {entry.path} is missing")
        elif (actual := sha256_file(path)) != entry.sha256:
            problems.append(f"{entry.id}: sha256 {actual} != manifest {entry.sha256}")
    if problems:
        raise ManifestError("source files do not match the manifest: " + "; ".join(problems))
