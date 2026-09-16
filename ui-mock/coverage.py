#!/usr/bin/env python3
"""Destination render-coverage inventory for the ui-mock render tool.

Destinations come from the sealed graph's `Destination.all` list in app2's
nav/Destinations.kt; render cases come from the same catalog `serve.py --list`
prints. The report pairs every destination with the render cases covering it,
or GAP, so missing mock coverage is listed rather than silently called
complete (issue #2636, acceptance 2). Stdlib only.
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re

from catalog import Case, code_mask, discover

DESTINATIONS_KT = Path("app2/src/main/java/com/pocketshell/next/nav/Destinations.kt")

ALL_LIST = re.compile(r"val all:\s*List<Destination>\s*get\(\)\s*=\s*listOf\(([^)]*)\)")
DECLARATION = re.compile(r'data object\s+(\w+)\s*:\s*Destination\(\s*"([^"]*)"')
NAME = re.compile(r"[A-Z]\w*")

# Word aliases for render sources whose screen does not spell the destination
# name; each entry is proven by the nav wiring in MainActivity, not guessed:
# - `composable(Destination.Ports.pattern)` renders servicesScreen;
# - "session tree" is the legacy vocabulary for the workspace detail (the
#   old tree screen was removed in #2726), so Destination.Tree aliases
#   Workspace to those words.
ALIASES: dict[str, tuple[str, ...]] = {
    "Ports": ("services",),
    "Workspace": ("session", "tree"),
}

# Destinations rendered by the exact same composable as another destination,
# so they inherit its coverage when they claim nothing themselves:
# hostUsageScreen = UsageRoute(onBack, selectedHostId = hostId).
SAME_SCREEN: dict[str, str] = {"HostUsage": "Usage"}

# Render classes for flows that are not navigation destinations at all. Their
# labels can still name a destination incidentally — the share picker's
# "no hosts" state is not the Hosts screen — so they are never attributed.
NON_DESTINATION = ("ShareScreen",)


@dataclass(frozen=True)
class Destination:
    name: str
    route: str


def words(text: str) -> list[str]:
    """Split camelCase, kebab-case and snake_case text into lowercase words."""
    camel = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", text)
    return [word for word in re.split(r"[^a-z0-9]+", camel.lower()) if word]


def parse_destinations(source: str) -> list[Destination]:
    """Read the `all` list in graph order; routes from the object declarations."""
    block = ALL_LIST.search(code_mask(source))
    if not block:
        raise ValueError("Destinations.kt: no `val all: List<Destination>` list found")
    names = list(dict.fromkeys(NAME.findall(block.group(1))))
    routes = dict(DECLARATION.findall(source))
    return [Destination(name, routes.get(name, "")) for name in names]


def stem(word: str) -> str:
    """Naive plural fold, so destination `Hosts` also matches case word `host`."""
    return word[:-1] if len(word) > 3 and word.endswith("s") else word


def case_words(case: Case) -> list[str]:
    class_stem = case.class_name.rsplit(".", 1)[-1]
    if class_stem.endswith("Renders"):
        class_stem = class_stem[: -len("Renders")]
    return words(" ".join([class_stem, case.method, case.label]))


def word_sets(destination: Destination) -> tuple[tuple[str, ...], ...]:
    candidates = [words(destination.name)]
    alias = ALIASES.get(destination.name)
    if alias:
        candidates.append(alias)
    return tuple(candidates)


def matches(word_set: tuple[str, ...], available: list[str]) -> tuple[bool, int]:
    """Every word must hit; score counts exact hits (stems score zero)."""
    exact = 0
    for word in word_set:
        if word in available:
            exact += 1
        elif stem(word) not in available:
            return False, 0
    return True, exact


def attribute(
    destinations: list[Destination], cases: list[Case]
) -> tuple[dict[str, list[Case]], list[Case], list[str]]:
    """Assign each case to its strongest destination; ties keep graph order.

    Returns per-destination cases, cases that match no destination, and the
    SAME_SCREEN destinations whose coverage was inherited.
    """
    vocab = [(destination, word_sets(destination)) for destination in destinations]
    claimed: dict[str, list[Case]] = {destination.name: [] for destination in destinations}
    unclaimed: list[Case] = []
    for case in cases:
        class_stem = case.class_name.rsplit(".", 1)[-1]
        if any(class_stem.startswith(prefix) for prefix in NON_DESTINATION):
            unclaimed.append(case)
            continue
        available = case_words(case)
        best: tuple[tuple[int, int], Destination] | None = None
        for destination, candidates in vocab:
            for candidate in candidates:
                hit, exact = matches(candidate, available)
                if hit and (best is None or (exact, len(candidate)) > best[0]):
                    best = ((exact, len(candidate)), destination)
        if best is None:
            unclaimed.append(case)
        else:
            claimed[best[1].name].append(case)
    inherited = []
    for name, original in SAME_SCREEN.items():
        if name in claimed and not claimed[name] and claimed.get(original):
            claimed[name] = list(claimed[original])
            inherited.append(name)
    return claimed, unclaimed, inherited


def qualified(case: Case) -> str:
    return f"{case.class_name.rsplit('.', 1)[-1]}.{case.method}"


def report(
    destinations: list[Destination],
    claimed: dict[str, list[Case]],
    unclaimed: list[Case],
    inherited: list[str],
) -> str:
    covered = sum(1 for destination in destinations if claimed[destination.name])
    width = max(len(destination.name) for destination in destinations)
    lines = [f"destination render coverage: {covered}/{len(destinations)} destinations covered"]
    for destination in destinations:
        entry = ", ".join(qualified(case) for case in claimed[destination.name]) or "GAP"
        lines.append(f"{destination.name.ljust(width)}  {entry}")
    gaps = [destination.name for destination in destinations if not claimed[destination.name]]
    lines.append("")
    if gaps:
        lines.append(f"uncovered destinations ({len(gaps)}): " + ", ".join(gaps))
    if inherited:
        via = ", ".join(f"{name} = {SAME_SCREEN[name]}" for name in inherited)
        lines.append(f"same-screen coverage (inherited): {via}")
    if unclaimed:
        names = ", ".join(qualified(case) for case in unclaimed)
        lines.append(f"render cases with no navigation destination ({len(unclaimed)}): {names}")
    return "\n".join(lines)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    source = (root / DESTINATIONS_KT).read_text(encoding="utf-8")
    destinations = parse_destinations(source)
    cases, _warnings = discover(root)
    claimed, unclaimed, inherited = attribute(destinations, cases)
    print(report(destinations, claimed, unclaimed, inherited))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
