# PokeDaisy on iPad: migration plan

A plan for bringing PokeDaisy to iPad (and later iPhone) without forking the app: one Kotlin codebase,
shared with Android through Kotlin Multiplatform (KMP), its UI drawn by Compose Multiplatform (CMP) on
iOS, and the same C emulator cores built for Apple platforms. Nothing in this document is built yet; it
is the order of work, the risks and how each step is checked.

Surveyed on main at `0040e61f` (v1.5.1). Numbers below are from that tree.

## Where the code stands

| Area | Size | Platform ties |
|---|---|---|
| `companion/data` (readers, tables, guides, save editor) | 484 files, ~278k lines (mostly generated tables) | 6 files import `android.*` (all `Bitmap` / `BitmapFactory`: `Gfx.kt`, `Pokedex.kt`, `Gen4Dex.kt`, the icon sources, `save/RomMonIcons.kt`). JVM APIs: `java.io` 29 files, `java.nio` 10, `java.util.zip` 15, `java.util.concurrent` 13, `synchronized` 19, `String.format` 25, `java.time` 5, `Thread(` 7, `MessageDigest` 4 |
| `companion/i18n` | 16 files, ~2.9k lines | none |
| `companion/ui` (incl. `ui/theme`) | 88 files, ~34k lines | 11 files: `Bitmap` (10), `BitmapFactory`, `Context`, `Configuration`; one `HttpURLConnection` (`AchievementsScreen.kt`) |
| `companion/coop` | 26 files | `java.net` sockets, Android NSD, `javax.crypto` (AES-GCM, HMAC, SHA-256), Bouncy Castle (X25519 only, `CoopKeys.kt`), ZXing (`CoopQr.kt`) |
| App root (`com.pokedaisy.app`, 109 files) | `PokeDaisyActivity` 5,685 lines, `EmulatorEngine` 2,501, `EmulatorView` 1,207, `MgbaCore` 520, `Prefs` 956 | Activities, `GLSurfaceView` + GLES, `AudioTrack`, `Presentation`, SAF / All files access, OkHttp (7 files), `org.json` (8 files), Firebase Crashlytics, the APK updater |
| Native (`app/src/main/cpp`) | `pokedaisy_jni.c` 2,213 lines (137 JNI exports), `pokedaisy_ra.c` (19), `pk_link_jni.c` (12), `pk_coop_lock_jni.c` (6), plus ~30 `pk_*.c` and `nds/` | mGBA 0.10.5, rcheevos 12.5, melonDS 1.1 (+ patch, ARM64 JIT on, GLES 3), DSperate (JIT on) |

Two facts make the KMP route realistic:

1. `ui-preview/` already compiles `companion/**`, `LibraryActivity`, `SettingsActivity` and ~30 more app
   files on Compose **Desktop** against hand-written shims (`ui-preview/data/shims/`, 16 files, and
   `ui-preview/src/shims/`, 25 files; 792 lines together). Those shims are an exact list of the Android
   surface the shared code touches, so they are the map for the `expect` / `actual` layer.
2. Everything game-specific that's hard (EXPAND, field reads, FLY / PC calls, cheats, rewind, flight,
   co-op link) is portable C inside the native library, not Kotlin. The 3DS and Linux ports already
   build most of it without Android.

The tie that has to be cut first is Kotlin itself: the app is on Kotlin 1.9.24 / Compose compiler
1.5.14, Paparazzi is pinned to 1.3.4 because of that, and ui-preview to CMP 1.5.12 / Kotlin 1.9.22. A
current Compose Multiplatform iOS target needs Kotlin 2.x.

## Target shape

