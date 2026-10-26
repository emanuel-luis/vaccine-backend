"""Simulator tests: no network, no sleeping. A fake transport plays the API and Keycloak, a fake
clock advances only when the simulator sleeps."""

from __future__ import annotations

import json
from collections.abc import Mapping
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

import pytest

from vaccine_elt.generator.core import LOCAL_TZ
from vaccine_elt.generator.inputs import Municipality, ReferenceData, load_schedule, schedule_path
from vaccine_elt.simulator import Clock, Drawer, Simulator, TokenCache

SCHEDULE = load_schedule(schedule_path())
DOSES = {(d.vaccine_code, d.dose_label): d for d in SCHEDULE}
NOW = datetime(2026, 11, 2, 15, 30, tzinfo=UTC)
REFERENCE = ReferenceData(
    municipalities=[
        Municipality(2111300, "MA", 7000, 7300),
        Municipality(5300108, "DF", 18000, 18500),
    ],
    units_by_municipality={2111300: ["0000001", "0000002"], 5300108: ["0000006"]},
    national_age0=2_358_265,
    next_snapshot_date=None,
)
UNITS = {u for units in REFERENCE.units_by_municipality.values() for u in units}


class FakeWorld:
    """Fake clock plus a scripted API: `statuses` are consumed per dose POST (default 201)."""

    def __init__(self, statuses: list[int | OSError] | None = None, expires_in: int = 300) -> None:
        self.t = 1000.0
        self.statuses = statuses or []
        self.expires_in = expires_in
        self.token_calls = 0
        self.sleeps: list[float] = []
        self.posts: list[tuple[str, str, dict[str, object]]] = []  # (key, bearer, body)

    def clock(self) -> Clock:
        return Clock(
            now=lambda: NOW + timedelta(seconds=self.t), monotonic=lambda: self.t, sleep=self.sleep
        )

    def sleep(self, seconds: float) -> None:
        self.sleeps.append(seconds)
        self.t += seconds

    def transport(
        self, method: str, url: str, headers: Mapping[str, str], body: bytes
    ) -> tuple[int, bytes]:
        if url.endswith("/token"):
            self.token_calls += 1
            token = f"token-{self.token_calls}"
            return 200, json.dumps({"access_token": token, "expires_in": self.expires_in}).encode()
        self.posts.append((headers["Idempotency-Key"], headers["Authorization"], json.loads(body)))
        outcome = self.statuses.pop(0) if self.statuses else 201
        if isinstance(outcome, OSError):
            raise outcome
        return outcome, b"{}"

    def simulator(
        self, tmp_path: Path, *, rate: float = 2, invalid_ratio: float = 0.0
    ) -> Simulator:
        clock = self.clock()
        return Simulator(
            Drawer(REFERENCE, SCHEDULE, seed=7, invalid_ratio=invalid_ratio),
            TokenCache(self.transport, clock, "http://kc/token", "vaccine-simulator", "s"),
            self.transport,
            clock,
            "http://api",
            rate,
            heartbeat=tmp_path / "alive",
        )


def test_valid_draws_fit_the_schedule_and_the_reference(tmp_path: Path) -> None:
    world = FakeWorld()
    world.simulator(tmp_path).run(max_doses=400)
    assert len(world.posts) == 400
    today = (NOW + timedelta(seconds=1000)).astimezone(LOCAL_TZ).date()
    persons = set()
    for _, _, body in world.posts:
        dose = DOSES[(str(body["vaccineCode"]), str(body["doseLabel"]))]
        age = (today - date.fromisoformat(str(body["birthDate"]))).days
        assert dose.min_age_days <= age <= dose.max_age_days
        assert body["cnesCode"] in UNITS
        assert body["sex"] in ("F", "M")
        persons.add(body["personId"])
    assert len(persons) == 400  # one new person per dose


