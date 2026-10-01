#!/usr/bin/env bash
# Guard the JS connected-lane serial ownership harness against one-shot flock
# reclaim assertions (#2421).
#
# The current harness drives the real JS dispatcher and smoke lane with fake
# ADB/Gradle processes. Its cases pin same-serial exclusion, distinct-serial
# concurrency, exact-JUnit failure release, and lock reclamation after wrapper
# death. Every reclaim oracle must remain bounded and checked; the scanner also
# fails closed on non-blocking flock option spellings it cannot classify.
#
#   scripts/check-serial-flock-reclaim-probe.sh             # check the real tree
#   scripts/check-serial-flock-reclaim-probe.sh --list      # print the inventory
#   scripts/check-serial-flock-reclaim-probe.sh --self-test # prove it goes red
#
# No JVM, Gradle, emulator, or network; the source scan and mutation tests are
# hermetic. The guard is wired into AvdLockScriptTest in the JVM test suite.

set -euo pipefail

REPO_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

run_guard() {
  python3 - "${POCKETSHELL_FLOCK_PROBE_GUARD_ROOT:-$REPO_ROOT}" "${1:-check}" <<'PY'
from pathlib import Path
import re
import sys

root = Path(sys.argv[1]).resolve()
mode = sys.argv[2] if len(sys.argv) > 2 else "check"

CANONICAL = Path("tests/scripts/connected-test-serial-ownership-test.sh")
ORACLE = "wait_for_serial_flock_reclaim"

# Pinned inventory. Each number is a category that is ALLOWED to exist; pinning
# it means a new occurrence of a lookalike shape is a conscious, reviewed edit
# rather than something that slides in next to the real thing.
EXPECT_ORACLE_DEFINITIONS = 1
EXPECT_FIXTURE_PROBES = 0
EXPECT_BOUNDED_RETRIES = 0
EXPECT_ORACLE_CALL_SITES = 7

# The replacement harness has no intentional instantaneous probes: every
# reclaim check goes through the bounded oracle.
EXEMPT_ONE_SHOTS = {}

# Every case that owns a reclaim assertion must reach it through the oracle.
# Naming them individually is what makes "revert one site to a bare probe"
# produce a precise failure instead of only a count mismatch.
REQUIRED_ORACLE_CASES = (
    "same_serial_lanes_serialize_across_worktrees",
    "distinct_serial_lanes_run_concurrently",
    "red_junit_fails_closed_and_releases_the_serial",
    "killed_js_lane_wrapper_does_not_leave_gradle_holding_the_serial",
    "lifecycle_lane_background_children_do_not_inherit_the_serial_lock",
)

HEREDOC_START = re.compile(r"<<(?!<)-?\s*(['\"]?)([A-Za-z_][A-Za-z0-9_]*)\1")
FUNC_START = re.compile(r"^([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\{\s*$")
FUNC_END = re.compile(r"^\}\s*$")
FLOCK_TOKEN = re.compile(
    # Bare `flock` as a COMMAND WORD. The trailing `[.-]` exclusion matters now
    # that unparsable options fail closed: the harness is full of state files
    # named `$run_id.flock-probe-ready`, and `-probe-ready` is not an option
    # cluster. Under the old silent-skip behaviour those matches were harmless
    # noise; under fail-closed they would be false rejections.
    r"""(?<![\w$"'-])flock(?![\w.-])"""        # bare `flock`
    r"""|"?\$\{?REAL_FLOCK\}?"?"""             # "$REAL_FLOCK" / ${REAL_FLOCK}
    r"""|"?\$\{?real_flock\}?"?"""             # the fake-tool fixture's copy
)
RETRY_HEAD = re.compile(r"(?:^|;\s*)(?:until|while)\s+(?:!\s+)?$")
# A retry/oracle loop must have a terminal condition: a comparison against a
# bound AND an exit from the loop. "Poll until free" with neither is not a
# bounded retry, it is a hang that a test timeout turns into a mystery.
# Deliberately NOT a bare `>`/`<`: every loop body here redirects output, and a
# redirection is not a bound. Only real comparison operators count.
BOUND_COMPARISON = re.compile(r">=|<=|-ge|-gt|-lt|-le")
BOUND_ESCAPE = re.compile(r"\b(fail|exit|return|break)\b")

# util-linux flock(1)'s option grammar, canonicalised to long names. Short
# options bundle (`-nx` == `-n -x`), and a value-taking short option takes the
# rest of its cluster (`-w5`) or the next token (`-w 5`).
SHORT_OPTIONS = {
    "s": "--shared",
    "x": "--exclusive",
    "e": "--exclusive",
    "u": "--unlock",
    "n": "--nonblock",
    "w": "--timeout",
    "E": "--conflict-exit-code",
    "o": "--close",
    "c": "--command",
    "F": "--no-fork",
    "h": "--help",
    "V": "--version",
}
# Every long spelling flock REGISTERS, including the ones `flock --help` does
# not print: flock(1) documents "-n, --nb, --nonblock, --nonblocking", and all
# four were verified here to return instantly against a held lock (util-linux
# 2.39.3). Omitting an alias would leave a working banned probe unmodelled.
LONG_OPTIONS = {
    "--shared": "--shared",
    "--exclusive": "--exclusive",
    "--unlock": "--unlock",
    "--nonblock": "--nonblock",
    "--nonblocking": "--nonblock",
    "--nb": "--nonblock",
    "--timeout": "--timeout",
    "--wait": "--timeout",
    "--conflict-exit-code": "--conflict-exit-code",
    "--close": "--close",
    "--command": "--command",
    "--no-fork": "--no-fork",
    "--verbose": "--verbose",
    "--help": "--help",
    "--version": "--version",
}
TAKES_VALUE = {"--timeout", "--conflict-exit-code", "--command"}
NONBLOCK_OPTION = "--nonblock"
TIMEOUT_OPTION = "--timeout"
# A timeout the guard can PROVE is non-zero must be a literal number. `-w "$t"`
# is not provable — it may well be `0`, i.e. the banned probe in disguise — so
# it fails closed rather than being waved through as a bounded wait.
LITERAL_SECONDS = re.compile(r"""^["']?(\d+(?:\.\d+)?)["']?$""")

failures = []


def heredoc_body_lines(lines):
    """Indices of lines inside a heredoc body (fake-tool / fixture payloads)."""
    inside = set()
    delimiter = None
    for index, line in enumerate(lines):
        if delimiter is not None:
            if line.strip() == delimiter:
                delimiter = None
            else:
                inside.add(index)
            continue
        if line.lstrip().startswith("#"):
            continue
        match = HEREDOC_START.search(line)
        if match:
            delimiter = match.group(2)
    if delimiter is not None:
        failures.append(f"unterminated heredoc opened for delimiter {delimiter!r}")
    return inside


def function_spans(lines):
    """{name: (start_index, end_index_exclusive)} for top-level `name() {`."""
    spans = {}
    name = None
    start = 0
    for index, line in enumerate(lines):
        if name is None:
            match = FUNC_START.match(line)
            if match:
                name = match.group(1)
                start = index
            continue
        if FUNC_END.match(line):
            spans.setdefault(name, (start, index + 1))
            name = None
    return spans


def enclosing_function(spans, index):
    for name, (start, end) in spans.items():
        if start <= index < end:
            return name
    return "<top-level>"


def resolve_long(name):
    """getopt_long(3) resolution: exact match, else unambiguous abbreviation.

    flock accepts `--nonblo` and `--time 5` exactly as it accepts the full
    spellings, so the guard has to resolve them the same way or a real probe
    slips past under a shortened name. Unknown OR ambiguous (`--n` is both
    `--nonblock` and `--no-fork`, which real flock rejects) returns None, and
    the caller fails closed on it.
    """
    if name in LONG_OPTIONS:
        return LONG_OPTIONS[name]
    candidates = {
        canonical for spelling, canonical in LONG_OPTIONS.items() if spelling.startswith(name)
    }
    return candidates.pop() if len(candidates) == 1 else None


def parse_flags(rest):
    """Leading option cluster of a flock invocation -> (canonical flags, unknown).

    `unknown` is non-empty for anything this scanner cannot positively resolve;
    the caller must treat that as a rejection, never as "no flags".
    """
    tokens = rest.split()
    flags = {}
    unknown = []
    position = 0
    while position < len(tokens):
        token = tokens[position]
        position += 1
        if token == "--":
            break
        if not token.startswith("-") or token == "-":
            break  # the lock file/dir/FD operand: options end here
        if token.startswith("--"):
            name, separator, attached = token.partition("=")
            canonical = resolve_long(name)
            if canonical is None:
                unknown.append(token)
                continue
            if canonical in TAKES_VALUE:
                if separator:
                    flags[canonical] = attached
                elif position < len(tokens):
                    flags[canonical] = tokens[position]
                    position += 1
                else:
                    unknown.append(token)  # value-taking option with no value
            elif separator:
                unknown.append(token)  # value attached to a valueless option
            else:
                flags[canonical] = None
            continue
        cluster = token[1:]
        while cluster:
            letter, cluster = cluster[0], cluster[1:]
            canonical = SHORT_OPTIONS.get(letter)
            if canonical is None:
                unknown.append(f"-{letter}")
                continue
            if canonical not in TAKES_VALUE:
                flags[canonical] = None
                continue
            if cluster:  # `-w5`
                flags[canonical] = cluster
                cluster = ""
            elif position < len(tokens):  # `-w 5`
                flags[canonical] = tokens[position]
                position += 1
            else:
                unknown.append(f"-{letter}")
    return flags, unknown


def timeout_is_zero(value):
    """(resolvable, is_zero) for a `-w`/`--timeout` value."""
    match = LITERAL_SECONDS.match(value.strip())
    if not match:
        return False, False
    return True, float(match.group(1)) == 0.0


def loop_body(lines, head_index):
    """Lines from a loop head to its matching `done` at the same indentation."""
    head = lines[head_index]
    indent = len(head) - len(head.lstrip())
    body = []
    for line in lines[head_index + 1 :]:
        stripped = line.strip()
        if stripped.startswith("done") and (len(line) - len(line.lstrip())) <= indent:
            return body, True
        body.append(line)
    return body, False


def is_bounded(body):
    text = "\n".join(body)
    return bool(BOUND_COMPARISON.search(text)) and bool(BOUND_ESCAPE.search(text))


def classify(path, lines):
    """Every non-blocking flock probe in `path`, positively categorised."""
    relative = path.relative_to(root)
    heredoc = heredoc_body_lines(lines)
    spans = function_spans(lines)
    sites = []
    for index, line in enumerate(lines):
        if line.lstrip().startswith("#"):
            continue
        for match in FLOCK_TOKEN.finditer(line):
            flags, unknown = parse_flags(line[match.end() :])
            function = enclosing_function(spans, index)
            site = {
                "file": relative,
                "line": index + 1,
                "function": function,
                "text": line.strip(),
            }
            # --- fail closed: no "cannot parse it, so carry on" branch --------
            if unknown:
                site["category"] = "UNCLASSIFIED"
                sites.append(site)
                failures.append(
                    f"{relative}:{index + 1}: `{function}` invokes flock with option(s) this "
                    f"guard cannot be classified against flock(1)'s grammar: {' '.join(unknown)} "
                    "— an unrecognised option may spell a non-blocking probe (issue #2421). "
                    f"Offending line: {line.strip()}"
                )
                continue
            # `-n` beats `-w`: LOCK_NB makes the call return immediately no
            # matter what alarm(2) was armed for.
            nonblock = NONBLOCK_OPTION in flags
            timeout = flags.get(TIMEOUT_OPTION)
            if timeout is not None and not nonblock:
                resolvable, is_zero = timeout_is_zero(timeout)
                if not resolvable:
                    site["category"] = "UNCLASSIFIED"
                    sites.append(site)
                    failures.append(
                        f"{relative}:{index + 1}: `{function}` waits on a serial flock for a "
                        f"non-literal timeout ({timeout}) the guard cannot prove is non-zero — "
                        "`-w 0` is a bare one-shot probe in disguise; use a literal number of "
                        f"seconds, or {ORACLE} (issue #2421). Offending line: {line.strip()}"
                    )
                    continue
                if is_zero:
                    nonblock = True  # `-w 0` == `-n`
                else:
                    site["category"] = "BOUNDED_TIMEOUT"
                    sites.append(site)
                    continue
            if not nonblock:
                # Positively blocking: no `-n`, no zero timeout. Not the #2421
                # shape (it waits rather than returning a spurious failure).
                site["category"] = "BLOCKING" if flags else "NO_FLAGS"
                sites.append(site)
                continue
            prefix = line[: match.start()].lstrip()
            if index in heredoc:
                site["category"] = "FIXTURE"
            elif function == ORACLE:
                site["category"] = "ORACLE_BODY"
                body, closed = loop_body(lines, index)
                if not closed:
                    failures.append(
                        f"{relative}:{index + 1}: {ORACLE}'s poll loop has no matching `done`"
                    )
                elif not is_bounded(body):
                    failures.append(
                        f"{relative}:{index + 1}: {ORACLE} polls without a bound — it must "
                        "fail closed after a bounded window, not wait forever"
                    )
            elif RETRY_HEAD.search(prefix):
                site["category"] = "BOUNDED_RETRY"
                body, closed = loop_body(lines, index)
                if not closed:
                    failures.append(
                        f"{relative}:{index + 1}: retry loop in `{function}` has no matching `done`"
                    )
                elif not is_bounded(body):
                    site["category"] = "UNBOUNDED_RETRY"
                    failures.append(
                        f"{relative}:{index + 1}: `{function}` retries a serial flock probe with "
                        "no bound and no escape — an unbounded wait passes vacuously by hanging, "
                        "it does not fail closed"
                    )
            elif (function, line.strip()) in EXEMPT_ONE_SHOTS:
                site["category"] = "EXEMPT"
            else:
                site["category"] = "ONE_SHOT"
                failures.append(
                    f"{relative}:{index + 1}: `{function}` probes a serial flock with a bare "
                    f"single-shot `flock -n` — use `{ORACLE} \"$lock\" \"$pgid\" <seconds>` "
                    f"(issue #2421). Offending line: {line.strip()}"
                )
            sites.append(site)
    return sites


def oracle_call_sites(path, lines):
    relative = path.relative_to(root)
    spans = function_spans(lines)
    calls = []
    for index, line in enumerate(lines):
        if line.lstrip().startswith("#"):
            continue
        if not re.search(rf"(?<![\w]){ORACLE}\s", line):
            continue
        function = enclosing_function(spans, index)
        if function == ORACLE:
            continue
        # The whole statement: this line plus any backslash continuations.
        statement = line
        cursor = index
        while statement.rstrip().endswith("\\") and cursor + 1 < len(lines):
            cursor += 1
            statement += "\n" + lines[cursor]
        checked = "||" in statement or re.search(r"(^|;|\s)if\s", statement) or "!" in statement
        if not checked:
            failures.append(
                f"{relative}:{index + 1}: `{function}` calls {ORACLE} without checking it — "
                "an unchecked reclaim oracle asserts nothing"
            )
        calls.append({"file": relative, "line": index + 1, "function": function})
    return calls


# --- domain resolution -------------------------------------------------------
# Resolve by CONTENT, not by path alone, so renaming or splitting the harness
# cannot silently switch this guard off; and hard-fail when the canonical file
# is gone rather than reporting "nothing to check, all good".
domain = []
tests_root = root / "tests"
if tests_root.is_dir():
    for candidate in sorted(tests_root.rglob("*.sh")):
        if ORACLE in candidate.read_text(encoding="utf-8"):
            domain.append(candidate)

canonical = root / CANONICAL
if not canonical.exists():
    print(f"FAIL: serial-flock reclaim-probe guard (#2421): {CANONICAL} is missing")
    print("  - the harness moved or was deleted; repoint this guard's CANONICAL path")
    raise SystemExit(1)
if canonical not in domain:
    print(f"FAIL: serial-flock reclaim-probe guard (#2421): {CANONICAL} no longer uses {ORACLE}")
    print("  - the bounded-retry reclaim oracle was removed from the harness it protects")
    raise SystemExit(1)

inventory = []
oracle_calls = []
for path in domain:
    lines = path.read_text(encoding="utf-8").splitlines()
    inventory += classify(path, lines)
    oracle_calls += oracle_call_sites(path, lines)

canonical_lines = canonical.read_text(encoding="utf-8").splitlines()
canonical_spans = function_spans(canonical_lines)
canonical_calls = [c for c in oracle_calls if c["file"] == CANONICAL]
canonical_sites = [s for s in inventory if s["file"] == CANONICAL]


def count(category, sites=None):
    return sum(1 for site in (sites if sites is not None else inventory) if site["category"] == category)


if mode == "list":
    for site in inventory:
        print(f"{site['file']}:{site['line']}\t{site['category']}\t{site['function']}\t{site['text']}")
    for call in oracle_calls:
        print(f"{call['file']}:{call['line']}\tORACLE_CALL\t{call['function']}")
    raise SystemExit(0)

# --- pinned inventory --------------------------------------------------------
definitions = count("ORACLE_BODY", canonical_sites)
if definitions != EXPECT_ORACLE_DEFINITIONS:
    failures.append(
        f"{CANONICAL}: expected exactly {EXPECT_ORACLE_DEFINITIONS} {ORACLE} definition(s), "
        f"found {definitions}"
    )

fixtures = count("FIXTURE", canonical_sites)
if fixtures != EXPECT_FIXTURE_PROBES:
    failures.append(
        f"{CANONICAL}: expected exactly {EXPECT_FIXTURE_PROBES} fixture (heredoc) flock probe(s), "
        f"found {fixtures} — a new probe inside a fake-tool payload must be reviewed, not "
        "absorbed by the fixture exemption"
    )

retries = count("BOUNDED_RETRY", canonical_sites)
if retries != EXPECT_BOUNDED_RETRIES:
    failures.append(
        f"{CANONICAL}: expected exactly {EXPECT_BOUNDED_RETRIES} inline bounded-retry probe(s), "
        f"found {retries}"
    )

if len(canonical_calls) != EXPECT_ORACLE_CALL_SITES:
    failures.append(
        f"{CANONICAL}: expected exactly {EXPECT_ORACLE_CALL_SITES} {ORACLE} call site(s), found "
        f"{len(canonical_calls)} — the five current lane cases pin seven reclaim assertions"
    )

# --- the exemption table cannot rot -----------------------------------------
found_exemptions = {
    (site["function"], site["text"]) for site in inventory if site["category"] == "EXEMPT"
}
for key in EXEMPT_ONE_SHOTS:
    if key not in found_exemptions:
        failures.append(
            f"{CANONICAL}: exempted one-shot probe no longer exists: {key[0]} / {key[1]} — "
            "remove the stale entry from EXEMPT_ONE_SHOTS so the exemption list stays honest"
        )

# ...and cannot be stretched. The table is keyed by (function, exact line), so a
# COPY of an exempt line inside its own function would otherwise ride the same
# key for free. One entry buys exactly one probe.
exempt_sites = count("EXEMPT")
if exempt_sites != len(EXEMPT_ONE_SHOTS):
    failures.append(
        f"{CANONICAL}: {len(EXEMPT_ONE_SHOTS)} exemption entry/entries cover {exempt_sites} "
        "one-shot probe(s) — one entry exempts one probe; a duplicated exempt line is a new "
        "instantaneous assertion and needs its own reviewed entry (issue #2421)"
    )

# --- per-case coverage -------------------------------------------------------
calls_by_function = {}
for call in canonical_calls:
    calls_by_function.setdefault(call["function"], 0)
    calls_by_function[call["function"]] += 1

for case in REQUIRED_ORACLE_CASES:
    if case not in canonical_spans:
        failures.append(f"{CANONICAL}: required reclaim case `{case}` no longer exists")
    elif not calls_by_function.get(case):
        failures.append(
            f"{CANONICAL}: `{case}` no longer asserts serial reclaim through {ORACLE} — "
            "every reclaim assertion goes through the bounded-retry oracle (issue #2421)"
        )

if failures:
    print("FAIL: serial-flock reclaim-probe guard (#2421)")
    for failure in failures:
        print(f"  - {failure}")
    raise SystemExit(1)

print(
    "PASS: serial-flock reclaim-probe guard (#2421) — "
    f"{len(canonical_calls)} oracle call site(s), "
    f"{definitions} oracle definition(s), "
    f"{retries} bounded inline retry/retries, "
    f"{fixtures} fixture probe(s), "
    f"{count('EXEMPT')} exempted negative held-lock assertion(s), "
    f"{count('BOUNDED_TIMEOUT')} bounded `-w <n>` wait(s), "
    f"{count('UNCLASSIFIED')} unclassifiable flock invocation(s), "
    f"{count('ONE_SHOT')} bare one-shot probe(s) "
    f"across {len(domain)} harness file(s)."
)
PY
}

