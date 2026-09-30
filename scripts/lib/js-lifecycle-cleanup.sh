#!/usr/bin/env bash

# The lifecycle runner adds a host-side logcat/socket watcher after acquiring
# the shared Gradle and AVD locks. Keep watcher shutdown in front of the
# existing combined lock cleanup instead of replacing pocketshell_release_all.
pocketshell_js_lifecycle_cleanup() {
  if declare -F stop_host_socket_watcher >/dev/null 2>&1; then
    stop_host_socket_watcher || true
  fi
  if declare -F pocketshell_release_all >/dev/null 2>&1; then
    pocketshell_release_all
  fi
}

pocketshell_install_js_lifecycle_cleanup_trap() {
  trap pocketshell_js_lifecycle_cleanup EXIT
}
