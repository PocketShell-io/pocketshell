#!/bin/sh
# The gateway lane's host agent (pocketshell#3086 slice 3): the REAL
# pocketshell-link, enrolled the way a user's host is (`pocketshell gateway
# enroll` runs exactly this helper command), then kept connected with `run`.
#
# It shares the `host` container's network namespace, so 127.0.0.1:22 is that
# host's own sshd. The enrollment pins the host key it probes over loopback
# and aborts unless it equals /enroll/host-key.pub, the key the runner read
# from the host itself. A restart (the lane's "device offline" step) keeps the
# enrolled state and only reconnects.
set -eu

GATEWAY=http://gateway:8080
STATE=/agent-state
CLI=/gw/pocketshell-link

log() { printf '[gwlane-agent] %s\n' "$*"; }

i=0
until wget -qO- "$GATEWAY/healthz" 2>/dev/null | grep -q '"tunnel":"enabled"'; do
  i=$((i + 1))
  [ "$i" -lt 120 ] || { log "the gateway never reported its tunnel enabled"; exit 1; }
  sleep 1
done
i=0
until nc -z 127.0.0.1 22 2>/dev/null; do
  i=$((i + 1))
  [ "$i" -lt 120 ] || { log "the host's sshd never listened on 127.0.0.1:22"; exit 1; }
  sleep 1
done

if "$CLI" show --config-dir "$STATE" >/dev/null 2>&1; then
  log "already enrolled as $DEVICE_ID; reconnecting"
else
  [ -s /enroll/token ] || { log "no enrollment token"; exit 1; }
  log "enrolling $DEVICE_ID"
  "$CLI" enroll --token-stdin \
    --server "$GATEWAY" \
    --insecure-dev \
    --dev-broker-issuer http://broker:8088 \
    --config-dir "$STATE" \
    --device-id "$DEVICE_ID" \
    --ssh-host 127.0.0.1:22 \
    --expect-host-key "$(tr -d '\n' < /enroll/host-key.pub)" < /enroll/token
fi
"$CLI" show --config-dir "$STATE"
exec "$CLI" run --server ws://gateway:8080 --insecure-dev --config-dir "$STATE" --verbose