case "${1:---check}" in
  --check)
    run_guard check
    exit 0
    ;;
  --list)
    run_guard list
    exit 0
    ;;
  --self-test) ;;
  *)
    echo "usage: $(basename "$0") [--check|--list|--self-test]" >&2
    exit 2
    ;;
esac

# --- self-test ---------------------------------------------------------------
# Mutates a COPY of the real harness, so every red below is produced by the
# guard reading the production file shape, not a toy fixture that happens to
# match a regex.

fixture_root="$(mktemp -d)"
cleanup() {
  rm -rf -- "$fixture_root"
}
trap cleanup EXIT INT TERM

harness_rel="tests/scripts/connected-test-serial-ownership-test.sh"
mkdir -p "$fixture_root/tests/scripts"
pristine="$fixture_root/pristine.sh"
cp "$REPO_ROOT/$harness_rel" "$pristine"

reset_fixture() {
  cp "$pristine" "$fixture_root/$harness_rel"
}

# Replace an exact source line, and hard-fail if the mutation is a no-op —
# a self-test whose mutant never applied proves nothing (docs/ci-pitfalls.md).
# Several harness lines are byte-identical across cases, so an optional 4th
# argument disambiguates: a substring the FOLLOWING line must contain.
mutate() {
  local label="$1" original="$2" replacement="$3" next_contains="${4:-}"
  python3 - "$fixture_root/$harness_rel" "$original" "$replacement" "$label" "$next_contains" <<'MUTATE'
import sys

path, original, replacement, label, next_contains = sys.argv[1:6]
lines = open(path, encoding="utf-8").read().splitlines(keepends=True)
hits = [
    i
    for i, line in enumerate(lines)
    if line.rstrip("\n") == original
    and (not next_contains or (i + 1 < len(lines) and next_contains in lines[i + 1]))
]
if len(hits) != 1:
    raise SystemExit(
        f"FAIL: self-test mutation '{label}' is not live: expected exactly 1 line "
        f"== {original!r} (followed by {next_contains!r}), found {len(hits)}"
    )
lines[hits[0]] = replacement + "\n"
open(path, "w", encoding="utf-8").write("".join(lines))
MUTATE
}

