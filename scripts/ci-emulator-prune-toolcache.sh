#!/usr/bin/env bash
# Preserve the live JDK, Node, and pnpm toolcache roots while dropping unused
# hosted-toolcache entries before the API 35 emulator is created.
set -euo pipefail

toolcache="${AGENT_TOOLSDIRECTORY:-}"
[[ -n "$toolcache" && -d "$toolcache" && "$toolcache" == /* && "$toolcache" != / ]] || exit 0

toolcache_real="$(readlink -f -- "$toolcache")"
declare -A keep_dirs=()

remember_root() {
  local candidate="$1" resolved rel root found=1
  [[ -n "$candidate" ]] || return 1

  if [[ "$candidate" == "$toolcache"/* ]]; then
    rel="${candidate#"$toolcache"/}"
    root="${rel%%/*}"
    if [[ -n "$root" ]]; then
      keep_dirs["$root"]=1
      found=0
    fi
  fi

  resolved="$(readlink -f -- "$candidate" 2>/dev/null || true)"
  if [[ -n "$resolved" && "$resolved" == "$toolcache_real"/* ]]; then
    rel="${resolved#"$toolcache_real"/}"
    root="${rel%%/*}"
    if [[ -n "$root" ]]; then
      keep_dirs["$root"]=1
      found=0
    fi
  fi

  return "$found"
}

java_path=""
if [[ -n "${JAVA_HOME:-}" ]]; then
  java_path="$JAVA_HOME/bin/java"
fi
node_path="$(command -v node || true)"
pnpm_path="$(command -v pnpm || true)"

java_in_toolcache=0
if remember_root "$java_path"; then
  java_in_toolcache=1
fi
remember_root "$node_path" || true
remember_root "$pnpm_path" || true

if (( java_in_toolcache )); then
  keep_names=("${!keep_dirs[@]}")
  printf 'Pruning %s; preserving active toolcache roots: %s\n' \
    "$toolcache" "${keep_names[*]}"
  find_args=("$toolcache" -mindepth 1 -maxdepth 1)
  for keep_name in "${keep_names[@]}"; do
    find_args+=( ! -name "$keep_name" )
  done
  find_args+=( -exec rm -rf -- {} + )
  if [[ "${CI_EMULATOR_TOOLCACHE_NO_SUDO:-0}" == 1 ]]; then
    find "${find_args[@]}" || true
  else
    sudo find "${find_args[@]}" || true
  fi
else
  # Preserve the historical fail-safe: when JAVA_HOME is not demonstrably
  # inside this toolcache, do not guess which root contains the live JDK.
  echo "JAVA_HOME not under $AGENT_TOOLSDIRECTORY; leaving toolcache intact to protect the JDK."
fi

# Check the exact paths captured before pruning. A later `command -v` could
# silently find another installation on PATH and hide deletion of the active
# toolcache executable.
for tool in node pnpm; do
  case "$tool" in
    node) executable="$node_path" ;;
    pnpm) executable="$pnpm_path" ;;
  esac
  if [[ -n "$executable" ]]; then
    if [[ ! -x "$executable" ]]; then
      echo "::error title=EMULATOR INFRA UNAVAILABLE::$tool executable disappeared during toolcache cleanup: $executable" >&2
      exit 1
    fi
    "$executable" --version
  fi
done
