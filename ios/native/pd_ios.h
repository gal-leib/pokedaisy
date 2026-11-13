// PokeDaisy for iPad: the C side the Swift app calls. One game at a time, driven from one thread (the app's display
// loop): the core (mGBA, GBA and Game Boy), the companion (3ds/core) and the host logic every screen-of-its-own host
// shares (3ds/host/pd_session.c). Only the sound is read from another thread (pd_ios_audio_read).
#ifndef PD_IOS_H
#define PD_IOS_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// The GBA's buttons, as the session takes them (3ds/core/pd_binput.h's PD_KEY_*).
enum {
    PD_IOS_KEY_A = 1 << 0,
    PD_IOS_KEY_B = 1 << 1,
    PD_IOS_KEY_SELECT = 1 << 2,
    PD_IOS_KEY_START = 1 << 3,
    PD_IOS_KEY_RIGHT = 1 << 4,
    PD_IOS_KEY_LEFT = 1 << 5,
    PD_IOS_KEY_UP = 1 << 6,
    PD_IOS_KEY_DOWN = 1 << 7,
    PD_IOS_KEY_R = 1 << 8,
    PD_IOS_KEY_L = 1 << 9,
};

// The companion's canvas and the "top screen" the game, the HUD and the QUICK MENU are laid out on.
#define PD_IOS_COMPANION_W 320
#define PD_IOS_COMPANION_H 240
#define PD_IOS_TOP_W 400
#define PD_IOS_TOP_H 240

// Where the app keeps its files: settings.ini, states/, cheats/, flight-area/, saves' backups. Call once, first.
void pd_ios_init(const char* dataDir, int audioRate);

// Starts the game at romPath with its save at savePath (made on the game's first save). The save is backed up first.
// false with a reason in error if it can't.
bool pd_ios_open(const char* romPath, const char* savePath, char* error, size_t errorLen);
// Finishes a save the game is writing, then closes the game. Safe without one.
void pd_ios_close(void);
bool pd_ios_running(void);
// The game's name as PokeDaisy knows it, and whether the companion supports it ("" = yes, else why not).
const char* pd_ios_title(void);
const char* pd_ios_unsupported(void);

// One screen refresh: the companion's taps, the QUICK MENU, the frames (one at 1x, more in fast-forward), the jobs
// between them. keys: PD_IOS_KEY_*. stickAngle: degrees clockwise from up, -1 = centred (the QUICK MENU's ring).
void pd_ios_tick(uint32_t keys, bool quickMenu, bool ffHeld, int stickAngle, unsigned ms);
// The game asked to be left (CLOSE GAME on the companion): true once, then false.
bool pd_ios_take_leave(void);
// The app is going to the background: the save the game was writing is finished and the settings written.
void pd_ios_suspend(void);

// The picture the "top screen" shows: the game's frame (w x h, its rows stride pixels apart, 0x00BBGGRR: RGBA bytes),
// or EXPAND's window, which fills PD_IOS_TOP_W x PD_IOS_TOP_H. fills: true for EXPAND's window, false for a frame
// drawn as high as the top screen and centred (a Game Boy game's pillarboxed in barRgb).
const uint32_t* pd_ios_frame(int* w, int* h, int* stride, bool* fills, uint32_t* barRgb);
// The overlay over the top screen (PD_IOS_TOP_W x PD_IOS_TOP_H, 0xAARRGGBB: BGRA bytes), shown when *shown; *dim =
// darken the game under it. *dirty: changed since the last call.
const uint32_t* pd_ios_overlay(bool* shown, bool* dim, bool* dirty);
// The companion (PD_IOS_COMPANION_W x PD_IOS_COMPANION_H, 0x00RRGGBB: BGRA bytes); *dirty: redrawn since the last call.
const uint32_t* pd_ios_companion(bool* dirty);
// A finger on the companion, in its canvas's pixels: down = touching (a move is another down), up = let go.
void pd_ios_touch(int x, int y, bool down);
// The device's battery for the companion's SETTINGS title (-1 = unknown).
void pd_ios_set_battery(int percent);

// The game's sound, interleaved stereo at pd_ios_init's rate: up to frames into out; how many (any thread).
size_t pd_ios_audio_read(int16_t* out, size_t frames);

#ifdef __cplusplus
}
#endif

#endif
