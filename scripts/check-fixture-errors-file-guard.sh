#!/usr/bin/env bash
# check-fixture-errors-file-guard.sh — issue #2670
#
# THE HAZARD THIS EXISTS FOR
#
# ERRORS_FILE ($HOME/.pocketshell-fixture-session-errors.json) is fixture-side
# state SHARED by every journey in the unfiltered emulator container (#2474):
# journeys write it through AgentsFixture.writeFile and the fixture wrapper on
# the host reads it. It is not per-journey state — a row left behind by
# journey A is visible to journey B, and nothing compares "errors seen" against
# "errors a still-running journey wrote".
#
# Today J02SessionTreeListJourney is the only writer and it clears before use
# (`AgentsFixture.exec("rm -f $ERRORS_FILE")` in its seed); J04 and J14 also
# clear. Nothing FORCES that pairing. A journey that starts writing the errors
# file without clearing it — or a writer whose clear is later deleted — poisons
# every later journey in the shared container while everything stays green.
# That is exactly the failure shape #2586/#2596 guarded against through the
# retired seed-file predicate, reintroduced through the new mechanism.
#
# THE RULE
#
# Any androidTest journey whose CODE writes ERRORS_FILE must also CLEAR it (an
# `rm -f`/`rm -rf` of the file) in the same journey file.
#
#   - A "write" is a `writeFile(` call (any receiver, including a local helper
#     that forwards to AgentsFixture.writeFile — J10's helper shape) whose
#     FIRST argument targets the errors file. Both call shapes count from day
#     one: the identifier alias (`writeFile(ERRORS_FILE, ...)`) and the
#     literal/template path (`writeFile("$HOME/.pocketshell-fixture-session-
#     errors.json", ...)`, `writeFile("$ERRORS_FILE", ...)`). The literal-path
#     false negative is #2596's exact lesson.
#   - "Code" means comments/KDoc are stripped before matching. A prose-only
#     mention of the errors file never demands cleanup — the #2586
#     false-positive lesson.
#   - An alias is any `val`/`var` (with or without `const`) whose string
#     initializer contains the file's basename, so a renamed constant is still
#     tracked, and an identifier that merely CONTAINS an alias's name
#     (ERRORS_FILE_SUFFIX) is not.
#   - Per-file analysis on purpose: each journey declares its own fixture
#     constants and seeds its own state; no cross-file contract exists to
#     enforce today.
#
# --self-test additionally pins the MEASURED selection over the real
# androidTest tree (in-scope files, writers) so the selection is recorded and
# stable: a new reference is a conscious one-line baseline update in this
# script, not a silent behavior change.
#
#   scripts/check-fixture-errors-file-guard.sh              # check the real tree
#   scripts/check-fixture-errors-file-guard.sh --self-test  # red/green + pin
#
# No JVM, no Gradle, no Android SDK, no network. Runs in `guards-static`.

set -euo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
BASENAME=".pocketshell-fixture-session-errors.json"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

tracked_androidtest_kt() {
  # Tracked-only on purpose, like check-androidtest-compile-wiring.sh: an
  # untracked scratch file in a sibling worktree must not change this guard's
  # verdict, and CI checkouts contain tracked files only, so parity is exact.
  git -C "$1" ls-files |
    { grep -E '(^|/)src/androidTest/.*\.kt$' || true; }
}

