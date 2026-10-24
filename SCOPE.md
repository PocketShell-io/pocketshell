# PocketShell scope

Purpose: give a person live, secure access to their own machines and the sessions
on them from a phone, browser, desktop or TUI, including machines behind NAT.
PocketShell must work without Hub, its login or its availability.

This repo: the Android/mobile client and the product charter shared by the PocketShell repositories.

## In scope

- Live end-to-end SSH terminals; human voice, keyboard and IME input.
- Host trust, keys and vault; settings and zero-knowledge sync.
- NAT access through the gateway, device enrollment, files/SFTP and port forwarding.
- Per-host sessions/workspaces, human-initiated start/kill on the person's own hosts,
  and display-only local `quse` output without quota history, capture or timers.
- The Android, web, desktop and TUI access clients, host helper and product site.

A person choosing "start this agent in this directory on this machine" is human
terminal access. Selecting accounts/machines, launching reviewers and retrying work
until AC pass belongs to Hub. Hub may stop/delete ANY session on a machine,
including manual PocketShell/psc sessions (t1405u); this does not make human
terminal access depend on Hub.

## NOT our job (owner)

- Model/tier routing, quota admission/reserves, queues, retries and account failover
  for agent work — owner: Hub.
- Task/AC/approval ledgers, cards and checklists — owner: Hub.
- Fleet placement and managed engine-account provisioning — owner: Hub.
- Agent-facing session automation and agent-to-agent orchestration APIs — owner:
  Hub for managed work; aplexer for local runtime and messaging primitives.
- Quota history, reset timers/schedulers and work/quota push notifications — owner: Hub.
- Engine/profile definitions, session identity/incarnation and state hooks — owner: aplexer;
  account/model policy belongs to Hub.
- Business MCP namespaces — owner: a separate DataOps MCP server for DataOps (F10).
  Dapier/AISL tools are removed; their respective projects own any future separately
  approved integration. PocketShell has no business-MCP exception.
- Requiring Hub, sharing its login or storing Hub credentials in sync/vault — owners:
  PocketShell for independent human access, Hub for its own identity and credentials.
- Silent psc/SSH fallback after Hub refuses managed work — owner: Hub for the
  refusal and work lifecycle; PocketShell only serves explicitly human-initiated access.
- IDE, CI/CD or cloud fleet management features — owners: the respective IDE/build
  projects for development pipelines, Hub for agent fleet work.

## Removed (Hub owns this)

These features are removed from PocketShell's product scope, not kept as a
maintenance-only feature set. F1 records the boundary; implementation/removal and
migration are delivered by the corresponding follow-up intakes.

- Agent-facing psc automation: managed start/send/stop/capture/wait workflows.
- `pocketshell-mcp`: retired (t1409u); archive the repository, never delete it (t1410u).
- CLI `push/` and `cards/`.
- Quota history/capture/reset/scheduler features and related work/quota notifications.
- "Continue agent" hooks.
- Conversations handoff export.

Hub owns the overlapping agent-work capabilities, not PocketShell's human terminal.
DataOps MCP moves to its own server (F10, t1407u); Dapier and AISL tools are
removed (t1407u–t1408u). None is retained through `pocketshell-mcp`.

## Shared boundary

aplexer owns the one-machine runtime and publishes its versioned CLI/RPC contract.
Hub and PocketShell consume that contract without copying its internals.
Optional links between products carry no tokens and create no runtime dependency.
Hub's bounded capture snapshots/task artifacts are work evidence; the live terminal,
files, ports, NAT access, keys, sync and human input remain PocketShell's job.

2026-10-24: separate products (t1394u); any-session Hub stop/delete (t1405u);
remove overlapping automation (t1406u), separate DataOps MCP (t1407u),
remove AISL tools (t1408u), retire/archive PocketShell MCP without deleting its
repository (t1409u–t1410u). A gray-area change needs an ADR with its intake ID
and Alexey's approval.
