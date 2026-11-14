# PokeDaisy for iPad

The Home Screen icon uses the existing PokeDaisy flower and blue lattice. Its opaque 1024px asset is copied from
`docs/brand/avatar.png` by `node scripts/gen_brand_images.mjs`; Xcode supplies the icon sizes and corner mask.

The iPad app hosts the native Metal game surface, audio, controllers and keyboard with the same Compose library,
setup, settings and companion screens Android uses. The library fills the window when no game is running. Games
picked from Files are copied into Documents/roms, with battery saves in Documents/saves; both are visible in Files.
LOAD SAVE uses a shared confirmation and keeps the previous save as a dated backup. The on-screen pad and its editor
are shared too; there is no separate UIKit pad or Swift game list.

## Battles

The companion's buttons drive the game's own menus in single battles (actions, moves and BACK), including a party
switch where its menu is mapped. SETTINGS has ONE-TAP MOVES, BATTLE VIEW, BATTLE STYLE, BATTLE INFO and BATTLE FOCUS.
The controller is shared with Android; the iPad runs it once per game frame and navigates by key presses.

## Speed and sound

SETTINGS has FF (a persistent toggle), FF SPEED (including INFINITE), GAME SPEED (kept per game), GAME VOLUME and MUTE GAME.
Fast-forward runs everywhere for now, with pitched, sped-up sound; INFINITE is silent and yields between bounded batches
of frames so the main thread still handles touch and the companion. SMART mode, scene speeds, steady music and speed
hotkeys wait for later PRs.

## Save states

MORE > STATES has ten slots, screenshots, delete and both undo actions, through the shared screen and slot controller.
Files are in Documents/states/<ROM SHA-256>. A manual load restores battery data too, so the current save is backed
up in Documents/saves/pokedaisy-backups first. State operations run between game frames and reset queued audio/input.

RESUME GAMES (companion SETTINGS > APP, on by default) captures a resume state on background/close. An initial open
loads the newest resume/manual snapshot only after the core verifies its embedded battery data matches the save.
Changed/unverifiable automatic states are parked; normal foreground returns retain the paused core. Import and
RESTART GAME commit a persistent fresh-boot marker before changing saves/opening the core, cleared only by a new
successful resume capture. Turning resume off keeps this marker and removes its automatic snapshot.

## Build and run

On a Mac with Xcode 16, a JDK 17 and the Android SDK (Gradle configures the shared modules' Android side too; `sdk.dir` in `local.properties`):

```sh
brew install xcodegen
cd iosApp
xcodegen
open PokeDaisy.xcodeproj
```

Pick an iPad (or the simulator) and run. The first build runs Gradle (`:shared-ui:embedAndSignAppleFrameworkForXcode`),
which takes a few minutes; later builds only rebuild what changed. For a device, set your own team under Signing &
Capabilities (a free Apple ID works for seven days).

From the command line (no signing, the simulator):

```sh
xcodebuild -project iosApp/PokeDaisy.xcodeproj -scheme PokeDaisy -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' CODE_SIGNING_ALLOWED=NO build
```

## Shared touch customization

The companion's TOUCH CONTROLS page, preview and EDIT LAYOUT are the existing shared Compose screens. Preferences use
TouchStyleCodec, also used by Android, backed by NSUserDefaults. The game, controls and companion share one Compose host;
Swift only switches between the library and game controllers. An editor uses the same safe-area layout bounds as the
game screen, pauses the core and keeps separate orientation layouts. Controller connection changes update AUTO visibility. FF (tap/hold),
turbo and state buttons use the existing playback/slot bridges; unsupported actions and skins are omitted.

## Shared application screens

The library, setup and app-settings presenters are the same Compose components Android uses. Swift switches between
controllers; Compose embeds the native Metal game surface and owns the panel layout. There is no separate Swift list,
menus, settings UI or touch pad. The library is full-window when no game is open, with grid/list, search, sorting, favorites/recent views and save import. Settings
and touch layout editing work before a game starts. Setup uses Files to copy games and keeps saves in Documents/saves;
Android folder linking and platform-specific media/tool services remain outside shared UI.

## Game screen layout

Upright, the game is above the companion and the controls are along the bottom, using Android's PortraitLayout
UNDER GAME arrangement. Sideways, the game is centered beside the docked companion and the controls overlay its area, using Android's default panel
width. The live screen, editor and both preview orientations use the same TouchStage bounds. Game Boy games keep their own aspect ratio. POSITION only
aligns preset buttons within the controls' area. Return to the library through shared SETTINGS > CLOSE GAME; there
is no separate GAMES overlay, toolbar controller or Swift panel-sizing bridge.