```
:shared            KMP: commonMain (data, i18n, theme, engine logic, Prefs interface)
                   androidMain / jvmMain / iosMain (actuals: files, zip, images, prefs, locks, clock)
:shared-ui         KMP + CMP: companion/ui, Library / Settings screens, resources (fonts, shaders)
:core-native       C: mGBA + rcheevos + pk_*.c behind one plain C header (pk_api.h)
                   -> libpokedaisy.so (Android, + thin JNI)  /  PokeDaisyCore.xcframework (iOS, cinterop)
:app               Android shell (Activities, GL view, AudioTrack, Presentation, updater)
ios/PokeDaisy      iOS shell (SwiftUI app, Metal view, AVAudioEngine, GCController, document picker)
ui-preview         stays: the desktop (jvm) target of :shared-ui, now the same sources without copying
```

## Steps

Each step lands on Android first where it can, so the Android app is the regression test for the
refactor and nothing ships half-moved. Steps 1 to 4 change no player-visible behaviour on Android.

### Step 0. Kotlin 2 and current Compose (Android only)

- **Work**: Kotlin 2.x with the Compose compiler Gradle plugin, the Compose BOM and AGP to what that
  Kotlin wants, Paparazzi past 1.3.4 (the newer releases need Kotlin 2), ui-preview to a current CMP
  (this also lifts its "CMP 1.6+ needs Google Maven jars" pin: check it still builds in cloud sessions
  where `dl.google.com` is blocked, or keep a pinned copy of the needed jars).
- **Risks**: K2 compiler differences in a 300k-line codebase (smart casts, overload picks); the
  generated tables (`*Gen.kt`) may compile slower or hit size limits; Paparazzi's image diffs move
  (they're viewing tools, not gates, so that's fine); ui-preview's `Icons.AutoMirrored` rewrite can go.
- **Verify**: `make test-quick`, then a full `make check` / CI with the user's OK; one Paparazzi class
  and a few `gradle render -Ponly=...` shots compared by eye; an APK on the Thor and the RP6 for a session.

### Step 1. One C API under the JNI (Android only)

- **Work**: split `pokedaisy_jni.c` (and the RA / link / coop lock JNI files) into `pk_api.c` / `pk_api.h`,
  plain C functions over C types (`pk_init(const char* rom, ...)`, `pk_run_frame()`, `pk_read_into(addr,
  buf, len)`, ...), and a thin JNI file that only converts arguments. `MgbaCore.kt` keeps its `external`
  functions, now calling the thin layer. Callbacks into Kotlin (RA HTTP requests, audio, log) become C
  function pointers set once.
- **Risks**: 137 exports is a lot of mechanical change; JNI-specific lifetimes (local refs, byte arrays
  pinned with `GetPrimitiveArrayCritical`) hide behaviour that has to be kept (no copies on the hot
  `pkReadInto` path); the global core lock must stay where it is (`PerformanceGuardsTest`).
- **Verify**: `make test-quick` (fixture tests call the readers, not JNI, so also run the native host
  tests: `libretro/test`, `native-capture` builds, which already compile the `pk_*.c` without JNI); a
  check script like `libretro/test/check_exports.sh` that every `pk_api.h` function has exactly one JNI
  caller; play FireRed, Emerald, Yellow and HeartGold on a device (FF, states, RA unlock, EXPAND).

### Step 2. A `:shared` KMP module with Android + JVM targets

- **Work**: move `companion/data`, `companion/i18n`, `companion/ui/theme`'s colour logic, `gamedata`'s
  generated Kotlin, `Hotkeys`, `GbaControls` and the engine's pure logic (pacing maths, `SmartFf`,
  `BattleInputController`, `FfGroupFit`, the music key / loop finders) into a KMP module with
  `androidTarget()` and `jvm()`. Code that still needs JVM APIs goes in a shared `jvmAndAndroidMain`
  source set for now; nothing is rewritten yet. ui-preview's `data/` module depends on it instead of
  compiling a copy. Tests move to `commonTest` / `jvmTest` with the code.
