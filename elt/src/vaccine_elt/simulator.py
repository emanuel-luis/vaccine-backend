"""Live dose simulator: posts one synthetic dose per tick to `POST /api/v1/doses`.

Draws come from the same inputs as the generator (vendored snapshots and the schedule seed), so a
valid dose is valid by construction: an active unit current in the latest CNES extract, and a birth
date that puts the age at the dose inside its schedule window. Stdlib HTTP only. This is one of the
two places allowed to read the wall clock (the other is ingest); everything is injectable for tests.
"""

from __future__ import annotations

import json
import logging
import random
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from itertools import accumulate
from pathlib import Path

from vaccine_elt.generator.core import LOCAL_TZ
from vaccine_elt.generator.inputs import Dose, ReferenceData

log = logging.getLogger("vaccine_elt.simulator")

TOKEN_MARGIN_S = 30.0  # refresh this long before `exp`
BACKOFF_START_S = 1.0
BACKOFF_MAX_S = 30.0
HEARTBEAT_SLICE_S = 10.0  # longest sleep without touching the heartbeat (healthcheck is 30 s)
SUMMARY_EVERY_S = 60.0
MAX_JITTER_DAYS = 14
HEARTBEAT = Path(tempfile.gettempdir()) / "simulator-alive"
RETRYABLE = frozenset({401, 429})

# (method, url, headers, body) -> (status, body). Raises OSError on network failures.
Transport = Callable[[str, str, Mapping[str, str], bytes], tuple[int, bytes]]


def urllib_transport(
    method: str, url: str, headers: Mapping[str, str], body: bytes
) -> tuple[int, bytes]:
    request = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read()
    except urllib.error.URLError as exc:
        raise OSError(str(exc.reason)) from exc


@dataclass
class Clock:
    now: Callable[[], datetime] = lambda: datetime.now(UTC)
    monotonic: Callable[[], float] = time.monotonic
    sleep: Callable[[float], None] = time.sleep


class Drawer:
    """Seeded draws of dose request bodies (dicts ready to be serialised)."""

    def __init__(
        self,
        reference: ReferenceData,
        schedule: list[Dose],
        *,
        seed: int | None,
        invalid_ratio: float,
    ) -> None:
        if not reference.municipalities or not schedule:
            raise ValueError("no municipality with a unit, or an empty schedule")
        self._reference = reference
        self._schedule = schedule
        self._cumulative = list(accumulate(m.age0 for m in reference.municipalities))
        self._invalid_ratio = invalid_ratio
        self._rng = random.Random(seed)

    def draw(self, now: datetime) -> dict[str, str]:
        rng = self._rng
        home = rng.choices(self._reference.municipalities, cum_weights=self._cumulative)[0]
        dose = rng.choice(self._schedule)
        age = min(
            max(dose.recommended_age_days + rng.randint(0, MAX_JITTER_DAYS), dose.min_age_days),
            dose.max_age_days,
        )
        today: date = now.astimezone(LOCAL_TZ).date()  # same zone as the API's administered_on
        body = {
            "personId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
            "birthDate": (today - timedelta(days=age)).isoformat(),
            "sex": "F" if rng.random() < home.age0_female / home.age0 else "M",
            "cnesCode": rng.choice(self._reference.units_by_municipality[home.code]),
            "vaccineCode": dose.vaccine_code,
            "doseLabel": dose.dose_label,
            "administeredAt": now.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        }
        if rng.random() < self._invalid_ratio:
            if rng.random() < 0.5:
                body["vaccineCode"] = "NOT_A_VACCINE"  # 422 unknown_vaccine_dose
            else:
                body["birthDate"] = (today + timedelta(days=1)).isoformat()  # 422 age_out_of_range
        return body


class TokenCache:
    """Client-credentials token, refreshed `TOKEN_MARGIN_S` before it expires and on demand."""

    def __init__(
        self, transport: Transport, clock: Clock, url: str, client_id: str, secret: str
    ) -> None:
        self._transport, self._clock = transport, clock
        self._url = url
        self._form = urllib.parse.urlencode(
            {"grant_type": "client_credentials", "client_id": client_id, "client_secret": secret}
        ).encode()
        self._token = ""
        self._expires_at = 0.0

    def get(self) -> str:
        if not self._token or self._clock.monotonic() >= self._expires_at - TOKEN_MARGIN_S:
            self._fetch()
        return self._token

    def invalidate(self) -> None:
        self._token = ""

    def _fetch(self) -> None:
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        status, raw = self._transport("POST", self._url, headers, self._form)
        if status != 200:
            raise OSError(f"token endpoint answered {status}")
        payload = json.loads(raw)
        self._token = payload["access_token"]
        self._expires_at = self._clock.monotonic() + float(payload["expires_in"])


@dataclass
class Simulator:
    drawer: Drawer
    tokens: TokenCache
    transport: Transport
    clock: Clock
    api_url: str
    rate: float
    heartbeat: Path = HEARTBEAT
    stats: Counter[str] = field(default_factory=Counter)  # since the last summary line
    totals: Counter[str] = field(default_factory=Counter)
    _last_summary: float = field(default=0.0, init=False)

    def run(self, max_doses: int | None = None) -> Counter[str]:
        """Post doses until `max_doses` (None: forever). Rate 0 only sleeps."""
        self._last_summary = self.clock.monotonic()
        sent, next_at = 0, self.clock.monotonic()
        while max_doses is None or sent < max_doses:
            self._touch()
            if self.rate <= 0:
                self._sleep(1.0)
                continue
            self._post(self.drawer.draw(self.clock.now()))
            sent += 1
            self._summarise()
            # Token bucket without catch-up: never burst after a stall.
            now = self.clock.monotonic()
            next_at = max(next_at + 1.0 / self.rate, now - 1.0)
            if next_at > now:
                self._sleep(next_at - now)
        self._summarise(force=True)
        return self.totals

    def _post(self, body: dict[str, str]) -> None:
        key = uuid.uuid4().hex  # fresh per dose, reused on every retry of this dose
        payload = json.dumps(body).encode()
        delay = BACKOFF_START_S
        while True:
            try:
                headers = {
                    "Authorization": f"Bearer {self.tokens.get()}",
                    "Content-Type": "application/json",
                    "Idempotency-Key": key,
                }
                status, _ = self.transport("POST", f"{self.api_url}/api/v1/doses", headers, payload)
            except OSError as exc:
                log.warning("dose post failed (%s), retrying in %.0f s", exc, delay)
            else:
                if status == 401:
                    self.tokens.invalidate()
                if status not in RETRYABLE and status < 500:
                    label = "accepted" if status == 201 else _label(status)
                    self.stats[label] += 1
                    self.totals[label] += 1
                    return
                log.warning("dose post answered %d, retrying in %.0f s", status, delay)
            self._sleep(delay)
            delay = min(delay * 2, BACKOFF_MAX_S)

    def _summarise(self, *, force: bool = False) -> None:
        now = self.clock.monotonic()
        if force or now - self._last_summary >= SUMMARY_EVERY_S:
            self._last_summary = now
            log.info("last minute: %s", dict(sorted(self.stats.items())) or "no doses")
            self.stats = Counter()

    def _touch(self) -> None:
        self.heartbeat.touch()

    def _sleep(self, seconds: float) -> None:
        while seconds > 0:
            self._touch()
            step = min(seconds, HEARTBEAT_SLICE_S)
            self.clock.sleep(step)
            seconds -= step


def _label(status: int) -> str:
    return "replayed" if status == 200 else f"rejected_{status}"
