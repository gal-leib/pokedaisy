# PokeDaisy for iPad (proof of concept)

A native iPad app: the game on mGBA and the companion beside it, from the same C the 3DS and Linux ports use. Like
those ports it is a rewrite, not the Android app recompiled: the Swift app here owns the screen (Metal), the sound
(AVAudioEngine), the buttons and the files, and everything else is shared C.

- **The companion** is `3ds/core/` (PARTY, BAG, MAP, GUIDE, POKéDEX, BATTLE, BOXES, STATES, SETTINGS, the TRAINER CARD) and
  `3ds/host/pd_session.c`, the host logic every host with the companion on a screen of its own shares (the QUICK MENU,
  the HUD, EXPAND, FLY, the field items, the battle controls). Its canvas is 320x240, drawn at a whole scale.
- **The game** runs on mGBA 0.10.5 (`third_party/mgba`), GBA and Game Boy / Color, as an interpreter: iOS gives no app the
  executable memory a recompiler needs, so armGBA's stays off (`native/pd_armgba_none.c`).
- **`native/pd_ios.c`** is the 3DS host's `run_game` without its screens: the core, the save, the session, the sound
  into a ring the audio thread reads (`pd_ios.h` is all Swift sees).
- **`App/`**: the game list (`LibraryViewController.swift`), a running game (`GameViewController.swift`: layout, the
  companion's taps, keyboard and game controllers), `Renderer.swift` (one Metal pass, sharp bilinear scaling),
  `GamepadView.swift` (the on-screen buttons) and `AudioOutput.swift`.

## Layout

Landscape: the game and the companion side by side (the companion at the largest whole scale that fits half the width),
centred over a row of buttons along the bottom: the D-pad under the left thumb, A / B under the right. Portrait: the game across the top, the companion under it with the D-pad and
A / B either side. The game sits on a 400x240 "top screen" as on the 3DS, so the HUD, QUICK MENU and EXPAND's window line
up with it unchanged.

Buttons: the D-pad (eight ways, a finger can roll round it), A, B, L, R, START, SELECT, QUICK (the QUICK MENU), FF (held)
and CLOSE. A keyboard works too (the simulator passes the Mac's): arrows, X = A, Z = B, A / S = L / R, Return = START,
Backspace = SELECT, Q = QUICK MENU, Tab or Space held = FF. A game controller: Nintendo's places (east = A), Y = QUICK MENU,
R2 = FF, the left stick aims the QUICK MENU's ring.

## Files

Everything is in the app's Documents folder, which the Files app shows (On My iPad > PokeDaisy): the games at its top,
their saves in `saves/` (`<game>.sav`, or a `.srm` already there), and `PokeDaisy/` with `settings.ini`, `states/`,
`cheats/<CRC32>.cheats`, `flight-area/` and `backups/<game>/` (the save copied before every start and before a state is
loaded, the newest 10 kept). + in the game list copies games and saves in from Files; nothing is ever moved or written
outside the app. No game, save or game art is bundled.

## Building

On a Mac with Xcode and CMake 3.20+ (`brew install cmake`):

    ios/build.sh ~/path/to/game.gba ~/path/to/game.sav

configures `build/ios` with CMake's Xcode generator, builds for the simulator, boots an iPad simulator, installs the app,
copies the files given into its Documents and launches it. `SIM="iPad Pro 13-inch (M4)"` picks the simulator. Open
`build/ios/PokeDaisy.xcodeproj` in Xcode to run it on an iPad: configure a separate build with your team, which
signs the app (the simulator build isn't signed), then press Run with the iPad as the destination:

    cmake -S ios -B build/ios-device -G Xcode -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos \
        -DCMAKE_OSX_ARCHITECTURES=arm64 -DPD_IOS_TEAM=<your team id>

A free Apple account works (its builds run for 7 days); `-DPD_IOS_BUNDLE_ID=` changes the bundle id if
`com.pokedaisy.ipad` is taken. The iPad needs Developer Mode on.

Anywhere else, the C side builds alone with a headless check:

    cmake -S ios -B build/ios-host && cmake --build build/ios-host -j8
    build/ios-host/pd_ios_smoke game.gba copy-of-save.sav /tmp/pd-data 600

## Not here yet

DS games, co-op, LINK PLAY, RetroAchievements, REWIND, STEADY music, shaders, translated text, cover art, the library's
continue cards, the save editor's screens (all in the other ports or the app). The rest of what the 3DS port lacks
(its README's "Not here yet") is missing here too.