- **Risks**: package-private / `internal` visibility across what becomes a module boundary; Gradle
  generator tasks (`make gamedata`, `NativeConfigsGen.kt`, `SiteDataExportTest`) that write into
  `app/src/main/kotlin` paths; build time.
- **Verify**: `make test-quick`; `GameDataMigrationTest`, `DexDetailsTest`, `BestEffortTest` by name;
  `make gamedata` and `make check`'s staleness check still pass; ui-preview renders the same PNGs.

### Step 3. Make `:shared` common, then add iOS targets

- **Work**: replace the JVM APIs with common ones or a small `expect` layer, one kind at a time:
  - files: `kotlinx-io` (or Okio) for `java.io` / `java.nio`;
  - zip / 7z: `java.util.zip` and commons-compress have no common twin. An `expect` `RomArchive` reader
    (Android: as today; iOS: zlib's minizip from the native library for `.zip`, and 7z through the C
    `LZMA SDK` or deferred; `.7z` may simply be iOS v2);
  - `Bitmap` / `BitmapFactory`: a common `PixelImage` (ARGB `IntArray` + size, as ui-preview's shim
    already is) with platform conversion to `ImageBitmap` at the UI edge;
  - `synchronized` / `ConcurrentHashMap` / `AtomicX`: `kotlinx-atomicfu` locks and atomics;
  - `String.format`: a tiny common formatter (most uses are `%d`, `%02d`, `%X`, `%.1f`);
  - `java.time` / `System.currentTimeMillis`: `kotlinx-datetime` and `kotlin.time`;
  - `MessageDigest` (SHA-1 / MD5 / CRC32 for ROM identity): the native library already has rcheevos'
    MD5; add SHA-1 there or use `kotlincrypto/hash`; CRC32 is a few lines;
  - `Thread(` -> coroutines on `Dispatchers.Default` / a single-thread dispatcher;
  - `Prefs` (956 lines over `SharedPreferences`): an interface over `multiplatform-settings`
    (`SharedPreferences` / `NSUserDefaults`), keys unchanged so Android users keep their settings.
  Then add `iosArm64()` and `iosSimulatorArm64()`.
- **Risks**: the generated tables on Kotlin/Native. A ~250k-line set of `listOf` / `arrayOf` constants
  can make K/N compile for a very long time and bloat the binary; if it does, change the generators to
  emit binary resources (packed tables read at start, lazily, like the records already are) rather than
  Kotlin literals. Measure this first, before the other rewrites. Also: `Regex` behaviour differs a
  little on K/N; ROM reads that assume JVM `ByteArray` performance.
- **Verify**: `commonTest` runs on `iosSimulatorArm64` in CI (macOS runner): the fixture decode tests
  (party / bag / battle / dex on every `*_battle` fixture) read RAM dumps from files and are the best
  proof the readers behave the same; time `:shared:linkDebugFrameworkIosSimulatorArm64` and the
  framework's size.

### Step 4. The native core as an XCFramework

- **Work**: a CMake build of mGBA (static, `BUILD_QT/SDL/LIBRETRO OFF`, no debuggers, GBA + GB cores),
  rcheevos and the `pk_*.c` files (without `nds/`) for `iphoneos-arm64` and `iphonesimulator-arm64`,
  packed as `PokeDaisyCore.xcframework`; a `.def` file over `pk_api.h` for cinterop; an `IosCore` in
  `iosMain` implementing the same `Core` interface `MgbaCore` becomes in `commonMain`. The hot
  `pkReadInto` path reads into pinned Kotlin arrays (`usePinned`) so there's no copy. The STEADY music
  renderer's second core (`rg`) comes along: it's the same API with another handle.
- **Risks**: mGBA's `USE_PTHREADS` / `DISABLE_THREADING` must match between the library and our files
  (the `mCoreRewindContext` size bug: keep `pk_rewind.c`'s `#error`); mGBA's GL / EGL code must stay out;
  file access through `VFile` must take paths the iOS sandbox allows; the `pk_savevf.c` mmap path
  (copy-in-memory fallback) matters more on iOS; Apple's static-library rules for the App Store (no
  dynamic code, no `dlopen` of downloaded code).