# Emits one event per line for the file list given as trailing arguments:
#   REF   <path>        — code (non-comment) reference to the errors file
#   WRITE <path> <line> — a writeFile call whose first argument targets it
#   CLEAR <path> <line> — an `rm -f`/`rm -rf` of it
scan_events() {
  local root="$1"
  shift
  [ -d "$root" ] || fail "scan root not found: $root"
  [ "$#" -gt 0 ] || return 0
  python3 - "$root" "$BASENAME" "$@" <<'PY'
import re
import sys

root, basename = sys.argv[1], sys.argv[2]


def strip_comments(src):
    # Remove // and /* */ comments while PRESERVING string/char literal
    # contents: fixture calls live inside string literals
    # (`AgentsFixture.exec("rm -f $ERRORS_FILE")`), and a naive strip or a
    # naive match would each get one half of that wrong.
    out = []
    i, n = 0, len(src)
    state = "code"
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if state == "line":
            if c == "\n":
                state = "code"
                out.append(c)
            i += 1
        elif state == "block":
            if c == "*" and nxt == "/":
                state = "code"
                out.append(" ")
                i += 2
            else:
                if c == "\n":
                    out.append(c)
                i += 1
        elif state == "str":
            out.append(c)
            if c == "\\":
                out.append(nxt)
                i += 2
                continue
            if c == '"':
                state = "code"
            i += 1
        elif state == "chr":
            out.append(c)
            if c == "\\":
                out.append(nxt)
                i += 2
                continue
            if c == "'":
                state = "code"
            i += 1
        else:
            if c == "/" and nxt == "/":
                state = "line"
                i += 2
            elif c == "/" and nxt == "*":
                state = "block"
                i += 2
            elif c == '"':
                state = "str"
                out.append(c)
                i += 1
            elif c == "'":
                state = "chr"
                out.append(c)
                i += 1
            else:
                out.append(c)
                i += 1
    return "".join(out)


ALIAS_RE = re.compile(
    r'\b(?:const\s+)?(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n]+)?=\s*"((?:[^"\\]|\\.)*)"'
)


def first_arg(code, open_paren):
    # Text of the first argument: from just after `(` to the first top-level
    # comma or the closing paren, skipping nested parens and strings. Newlines
    # inside the call are fine — J02's write spans three lines.
    depth = 0
    i = open_paren
    n = len(code)
    buf = []
    while i < n:
        c = code[i]
        if c == '"':
            buf.append(c)
            i += 1
            while i < n and code[i] != '"':
                if code[i] == "\\":
                    buf.append(code[i])
                    i += 1
                buf.append(code[i])
                i += 1
            if i < n:
                buf.append('"')
                i += 1
            continue
        if c == "(":
            depth += 1
            i += 1
            continue
        if c == ")":
            if depth == 0:
                break
            depth -= 1
            i += 1
            continue
        if c == "," and depth == 0:
            break
        buf.append(c)
        i += 1
    return "".join(buf)


def line_of(code, pos):
    return code.count("\n", 0, pos) + 1


for rel in sys.argv[3:]:
    path = root + "/" + rel
    try:
        with open(path, encoding="utf-8") as fh:
            code = strip_comments(fh.read())
    except OSError:
        continue
    aliases = {name for name, init in ALIAS_RE.findall(code) if basename in init}
    # \b only around identifiers: the basename starts with a literal dot, and
    # a \b before a dot never matches (dot is a non-word char).
    parts = [r"\b" + re.escape(a) + r"\b" for a in sorted(aliases)]
    parts.append(re.escape(basename))
    target_re = re.compile("|".join(parts))
    refs, writes, clears = [], [], []
    for m in re.finditer(r"\bwriteFile\s*\(", code):
        if target_re.search(first_arg(code, m.end())):
            ln = line_of(code, m.start())
            writes.append(ln)
            refs.append(ln)
    for m in re.finditer(r"\brm\s+-\w*f\w*[^\n]*", code):
        if target_re.search(m.group(0)):
            ln = line_of(code, m.start())
            clears.append(ln)
            refs.append(ln)
    for m in ALIAS_RE.finditer(code):
        if basename in m.group(2):
            refs.append(line_of(code, m.start()))
    if refs:
        print("REF " + rel)
    for ln in writes:
        print("WRITE %s %d" % (rel, ln))
    for ln in clears:
        print("CLEAR %s %d" % (rel, ln))
PY
}

