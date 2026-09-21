#!/usr/bin/env bash
# scripts/ci-app2-changed-modules.sh — rewrite task M-2.
#
# Per-JOB path filtering for .github/workflows/app2.yml.
#
# GitHub's `on.<event>.paths:` filter is WORKFLOW-level: it decides whether the
# whole run happens, not which jobs inside it do. app2.yml used to carry that
# filter; issue #2509 removed it because it is the #2354 required-check footgun
# and empirically suppressed the D37 `schedule:` cadence. Per-job selection
# stays here: this script computes one boolean per new module from the push/PR
# diff and writes them to $GITHUB_OUTPUT for the downstream jobs' `if:` guards.
# A schedule/dispatch run passes an empty --base and fail-opens every lane.
#
# Deliberately a script, not an inline `run:` block or a third-party
# paths-filter action:
#   * the repo pins zero third-party filtering actions today (only
#     actions/*, astral-sh/setup-uv, and the SHA-pinned emulator runner), and
#     adding one would be a new supply-chain surface for ~20 lines of logic;
#   * the same reasoning scripts/ci-plan.sh records — the "never
#     under-select" safety property deserves a testable home rather than a
#     YAML expression nothing exercises. Self-test: --self-test.
#
# FAIL-OPEN, LIKE ci-plan.sh / select-test-areas.sh
# An unknown/unreadable diff base (first push to a new ref, force-push,
# shallow checkout without the base commit, workflow_dispatch) selects
# EVERYTHING. Under-selection is the only failure mode that hides a break, so
# every uncertain case latches "run it all".
#
# SHARED-INFRASTRUCTURE PATHS also select everything: the version catalog,
# settings/root build script, the Gradle wrapper, this script, the workflow
# itself and tests/docker/** (the fixture images, including the pinned
# pocketshell wheel + aplexer pins file the agents fixtures install — issue
# #2643). A catalog bump touches no module directory but can break every lane.
#
# Issue #2592 added `tools/pocketshell/` here because the fixture Dockerfiles
# COPYed the CLI tree from outside tests/docker/, so a CLI-side change rebuilt
# the image the journey lane runs against while touching nothing under
# tests/docker/ (the #2588 aplexer pin bump produced a green app2 run in which
# every app2 job skipped). Issue #2643 DELETED that entry with its subject:
# the CLI moved to PocketShell-io/pocketshell-cli, the fixtures install the
# pinned PyPI wheel, and their only app-repo input (fixture-pins.txt) lives
# under tests/docker/. The --self-test COPY-source guard below still catches
# the next fixture input added from elsewhere in the repo.
#
# This is not a list anyone has to remember to update: --self-test parses every
# COPY/ADD in tests/docker/Dockerfile.*, and any source resolving outside
# tests/docker/ that SHARED_PREFIXES does not cover fails the selector's own
# gate. The next fixture input added from elsewhere in the repo reddens here
# instead of silently under-selecting.
#
# APP2-ONLY DEPENDENCY EDGES (issue #2824)
# app2 compiles against shared modules that no core lane owns — ui-kit,
# ui-screens, core-storage, core-terminal, core-voice, core-usage,
# core-diagnostics. Until #2824
# they appeared in neither list, so a commit touching only one of them selected
# ZERO lanes: the app2-journey emulator lane (the D36 full-suite signal and the
# D37 fault/release verdict) SKIPPED while the run still reported success. They
# are edges onto the app2 lane alone, not SHARED_PREFIXES: none of them reaches
# core-hostapi/core-transport/core-portfwd, so fanning all four lanes out would
# over-select. shared/test-support/ IS a shared prefix instead — the core
# lanes' own tests compile against it too.
#
# core-hostapi is the same edge with a different shape: it OWNS a lane
# (MODULE_DIRS[0]) and app2 also compiles against it (app2/build.gradle.kts:468,
# added by 2df828cb8), so the edge is written against the lane hit rather than
# against APP2_DEP_DIRS. It was missed for the same reason the six above were:
# nothing derived the edge list from app2's real dependencies.
#
# That list is likewise not one anyone has to remember, and the guard that keeps
# it honest derives from LANE SELECTION, not from membership of a list:
# --self-test parses every project(":shared:...") dependency out of
# app2/build.gradle.kts and, for each one, drives this very script over a
# synthetic one-file diff under that module and requires app2=true. The weaker
# predicate ("is the module NAMED by some lane rule") is what hid core-hostapi
# for two weeks — it is in MODULE_DIRS, so a membership test reads it as
# covered, while the lane it selects was hostapi and not app2. The next module
# #2636 extracts out of app2/src reddens here instead of silently deselecting
# the journey lane, and so does any future dependency on a module some other
# lane already names.
#
# USAGE
#   ci-app2-changed-modules.sh --base <sha|ref>      # push: github.event.before
#   ci-app2-changed-modules.sh --base ""             # unknown -> everything
#   ci-app2-changed-modules.sh --self-test
#
# OUTPUT (stdout, and $GITHUB_OUTPUT when set)
#   hostapi=true|false
#   transport=true|false
#   portfwd=true|false
#   app2=true|false
# plus a human-readable plan on stderr.