- **Verify**: an XCTest (or Kotlin `iosTest`) that boots the diagnostic ROMs used by `linux-host-test`,
  runs N frames and compares frame CRCs and a RAM read with the same run in `native-capture/mgba_dump`;
  a save written, reloaded, byte-equal; a state saved and loaded.

### Step 5. The UI in `commonMain`

- **Work**: move `companion/ui/**` into `:shared-ui`'s `commonMain`. The shims become the `expect`
  layer: for each file in `ui-preview/*/shims/`, either the code stops needing it (most of the activity
  and `Intent` stubs) or it turns into a small platform interface (`Toast` -> a notice host, `Context`
  -> an `AppEnvironment`, `Configuration` -> window size classes, `Uri` -> a path / bookmark type).
  Fonts (Pixel Operator, PixelMplusJP, GalmuriKO) and shaders go to CMP resources. Library and Settings
  are private composables inside their Activities today: pull them out into `LibraryScreen` /
  `SettingsScreen` with the Activities as hosts. `GameClickSound` (SoundPool) and haptics become
  interfaces. ui-preview becomes `:shared-ui`'s desktop target with no source copy.
- **Risks**:
  - per-glyph font fallback: `PixelTypeface.kt` uses Android's `CustomFallbackBuilder` so Japanese and
    Hangul fall to the pixel fonts glyph by glyph; CMP on iOS (Skia) has to give the same fallback order,
    or Japanese / Korean text comes out in the system font;
  - `CompanionColors`' AGSL `RenderEffect` (ON COMPANION shaders): on iOS it's Skia's runtime effects,
    which take the same SkSL, but the API differs (`RuntimeShader` vs Skiko's `RuntimeEffect`); behind
    an `expect`;
  - pixel-exact text: `GbaText` / `GbaTextMetrics` rely on whole-pixel font sizes and `drawText`; Skia on
    iOS rounds differently than Android's text stack; check crispness at iPad densities (2.0 / 3.0);
  - Compose focus and D-pad navigation (`focusStart`, `gbaFocusRing`) must be fed from GCController, not
    key events;
  - performance: CMP iOS is slower than Android for very long lazy lists and many `Text` nodes; the
    weak-device work (`GbaText` as one node, no path clips) already helps.
- **Verify**: ui-preview renders stay the same PNGs (the jvm target now compiles the real
  `commonMain`); Paparazzi on Android; an iOS screenshot test (Compose UI tests on the simulator, or
  XCUITest screenshots) of the main tabs for FireRed and Emerald fixtures, compared by eye against the
  ui-preview shots; `LocalizedScreenshotTest`'s screens in JA and KO on iOS.

### Step 6. The iOS shell (first playable)

- **Work**, an Xcode project (`ios/`) with a SwiftUI app hosting `ComposeUIViewController`s:
  - **Video**: a `CAMetalLayer` / `MTKView` drawing the core's 240x160 (or EXPAND's wider) frame as a
    texture, integer-scaled like `EmulatorView`. Filters: our own shaders (LCD, LCD PAPER, CRT,
    SCANLINES, UNLIT, GBA COLORS) are short and get hand-ported to Metal Shading Language; libretro's
    xBRZ / xBR / ScaleFX `.glsl` go through glslang -> SPIR-V -> SPIRV-Cross -> MSL at build time (the
    files stay unmodified, as their licences and the repo's rule want). SMOOTHING can be iOS v2.
  - **Pacing**: `CADisplayLink` drives `EmulatorEngine.onVsync` exactly as Android's vsync tick does;
    ProMotion iPads run 120 Hz, which the FF-on-refresh code already handles (`ffFramesPerRefresh`).
    Set `preferredFrameRateRange` to 60 like `Surface.setFrameRate`.
  - **Audio**: `AVAudioEngine` with an `AVAudioSourceNode` pulling from a ring buffer the emu thread
    fills; the dynamic rate control stays in shared code. Audio session category `.ambient` (or
    `.playback` with a setting), interruptions handled. STEADY clips play through a second player node;
    FLAC decode via `AVAudioFile` (it reads FLAC since iOS 11).
  - **Input**: `GCController` (MFi, Xbox, PlayStation, Backbone, 8BitDo) and `GCKeyboard`, mapped into
    `GbaInput` with the same binding model; rebinding stays "press the key" (`KeyCapture`), fed from
    GCController's value-changed handlers. The touch controls are already Compose.
  - **Files**: the app's Documents folder shown in Files (`UIFileSharingEnabled`,
    `LSSupportsOpeningDocumentsInPlace`) as the default `roms/` and saves place; "link a ROMs folder" =
    `UIDocumentPickerViewController` for a folder + a security-scoped bookmark per linked folder (the
    `Prefs.romsFolders` list holds bookmarks on iOS); a save next to a ROM in a linked folder is written
    under that bookmark. Opening a `.gba` / `.gbc` / `.zip` / `.bps` from Files or AirDrop =
    `CFBundleDocumentTypes` + `onOpenURL`, replacing `LaunchActivity`'s intents. `SavesLocation` /
    `SaveBackups` keep their order and rules.
  - **Lifecycle**: `scenePhase` background -> the existing `sleep` path (finish a save in progress,
    write the resume state); iOS can end the app in the background, so the resume state is written
    every time, not only on screen-off.
  - **Layouts**: an iPad held sideways is a single screen, so the companion window / side panel paths
    (`SidePanel`, `companionWindowSize`) apply as on the RP6; an iPad held upright is the
    `PortraitPanel` layout (game on top, companion under it), which on a 4:3 iPad is close to the Thor.
    Stage Manager / Split View resize the window: the stage already lays out from its size. An external
    display (`UIWindowSceneSessionRoleExternalDisplayNonInteractive`) maps onto the two-screen mode: the
    game on the TV, the companion on the iPad (SWAP SCREENS' code path, with `Screens` backed by
    `UIScene`s instead of `DisplayManager`).
