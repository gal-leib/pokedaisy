# PokeDaisy for iPad

The iPad app: a SwiftUI app with the game (the iOS core's `GameViewController`: Metal, sound, controllers and a
hardware keyboard) beside the companion's shared screens (`shared-ui`, Compose Multiplatform). With no game running, the
game's place lists the games added from Files (ADD GAMES); a game is copied into the app's `Documents/roms/` and keeps its
save in `Documents/saves/`, both visible in Files. There are no on-screen buttons yet: play with a controller or a
keyboard.

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
