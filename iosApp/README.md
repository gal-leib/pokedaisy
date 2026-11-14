# PokeDaisy for iPad

The iPad app hosts the native Metal game surface, audio, controllers and keyboard with the same Compose companion
screens and touch controls Android uses. With no game running, the app lists the games added from Files (ADD GAMES); a
game is copied into the app's `Documents/roms/` and keeps its save in `Documents/saves/`, both visible in Files. A
game's menu (the circle beside it) has Load a save from Files, which puts the picked save in its place and keeps the one
before as `<name>.backup-<date>.sav` (RetroArch's compressed saves aren't read yet). A controller or a keyboard works
too.

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
Swift only switches between the game list and the game screen. An editor uses the same safe-area layout bounds as the
game screen, pauses the core and keeps separate orientation layouts. Controller connection changes update AUTO visibility. FF (tap/hold),
turbo and state buttons use the existing playback/slot bridges; unsupported actions and skins are omitted.

## Game screen layout

Upright, the screen follows Android's PortraitLayout: with the touch page's COMPANION at UNDER GAME (the default) the
companion sits under the game and the controls get a strip of their own along the bottom, as on an Android phone; with
AT BOTTOM the companion is along the bottom and the controls take the gap above it, or the game where there is none. Sideways, the game is centered beside the docked companion and the controls overlay its area, using Android's default panel
width. The live screen, editor and both preview orientations use the same TouchStage bounds. Game Boy games keep their own aspect ratio. Return to the game list through shared SETTINGS > CLOSE GAME; there
is no separate GAMES overlay, toolbar controller or Swift panel-sizing bridge.