do_check() {
  local root="$1"
  git -C "$root" rev-parse --git-dir >/dev/null 2>&1 ||
    fail "\`$root\` is not a git checkout, so the tracked-journey scan could not run. 'I could not check' is not 'I checked and it is fine'."

  local files
  files="$(tracked_androidtest_kt "$root")"
  if [ -z "$files" ]; then
    fail "no tracked src/androidTest/*.kt journey sources found under $root — a guard that finds nothing to check must not report success."
  fi

  local events
  events="$(scan_events "$root" $(printf '%s\n' "$files"))"

  local writers scope
  writers="$(printf '%s\n' "$events" | awk '$1 == "WRITE" { print $2 }' | LC_ALL=C sort -u)"
  scope="$(printf '%s\n' "$events" | awk '$1 == "REF" { print $2 }' | LC_ALL=C sort)"

  local violations="" w ln
  while IFS= read -r w; do
    [ -n "$w" ] || continue
    if ! printf '%s\n' "$events" | awk -v w="$w" '$1 == "CLEAR" && $2 == w { found = 1 } END { exit !found }'; then
      ln="$(printf '%s\n' "$events" | awk -v w="$w" '$1 == "WRITE" && $2 == w { print $3; exit }')"
      violations="${violations}VIOLATION: ${root}/${w}:${ln} writes ERRORS_FILE (AgentsFixture.writeFile) but this journey never clears it.
  A row left in \$HOME/.pocketshell-fixture-session-errors.json poisons every later
  journey in the shared unfiltered container (#2474) while everything stays green —
  the failure shape #2586/#2596 guarded against.
  Fix: clear it in the journey's seed (AgentsFixture.exec(\"rm -f \$ERRORS_FILE\") —
  see J02SessionTreeListJourney.kt), or stop writing the file.
"
    fi
  done <<< "$writers"

  if [ -n "$violations" ]; then
    printf '%s' "$violations" >&2
    exit 1
  fi

  local n m k
  n="$(printf '%s\n' "$files" | wc -l | tr -d ' ')"
  m="$(printf '%s' "$scope" | { grep -c . || true; })"
  k="$(printf '%s' "$writers" | { grep -c . || true; })"
  echo "OK: $n tracked journey file(s) scanned."
  echo "    ERRORS_FILE in scope ($m): $(printf '%s' "$scope" | tr '\n' ' ')"
  echo "    ERRORS_FILE writers ($k): $(printf '%s' "$writers" | tr '\n' ' ')- every writer clears before use."
}

# --- self-test ---------------------------------------------------------------
# Synthetic journeys live in a throwaway git repo (tracked-only scan), and the
# real tree is pinned at the end so the measured selection stays recorded. The
# pass count is asserted, so the self-test cannot pass vacuously.

SELFTEST_EXPECTED_CASES=16
selftest_passed=0
selftest_failed=0
SANDBOX=""
ST_OUT=""
ST_RC=0

st_ok() {
  echo "  ok   $*"
  selftest_passed=$((selftest_passed + 1))
}

st_bad() {
  echo "  FAIL $*" >&2
  selftest_failed=$((selftest_failed + 1))
}

run_do_check() {
  set +e
  ST_OUT="$(do_check "$1" 2>&1)"
  ST_RC=$?
  set -e
}

expect_red() {
  local label="$1" want="$2" root="$3"
  run_do_check "$root"
  if [ "$ST_RC" -eq 0 ]; then
    st_bad "$label: guard stayed GREEN on the poisoned fixture"
    return
  fi
  case "$ST_OUT" in
    *"$want"*) st_ok "$label (red, named the right file)" ;;
    *) st_bad "$label: red, but for the wrong reason — wanted '$want', got:
$ST_OUT" ;;
  esac
}

expect_green() {
  local label="$1" root="$2"
  run_do_check "$root"
  if [ "$ST_RC" -eq 0 ]; then
    st_ok "$label (green)"
  else
    st_bad "$label: guard went RED on a compliant tree:
$ST_OUT"
  fi
}

expect_scope() {
  local label="$1" root="$2" want="$3" got
  got="$(scan_events "$root" $(tracked_androidtest_kt "$root") | awk '$1 == "REF" { print $2 }' | LC_ALL=C sort)"
  if [ "$got" = "$want" ]; then
    st_ok "$label"
  else
    st_bad "$label: scope mismatch — wanted:
$want
got:
$got"
  fi
}

expect_writers() {
  local label="$1" root="$2" want="$3" got
  got="$(scan_events "$root" $(tracked_androidtest_kt "$root") | awk '$1 == "WRITE" { print $2 }' | LC_ALL=C sort -u)"
  if [ "$got" = "$want" ]; then
    st_ok "$label"
  else
    st_bad "$label: writers mismatch — wanted:
$want
got:
$got"
  fi
}

add_fixture() {
  git -C "$SANDBOX" add -A
}

