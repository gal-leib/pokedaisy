# PokeDaisy for iPad

The iPad app's shell: a SwiftUI app that shows the companion's shared screens (`shared-ui`, Compose
Multiplatform) beside the game. The game itself (the iOS core, its Metal view, sound and input) plugs in through
`GameScreen.makeViewController` in `RootView.swift`; until then a dark placeholder stands in.

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