set -uo pipefail

ZERO="0000000000000000000000000000000000000000"

# Module directory, in the emit() order: hostapi, transport, portfwd, app2.
declare -a MODULE_DIRS=(
  "shared/core-hostapi"
  "shared/core-transport"
  "shared/core-portfwd"
  "app2"
)

# A change to any of these selects every lane.
declare -a SHARED_PREFIXES=(
  "gradle/"
  "settings.gradle.kts"
  "build.gradle.kts"
  "gradle.properties"
  "gradlew"
  "gradlew.bat"
  "tests/docker/"
  # tests/docker/** covers every current fixture COPY source, including the
  # pinned-wheel pins file (issue #2643). The --self-test COPY-source guard
  # keeps this entry honest for any future fixture input added elsewhere.
  ".github/workflows/app2.yml"
  "scripts/ci-app2-changed-modules.sh"
  "scripts/check-app2-lane-execution.py"
  # Issue #2474: the app2-journey lane's runner. It is app2-only in effect, but
  # listing it here rather than under the app2 module means a runner change
  # fail-opens every lane instead of starting a run whose every job deselects
  # itself (the runner path is not under app2/).
  "scripts/ci-app2-journey-suite.sh"
  # Issue #2824: shared/test-support is a test dependency of app2 AND of
  # core-transport, core-portfwd, core-storage, core-terminal and core-voice, so
  # a change to it can redden any lane's tests -> it belongs here, not on the
  # app2-only edge list below.
  "shared/test-support/"
)

# Shared modules app2 compiles against that no lane of its own covers: each is a
# dependency edge onto the app2 lane (issue #2824). A module that DOES own a
# lane (core-hostapi, core-transport, core-portfwd) is not listed here — its
# edge is written against the lane hit in plan() instead. Kept honest by
# --self-test's app2-dependency drift guard, which derives from the lane
# selection this script actually produces, so neither form can go stale.
declare -a APP2_DEP_DIRS=(
  "shared/ui-kit"
  "shared/ui-screens"
  "shared/core-storage"
  "shared/core-terminal"
  "shared/core-voice"
  "shared/core-usage"
  "shared/core-diagnostics"
)

# True when <path> is at or under any of the remaining arguments (prefix list).
# A prefix ending in "/" is a directory; a bare filename matches exactly (and,
# harmlessly, anything that extends it — no two entries here are prefixes of a
# different real path).
matches_any_prefix() {
  local path="$1"
  shift
  local prefix
  for prefix in "$@"; do
    [[ -z "$prefix" ]] && continue
    if [[ "$path" == "$prefix" || "$path" == "$prefix"* ]]; then
      return 0
    fi
  done
  return 1
}

