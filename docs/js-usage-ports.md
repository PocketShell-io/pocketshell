# JS Android Usage and Port Tools

The JS-first Android shell is the user interface for host usage and TCP port
forwarding. TypeScript calls `pocketshell-core` for usage parsing, quota state,
listener selection, auto/manual intent, reconnect recovery, and tunnel cleanup.
Android Kotlin is limited to SSH command and socket effects exposed by the
Capacitor bridge.

## Provider usage

The Settings screen links to Provider usage. The screen calls
`pocketshell usage --json` through the current SSH connection and displays the
provider windows, remaining quota, reset times, reset credits, and blocked or
error states. Provider credentials stay on the host. Refresh is explicit; the
JS shell does not yet provide cached readings or periodic polling.

`tests/docker/agent-fixtures/pocketshell-usage.ndjson` supplies deterministic
ok, blocked, and error provider records to the packaged Android journey. The
screen uses the shared core display names and quota threshold helpers, so
presentation policy follows the same source as the desktop app.

## Port discovery and forwarding

The Settings screen links to Port forwarding. The screen shows remote listener
discovery from the connected host and a loopback address for each active
tunnel. The user can:

- turn automatic forwarding on or off (eligible TCP ports are 1024–10000);
- stop or resume a discovered service;
- enter any valid TCP port for an explicit manual forward, including a port
  outside the automatic range; and
- scan the host again.

Manual desired ports and the auto-forward choice are saved per host. The core
controller closes generation-scoped native handles on reconnect and restores
desired manual forwards after the new SSH generation is ready. Automatically
forwarded listeners use core's bounded missing-scan policy; a transient failed
scan retains existing tunnels.

The feature pages are separate Settings destinations. Returning to the live
session restores its terminal and composer; the feature pages do not add
controls to the terminal viewport or composer row.

## Packaged Docker journey

The registered `UsagePortsDockerJourneyTest#usageAndPortForwardingPoliciesUseDockerAndNativePlugin`
Android journey against the Docker `agents` fixture verifies both tools
through the actual Capacitor bridge. It reads quota/error records from the
host, starts a uniquely tagged temporary HTTP listener inside the fixture,
checks the automatic loopback tunnel with an HTTP request, verifies two
successful missing scans close that tunnel, manually forwards the fixture SSH
port and reads its banner, then checks that the desired manual tunnel is
reopened on a new SSH connection and generation. It also verifies bounded
native cleanup after disconnect. The exact JUnit checker fails closed unless
this one method ran once without skips, failures, or extra tests.

Run the packaged journey with an available API 35+ emulator. Rebuild one
unclaimed Docker pool lane from this checkout; the pool helper stamps the host
CLI version to match the APK and keeps the fixture separate from shared port
2222:

```bash
scripts/agents-pool.sh up 2244
```

Then run:

```bash
scripts/connected-js-usage-ports.sh \
  --port 2244 \
  --container pocketshell-test-agents-2244 \
  --suffix i2859 \
  --run-id js2859-usage-ports
```

The runner fails closed if either the fixture version stamp differs from the
packaged APK version or the baked usage fixture hash differs from this source
tree.

The runner captures full-device PNGs for the usage, automatic-forward, and
manual-forward screens, a strict JUnit report, the same-run host terminal
capture, normalized process command-line and stop-decision evidence, Docker
listener table, and an independent Docker state oracle under
`android/app/build/outputs/js-usage-ports/<run-id>/`. These screenshots are
real-app review evidence. Screenshot capture waits for the expected screen
data, two WebView animation frames, a committed WebView visual state, and
Android's UI thread to become idle; screenshots do not by themselves grant
maintainer visual sign-off.