self_test() {
  SANDBOX="$(mktemp -d)"
  trap 'rm -rf "${SANDBOX:-}"' EXIT
  local fx="$SANDBOX/app2/src/androidTest/java/com/pocketshell/next"
  mkdir -p "$fx"

  cat > "$fx/JourneyOkIdentifier.kt" <<'EOF'
class JourneyOkIdentifier {
    companion object {
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.exec("rm -f $ERRORS_FILE")
        AgentsFixture.writeFile(
            ERRORS_FILE,
            "[{\"message\": \"boom\"}]",
        )
    }
}
EOF
  git -C "$SANDBOX" init -q
  git -C "$SANDBOX" config user.email selftest@example.invalid
  git -C "$SANDBOX" config user.name selftest
  add_fixture

  echo "== check-fixture-errors-file-guard self-test =="

  # Case 1-3 — the compliant shape (J02 today): alias + clear + multi-line
  # identifier write. Green, in scope, counted as a writer.
  expect_green   "1 identifier writer with clear"      "$SANDBOX"
  expect_scope   "2 identifier writer in scope"        "$SANDBOX" "app2/src/androidTest/java/com/pocketshell/next/JourneyOkIdentifier.kt"
  expect_writers "3 identifier writer detected"        "$SANDBOX" "app2/src/androidTest/java/com/pocketshell/next/JourneyOkIdentifier.kt"

  # Case 4 — THE HEADLINE CASE: a journey that writes ERRORS_FILE and never
  # clears it. This is the red-first proof on the guard's own terms (#2670).
  cat > "$fx/JourneyRedIdentifier.kt" <<'EOF'
class JourneyRedIdentifier {
    companion object {
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.writeFile(
            ERRORS_FILE,
            "[{\"message\": \"boom\"}]",
        )
    }
}
EOF
  add_fixture
  expect_red "4 identifier writer without clear" "JourneyRedIdentifier.kt" "$SANDBOX"
  rm "$fx/JourneyRedIdentifier.kt"
  add_fixture

  # Case 5 — the literal-path shape with NO alias at all. The #2596 false
  # negative: an identifier-only guard would stay green here.
  cat > "$fx/JourneyRedLiteral.kt" <<'EOF'
class JourneyRedLiteral {
    private fun seed() {
        AgentsFixture.writeFile(
            "$HOME/.pocketshell-fixture-session-errors.json",
            "[{\"message\": \"boom\"}]",
        )
    }
}
EOF
  add_fixture
  expect_red "5 literal-path writer without clear" "JourneyRedLiteral.kt" "$SANDBOX"
  rm "$fx/JourneyRedLiteral.kt"
  add_fixture

  # Case 6 — the template shape (`"$ERRORS_FILE"` inside the string).
  cat > "$fx/JourneyRedTemplate.kt" <<'EOF'
class JourneyRedTemplate {
    companion object {
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.writeFile("$ERRORS_FILE", "[{\"message\": \"boom\"}]")
    }
}
EOF
  add_fixture
  expect_red "6 template-path writer without clear" "JourneyRedTemplate.kt" "$SANDBOX"
  rm "$fx/JourneyRedTemplate.kt"
  add_fixture

  # Case 7 — a RENAMED alias. Tracking the constant's NAME instead of the file
  # path would stay green here.
  cat > "$fx/JourneyRedAlias.kt" <<'EOF'
class JourneyRedAlias {
    companion object {
        const val SESSION_ERRORS = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.writeFile(SESSION_ERRORS, "[{\"message\": \"boom\"}]")
    }
}
EOF
  add_fixture
  expect_red "7 renamed alias writer without clear" "JourneyRedAlias.kt" "$SANDBOX"
  rm "$fx/JourneyRedAlias.kt"
  add_fixture

  # Case 8 — a `var` (non-const) alias. Aliases are tracked by their string
  # initializer, not by declaration flavor, so this must red like case 7.
  cat > "$fx/JourneyRedVarAlias.kt" <<'EOF'
class JourneyRedVarAlias {
    companion object {
        var SESSION_ERRORS = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.writeFile(SESSION_ERRORS, "[{\"message\": \"boom\"}]")
    }
}
EOF
  add_fixture
  expect_red "8 var-alias writer without clear" "JourneyRedVarAlias.kt" "$SANDBOX"
  rm "$fx/JourneyRedVarAlias.kt"
  add_fixture

  # Case 9-10 — the #2586 false-positive lesson: prose-only mentions (line
  # comment + KDoc) never demand cleanup, and neither does a decoy identifier
  # that merely CONTAINS the alias's name. Green and OUT of scope.
  cat > "$fx/JourneyOkProse.kt" <<'EOF'
// Prose mention: $HOME/.pocketshell-fixture-session-errors.json is cleared by
// J02, so nothing here needs an rm -f. This comment must not create scope.
/**
 * KDoc prose: the fixture errors file .pocketshell-fixture-session-errors.json
 * is documented here, and that is all.
 */
class JourneyOkProse {
    companion object {
        const val ERRORS_FILE_SUFFIX = ".json"
    }

    private fun seed() {
        AgentsFixture.writeFile("$DIR/$TEXT_FILE$ERRORS_FILE_SUFFIX", "hello")
    }
}
EOF
  add_fixture
  expect_green "9 prose-only mention stays green" "$SANDBOX"
  expect_scope "10 prose-only mention is out of scope" "$SANDBOX" "app2/src/androidTest/java/com/pocketshell/next/JourneyOkIdentifier.kt"
  rm "$fx/JourneyOkProse.kt"
  add_fixture

  # Case 11-13 — the clear-only shape (J04/J14 today): declares the constant
  # and clears it, never writes. In scope, but NOT a writer.
  cat > "$fx/JourneyOkClearOnly.kt" <<'EOF'
class JourneyOkClearOnly {
    companion object {
        const val ERRORS_FILE = "\$HOME/.pocketshell-fixture-session-errors.json"
    }

    private fun seed() {
        AgentsFixture.exec("rm -f $ERRORS_FILE")
    }
}
EOF
  add_fixture
  expect_green   "11 clear-only journey stays green" "$SANDBOX"
  expect_scope   "12 clear-only journey in scope"    "$SANDBOX" "app2/src/androidTest/java/com/pocketshell/next/JourneyOkClearOnly.kt
app2/src/androidTest/java/com/pocketshell/next/JourneyOkIdentifier.kt"
  expect_writers "13 clear-only journey is not a writer" "$SANDBOX" "app2/src/androidTest/java/com/pocketshell/next/JourneyOkIdentifier.kt"

  # Case 14-15 — pin the MEASURED selection over the real androidTest tree so
  # it stays recorded and stable (J02/J04/J14 in scope; J03/J15/J10 out; J02
  # the only writer). A new reference reddens here and demands a conscious
  # one-line baseline update in this self-test, not a silent behavior change.
  local real_scope="app2/src/androidTest/java/com/pocketshell/next/tree/J02SessionTreeListJourney.kt
app2/src/androidTest/java/com/pocketshell/next/tree/J04CreateSessionJourney.kt
app2/src/androidTest/java/com/pocketshell/next/tree/J14StopSessionJourney.kt"
  expect_green   "14 real androidTest tree is compliant" "$ROOT_DIR"
  expect_scope   "15 real-tree scope pin (recorded selection)" "$ROOT_DIR" "$real_scope"
  expect_writers "16 real-tree writers pin (J02 only)" "$ROOT_DIR" "app2/src/androidTest/java/com/pocketshell/next/tree/J02SessionTreeListJourney.kt"

  echo
  echo "$selftest_passed passed, $selftest_failed failed"
  if [ "$selftest_failed" -ne 0 ]; then
    fail "self-test had $selftest_failed failing case(s)"
  fi
  if [ "$selftest_passed" -ne "$SELFTEST_EXPECTED_CASES" ]; then
    fail "self-test ran $selftest_passed case(s), expected exactly $SELFTEST_EXPECTED_CASES — a case was skipped or silently dropped"
  fi
  echo "OK: all $SELFTEST_EXPECTED_CASES self-test cases behaved as expected."
}

# --- entrypoint ---------------------------------------------------------------
MODE="check"
TARGET="$ROOT_DIR"
while [ $# -gt 0 ]; do
  case "$1" in
    --self-test) MODE="selftest"; shift ;;
    -h|--help)
      sed -n '2,60p' "${BASH_SOURCE[0]}"
      exit 0
      ;;
    *) fail "unknown argument: $1" ;;
  esac
done

case "$MODE" in
  selftest) self_test ;;
  check) do_check "$TARGET" ;;
esac