# Issue #2592 — the anti-drift half of the COPY-source rule.
#
# Prints, one per line, every repo path that a tests/docker/Dockerfile.* COPYs
# (or ADDs) into a fixture image, normalised to repo-root-relative form. The
# fixture Dockerfiles use two different build contexts: the compose services
# built from the repo root spell their sources "tests/docker/..." / "tools/...",
# while the ones built with tests/docker as context spell them bare
# ("sshd_config"). Both are resolved here, and anything that resolves to
# NEITHER is a hard failure rather than a silent skip — an unparsed COPY is
# exactly the invisible input this guard exists to stop.
fixture_copy_sources() {
  local root="$1"
  local -a files=()
  local df
  while IFS= read -r df; do
    [[ -n "$df" ]] && files+=("$df")
  done < <(find "$root/tests/docker" -maxdepth 1 -name 'Dockerfile.*' -type f 2>/dev/null | sort)

  if [[ ${#files[@]} -eq 0 ]]; then
    echo "fixture_copy_sources: no tests/docker/Dockerfile.* under '${root}'" >&2
    return 2
  fi

  local line n src i first last
  local -a tok=()
  for df in "${files[@]}"; do
    n=0
    while IFS= read -r line || [[ -n "$line" ]]; do
      n=$((n + 1))
      line="${line%$'\r'}"
      [[ "$line" =~ ^[[:space:]]*(COPY|ADD)[[:space:]] ]] || continue
      if [[ "$line" == *'<<'* || "$line" == *'['* || "$line" =~ \\[[:space:]]*$ ]]; then
        echo "fixture_copy_sources: unsupported COPY form at ${df}:${n}: ${line}" >&2
        return 2
      fi
      tok=()
      read -r -a tok <<<"$line"
      first=1
      local from_stage=0
      while [[ $first -lt ${#tok[@]} && "${tok[first]}" == --* ]]; do
        # `COPY --from=<stage|image> ...` copies out of another build stage, not
        # out of the repo: its sources are container paths and must not be
        # resolved against the checkout.
        [[ "${tok[first]}" == --from=* ]] && from_stage=1
        first=$((first + 1))
      done
      if [[ $from_stage -eq 1 ]]; then
        continue
      fi
      last=$((${#tok[@]} - 2)) # the final token is the destination
      if [[ $last -lt $first ]]; then
        echo "fixture_copy_sources: cannot parse sources at ${df}:${n}: ${line}" >&2
        return 2
      fi
      for ((i = first; i <= last; i++)); do
        src="${tok[i]}"
        src="${src#./}"
        # A glob widens to its directory: coverage of the directory covers
        # every file the glob could pick up.
        if [[ "$src" == *[*?]* ]]; then
          src="$(dirname "$src")/"
        fi
        if [[ -e "$root/$src" ]]; then
          printf '%s\n' "$src"
        elif [[ -e "$root/tests/docker/$src" ]]; then
          printf '%s\n' "tests/docker/$src"
        else
          echo "fixture_copy_sources: COPY source '${src}' at ${df}:${n} resolves to no repo path" >&2
          return 2
        fi
      done
    done <"$df"
  done
}

# Fails (rc 1) when a fixture-image COPY source is NOT covered by the prefix
# list passed after <root>. rc 2 means the Dockerfiles could not be read/parsed.
check_fixture_copy_sources_covered() {
  local root="$1"
  shift
  local -a prefixes=("$@")
  local srcs src rc=0
  srcs="$(fixture_copy_sources "$root")" || return 2
  while IFS= read -r src; do
    [[ -z "$src" ]] && continue
    if ! matches_any_prefix "$src" "${prefixes[@]}"; then
      echo "fixture COPY source '${src}' is not covered by SHARED_PREFIXES -> a change to it would rebuild a fixture image while selecting NO lane (issue #2592)" >&2
      rc=1
    fi
  done <<<"$srcs"
  return $rc
}

# Issue #2824 — the anti-drift half of the app2 dependency-edge rule.
#
# Prints, one per line, every `shared/<module>` directory app2/build.gradle.kts
# declares a project dependency on, in any configuration (implementation,
# testImplementation, androidTestImplementation, testFixtures). `//` comments
# are stripped first so a commented-out example cannot invent an edge, and a
# file that yields NO shared dependency at all is a hard failure rather than a
# silent pass — an unparsed build script is exactly the invisible input this
# guard exists to stop.
app2_shared_project_deps() {
  local root="$1"
  local f="$root/app2/build.gradle.kts"
  if [[ ! -f "$f" ]]; then
    echo "app2_shared_project_deps: no '${f}'" >&2
    return 2
  fi
  local deps
  deps="$(sed 's://.*::' "$f" |
    grep -oE 'project\("?:shared:[A-Za-z0-9_.-]+"?\)' |
    sed -E 's/.*:shared:([A-Za-z0-9_.-]+).*/shared\/\1/' |
    sort -u)"
  if [[ -z "$deps" ]]; then
    echo "app2_shared_project_deps: parsed no project(\":shared:...\") out of '${f}'" >&2
    return 2
  fi
  printf '%s\n' "$deps"
}

# Fails (rc 1) when a shared module app2 depends on does NOT select the app2
# lane. rc 2 means the build script could not be read/parsed, or the scratch
# repo could not be built — never a silent pass.
#
# THE PREDICATE IS LANE SELECTION, NOT LIST MEMBERSHIP. The first form of this
# guard asked "is this module NAMED by MODULE_DIRS / SHARED_PREFIXES /
# APP2_DEP_DIRS", which is strictly weaker, and the gap between the two is
# where shared/core-hostapi hid: it is MODULE_DIRS[0], so a membership test
# read it as covered, while MODULE_DIRS[0] selects the HOSTAPI lane and no edge
# carried it to app2. Driving the real selector over a synthetic one-file diff
# under each dependency closes that gap by construction — the guard asserts the
# property the lane rules exist to provide, so it cannot be satisfied by a rule
# that names a module without selecting app2 for it.
#
#   <root>     tree whose app2/build.gradle.kts is parsed for dependencies
#   <selector> the selector script to drive (this file, or a mutant of it in
#              the self-test's own liveness arm)
#   <scratch>  an empty directory the caller owns; a throwaway git repo is
#              built here and left for the caller to clean up
check_app2_deps_select_app2() {
  local root="$1" selector="$2" scratch="$3"
  local deps dep base out rc=0
  deps="$(app2_shared_project_deps "$root")" || return 2
  if ! mkdir -p "$scratch" || ! git -C "$scratch" init -q 2>/dev/null; then
    echo "check_app2_deps_select_app2: cannot build scratch repo at '${scratch}'" >&2
    return 2
  fi
  git -C "$scratch" config user.email t@example.com
  git -C "$scratch" config user.name t
  echo seed >"$scratch/seed.txt"
  git -C "$scratch" add -A
  git -C "$scratch" commit -qm seed
  if ! base="$(git -C "$scratch" rev-parse HEAD 2>/dev/null)"; then
    echo "check_app2_deps_select_app2: no seed commit in '${scratch}'" >&2
    return 2
  fi
  while IFS= read -r dep; do
    [[ -z "$dep" ]] && continue
    git -C "$scratch" reset -q --hard "$base"
    mkdir -p "$scratch/$dep/src/main"
    date +%s%N >"$scratch/$dep/src/main/DriftProbe.kt"
    git -C "$scratch" add -A
    git -C "$scratch" commit -qm "probe $dep"
    out="$(cd "$scratch" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$selector" --base "$base" 2>/dev/null)"
    if ! grep -qx 'app2=true' <<<"$out"; then
      echo "app2 depends on '${dep}' but a diff touching only that module does NOT select the app2 lane (selector emitted: $(tr '\n' ' ' <<<"$out")) -> app2-unit and app2-journey would skip while a break in that module can redden app2 (issue #2824)" >&2
      rc=1
    fi
  done <<<"$deps"
  git -C "$scratch" reset -q --hard "$base"
  return $rc
}

emit() {
  # emit <hostapi> <transport> <portfwd> <app2>
  local out
  out="$(printf 'hostapi=%s\ntransport=%s\nportfwd=%s\napp2=%s\n' "$1" "$2" "$3" "$4")"
  printf '%s\n' "$out"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s\n' "$out" >>"$GITHUB_OUTPUT"
  fi
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    printf 'app2 lane selection: hostapi=%s transport=%s portfwd=%s app2=%s\n' \
      "$1" "$2" "$3" "$4" >>"$GITHUB_STEP_SUMMARY"
  fi
}

plan() {
  local base="$1"
  local changed=""

  if [[ -z "$base" || "$base" == "$ZERO" ]] || ! git cat-file -e "${base}^{commit}" 2>/dev/null; then
    echo "app2 lane selection: base '${base}' is unusable -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  if ! changed="$(git diff --name-only "$base" HEAD 2>/dev/null)"; then
    echo "app2 lane selection: git diff against '${base}' failed -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  if [[ -z "$changed" ]]; then
    echo "app2 lane selection: empty diff against '${base}' -> selecting ALL lanes (fail-open)" >&2
    emit true true true true
    return 0
  fi

  # NUL-safe-ish: iterate line by line (git diff --name-only is one path per
  # line) rather than word-splitting, so a path containing a space is one path.
  local path
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    if matches_any_prefix "$path" "${SHARED_PREFIXES[@]}"; then
      echo "app2 lane selection: shared path '${path}' changed -> selecting ALL lanes" >&2
      emit true true true true
      return 0
    fi
  done <<<"$changed"

  local -a hit=(false false false false)
  local i dep app2_dep_hit=false
  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    for i in 0 1 2 3; do
      if [[ "$path" == "${MODULE_DIRS[i]}/"* ]]; then
        hit[i]=true
      fi
    done
    for dep in "${APP2_DEP_DIRS[@]}"; do
      if [[ "$path" == "$dep/"* ]]; then
        app2_dep_hit=true
      fi
    done
  done <<<"$changed"

  # DEPENDENCY EDGES. A change to a dependency can break a dependant while
  # touching none of its paths, so each edge is walked here to keep the "never
  # under-select" property true:
  #   core-transport -> core-portfwd (task P-4: the tunnel engine runs over
  #     HostConnection and re-exports its types as `api`)
  #   core-transport -> app2         (task M-3: the connect package implements
  #     its TrustStore / AuthSecretResolver seams)
  #   core-portfwd   -> app2         (task P-4: the ports package drives the
  #     forwarder and supervisor directly)
  #   shared/ui-kit, shared/ui-screens, shared/core-storage,
  #   shared/core-terminal, shared/core-voice, shared/core-usage,
  #   shared/core-diagnostics -> app2
  #     (issue #2824: app2/build.gradle.kts compiles against all seven; none of
  #     them reaches a core lane, so the edge lands on app2 only)
  #   core-hostapi -> app2           (issue #2824:
  #     app2/build.gradle.kts:468 `implementation(project(":shared:core-hostapi"))`,
  #     added by 2df828cb8; the session tree, session switcher, create-session
  #     sheet and MainActivity all compile against it. This edge is written
  #     against the lane hit, not APP2_DEP_DIRS, because core-hostapi owns a
  #     lane of its own — which is exactly why the membership-shaped drift
  #     guard used to read it as covered while it selected only hostapi.)
  if [[ "$app2_dep_hit" == "true" ]]; then
    hit[3]=true
  fi
  if [[ "${hit[0]}" == "true" ]]; then
    hit[3]=true
  fi
  if [[ "${hit[1]}" == "true" ]]; then
    hit[2]=true
    hit[3]=true
  fi
  if [[ "${hit[2]}" == "true" ]]; then
    hit[3]=true
  fi

  echo "app2 lane selection: base=${base} hostapi=${hit[0]} transport=${hit[1]} portfwd=${hit[2]} app2=${hit[3]}" >&2
  emit "${hit[0]}" "${hit[1]}" "${hit[2]}" "${hit[3]}"
}

self_test() {
  local tmp status=0 out checks=0
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  git -C "$tmp" init -q
  git -C "$tmp" config user.email t@example.com
  git -C "$tmp" config user.name t
  mkdir -p "$tmp/shared/core-hostapi" "$tmp/shared/core-transport" \
    "$tmp/shared/core-portfwd" "$tmp/app2" "$tmp/gradle" "$tmp/shared/ui-kit" \
    "$tmp/shared/ui-screens"
  echo seed >"$tmp/seed.txt"
  git -C "$tmp" add -A
  git -C "$tmp" commit -qm seed
  local base
  base="$(git -C "$tmp" rev-parse HEAD)"

  check() {
    # check <label> <hostapi> <transport> <portfwd> <app2>
    local label="$1" eh="$2" et="$3" ep="$4" ea="$5"
    checks=$((checks + 1))
    out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "$base" 2>/dev/null)"
    local want
    want="$(printf 'hostapi=%s\ntransport=%s\nportfwd=%s\napp2=%s' "$eh" "$et" "$ep" "$ea")"
    if [[ "$out" != "$want" ]]; then
      echo "FAIL [$label]: expected '$want', got '$out'" >&2
      status=1
    else
      echo "ok   [$label] -> hostapi=$eh transport=$et portfwd=$ep app2=$ea"
    fi
  }

  commit_file() {
    local rel="$1"
    mkdir -p "$tmp/$(dirname "$rel")"
    date +%s%N >"$tmp/$rel"
    git -C "$tmp" add -A
    git -C "$tmp" commit -qm "touch $rel"
  }

  # app2 rides along: app2/build.gradle.kts:468 declares
  # implementation(project(":shared:core-hostapi")) (added by 2df828cb8), and
  # the session tree / switcher / create-session sheet / MainActivity compile
  # against it. This arm asserted `false` for the app2 column from f0996c079
  # until issue #2824 — it pinned the under-selection as intended behaviour and
  # proved it green on every run of the workflow's self-test step, exactly like
  # the "unrelated module" arm the issue body indicts.
  commit_file "shared/core-hostapi/src/main/A.kt"
  check "hostapi pulls app2 in (#2824, app2/build.gradle.kts:468)" true false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-transport/src/main/B.kt"
  # portfwd and app2 ride along: both depend on core-transport (M-3 / P-4).
  check "transport pulls portfwd and app2 in" false true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-portfwd/src/main/P.kt"
  # app2 rides along: the ports package drives the forwarder directly (P-4).
  check "portfwd pulls app2 in" false false true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "app2/src/main/C.kt"
  check "app2 only" false false false true

  # Issue #2824. These seven arms used to be a single "unrelated module" case
  # asserting that a shared/ui-kit commit selects NOTHING — the selector pinned
  # the under-selection as intended behaviour and proved it green on every run
  # of the workflow's self-test step. app2 compiles against all seven modules,
  # so each one is an edge onto the app2 lane and onto no other.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/ui-kit/src/main/D.kt"
  check "ui-kit pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/ui-screens/src/main/E.kt"
  check "ui-screens pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/ui-kit/src/main/D.kt"
  commit_file "shared/ui-screens/src/main/E.kt"
  check "ui-kit + ui-screens together pull app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-storage/src/main/S.kt"
  check "core-storage pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-terminal/src/main/T.kt"
  check "core-terminal pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-voice/src/main/V.kt"
  check "core-voice pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-usage/src/main/U.kt"
  check "core-usage pulls app2 in (#2824)" false false false true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-diagnostics/src/main/Dg.kt"
  check "core-diagnostics pulls app2 in (#2824, D19 module)" false false false true

  # ...and the app2 edge is an edge, not a blanket "anything under shared/".
  # core-assistant is in settings.gradle.kts but app2/build.gradle.kts declares
  # no dependency on it, so it must still deselect every lane. The dependency
  # drift guard below is what flips this arm the day that stops being true.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-assistant/src/main/A2.kt"
  check "a shared module app2 does NOT depend on selects nothing" false false false false

  # shared/test-support is a test dependency of app2 AND of the core modules, so
  # unlike the six above it fans every lane out (issue #2824).
  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/test-support/src/main/TS.kt"
  check "test-support (shared)" true true true true

  # The retained negative: a genuinely unrelated path still deselects every
  # lane. Without it the positive arms above could be satisfied by a selector
  # that had simply stopped being able to say no (acceptance criterion 3).
  git -C "$tmp" reset -q --hard "$base"
  commit_file "docs/architecture.md"
  check "a docs-only change selects nothing" false false false false

  git -C "$tmp" reset -q --hard "$base"
  commit_file "gradle/libs.versions.toml"
  check "version catalog (shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "tests/docker/Dockerfile.ssh"
  check "docker fixture (shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file ".github/workflows/app2.yml"
  check "the workflow itself (shared)" true true true true

  # Issue #2474: the journey lane's runner. A change to it always starts a run
  # (no trigger-level `paths:` — issue #2509); this list must still select lanes
  # so the run is not an empty skip-fest.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "scripts/ci-app2-journey-suite.sh"
  check "the journey runner (shared)" true true true true

  # Issue #2643: the fixture images install the pinned pocketshell wheel, and
  # their app-repo input is tests/docker/fixture-pins.txt — a pin bump must
  # fan every lane out (it changes the image the journey lane runs against).
  git -C "$tmp" reset -q --hard "$base"
  commit_file "tests/docker/fixture-pins.txt"
  check "fixture pins file (pinned CLI wheel, shared)" true true true true

  git -C "$tmp" reset -q --hard "$base"
  commit_file "tests/docker/Dockerfile.agents"
  check "agents image definition (shared)" true true true true

  # Discriminator: the prefix is tools/pocketshell/, not tools/. A sibling tool
  # is no fixture input and must not fan the lanes out.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "tools/some-other-tool/x.py"
  check "an unrelated tools/ path is not shared" false false false false

  git -C "$tmp" reset -q --hard "$base"
  commit_file "shared/core-hostapi/x.kt"
  commit_file "app2/y.kt"
  check "two modules" true false false true

  # A path containing a space is ONE path, not several words — the reason the
  # matcher reads the diff line by line instead of word-splitting it. This case
  # discriminates: word-splitting yields the token "app2/summary.md", which
  # matches the app2 prefix and would wrongly select the app2 lane for a docs
  # file that merely has "app2" in its directory name.
  git -C "$tmp" reset -q --hard "$base"
  commit_file "docs/notes on app2/summary.md"
  check "space in path is not two paths" false false false false

  # Issue #2592 — the anti-drift property, asserted against the REAL tree: every
  # path a fixture Dockerfile COPYs from outside tests/docker/ must be covered by
  # SHARED_PREFIXES. This is what stops the list going stale the next time a
  # fixture grows an input from elsewhere in the repo.
  local -a pruned=()
  local prefix grc
  checks=$((checks + 2))
  if check_fixture_copy_sources_covered "$REPO_ROOT" "${SHARED_PREFIXES[@]}"; then
    echo "ok   [every fixture COPY source is covered by SHARED_PREFIXES]"
  else
    echo "FAIL [fixture COPY source not covered by SHARED_PREFIXES] (see above)" >&2
    status=1
  fi

  # ...and the guard is LIVE: strip the tests/docker/ prefix and it must go
  # red (every current fixture COPY source resolves under tests/docker/, so
  # removing its prefix must expose them). A guard that cannot fail is
  # decoration (G6).
  for prefix in "${SHARED_PREFIXES[@]}"; do
    [[ "$prefix" == "tests/docker/" ]] && continue
    pruned+=("$prefix")
  done
  check_fixture_copy_sources_covered "$REPO_ROOT" "${pruned[@]}" 2>/dev/null
  grc=$?
  if [[ $grc -eq 1 ]]; then
    echo "ok   [COPY-source guard reddens when the tests/docker/ prefix is removed]"
  else
    echo "FAIL [COPY-source guard did not redden without the tests/docker/ prefix: rc=$grc]" >&2
    status=1
  fi

  # ...and a fixture input added from ELSEWHERE IN THE REPO reddens too — the
  # literal drift scenario #2592 is about, on a synthetic tree so it stays true
  # after issue #2643 removed the last real out-of-directory COPY source.
  mkdir -p "$tmp/fixture-drift/tests/docker" "$tmp/fixture-drift/tools/newthing"
  echo x >"$tmp/fixture-drift/tools/newthing/x.txt"
  printf 'FROM scratch\nCOPY tools/newthing/x.txt /x\n' \
    >"$tmp/fixture-drift/tests/docker/Dockerfile.fake"
  checks=$((checks + 1))
  check_fixture_copy_sources_covered "$tmp/fixture-drift" "${SHARED_PREFIXES[@]}" 2>/dev/null
  grc=$?
  if [[ $grc -eq 1 ]]; then
    echo "ok   [a NEW fixture COPY source outside the prefix list reddens the guard]"
  else
    echo "FAIL [drift of a new fixture COPY source went undetected: rc=$grc]" >&2
    status=1
  fi

  # Issue #2824 — the app2 dependency-edge anti-drift property, asserted against
  # the REAL tree and in its STRONG form: every shared module
  # app2/build.gradle.kts declares a project dependency on must make THIS
  # selector emit app2=true for a diff touching only that module. This is what
  # stops the edge rules going stale as #2636 keeps extracting app2 presentation
  # into new shared modules.
  checks=$((checks + 1))
  if check_app2_deps_select_app2 "$REPO_ROOT" "$SELF" "$tmp/dep-real"; then
    echo "ok   [every shared module app2 depends on selects the app2 lane]"
  else
    echo "FAIL [an app2 shared dependency does not select the app2 lane] (see above)" >&2
    status=1
  fi

  # ...and the guard's predicate is SELECTION, not membership of a lane list.
  # That distinction is the whole finding: shared/core-hostapi under-selected
  # from 2df828cb8 until this change while the first form of this guard — "is
  # the module NAMED by MODULE_DIRS / SHARED_PREFIXES / APP2_DEP_DIRS" — read it
  # as covered, because it is MODULE_DIRS[0] and MODULE_DIRS[0] selects the
  # HOSTAPI lane. Two halves prove the strong form is in force:
  #
  #   (a) core-hostapi IS named by a lane rule, so a membership predicate passes
  #       it -- i.e. the two predicates genuinely differ here;
  #   (b) a mutant selector with the core-hostapi -> app2 edge disabled is still
  #       caught, because the guard asks which lane the selector actually picks.
  #
  # (a) alone would not discriminate the weak form from the strong; (b) alone
  # would be a liveness arm that cannot say WHICH predicate is live. Together
  # they pin it. A guard that cannot fail is decoration (G6).
  checks=$((checks + 2))
  local -a module_prefixes=()
  for prefix in "${MODULE_DIRS[@]}"; do module_prefixes+=("$prefix/"); done
  if matches_any_prefix "shared/core-hostapi/" "${module_prefixes[@]}"; then
    echo "ok   [core-hostapi is named by a lane rule, so a membership predicate would pass it]"
  else
    echo "FAIL [core-hostapi is not in MODULE_DIRS any more: the anti-blindness arm below no longer discriminates the two predicates]" >&2
    status=1
  fi

  mkdir -p "$tmp/blind"
  # shellcheck disable=SC2016 # the ${hit[0]} here is literal SOURCE TEXT being matched in a copy of this file, not an expansion.
  sed 's/"${hit\[0\]}" == "true"/"${hit[0]}" == "edge-disabled-by-self-test"/' \
    "$SELF" >"$tmp/blind/selector.sh"
  if cmp -s "$SELF" "$tmp/blind/selector.sh"; then
    # The mutation did not apply, so a green result below would prove nothing —
    # a silent no-op mutant is the classic way a liveness arm goes vacuous.
    echo "FAIL [anti-blindness mutant is byte-identical to the selector: the core-hostapi -> app2 edge no longer matches the mutation anchor]" >&2
    status=1
  else
    check_app2_deps_select_app2 "$REPO_ROOT" "$tmp/blind/selector.sh" "$tmp/dep-blind" 2>/dev/null
    grc=$?
    if [[ $grc -eq 1 ]]; then
      echo "ok   [the guard reddens for a selector that NAMES core-hostapi but does not select app2 for it]"
    else
      echo "FAIL [guard passed a selector with the core-hostapi -> app2 edge disabled: rc=$grc -- it is testing membership, not selection]" >&2
      status=1
    fi
  fi

  # ...and a NEW shared module added to app2/build.gradle.kts reddens too — the
  # literal #2636 drift scenario, on a synthetic tree so it stays true whatever
  # the real build script currently declares.
  mkdir -p "$tmp/dep-drift/app2"
  printf 'dependencies {\n    implementation(project(":shared:ui-motion"))\n}\n' \
    >"$tmp/dep-drift/app2/build.gradle.kts"
  checks=$((checks + 1))
  check_app2_deps_select_app2 "$tmp/dep-drift" "$SELF" "$tmp/dep-drift-repo" 2>/dev/null
  grc=$?
  if [[ $grc -eq 1 ]]; then
    echo "ok   [a NEW shared module app2 depends on reddens the dependency guard]"
  else
    echo "FAIL [drift of a new app2 shared dependency went undetected: rc=$grc]" >&2
    status=1
  fi

  # ...and an unreadable/unparseable build script is rc 2 (hard failure), never
  # a silent pass: the guard must not go quiet when its input disappears.
  mkdir -p "$tmp/dep-empty/app2"
  printf 'dependencies {\n    // implementation(project(":shared:ui-kit"))\n}\n' \
    >"$tmp/dep-empty/app2/build.gradle.kts"
  checks=$((checks + 2))
  check_app2_deps_select_app2 "$tmp/dep-empty" "$SELF" "$tmp/dep-empty-repo" 2>/dev/null
  grc=$?
  if [[ $grc -eq 2 ]]; then
    echo "ok   [a build script with no parseable shared dependency is rc 2, not a pass]"
  else
    echo "FAIL [unparseable app2 build script did not hard-fail: rc=$grc]" >&2
    status=1
  fi
  # "$tmp/dep-missing" is DELIBERATELY never created — this arm asserts that a
  # vanished app2/build.gradle.kts hard-fails instead of passing quietly. Do not
  # add a mkdir for it: creating the path turns the arm into a silent pass.
  check_app2_deps_select_app2 "$tmp/dep-missing" "$SELF" "$tmp/dep-missing-repo" 2>/dev/null
  grc=$?
  if [[ $grc -eq 2 ]]; then
    echo "ok   [a missing app2 build script is rc 2, not a pass]"
  else
    echo "FAIL [missing app2 build script did not hard-fail: rc=$grc]" >&2
    status=1
  fi

  # Fail-open: an unusable base selects everything.
  checks=$((checks + 3))
  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "$ZERO" 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [zero base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [zero base fails open]"
  fi

  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base "" 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [empty base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [empty base fails open]"
  fi

  out="$(cd "$tmp" && env -u GITHUB_OUTPUT -u GITHUB_STEP_SUMMARY bash "$SELF" --base deadbeefdeadbeefdeadbeefdeadbeefdeadbeef 2>/dev/null)"
  if [[ "$out" != "$(printf 'hostapi=true\ntransport=true\nportfwd=true\napp2=true')" ]]; then
    echo "FAIL [unknown base fails open]: got '$out'" >&2
    status=1
  else
    echo "ok   [unknown base fails open]"
  fi

  # Bumped 13 -> 14 by issue #2474's journey-runner case, 14 -> 20 by issue
  # #2592's fixture-input diff cases plus the three COPY-source drift-guard
  # checks; the #2592 CLI-tree cases were re-pointed to the fixture pins file
  # and image definition by issue #2643. 20 -> 35 by issue #2824: nine added diff
  # cases for the app2-only dependency edges (five new positives plus the
  # repurposed ui-kit arm, the ui-kit + ui-screens pair, the core-assistant
  # discriminator, test-support, and the docs-only negative) plus six
  # app2-dependency drift-guard checks (the real-tree selection assertion, the
  # two anti-blindness halves, the new-module drift case, and the two rc-2
  # hard-failure cases). The core-hostapi arm was REPURPOSED, not added — it
  # already existed asserting app2=false, which is the defect it now pins shut —
  # so it moves no count.
  if [[ $checks -ne 36 ]]; then
    echo "FAIL: expected 36 checks, ran $checks" >&2
    status=1
  fi

  if [[ $status -eq 0 ]]; then
    echo "ci-app2-changed-modules self-test: $checks checks PASSED"
  else
    echo "ci-app2-changed-modules self-test: FAILED" >&2
  fi
  return $status
}

SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
# The real checkout, for the --self-test COPY-source drift guard only. `plan`
# never reads it: selection must stay pure git so it works on any checkout.
REPO_ROOT="$(cd "$(dirname "$SELF")/.." && pwd)"

BASE=""
MODE="plan"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --base)
      BASE="${2:-}"
      shift 2
      ;;
    --self-test)
      MODE="self-test"
      shift
      ;;
    -h | --help)
      # The whole leading comment block, however long it grows — a fixed line
      # range silently truncated --help the moment the header did (issue #2592).
      awk 'NR == 1 { next } !/^#/ { exit } { sub(/^# ?/, ""); print }' "$SELF"
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ "$MODE" == "self-test" ]]; then
  self_test
  exit $?
fi

plan "$BASE"