SELFTEST_CHECKS=0

expect_pass() {
  local label="$1" output
  SELFTEST_CHECKS=$((SELFTEST_CHECKS + 1))
  if ! output="$(POCKETSHELL_FLOCK_PROBE_GUARD_ROOT="$fixture_root" run_guard check 2>&1)"; then
    printf '%s\n' "$output"
    echo "FAIL: guard self-test '$label' expected a pass but the guard went red"
    exit 1
  fi
  shift
  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf '%s\n' "$output"
      echo "FAIL: guard self-test '$label' missing expected report fragment: $needle"
      exit 1
    fi
  done
}

expect_fail() {
  local label="$1"
  SELFTEST_CHECKS=$((SELFTEST_CHECKS + 1))
  shift
  set +e
  local output
  output="$(POCKETSHELL_FLOCK_PROBE_GUARD_ROOT="$fixture_root" run_guard check 2>&1)"
  local rc=$?
  set -e
  if [[ "$rc" -eq 0 ]]; then
    printf '%s\n' "$output"
    echo "FAIL: guard self-test '$label' expected a rejection but the guard passed"
    exit 1
  fi
  local needle
  for needle in "$@"; do
    if [[ "$output" != *"$needle"* ]]; then
      printf '%s\n' "$output"
      echo "FAIL: guard self-test '$label' missing expected finding: $needle"
      exit 1
    fi
  done
}

