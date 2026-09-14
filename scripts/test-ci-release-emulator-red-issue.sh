#!/usr/bin/env bash
# Self-test for scripts/ci-release-emulator-red-issue.sh (issue #2675).
#
# Same shape as scripts/test-ci-red-issue.sh (#2353): stubs `gh` on PATH so
# this runs with no network, no real repo, no authentication, and captures
# every `gh` invocation's exact args (NUL-separated, so a call is never
# mis-parsed by whitespace in --body). Covers: no existing tracking issue =>
# create carrying the marker, the red run URL, the triggering Tests run URL
# and the SHA; an existing open issue found via the marker search => comment
# (never a second create); a failed search still creates (fail toward
# over-notifying); a failed create is loud; missing gh is loud; missing
# required arguments exit 2.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-release-emulator-red-issue.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT
mkdir -p "$SANDBOX/bin"

# $1 = the issue number `issue list` should report as already-existing (empty
#      string = none found, so the target must create a new one).
write_fake_gh() {
  local existing="$1"
  cat > "$SANDBOX/bin/gh" <<FAKEGH
#!/usr/bin/env bash
n=\$(( \$(cat "$SANDBOX/call-count.txt" 2>/dev/null || echo 0) + 1 ))
echo "\$n" > "$SANDBOX/call-count.txt"
printf '%s\0' "\$@" > "$SANDBOX/call-\$n.args"
if [[ "\${FAKE_GH_ISSUE_LIST_FAIL:-}" == "1" && "\$1 \$2" == "issue list" ]]; then exit 1; fi
if [[ "\${FAKE_GH_CREATE_FAIL:-}" == "1" && "\$1 \$2" == "issue create" ]]; then exit 1; fi
case "\$1 \$2" in
  "issue list") echo '$existing' ;;
  "issue comment") echo "commented" ;;
  "issue create") echo "https://github.com/owner/repo/issues/999" ;;
esac
FAKEGH
  chmod +x "$SANDBOX/bin/gh"
}

read_call_args() {
  local _rca_n="$1" _rca_outvar="$2"
  local -a _rca_accum=()
  local _rca_a
  while IFS= read -r -d '' _rca_a; do
    _rca_accum+=("$_rca_a")
  done < "$SANDBOX/call-$_rca_n.args"
  eval "$_rca_outvar=(\"\${_rca_accum[@]}\")"
}

arg_value_after() {
  local n="$1" flag="$2"
  local -a args=()
  read_call_args "$n" args
  local i
  for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[$i]}" == "$flag" ]]; then
      printf '%s' "${args[$((i + 1))]}"
      return 0
    fi
  done
  return 1
}

reset_sandbox_calls() { rm -f "$SANDBOX"/call-*.args "$SANDBOX/call-count.txt"; }

RUN_URL="https://github.com/owner/repo/actions/runs/34759080447"
SHA="1a460a273deadbeef"

# -----------------------------------------------------------------------
# 1. No existing tracking issue => creates one with marker, run URL and SHA.
reset_sandbox_calls
write_fake_gh ""
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" \
  --repo owner/repo --run-url "$RUN_URL" --sha "$SHA" 2>&1)"
echo "$out" | grep -q "Created tracking issue" || fail "expected a create when no existing issue is found: $out"
calls="$(cat "$SANDBOX/call-count.txt")"
[[ "$calls" -eq 2 ]] || fail "expected exactly 2 gh calls (list, create), got $calls"
create_body="$(arg_value_after 2 --body)" || fail "create call must carry --body"
echo "$create_body" | grep -q "pocketshell-release-emulator-red-marker" || fail "created body must include the stable marker"
echo "$create_body" | grep -qF "$RUN_URL" || fail "created body must include the red run URL"
echo "$create_body" | grep -qF "$SHA" || fail "created body must include the SHA under validation"
create_title="$(arg_value_after 2 --title)" || fail "create call must carry --title"
echo "$create_title" | grep -q "issue #2675" || fail "created title must reference issue #2675: $create_title"
pass "no existing issue creates a new tracking issue with marker, run URL and SHA"

# -----------------------------------------------------------------------
# 2. Existing open issue found => comments on it, never creates.
reset_sandbox_calls
write_fake_gh "42"
out="$(PATH="$SANDBOX/bin:$PATH" "$TARGET" \
  --repo owner/repo --run-url "$RUN_URL" --sha "$SHA" 2>&1)"
echo "$out" | grep -q "Commented on existing tracking issue #42" || fail "expected a comment on the existing issue: $out"
calls="$(cat "$SANDBOX/call-count.txt")"
[[ "$calls" -eq 2 ]] || fail "expected exactly 2 gh calls (list, comment), got $calls"
read_call_args 2 comment_args
# shellcheck disable=SC2154  # assigned via read_call_args' eval outvar
[[ "${comment_args[2]}" == "42" ]] || fail "must comment on issue #42, args were: ${comment_args[*]}"
pass "existing open issue found by marker search gets a comment, not a second issue"

# -----------------------------------------------------------------------
# 3. gh issue list fails => still creates (never silently drop the signal).
reset_sandbox_calls
write_fake_gh ""
out="$(PATH="$SANDBOX/bin:$PATH" FAKE_GH_ISSUE_LIST_FAIL=1 "$TARGET" \
  --repo owner/repo --run-url "$RUN_URL" --sha "$SHA" 2>&1)"
echo "$out" | grep -q "Created tracking issue" || fail "a failed search must still result in a create: $out"
pass "a failed issue-list search still creates a tracking issue rather than dropping the notification"

# -----------------------------------------------------------------------
# 4. gh issue create fails => loud non-zero exit.
reset_sandbox_calls
write_fake_gh ""
set +e
out="$(PATH="$SANDBOX/bin:$PATH" FAKE_GH_CREATE_FAIL=1 "$TARGET" \
  --repo owner/repo --run-url "$RUN_URL" --sha "$SHA" 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "a failed gh issue create must exit non-zero"
echo "$out" | grep -qi "failed to create" || fail "a failed gh issue create must say so: $out"
pass "a failed gh issue create surfaces loudly"

# -----------------------------------------------------------------------
# 5. gh CLI missing entirely => loud non-zero exit.
set +e
out="$(bash "$TARGET" --repo owner/repo --run-url u --sha "$SHA" --gh does-not-exist-gh-binary-xyz 2>&1)"
rc=$?
set -e
[[ "$rc" -ne 0 ]] || fail "missing gh must exit non-zero"
echo "$out" | grep -qi "gh CLI not found" || fail "missing gh must say so: $out"
pass "missing gh CLI surfaces loudly"

# -----------------------------------------------------------------------
# 6. Missing required arguments => usage error, exit 2.
set +e
bash "$TARGET" --repo owner/repo >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing required arguments must exit 2, got $rc"
pass "missing required arguments exits 2"

echo "OK: $pass_count self-test case(s) passed."