- **Risks**: Compose-in-SwiftUI keyboard and focus quirks; a Metal layer under Compose views (z-order,
  touch pass-through like `GameStageLayout.overGame`); `PokeDaisyActivity` (5.7k lines) is the largest
  piece of Android-only logic and has to be split into a shared `GameSession` (what happens when) and
  a thin platform host, or the iOS shell grows a second copy of it; App Store review of the save /
  ROM import flow.
- **Verify**: milestone "FireRed and Emerald play on an iPad with the companion": the FPS / CPU panel
  reads ~60.0 with 0 late frames over 10 minutes, no audio underruns, FF 4x steady, a save made in game
  survives a force quit and loads in the Android app (same file), a resume state comes back after the
  app is killed in the background, a controller and the touch pad both play a battle. Instruments' Time
  Profiler on an older iPad (A12 / A13) for the weak-device case.

### Step 7. Feature parity pass

Most features are shared code or C once steps 2 to 6 are done; this step turns them on one by one, each
with its own check from the repo's existing verification notes:

- savestates, REWIND, TURBO, GAME SPEED, SCENE SPEEDS, FF MUSIC (STEADY needs the second core and the
  FLAC cache under an iOS budget: `Caches/` can be purged by the system, which is fine for clips);
- EXPAND / ZOOM (pure C in `pk_wide.c`; check with `widecheck` frames from the iOS build against
  `mgba_dump`), HUD, QUICK MENU, BATTLE BUTTONS, FLY / PC / field items, FREE FLIGHT, PLAYER LOOK,
  cheats, game-option patches;