# 0. Baseline. The JS-lane harness uses only the bounded reclaim oracle.
reset_fixture
expect_pass "unmutated JS-lane harness" \
  "7 oracle call site(s)" \
  "0 fixture probe(s)" \
  "0 exempted negative held-lock assertion(s)" \
  "0 bare one-shot probe(s)"

# 1. THE #2421 REGRESSION: convert one current case back to an instant probe.
reset_fixture
mutate "same-serial reclaim changed to one-shot" \
  '  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$second_pid" 3 \' \
  '  flock -n "$SANDBOX/avd-locks/avd-lock-emulator-5554" true \' \
  'same-serial lock remained held'
expect_fail "same-serial reclaim changed to one-shot" \
  "probes a serial flock with a bare single-shot" \
  "same_serial_lanes_serialize_across_worktrees"

# 2. A brand-new lane case with a bare probe is rejected, not hidden by counts.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'NEWCASE'

new_js_lane_one_shot_is_rejected() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock -n "$serial_lock" true || fail "new lane lost its serial flock"
}
NEWCASE
expect_fail "new JS lane case with a bare one-shot" \
  "probes a serial flock with a bare single-shot" \
  "new_js_lane_one_shot_is_rejected"

# 3. The reclaim oracle itself must remain bounded and fail closed.
reset_fixture
mutate "unbounded reclaim oracle" \
  '    if (( waited++ >= limit )); then' \
  '    if false; then'
