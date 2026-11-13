# PokeDaisy on iPad: plan for the full app, merged upstream

How to bring the whole PokeDaisy app (not a separate port) to iPad, and later iPhone, through a series of small
pull requests that the maintainer can review one at a time, that keep the Android app working after every one,
and that land in the upstream repository rather than living in a fork.

The route: one Kotlin codebase shared with Android through Kotlin Multiplatform (KMP), the companion and the
top-screen UI drawn by Compose Multiplatform (CMP) on iOS, and the same C cores built for Apple platforms behind a
thin Swift host. Nothing here is built yet. This is the order of work, what has to be agreed first, the risks and
how each step is checked.

Surveyed 2026-11-13 on upstream `main` at `0040e61f` (v1.5.1) and upstream `dev` at `05e98fd8`.

## 1. Upstream and how it takes contributions

- **Upstream is `lidor30/pokedaisy`** (public; `gal-leib/pokedaisy` is a GitHub fork of it). Its maintainer is the
  only code owner (`.github/CODEOWNERS`: `* @lidor30`).
- **Contributions arrive as pull requests from forks into `dev`**, not `main`. Recent merged examples: #730 and #721
  (ZTMYO), #704 / #706 / #701 / #686 (DFelten), #709 / #710 / #711 / #718 / #719 (keeganwhitacre, the Linux and
  handheld work). `dev` is merged into `main` at each release.
