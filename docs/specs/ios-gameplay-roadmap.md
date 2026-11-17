# Gameplay-first GB/GBC/GBA roadmap

Agreed with the user on 17 November 2026. Prioritize playing the game and its shared screen UI together.
This replaces the ordering of the older iPad parity plan, not its inventory of unfinished capabilities.
A working first iOS release and full Android parity for GB/GBC/GBA are separate completion targets.

## Working rules

- Reuse Android's existing behavior and Compose screens. Shared behavior belongs in common code used by both hosts.
  Native adapters own rendering, audio, OS services and device input; do not copy gameplay policies into them.
- Keep sources compiled in place. Continue on new, self-contained PR branches with explicit immediate bases.
  Preserve existing published review heads. Coordinate directly with the user; no separate upstream coordination chat.
- A settings row or compiled screen does not establish parity. Track per-game availability, runtime behavior and
  validation separately. Unknown coverage remains unverified, not silently counted as supported or excluded.
- Use focused JVM/Native checks, the required quick suite and inspected renders. Do not restart broad CI polling.
  Use isolated simulator checks for interaction; physical installation remains a separate user-coordinated step.
- Every player-visible change includes its manual update and NEW tag where appropriate.

## Order and exit criteria

| Phase | Scope | Exit criteria |
| --- | --- | --- |
| 1. Shared one-screen experience | HUD and battle overlays; companion windows, docking, resizing, rotation, touch placement, input/focus ownership and recovery | Phone/tablet interaction checks cover portrait and landscape, touch and controller ownership, dismissal, persistence and background/foreground. Any gaps found are fixed in shared code where applicable. |
| 2. Everyday gameplay | Bike/rod, remaining field actions and options; GB/GBC support audited alongside GBA | Each capability has a supported-game matrix, reuses the existing Android implementation and has positive, unavailable, stale-result and save-preservation checks as applicable. |
| 3. Expanded picture and camera | EXPAND, zoom, automatic dialogue zoom and look-around | Shared camera policy drives native rendering; transitions to menus, battles, rotation and companion layouts preserve correct input and geometry. |
| 4. Gameplay presentation | Filters, screen overlays and touch skins | Shared settings and layout integrate with native rendering/import adapters; rotation, resizing and one-screen overlays remain usable. |
| 5. Advanced gameplay | Scenic flight, appearance/follower changes, day/night and remaining modifiers | Same per-game capability and behavioral coverage as Android where applicable, with native rendering prerequisites satisfied. |

Deferred unless they block gameplay: cover/media services, library tools, achievements, steady FF music and release
packaging. DS, second-display hardware and Android-specific OS integrations are outside the first iOS release.
Co-op/link is also excluded from that release; exclusion does not mean full GB/GBA parity has been achieved.

## Phase 1 checklist

Baseline: shared game-screen/layout migration, Quick Menu and setup are in place. The user confirmed the combined
Quick Menu/setup checkpoint `79125f27` works on their physical devices. Field HUD PR #151 (`915faccf`) and battle-button
PR #152 (`e2673c4b`) have quick/Native tests and inspected previews; interactive validation of those new overlays is pending.
The statuses below describe this checkpoint, not a claim that every listed scenario has passed.

| Capability | Current implementation | Checks still required |
| --- | --- | --- |
| Shared picture and companion layout | Common GameScreen, portrait layout, landscape side/window/dock modes; GB/GBC 10:9 and GBA 3:2 pictures | Confirm picture bounds and usable controls on phone/tablet, both orientations and narrow windows. |
| Companion recovery and persistence | Common open/close controls, resize grip, docking and stored sizes | Close/reopen every mode, rotate while open/hidden, relaunch and recover without losing access to controls or Settings. |
| Field HUD | Shared renderer/settings; settled GBA field signal; custom control avoidance | Open/close party, bag, map and wild panels. Check transitions into battles/menus and Quick Menu. Audit GB/GBC HUD support separately. |
| Battle buttons | Shared renderer, readiness latch, single-battle policy and control avoidance | Exercise actions, moves, party and cancel; verify unsupported/double battles and no-room layouts. Check GB/GBC and GBA coverage independently. |
| Overlay ownership | Companion windows, Quick Menu, editor and binding capture already have input gates | Audit HUD/battle windows against retained hidden companion state; verify nested back order, text focus and held-input recovery. Keep WILD KEEP OPEN gameplay behavior. |
| Touch layouts | Common editor and actual placed-control bounds feed overlays | Custom positions, hidden/AUTO controls, controller connection, rotation and edit/cancel/done must leave controls reachable. |
| Lifecycle and state | Existing native lifecycle plus shared game runtime and state controllers | Background/foreground with panels open; load/restart/close must not retain old overlay handlers, scripted actions or input holds. |

First execution step: inspect overlay/back/focus ownership and add a failing regression for any concrete gap before
changing it. Then exercise the new HUD/battle integration in isolated simulators. Native tests or previews alone do
not close the interactive-validation items.

## Phase 2 checklist

These rows are audit/work units, not an assertion that all Android game families support each operation.
For each row record Android's actual supported ROM families, iOS wiring and tested behavior before marking it complete.

| Capability | GB/GBC baseline | GBA baseline | Next work |
| --- | --- | --- | --- |
| Playback, bindings and rewind | Shared speed/input policies and native rewind in place | Same | Verify behavior through phase-1 overlays, turbo, keyboard/controller and touch hold transitions. |
| Cheats | iOS GB cheats currently hidden | Shared editor/store and native core integration in place | Audit Android's GB/GBC cheat formats and capabilities, then share the missing core/host adapter. |
| PC, classic FLY, Repel and bag/party actions | Android has separate GB helpers; iOS coverage needs mapping | Existing shared field jobs and Quick Menu wiring | Record coverage per action and ROM family, including unavailable cases and bag/party mutations. |
| Bike and fishing rod | Audit Android capability first | Android behavior exists; iOS Quick Menu omits these actions | Extract/reuse queued registered-item work, input pulses, retry and restoration rules; both hosts adopt it. |
| HEAL, FLASH and remaining field actions | Audit Android helpers and per-game availability | iOS Quick Menu does not offer HEAL/FLASH | Share supported action policy and transactions; do not add dead menu entries. |
| Everyday game options | GB/GBC field options remain unwired on iOS | Shared auto-run, encounters, indoor running, instant text, reusable TMs, HM/EXP/level-cap work is in place | Map missing text-speed and GB/GBC options; preserve built-in game behavior and Android's support limits. |
| Save and game transitions | Existing shared save/state workflows | Same | Check field jobs, item restoration and scripts across save/load, ROM replacement, restart and close. |

Shiny odds, trade evolutions and other remaining modifiers stay visible in the complete inventory; put each in
phase 2 or phase 5 based on its gameplay priority rather than treating the old broad P2 label as complete.

## Completion ledger

Each capability needs: Android support source, GB/GBC/GBA ROM coverage, common implementation owner, native adapter,
PR/base/commit, JVM and Native evidence, inspected preview where relevant, interaction evidence and remaining limits.
Keep three distinct statuses: not wired, implemented but unverified interactively, and verified for named scenarios.
A percentage based on implemented CompanionSettings members is not a release or parity acceptance criterion.