expect_fail "unbounded reclaim oracle" \
  "wait_for_serial_flock_reclaim polls without a bound"

# 4. Deleting a checked reclaim assertion from a named case is rejected.
reset_fixture
mutate "same-serial reclaim assertion deleted" \
  '  wait_for_serial_flock_reclaim "$SANDBOX/avd-locks/avd-lock-emulator-5554" "$second_pid" 3 \' \
  '  true \' \
  'same-serial lock remained held'
expect_fail "same-serial reclaim assertion deleted" \
  "expected exactly 7 wait_for_serial_flock_reclaim call site(s), found 6" \
  "no longer asserts serial reclaim through wait_for_serial_flock_reclaim"

# 5. SPELLING EVASION A: bundled short options are still an immediate probe.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'BUNDLED'

bundled_short_option_is_still_a_one_shot() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock -nx "$serial_lock" true || fail "left its serial flock in a descendant"
}
BUNDLED
expect_fail "bundled -nx one-shot" \
  "probes a serial flock with a bare single-shot" \
  "bundled_short_option_is_still_a_one_shot"

# 6. A zero-second wait is `-n` with extra steps and loses the same race.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'ZERO'

zero_timeout_is_still_a_one_shot() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock -w 0 "$serial_lock" true || fail "left its serial flock in a descendant"
}
ZERO
expect_fail "-w 0 one-shot" \
  "probes a serial flock with a bare single-shot" \
  "zero_timeout_is_still_a_one_shot"

