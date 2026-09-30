# Review Standards

Acceptance bars a reviewer must apply for specific classes of user-facing
change, beyond the general "run build + tests + check each acceptance
criterion" baseline in `process.md`. Load this doc when reviewing terminal,
session-switch, or visual/layout/keyboard work; `.claude/agents/reviewer.md`
carries the operational mechanics of how to run these checks.

## Session-switch / reconnect / SSH journeys

For any change touching session switching, aplexer attach/reattach, SSH
lease/transport, reconnect, or foreground/background lifecycle, a single
happy-path run is not sufficient (this shipped multiple regressions in the
v0.3.30 wave precisely because it was treated as sufficient). On the
emulator + Docker, the reviewer must:

- Switch between ≥2 live sessions repeatedly (A→B→C→A) and after each
  switch confirm, from authoritative artifacts: the correct (non-stale)
  session is shown, no `Disconnected`/EOF band, the pane content is
  re-seeded (not blank), no spurious reconnect, input routes correctly.
- Background→foreground within the grace window and confirm it reattaches
  without a reconnect (and that beyond-grace still reconnects cleanly).
- Base approval on connection-lifecycle logs, the host's aplexer session row,
  and viewport artifacts from the same run, never a passing assertion alone.

Code-read + one happy-path screenshot is grounds for `CHANGES REQUESTED`.

D34 exception for connection-core mechanism fixes (transport/storm/reconnect/
lease): accept an observed headless real-transport red→green (on `main`,
pocketshell-core's Docker `npm run test:integration` tier; on `release/0.5.x`,
the Kotlin Docker/toxiproxy integration suite) as first-class proof — don't
return `BLOCKED` for a missing emulator when a qualifying headless
observation exists. Still reject proof that only exercises a seam/lambda
having fired rather than the symptom-defining signal on the real transport.
The emulator journey remains the batched backstop for anything user-visible
(rendered viewport, wrong/blank/stale session, IME/layout).

### A fixture-driven journey is not emulator evidence for a host-behaviour change