- **The rules a PR meets** (`docs/RELEASING.md`'s "Collaborators with write access", `CLAUDE.md`, the PR template):
  - one approval from the code owner, dropped again by any new push;
  - CI green (`.github/workflows/ci.yml`: unit tests, the debug APK, ui-preview's stress and docs-search checks);
  - the PR template's checklist: the manual (`docs/manual/`) updated for anything a player can notice, changed
    screens looked at (ui-preview / Paparazzi), `make check` passing;
  - the repo's standing rules in `CLAUDE.md`: commits carry no AI attribution lines (a local `commit-msg` hook rejects
    them), new bundled code goes in `NOTICE` and the LICENSES page, workflow actions are pinned to commit SHAs, a new
    player-facing feature gets a NEW tag, new UI text gets its eight translations, no ROMs, saves or secrets.
- **It moves fast.** 50 commits reached `dev` on 2026-11-12 and 13 alone, from the maintainer and four outside
  contributors. Any PR that touches hundreds of files at once will conflict with work in flight; the plan is shaped
  around that.
- **There is a precedent for other platforms in-tree**: `3ds/` and `linux/` (with `libretro/`) are ports that live
  in the same repo, build in their own CI workflows (`linux.yml`, `libretro.yml`) and never get in the Android app's
  way. An `ios/` folder fits the same pattern.

## 2. Rules for every PR in this plan

1. **Branch from upstream `dev`**, target upstream `dev`. (The fork's `main` equals upstream `main`; its `dev` needs
   syncing first.)
2. **The Android app keeps working and looks the same.** Every PR before the iOS app exists is a refactor a player
   can't see, or one they can see that follows the repo's manual / NEW tag / translation rules like any feature.
3. **Small and single-purpose.** Hand-written diff under ~500 lines where possible; mechanical changes (renames,
   API swaps) in their own PRs, with the mechanical rule stated in the description so review is a spot-check.
4. **No mass file moves while others are working.** Shared code is compiled where it already lives (section 5,
   PR 6). If a physical move to `shared/src/commonMain` is ever wanted, it is one pure `git mv` PR, timed with the
   maintainer right after a release.
5. **CI proves it.** Each PR names its check: an existing test, a new one, or a ui-preview render. Full suites run in
   CI; locally, `make test-quick` and the tests for the files touched (the repo's testing rule).
6. **iOS-only code goes under `ios/`** (plus `iosMain` source sets), so the maintainer can merge it without a Mac and
   Android builds never depend on it.

## 3. What needs the maintainer's agreement before work starts

Raise these together, before the first PR, as one upstream Discussion or issue with a link to this document.

| # | Decision | Why it can't wait |
|---|---|---|
| 1 | **The route: KMP + Compose Multiplatform for the full app.** The alternative is growing the C ports (the 3DS / Linux companion in `3ds/core/`, which the iPad proof of concept uses) into a full iOS app, a second implementation of every feature. | It decides everything after it, and KMP changes the Android build. |
| 2 | **Kotlin 2** (from 1.9.24) with the Compose compiler plugin, a newer Compose BOM, Paparazzi past 1.3.4, ui-preview's pins. | `CLAUDE.md` documents the current pins; every later PR depends on it. |
| 3 | **Shared code compiled in place, no file moves**, with a `:shared` Gradle module that points at the existing folders. | Avoids conflicts with every open PR; changes how the app's Gradle files read. |
| 4 | **New dependencies**: Ktor client, kotlinx.serialization, multiplatform-settings, kotlinx-io (or Okio), atomicfu, kotlinx-datetime; later CMP itself. | `NOTICE` / LICENSES entries; APK size. |
| 5 | **Refactors of the big Android classes**: a plain C API under the JNI (`pokedaisy_jni.c`), a `GameSession` split out of `PokeDaisyActivity` / `EmulatorEngine`, the Library and Settings screens split out of their Activities. | These are the maintainer's most edited files. Timing matters as much as approval. |
| 6 | **CI**: a macOS job (GitHub's hosted runners are free for public repos) that builds the iOS framework and runs the shared tests on the simulator; required or advisory. | Adds minutes to every PR. |
| 7 | **The Apple side**: who holds the paid Apple Developer account, the bundle id (`com.pokedaisy.*`), signing secrets in a protected `ios-release` environment like the Android `release` one, TestFlight testers. | The proof of concept uses a personal free team; a release can't. |
| 8 | **App Store risk**: the name and "Pokémon" in the listing (trademark), and whether to ship on the App Store at all or TestFlight / AltStore first. | A store rejection or takedown late in the work wastes it. |
| 9 | **Scope of the first iOS release**: GBA + Game Boy with the companion; what's left out (DS, co-op, LINK PLAY, the APK updater, the frontend launch intents). | Keeps the manual and the website honest about the iOS build. |
| 10 | **Who reviews and tests iOS changes**, and on which devices (an iPad mini is in hand). | The code owner reviews everything; iOS PRs need someone who can run them. |
| 11 | **Docs**: an iOS section in `CLAUDE.md` / `docs/DEVELOPMENT.md` and "On iPad" notes in the manual, in the repo's style. | The manual is the single source for the site and the in-app HELP. |

## 4. What exists today and how it folds in

- **The iPad proof of concept** (fork branch `claude/project-thread-b6fnra`, draft PR #3 on the fork, kept there, not
  merged): a Swift app in `ios/` over mGBA and the C companion from `3ds/core/` + `3ds/host/pd_session.c`, running on
  a real iPad mini. Section 6 lists what carries over.
- **Kotlin 2** (fork PR #2, branch `claude/project-thread-ic6z3l`): Kotlin 2.4.21, Compose BOM 2025.03.01, Paparazzi
  1.3.5, ui-preview on the same compiler. Never built for Android: no CI ran on the fork's PRs (Actions look off
  there) and the cloud session can't reach Google's Maven. It becomes upstream PR 1 below once it has been built.
- **Shared module WIP** (fork branch `claude/project-thread-5xppq9`, doesn't build): moves 500+ files into `shared/`
  and adds a platform layer (`platform/Bytes`, `Concurrent`, `Files`, `Image`, `Streams`, `System`, `Text` + an
  Android actual). The platform layer is kept and re-cut into PRs 4 and 5 below, *without* the move.

Before any of it goes upstream, on the fork: sync `dev` from upstream, turn on GitHub Actions for the fork if they're off (so
`ci.yml` builds the Android app on PRs; GitHub's runners reach Google's Maven), and rebase PR #2 on `dev`.

## 5. The PR sequence

Phases A to C are Android-only refactors (no player sees them), D adds iOS build targets in CI, E is the iOS app.
Within a phase, PRs can run in parallel where marked.

### Phase A: prerequisites

**PR 1. Kotlin 2 and current Compose.** Fork PR #2, rebased on `dev`.
- *Android stays working because*: no source changes beyond what K2 requires; CI builds and tests it.
- *Check*: CI green; one Paparazzi class and a few `gradle render -Ponly=...` shots by eye; the maintainer plays a
  session on the Thor (he owns the hardware and the release key).
- *Risk*: K2 compile differences in ~300k lines; generated `*Gen.kt` tables compiling slower. Split it if review
  wants (Kotlin + compiler plugin; Paparazzi; ui-preview).

**PR 2a to 2e. A plain C API under the JNI** (parallel with Phase B). `pokedaisy_jni.c` (2,213 lines, 137 exports)
plus `pokedaisy_ra.c`, `pk_link_jni.c`, `pk_coop_lock_jni.c` become `pk_api.c` / `pk_api.h` (plain C types and
callbacks) and thin JNI files that only convert arguments. One PR per area: core and frames; memory reads and writes;
states, saves and rewind; RetroAchievements; cheats, patches, link and co-op.
- *Android stays working because*: `MgbaCore.kt`'s `external` functions keep their signatures.
- *Check*: `make test-quick`; the native builds that already compile the `pk_*.c` without JNI (`native-capture`,
  `libretro/test`); a script that every `pk_api.h` function has one JNI caller; a session of FireRed, Emerald, Yellow
  and HeartGold (FF, states, an RA unlock, EXPAND).
- *Risk*: the hot `pkReadInto` path must keep reading without a copy; the core lock stays where
  `PerformanceGuardsTest` expects it.

### Phase B: platform-neutral Kotlin, in place

**PR 3a to 3g. HTTP and JSON** (parallel, any time after PR 1). OkHttp in 7 files (`AppUpdater`, `BestEffortShare`,
`SteamGridDbClient`, `ScreenScraperClient`, `DocsApi`, `RetroAchievements`, `CoopRelaySocket`) and the
`HttpURLConnection` in `AchievementsScreen.kt` move to Ktor client (OkHttp engine on Android), `org.json` (8 files) to
kotlinx.serialization. One client per PR. Good first PRs: small, self-contained, easy to review.
- *Check*: the existing parsing tests (`BestEffortShareTest`, the RA / docs tests); a manual pass over covers, HELP,
  an RA sign-in.

**PR 4. The platform layer.** The WIP's `platform/` files added inside `:app` (package `com.pokedaisy.app.platform`),
JVM / Android implementations only, used by nothing yet.
- *Check*: unit tests for each helper (byte order, the formatter, file helpers) against the JDK calls they replace.

**PR 5a to 5h. Data code onto the platform layer**, one kind of API per PR, files stay where they are:
`java.io` / `java.nio` (29 / 10 files in `companion/data`), `java.util.zip` and commons-compress (15),
`Bitmap` / `BitmapFactory` (6, onto a common `PixelImage`), `synchronized` / `java.util.concurrent` (19 / 13, onto
atomicfu), `String.format` (25), `java.time` (5), `MessageDigest` / CRC32 (4), `Thread(` (7, onto coroutines).
`Prefs` (956 lines over `SharedPreferences`) behind an interface over multiplatform-settings, same keys.
- *Android stays working because*: behaviour-preserving swaps, each over code the fixture tests cover.
- *Check*: `make test-quick` plus the tests of the files touched by name (`MoneyTest`, `DexDetailsTest`,
  `RomItemTextTest`, `BestEffortTest`...); a `grep` in the PR description showing the API is gone from
  `companion/data`.

### Phase C: the shared modules

**PR 6. `:shared` compiles `companion/data`, `companion/i18n` and the theme logic in place.** A KMP module with
`androidTarget()` and `jvm()` whose `commonMain` source set points at the existing folders under
`app/src/main/kotlin` with include patterns (the way ui-preview already compiles app sources), while `:app` excludes
those folders from its own compile and depends on `:shared`. Code that still needs the JVM sits in a
`jvmAndAndroidMain` set. ui-preview's `data/` module depends on `:shared` instead of compiling its own copy.
- *Android stays working because*: the same files compile into the same classes, from a different module.
- *Check*: `make test-quick`; `GameDataMigrationTest`, `BestEffortTest` by name; `make gamedata` and `make check`'s
  staleness checks (the generators keep writing to the same paths); ui-preview renders unchanged.
- *Risk*: `internal` visibility across the new module boundary; AGP's source-set filtering (fallback: exclude in
  the `KotlinCompile` task, as ui-preview does).

**PR 7a to 7c. Engine logic into `:shared`.** The pure parts of `EmulatorEngine` and `PokeDaisyActivity` (pacing
maths, `SmartFf`, `BattleInputController`, `FfGroupFit`, the music key and loop finders, `Hotkeys`, `GbaControls`)
first, then a `GameSession` that holds "what happens when" (start, resume, sleep, close, the companion's sampling)
with the Activity as its Android host.
- *Check*: the existing controller tests (`BattleInputControllerTest`, `TipSchedulerTest`...) move with the code; a
  device session with states, FF, a battle switch and sleep / wake.
- *Risk*: the largest and most edited Android files; agree timing with the maintainer (decision 5).

**PR 8a to 8e. The UI into `:shared-ui`, in place.** `companion/ui/**` compiled by a CMP module (Android + desktop),
the ui-preview shims (`ui-preview/data/shims/`, `ui-preview/src/shims/`: 41 files, 792 lines) turned into `expect`
declarations or small interfaces (`Toast` -> a notice host, `Context` -> an app environment, `Uri` -> a path or
bookmark type), fonts and shaders into CMP resources, `GameClickSound` and haptics behind interfaces. Library and
Settings, private composables inside their Activities today, pulled out into `LibraryScreen` / `SettingsScreen`.
ui-preview becomes `:shared-ui`'s desktop target with no source copying.
- *Check*: ui-preview renders identical PNGs; Paparazzi classes for the screens moved; `LocalizedScreenshotTest`.
- *Risk*: Compose on Android goes from AndroidX artifacts to CMP's (they map to the same AndroidX code on Android,
  but versions are pinned by CMP).

### Phase D: iOS targets in CI

**PR 9. `iosArm64` / `iosSimulatorArm64` targets for `:shared`**, plus a macOS CI workflow (`ios.yml`) that runs
`commonTest` on the simulator. The fixture decode tests (party, bag, battle, dex over every `*_battle` fixture) read
RAM dumps from files and are the proof the readers behave the same on iOS.
- *First measure*: Kotlin/Native compile time and binary size of the generated tables (~250k lines of literals). If
  they're too heavy, the generators emit packed binary resources read lazily instead, which is its own earlier PR.

**PR 10. The native core as an XCFramework.** mGBA (static, GBA + Game Boy, no frontends), rcheevos and the app's
`pk_*.c` (without `nds/`) built for device and simulator by CMake under `ios/`, a cinterop `.def` over `pk_api.h`, and
`IosCore` in `iosMain` implementing the same `Core` interface `MgbaCore` implements on Android.
- *Check*: an `iosTest` boots the diagnostic ROMs `linux-host-test` uses, runs N frames and compares frame CRCs and a
  RAM read with `native-capture/mgba_dump`; a save written and reloaded byte-equal.

**PR 11. `:shared-ui` gets its iOS targets**, rendering the main companion tabs from fixtures in a simulator test.
- *Check*: those renders beside ui-preview's, by eye; Japanese and Korean text (per-glyph font fallback through
  Skia must match `PixelTypeface.kt`'s order).

### Phase E: the iOS app

**PR 12. `ios/` app shell**: a SwiftUI app hosting `ComposeUIViewController`, the Metal game view, audio, input and
files from the proof of concept (section 6), wired to `GameSession`. First milestone: FireRed and Emerald play on an
iPad with the companion beside the game (landscape) or under it (portrait).
- *Check*: the FPS / CPU panel reads ~60.0 with no late frames over 10 minutes; no audio underruns; FF 4x steady; an
  in-game save survives a force quit and loads in the Android app; a resume state comes back after the app is killed
  in the background; a controller and the touch controls both play a battle.

**PR 13a onwards. Feature parity**, each its own PR with the check the repo already uses for it: savestates, REWIND,
TURBO, GAME SPEED, STEADY music (second core, FLAC cache in `Caches/`), EXPAND / ZOOM (`widecheck` against
`mgba_dump`), HUD, QUICK MENU, BATTLE BUTTONS, FLY / PC / field items, FREE FLIGHT, PLAYER LOOK, cheats, RetroAchievements
(softcore only), Game Boy games, covers, HELP, PATCH A ROM, SAVE EDITOR, TRADE SAVES, the screen filters (our shaders
hand-ported to Metal; libretro's through SPIRV-Cross at build time), an external display (the iPad screen as the
companion, the TV as the game: the two-screen code path with `UIScene`s), Stage Manager sizes. The manual gets an
"On iPad" note wherever a feature differs, and the website lists iOS only once a release is live.

**PR 14. Crash reports** (Firebase Crashlytics for iOS, opt-in exactly as on Android, `GoogleService-Info.plist`
from a secret, never committed) and **PR 15. Release**: TestFlight builds from a protected workflow like
`release.yml`, the maintainer's approval as for Android.

**Later: co-op** (behind its flag on Android too): X25519 from CryptoKit instead of Bouncy Castle (the only use is
`CoopKeys.kt`), AES-GCM / HMAC / SHA-256 from CryptoKit behind an `expect`, Bonjour (`NWBrowser`) instead of Android
NSD, CoreImage for the QR code; `CoopSafetyTest` and the Noise test vectors run on iOS.

**Later: Nintendo DS.** No JIT for App Store apps, so melonDS (`ENABLE_JIT OFF`) and DSperate (`DSPERATE_JIT OFF`) run
interpreted. Gate it on a benchmark first: `nds_dump`'s HeartGold walking and battle traces replayed on iPads
(M-series and A-series), carrying on only where they hold ~55 fps. The GLES renderer would need ANGLE's Metal
backend or a Metal port.

## 6. What carries over from the proof of concept

| Piece (`ios/` on the PoC branch) | In the full app |
|---|---|
| `CMakeLists.txt`: mGBA as a static library for iOS (the flags that turn off frontends, GL, Qt, SDL, debuggers) | Carries: the base of PR 10's XCFramework build, with the app's `pk_*.c` and rcheevos added instead of `3ds/core` |
| `Renderer.swift`: one Metal pass, sharp bilinear, textures uploaded per frame | Carries: the game view in PR 12; the filters are added on top |
| `AudioOutput.swift`: `AVAudioEngine` + `AVAudioSourceNode` reading a C ring buffer on the audio thread | Carries as is; the app's dynamic rate control (shared Kotlin) sets the ring's resampling |
| Input in `GameViewController.swift`: keyboard through `pressesBegan`, `GCController` with Nintendo's button places, the stick angle for the QUICK MENU ring, the keyboard-after-rotation fix | Carries, feeding the shared `GbaInput` / hotkeys / `KeyCapture` instead of `pd_ios_tick`'s key bits |
| Files: everything in Documents, shown in the Files app (`Info.plist`'s file sharing keys), `saves/`, backups before every start, `UIDocumentPickerViewController` import as a copy | Carries, mapped onto `SavesLocation` / `SaveBackups`; linked folders add security-scoped bookmarks |
| Signing: `PD_IOS_TEAM` / `PD_IOS_BUNDLE_ID` cache variables (never committed), simulator builds unsigned, `build.sh`'s simulator boot / install / copy-in flow, the Xcode 27 `DeviceHub` fallback | Carries for development; a release adds a paid team and CI signing (decision 7) |
| `smoke/pd_ios_smoke.c`: a headless run of the native side on any machine | Carries as the idea behind PR 10's headless check |
| Layout lessons: landscape game and companion side by side over a row of buttons, portrait stacked, whole scales | Inform the shared layout; the app's own `SidePanel` / `PortraitPanel` paths do the work |
| `pd_ios.c` / `pd_ios.h` (the 3DS host's `run_game`), the `3ds/core` companion, `LibraryViewController.swift`, `GamepadView.swift`, `pd_armgba_none.c` | Not carried: the full app's companion, library and touch controls are the shared Compose ones, and the app doesn't link armGBA |

## 7. Risks, biggest first

1. **Upstream churn** against the refactors in phases B and C: mitigated by no file moves, small PRs and timing with
   the maintainer; a PR that sits too long gets rebased or split, never force-merged.
2. **Kotlin/Native and the generated tables**: compile time and binary size; measured in PR 9 before the iOS UI work.
3. **`PokeDaisyActivity` / `EmulatorEngine`**: without the `GameSession` split the iOS shell would grow a second copy
   of them.
4. **Pixel-exact text and per-glyph font fallback on Skia**: the app's whole look depends on it.
5. **App Store review** of the name and the ROM import flow.
6. **DS speed without a JIT.**

## 8. Open questions for Gal

- iPad only for the first release, or iPhone too (mostly the upright layout)?
- Minimum iOS: 16 keeps `GCController`, Stage Manager and `AVAudioSourceNode` simple and covers the iPad mini.
- Whether Gal or the maintainer opens the upstream Discussion for section 3.