# 7. A positive literal timeout remains positively classified as bounded.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'BOUNDEDWAIT'

bounded_wait_is_not_a_one_shot_probe() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock -w 5 "$serial_lock" true || fail "bounded wait did not reclaim the serial"
}
BOUNDEDWAIT
expect_pass "house-idiom bounded wait stays allowed" \
  "1 bounded" \
  "0 bare one-shot probe(s)"

# 8. An undocumented long alias and getopt_long abbreviation remain detected.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'LONGALIAS'

abbreviated_long_alias_is_still_a_one_shot() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock --nonblo "$serial_lock" true || fail "left its serial flock in a descendant"
}
LONGALIAS
expect_fail "abbreviated --nonblock alias" \
  "probes a serial flock with a bare single-shot" \
  "abbreviated_long_alias_is_still_a_one_shot"

# 9. Unknown and ambiguous options fail closed instead of being waved through.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'UNKNOWNOPT'

unknown_option_is_not_waved_through() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554"
  flock --n "$serial_lock" true || fail "left its serial flock in a descendant"
}
UNKNOWNOPT
expect_fail "unclassifiable flock option" \
  "cannot be classified against flock(1)'s grammar" \
  "unknown_option_is_not_waved_through"

# 10. A timeout the guard cannot prove is non-zero is not assumed safe.
reset_fixture
cat >> "$fixture_root/$harness_rel" <<'VARTIMEOUT'