For any change to behaviour the HOST derives (session liveness/reap/create
from `a list --json`, listing filters, kill/attach semantics), a journey whose
host responses are canned cannot validate it — it runs honestly, reports
green, and proves nothing about the change (the vacuous-green shape in
`ci-pitfalls.md`; found in the #2554 review, fixed in #2556). Emulator
evidence for a host-behaviour change must:

- drive the real host CLI + real aplexer (the Docker `agents` fixture ships
  the pinned PyPI wheel and pinned `a`/`aplexer` release binaries — no
  session stubs since #2563/#2643), and
- read its oracle over an INDEPENDENT connection, asserting the host state
  itself (raw `a --json list`, file/process state), not just the app's
  re-render of it, and
- discriminate: the load-bearing assertion must fail when the host change is
  neutralized and pass when it is restored, demonstrated this run. A journey
  whose oracle survives the revert has the same defect as no journey.

`scripts/connected-test.sh` refuses to instrument against an agents fixture
whose baked `fixture-pins.txt` predates the checkout (the #2554 review's
stale-image specimen), so a lane cannot silently validate against a fixture
older than the commit under test.

## Visual / composer / keyboard / layout regressions

Several "fixed + approved + closed" UI issues shipped still broken because
the reviewer verified a narrow proxy (an isolated component test, a render
of one composable) instead of the maintainer's actual on-screen scenario.
For any layout/composer/chrome/keyboard/IME/insets change, or anything
reported as "hidden / clipped / cut off / squished / can't reach":

- Reproduce the bug as failing first, on the emulator, before judging the
  fix. If you can't reproduce the original problem, you can't certify it
  fixed.
- Reproduce the exact reported scenario, including transient state —
  keyboard up if that's the report, the right pane type (shell vs agent).
- Isolated component tests and browser (`pnpm dev`) renders are the fast first check
  only, never sufficient alone to close an occlusion/layout bug. The
  acceptance is a full-device emulator screenshot of the exact reported
  state, showing every previously-hidden control fully visible and
  tappable.
- Verify reachability, not presence — "in the hierarchy" is not "user can
  see and tap it."

### The containment-assertion trap

`assertNodeFullyWithinRoot(tag)` / `assertNodeFullyAboveImeOrKeyboard(tag,
...)` (in `app/src/androidTest/.../proof/signals/ComposeSignals.kt`) are the
right assertions to demand — a bare `assertIsDisplayed()` is satisfied by
mere layout participation, not viewport containment. But confirm the
harness matches production:

- Every window in the app is edge-to-edge (`targetSdk=35` on Android 15
  makes even a bare `ComponentActivity` edge-to-edge) — the root spans the
  full device including the strip behind the system bars, so
  `assertNodeFullyWithinRoot` must subtract the measured navigation-bar
  strip, or a row painted underneath the nav bar reads as "fully within
  root" while a user physically cannot tap it.
- A bare `setContent` test harness renders ~126px higher than production,
  because `MainActivity` pads its top-level `Surface` with
  `WindowInsets.safeDrawing.exclude(WindowInsets.ime)`. Use
  `Modifier.productionWindowChromePadding()` on the harness root.
- For a synthetic inset (the standard way to reach keyboard-up state
  without a real soft IME on CI's swiftshader AVD), pass the inset value
  Compose actually consumed to `assertNodeFullyWithinSystemBarsContentArea`
  — don't assert against zero.

## Fast browser design checks — fast first check, never the acceptance

`pnpm dev` serves the real Vue screens with the shared `packages/ui` tokens
in a browser, with no emulator. Screenshot them at a phone-sized viewport (see
[design-system.md](design-system.md) "Visual checks"). Both the implementer
(before the emulator run) and the reviewer (as a fast first visual check, in
addition to — never instead of — full emulator validation) should look at the
changed screen and compare it to any linked mockup. The browser has no native
SSH, safe-area insets or IME, so layout that depends on them is proven only on
the emulator.

## Regression-proof validity checklist (per PR, for layout/lifecycle/occlusion/keyboard fixes)

- [ ] Asserts viewport containment, not `assertIsDisplayed()`, for the
      control reported hidden/clipped/off-screen.
- [ ] Reproduces the reported state (real screen/sheet window, keyboard up
      where relevant) — not a convenient standalone render.
- [ ] No `*StandIn`/`*Proxy` substituting for a view whose cost/geometry is
      the symptom, unless explicitly justified in a comment the reviewer
      agrees with.
- [ ] For event-driven flows: both the subscriber-alive and the
      subscriber-torn-down path are covered.
- [ ] No `assumeTrue(...)`/`assumeFalse(isRunningOnCi())` on the
      load-bearing assertion — inject the state synthetically and hard-fail
      instead, or CI asserts nothing.
- [ ] `scripts/check-test-validity.sh` reports no new unjustified smell.

## Terminal / SSH / session / agent artifact review

Base approval on the artifact bundle, not the test result line. Authoritative
evidence: `*-viewport.png` terminal screenshots, `*-visible-terminal.txt`,
capture/timing summaries, and Docker/emulator/instrumentation logs from the
same run. Full-device screenshots are advisory only for terminal content
unless the run's summary shows they agree with the viewport capture.

Reject when: authoritative viewport screenshots are missing, blank,
header-only, or stale; visible-terminal text is missing, empty, or
contradicts the screenshots; timing files are missing for a responsiveness
claim; logs are from another run or contradict the claimed result; or a
full-device screenshot is the only proof of terminal content.

Local workbench on `main`: `scripts/connected-test.sh <lane> --suffix i<N>`
runs one packaged JS lane (`smoke`, `lifecycle` or `composer-docker`) against a
booted emulator, and each lane asserts its exact JUnit result set. The legacy
unfiltered instrumented suite and `scripts/capture-walkthrough-screenshots.sh`
screenshot reruns are 0.5.x history and apply only on `release/0.5.x`.
Issue #2481 deleted `scripts/terminal-workbench.sh` and its
`REAL_AGENTS=1` real-agent mode with the `app` module classes they drove;
real-agent CLI rendering has no successor because agent awareness is a cut
feature. Full setup in [testing.md](testing.md).

## Reopened / recurring issues — durable-fix gate (D31)

Flag explicitly in the reviewer brief when an issue was ever closed before,
or a sibling issue closed the same symptom. Then the fix must ship with a
regression test that fails on the bug (red→green proven this run), covers
the whole class (other sites/sessions/agent-kinds/states, not just the one
reported instance), reproduces the maintainer's exact scenario, and runs in
per-push CI or the pre-tag gate. No durable test means `CHANGES REQUESTED`,
not waivable. Also re-check the area's recently-closed sibling symptoms for
resurrection (adjacency sweep) — a resurrected sibling blocks even when the
issue's own acceptance criteria pass. Full rationale: `docs/decisions.md`
D31/D32/D33.
