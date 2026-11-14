# PokeDaisy for iPad

The iPad app: a SwiftUI app with the game (the iOS core's `GameViewController`: Metal, sound, controllers and a
hardware keyboard) beside the companion's shared screens (`shared-ui`, Compose Multiplatform). With no game running, the
game's place lists the games added from Files (ADD GAMES); a game is copied into the app's `Documents/roms/` and keeps its
save in `Documents/saves/`, both visible in Files. A game's menu (the circle beside it) has Load a save from Files, which puts the picked save in its place and keeps
the one before as `<name>.backup-<date>.sav` (RetroArch's compressed saves aren't read yet). The game's buttons sit in a strip under it: the app's own touch
controls (`TouchControls`, shared with Android) or, after a tap on TOUCH, the proof of concept's UIKit buttons
(`GamepadView.swift`), kept until touch delay has been timed on a real iPad and one of the two is picked. A controller
or a keyboard works too.

## Battles

The companion's buttons drive the game's own menus in single battles (actions, moves and BACK), including a party
switch where its menu is mapped. SETTINGS has ONE-TAP MOVES, BATTLE VIEW, BATTLE STYLE, BATTLE INFO and BATTLE FOCUS.
The controller is shared with Android; the iPad runs it once per game frame and navigates by key presses.

## Speed and sound

SETTINGS has FF (a persistent toggle), FF SPEED (including INFINITE), GAME SPEED (kept per game), GAME VOLUME and MUTE GAME.
Fast-forward runs everywhere for now, with pitched, sped-up sound; INFINITE is silent and yields between bounded batches
of frames so the main thread still handles touch and the companion. SMART mode, scene speeds, steady music and speed
hotkeys wait for later PRs.

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