def test_invalid_ratio_and_zero(tmp_path: Path) -> None:
    for ratio, expected in ((0.0, 0), (1.0, 300)):
        world = FakeWorld()
        world.simulator(tmp_path, invalid_ratio=ratio).run(max_doses=300)
        today = (NOW + timedelta(seconds=1000)).astimezone(LOCAL_TZ).date()
        bad = sum(
            body["vaccineCode"] == "NOT_A_VACCINE"
            or date.fromisoformat(str(body["birthDate"])) > today
            for _, _, body in world.posts
        )
        assert bad == expected
    world = FakeWorld()
    world.simulator(tmp_path, invalid_ratio=0.2).run(max_doses=2000)
    today = (NOW + timedelta(seconds=1000)).astimezone(LOCAL_TZ).date()
    bad = sum(
        body["vaccineCode"] == "NOT_A_VACCINE" or date.fromisoformat(str(body["birthDate"])) > today
        for _, _, body in world.posts
    )
    assert 300 < bad < 500  # about 20 % of 2000


def test_same_key_on_retry_and_fresh_key_per_dose(tmp_path: Path) -> None:
    world = FakeWorld([503, OSError("reset"), 201, 201])
    totals = world.simulator(tmp_path).run(max_doses=2)
    keys = [key for key, _, _ in world.posts]
    assert keys[0] == keys[1] == keys[2] != keys[3]
    assert world.posts[0][2] == world.posts[1][2] == world.posts[2][2]
    assert totals == {"accepted": 2}
    assert world.sleeps[:2] == [1.0, 2.0]  # exponential backoff on the retries


def test_backoff_is_capped_and_heartbeat_touched(tmp_path: Path) -> None:
    world = FakeWorld([503] * 8)
    world.simulator(tmp_path).run(max_doses=1)
    # 1, 2, 4, 8, 16, then 30 s (sliced in steps of at most 10 s so the heartbeat stays fresh).
    assert sum(world.sleeps) == 1 + 2 + 4 + 8 + 16 + 30 + 30 + 30
    assert all(s <= 10.0 for s in world.sleeps)
    assert (tmp_path / "alive").exists()


def test_token_refreshed_on_401_and_before_expiry(tmp_path: Path) -> None:
    world = FakeWorld([401, 201], expires_in=100)
    sim = world.simulator(tmp_path, rate=0.5)
    sim.run(max_doses=1)
    assert [bearer for _, bearer, _ in world.posts] == ["Bearer token-1", "Bearer token-2"]
    assert world.posts[0][0] == world.posts[1][0]
    # 100 s lifetime, refreshed 30 s early: the next dose is 2 s later, so the token is reused...
    sim.run(max_doses=1)
    assert world.posts[-1][1] == "Bearer token-2"
    # ...but after 80 s it is replaced.
    world.t += 80
    sim.run(max_doses=1)
    assert world.posts[-1][1] == "Bearer token-3"


def test_rejects_are_counted_not_retried(tmp_path: Path) -> None:
    world = FakeWorld([422, 200, 409, 201])
    totals = world.simulator(tmp_path).run(max_doses=4)
    assert len(world.posts) == 4
    assert totals == {"rejected_422": 1, "replayed": 1, "rejected_409": 1, "accepted": 1}


def test_rate_limits_and_zero_pauses(tmp_path: Path) -> None:
    world = FakeWorld()
    world.simulator(tmp_path, rate=4).run(max_doses=5)
    assert sum(world.sleeps) == pytest.approx(1.25)  # 5 doses at 4/s: no burst, no catch-up

    class Stop(Exception):
        pass

    paused = FakeWorld()
    sim = paused.simulator(tmp_path, rate=0)

    def stop_after_three(seconds: float) -> None:
        paused.sleeps.append(seconds)
        if len(paused.sleeps) == 3:
            raise Stop

    sim.clock.sleep = stop_after_three
    with pytest.raises(Stop):
        sim.run()
    assert paused.posts == [] and paused.token_calls == 0