- RetroAchievements (rcheevos is in the XCFramework; the HTTP goes through step 8; the `User-Agent`
  names the iOS build); softcore stays the only mode;
- the Game Boy games (mGBA's GB core, `pk_gb_audio.c`'s resampler);
- covers (SteamGridDB, RA box art), HELP (`DocsApi`), PATCH A ROM, SAVE EDITOR, TRADE SAVES, LINK PLAY;
- the manual: an "On iPad" note where a feature differs (folders, controllers, no updater), the
  settings that don't exist on iOS hidden there, NEW tags as usual.

Dropped or replaced on iOS: the APK updater (TestFlight / App Store), All files access, the frontend
launch intents (a `pokedaisy://` URL scheme instead, if a frontend wants it), foldable postures,
`TouchInputWindows`, Android NSD (-> Bonjour, step 8), `DualScreenPresentation` (-> scenes, step 6).

### Step 8. Networking and services

- **HTTP**: OkHttp is used in 7 files (`AppUpdater`, `BestEffortShare`, `SteamGridDbClient`,
  `ScreenScraperClient`, `DocsApi`, `RetroAchievements`, `CoopRelaySocket`) plus one
  `HttpURLConnection` in `AchievementsScreen.kt`; JSON is `org.json` in 8 files. Move to Ktor client
  (OkHttp engine on Android, Darwin on iOS, WebSockets for the co-op relay) and `kotlinx.serialization`
  JSON. Keep the same requests, headers and timeouts; do it on Android first, file by file.
- **Crash reports**: Firebase Crashlytics' iOS SDK through Swift Package Manager, opt-in exactly as on
  Android (collection off until the player says yes), with Touchlab's CrashKiOS to symbolicate Kotlin
  frames; the `GoogleService-Info.plist` written from a secret in CI like `google-services.json`, never
  committed. Native crashes in the core are covered by Crashlytics' own signal handler.
- **Co-op link** (behind `FeatureFlags.coop`, so it can wait): Bouncy Castle is used for X25519 only;
  AES-GCM, HMAC-SHA256 and SHA-256 come from `javax.crypto` / `MessageDigest`. On iOS: CryptoKit
  (`Curve25519.KeyAgreement`, `AES.GCM`, `HMAC<SHA256>`) behind an `expect` crypto interface, or
  `cryptography-kotlin` if its X25519 support is there by then. `CoopNoise` / `CoopChannel` stay shared;
  the Noise IK test vectors in the existing tests must pass on iOS too. LAN discovery: Android NSD ->
  `NWBrowser` / `NWListener` (Bonjour; needs `NSLocalNetworkUsageDescription` and `NSBonjourServices`);
  direct TCP -> `Network.framework` or Ktor sockets. ZXing (QR) -> CoreImage's QR generator.
  `CoopSafetyTest`'s rule (nothing from the network reaches game memory) is shared code and holds.
- **Risks**: Ktor's Darwin engine and OkHttp differ on redirects, proxies and cancellation; RA's
  rate limits and the queued-answer-on-emu-thread design (`RetroAchievements.onFrame`) must not change.
- **Verify**: the existing client tests (`BestEffortShareTest`, the RA / docs parsing tests) on both
  targets; a sign-in to RA and an unlock on iOS; co-op: an iPad and an Android device pair over the
  relay and over the same Wi-Fi.

### Step 9. Release

- TestFlight builds from a `macos` GitHub runner (`release-ios.yml`), signed with an App Store Connect
  API key held in a protected environment like the Android signing secrets; the owner approves, as
  with Android releases.
- App Store review: emulators are allowed (guideline 4.7, since 2024) as long as no game is bundled or
  linked (already the repo's rule: no ROMs, game art rebuilt from the player's own ROM by `RomArt`).
  Expect questions about the import flow and the name: "Pokémon" in the app's text and the store
  listing is a trademark risk (a takedown request is possible). The store listing should describe "Game
  Boy Advance games and fan-made hacks" and keep the invented creatures in screenshots, like the
  website.