variable_timeout_is_not_assumed_bounded() {
  local serial_lock="$SANDBOX/avd-locks/avd-lock-emulator-5554" timeout="$2"
  flock -w "$timeout" "$serial_lock" true || fail "left its serial flock in a descendant"
}
VARTIMEOUT
expect_fail "non-literal flock timeout" \
  "the guard cannot prove is non-zero" \
  "variable_timeout_is_not_assumed_bounded"

# 11. The guard must never report "nothing to check" as success.
reset_fixture
rm -- "$fixture_root/$harness_rel"
expect_fail "harness deleted" "is missing"

reset_fixture
python3 - "$fixture_root/$harness_rel" <<'STRIP'
import sys

path = sys.argv[1]
text = open(path, encoding="utf-8").read()
if "wait_for_serial_flock_reclaim" not in text:
    raise SystemExit("FAIL: self-test mutation 'oracle stripped' is not live")
open(path, "w", encoding="utf-8").write(text.replace("wait_for_serial_flock_reclaim", "noop_probe"))
STRIP
expect_fail "oracle stripped from the harness" "no longer uses wait_for_serial_flock_reclaim"

# Restored fixture still passes: every red above came from the mutation.
reset_fixture
expect_pass "restored harness" "7 oracle call site(s)" "0 bare one-shot probe(s)"

# Issue #2113 anti-vacuity: an early `exit 0` reads exactly like a full pass, so
# pin the number of guard invocations this self-test actually made.
EXPECTED_SELFTEST_CHECKS=14
if (( SELFTEST_CHECKS != EXPECTED_SELFTEST_CHECKS )); then
  echo "FAIL: guard self-test ran $SELFTEST_CHECKS check(s), expected $EXPECTED_SELFTEST_CHECKS"
  exit 1
fi

echo "PASS: #2421 serial-flock reclaim-probe guard self-test rejects every one-shot regression shape ($SELFTEST_CHECKS checks)"