- The website: an iPad row on the download page only once the App Store build is live (the site never
  lists what the released app can't do).

### Step 10 (later). Nintendo DS

App Store apps can't map memory as executable, so there is no JIT: melonDS' ARM64 JIT
(`ENABLE_JIT ON` in `CMakeLists.txt`) and DSperate's recompiler must be built off. melonDS' interpreter is
several times slower; on an M-series iPad it may reach full speed for HeartGold / Platinum, on A-series
iPads likely not.

- **Work**: add `nds/` to the XCFramework with `ENABLE_JIT OFF` (and DSperate with `DSPERATE_JIT OFF`);
  the software 3D renderer first (DS RESOLUTION = ORIGINAL), the GLES renderer from our patch later
  through ANGLE's Metal backend or a Metal port; the DS bottom screen as the companion's place or a touch
  window (the one-screen `DsTouchWindow` path).
- **Risks**: speed without a JIT is the whole question; melonDS' GL patch is GLES 3.2, which needs
  ANGLE (MetalANGLE / ANGLE Metal) on iOS; App Store review of a DS emulator is the same rule set.
  Outside the App Store (EU alternative marketplaces, sideloading with a debugger-enabled JIT) the JIT
  could come back, but that's a separate distribution track.
- **Verify**: before any UI work, a benchmark: `native-capture/nds_dump`'s HeartGold walking and battle
  traces replayed on iPad hardware (M1, M2, A14, A16) with the interpreter, frames per second per
  device; go on only where it's at least ~55 fps. Then the DS fixtures' field reads compared with
  `nds_dump`.

## Order and milestones

| # | Step | Ships on | Milestone |
|---|---|---|---|
| 0 | Kotlin 2 / current Compose | Android | the Android release builds on Kotlin 2 |
| 1 | C API under the JNI | Android | `pk_api.h` is the only way into the core |
| 2 | `:shared` (Android + JVM) | Android | data, i18n, engine logic out of `:app` |
| 3 | `:shared` common + iOS targets | CI | fixture tests green on the iOS simulator |
| 4 | Core XCFramework | CI | a ROM boots in an iOS test, frame CRCs match `mgba_dump` |
| 5 | UI in `commonMain` | Android + desktop | ui-preview renders `commonMain` unchanged |
| 6 | iOS shell | TestFlight (internal) | FireRed / Emerald playable with the companion on iPad |
| 7 | Feature parity | TestFlight (beta) | the README's GBA / GB features on iPad, gaps listed |
| 8 | Networking / services | both | Ktor everywhere, crash reports on iOS, co-op when its flag goes |
| 9 | Release | App Store | public iPad build |
| 10 | DS | later | only if the interpreter benchmark passes |

Steps 1 and 2 can run side by side; step 8's HTTP move can start any time after step 2 and is easier
done early (it removes OkHttp / `org.json` from the shared code before step 3).

## Biggest risks, in order

1. **Kotlin/Native and the generated tables** (step 3): compile time and binary size. Measured first;
   the fallback (binary resources) touches the generators, not the readers.
2. **`PokeDaisyActivity` / `EmulatorEngine`** mixing logic with Android: without splitting them into
   shared logic + a host, the iOS shell becomes a second app to keep in step.
3. **Pixel-exact text and per-glyph font fallback on Skia** (step 5): the app's look depends on it.
4. **App Store review** of the name and the ROM import flow (step 9).
5. **DS speed without a JIT** (step 10).

## Open questions for the owner

- iPad only, or iPhone too? (Most of the work is the same; iPhone mostly means the upright layout.)
- Minimum iOS: 16 keeps `GCController` / Stage Manager / `AVAudioSourceNode` simple and covers iPads
  from 2017 on.
- Which iPad and controller to test on first.
- Is co-op wanted on iPad at launch, or after its Android flag goes?
