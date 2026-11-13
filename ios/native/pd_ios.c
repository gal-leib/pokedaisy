// PokeDaisy for iPad: the game on mGBA and the companion from 3ds/core, through the session the 3DS host and the
// headless simulator share (3ds/host/pd_session.c). This file is what ctr/main.c's run_game is for the 3DS, without
// its screens, sound and buttons: the Swift app has those (pd_ios.h).
#include "pd_ios.h"

#include <dirent.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#include <mgba-util/vfs.h>
#include <mgba/core/blip_buf.h>
#include <mgba/core/config.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/internal/gba/audio.h>
#include <mgba/internal/gba/gba.h>

#include "../../3ds/core/pd_canvas.h"
#include "../../3ds/core/pd_game.h"
#include "../../3ds/core/pd_settings.h"
#include "../../3ds/host/pd_session.h"
#include "../../app/src/main/cpp/pk_call.h"
#include "../../app/src/main/cpp/pk_gb_audio.h"
#include "../../app/src/main/cpp/pk_host.h"

#define MAX_BACKUPS 10
#define AUDIO_CHUNK 512
// About a third of a second of sound, interleaved stereo; a power of two.
#define RING_FRAMES 16384

static char dataDir[1024];
static char settingsPath[1100];
static int audioRate = 48000;

static struct pd_settings settings;
static struct pd_session session;
static uint32_t video[PD_GAME_STRIDE * 160];
static uint32_t filled[PD_IOS_TOP_W * PD_IOS_TOP_H];
static uint32_t companionPx[PD_UI_WIDTH * PD_UI_HEIGHT];
static bool companionDirty = true;
static struct mCore* core;
static void* romBytes;
static bool running, leaveAsked, touching;
static int touchX, touchY;
static char romBase[256];
static char savePath[1024];

// --- logging: mGBA's default logger prints every unmapped I/O write ---

static void noop_log(struct mLogger* log, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) log; (void) category; (void) level; (void) format; (void) args;
}
static struct mLogger silentLogger = { .log = noop_log, .filter = NULL };

// --- sound: one ring the core's thread fills and the audio thread empties ---

static int16_t ring[RING_FRAMES * 2];
static atomic_size_t ringWrite, ringRead;

static void ring_push(const int16_t* stereo, size_t frames) {
    size_t w = atomic_load_explicit(&ringWrite, memory_order_relaxed);
    size_t r = atomic_load_explicit(&ringRead, memory_order_acquire);
    size_t room = RING_FRAMES - (w - r);
    if (frames > room) frames = room; // no room: the newest sound goes, rather than wait
    for (size_t i = 0; i < frames; i++) {
        size_t at = (w + i) & (RING_FRAMES - 1);
        ring[at * 2] = stereo[i * 2];
        ring[at * 2 + 1] = stereo[i * 2 + 1];
    }
    atomic_store_explicit(&ringWrite, w + frames, memory_order_release);
}

size_t pd_ios_audio_read(int16_t* out, size_t frames) {
    size_t r = atomic_load_explicit(&ringRead, memory_order_relaxed);
    size_t w = atomic_load_explicit(&ringWrite, memory_order_acquire);
    size_t have = w - r;
    if (frames > have) frames = have;
    for (size_t i = 0; i < frames; i++) {
        size_t at = (r + i) & (RING_FRAMES - 1);
        out[i * 2] = ring[at * 2];
        out[i * 2 + 1] = ring[at * 2 + 1];
    }
    atomic_store_explicit(&ringRead, r + frames, memory_order_release);
    return frames;
}

static bool muted(void) {
    return session.ff || settings.gameMuted;
}

static void push_scaled(int16_t* stereo, size_t frames) {
    if (muted()) return;
    if (settings.gameVolume < 100)
        for (size_t i = 0; i < frames * 2; i++) stereo[i] = (int16_t) (stereo[i] * settings.gameVolume / 100);
    ring_push(stereo, frames);
}

static struct mAVStream stream;

static void post_audio(struct mAVStream* s, blip_t* left, blip_t* right) {
    (void) s;
    static int16_t chunk[AUDIO_CHUNK * 2];
    int avail;
    while ((avail = blip_samples_avail(left)) > 0) {
        int n = avail < AUDIO_CHUNK ? avail : AUDIO_CHUNK;
        blip_read_samples(left, chunk, n, true);
        blip_read_samples(right, chunk + 1, n, true);
        push_scaled(chunk, (size_t) n);
    }
}

// A Game Boy game's sound comes from pk_gb_audio, never its core's own channels (CLAUDE.md's Yellow notes).
static struct pk_gb_audio gbAudio;
static bool gbAudioOn;

static void gb_audio_pump(void) {
    if (!gbAudioOn) return;
    static int16_t chunk[AUDIO_CHUNK * 2];
    int n;
    while ((n = pk_gb_audio_read(&gbAudio, chunk, AUDIO_CHUNK)) > 0) push_scaled(chunk, (size_t) n);
}

// --- files ---

static void make_dir(const char* path) {
    mkdir(path, 0755);
}

static bool copy_file(const char* from, const char* to) {
    FILE* in = fopen(from, "rb");
    if (!in) return false;
    FILE* out = fopen(to, "wb");
    if (!out) {
        fclose(in);
        return false;
    }
    char buf[16384];
    size_t n;
    bool ok = true;
    while ((n = fread(buf, 1, sizeof(buf), in)) > 0)
        if (fwrite(buf, 1, n, out) != n) ok = false;
    fclose(in);
    if (fclose(out) != 0) ok = false;
    return ok;
}

static int cmp_names(const void* a, const void* b) {
    return strcmp(*(const char* const*) a, *(const char* const*) b);
}

// The save as it is now, into backups/<game>/<when>.sav, the newest MAX_BACKUPS kept (the repo's "save files are
// sacred": every start, and before a state load).
static void backup_save(void* ctx) {
    (void) ctx;
    struct stat st;
    if (stat(savePath, &st) != 0 || st.st_size == 0) return;
    char dir[1400];
    snprintf(dir, sizeof(dir), "%s/backups", dataDir);
    make_dir(dir);
    snprintf(dir, sizeof(dir), "%s/backups/%s", dataDir, romBase);
    make_dir(dir);
    char stamp[32];
    time_t now = time(NULL);
    strftime(stamp, sizeof(stamp), "%Y%m%d-%H%M%S", localtime(&now));
    char to[1500];
    snprintf(to, sizeof(to), "%s/%s.sav", dir, stamp);
    copy_file(savePath, to);

    DIR* d = opendir(dir);
    if (!d) return;
    char* names[256];
    int count = 0;
    struct dirent* e;
    while ((e = readdir(d)) && count < 256)
        if (e->d_name[0] != '.') names[count++] = strdup(e->d_name);
    closedir(d);
    qsort(names, (size_t) count, sizeof(*names), cmp_names);
    for (int i = 0; i < count; i++) {
        if (i < count - MAX_BACKUPS) {
            char old[1700];
            snprintf(old, sizeof(old), "%s/%s", dir, names[i]);
            unlink(old);
        }
        free(names[i]);
    }
}

static void state_path(void* ctx, int slot, char* vf, size_t vfLen, char* path, size_t pathLen) {
    (void) ctx;
    snprintf(path, pathLen, "%s/states/%s.ss%d", dataDir, romBase, slot);
    snprintf(vf, vfLen, "%s", path);
}

static void cheat_path(void* ctx, uint32_t crc, char* path, size_t len) {
    (void) ctx;
    snprintf(path, len, "%s/cheats/%08X.cheats", dataDir, crc);
}

static void flight_area_path(void* ctx, uint32_t crc, uint32_t key, char* path, size_t len) {
    (void) ctx;
    snprintf(path, len, "%s/flight-area/%08X-%08X.txt", dataDir, crc, key);
}

static void settings_changed(void* ctx) {
    (void) ctx;
    pd_settings_save(&settings, settingsPath);
}

static uint64_t clock_ns(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t) t.tv_sec * 1000000000u + (uint64_t) t.tv_nsec;
}

void pd_ios_init(const char* dir, int rate) {
    mLogSetDefaultLogger(&silentLogger);
    snprintf(dataDir, sizeof(dataDir), "%s", dir);
    audioRate = rate > 0 ? rate : 48000;
    static const char* const SUBDIRS[] = { "states", "cheats", "flight-area", "backups" };
    make_dir(dataDir);
    for (size_t i = 0; i < sizeof(SUBDIRS) / sizeof(*SUBDIRS); i++) {
        char p[1100];
        snprintf(p, sizeof(p), "%s/%s", dataDir, SUBDIRS[i]);
        make_dir(p);
    }
    snprintf(settingsPath, sizeof(settingsPath), "%s/settings.ini", dataDir);
    pd_settings_defaults(&settings);
    pd_settings_load(&settings, settingsPath);
    // The app places the picture itself (as high as the top screen); SCREEN's row stays hidden.
    settings.screenMode = PD_SCREEN_SHARP;
}

// --- the game ---

static void fail(char* error, size_t len, const char* why) {
    snprintf(error, len, "%s", why);
}

bool pd_ios_open(const char* romPath, const char* save, char* error, size_t errorLen) {
    pd_ios_close();
    snprintf(savePath, sizeof(savePath), "%s", save);
    const char* slash = strrchr(romPath, '/');
    snprintf(romBase, sizeof(romBase), "%s", slash ? slash + 1 : romPath);
    char* dot = strrchr(romBase, '.');
    if (dot) *dot = 0;

    // From a copy in memory, as pd_sim does: a GPIO write swaps mGBA's own mapping of a mapped file (FireRed), which
    // would leave the session's ROM pointer dangling.
    FILE* f = fopen(romPath, "rb");
    if (!f) {
        fail(error, errorLen, "Couldn't open the game file.");
        return false;
    }
    fseek(f, 0, SEEK_END);
    long len = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (len <= 0 || len > 64 * 1024 * 1024) {
        fclose(f);
        fail(error, errorLen, "That file isn't a game PokeDaisy plays.");
        return false;
    }
    romBytes = malloc((size_t) len);
    if (!romBytes || fread(romBytes, 1, (size_t) len, f) != (size_t) len) {
        fclose(f);
        free(romBytes);
        romBytes = NULL;
        fail(error, errorLen, "Couldn't read the game file.");
        return false;
    }
    fclose(f);

    memset(&session, 0, sizeof(session));
    pd_game_detect(&session.game, romBytes, (size_t) len);
    struct VFile* rom = VFileFromMemory(romBytes, (size_t) len);
    core = mCoreFindVF(rom);
    if (!core || !core->init(core)) {
        if (rom) rom->close(rom);
        free(romBytes);
        romBytes = NULL;
        core = NULL;
        fail(error, errorLen, "That file isn't a game PokeDaisy plays.");
        return false;
    }
    mCoreInitConfig(core, NULL);
    core->setVideoBuffer(core, (color_t*) video, PD_GAME_STRIDE);
    bool gb = core->platform(core) == mPLATFORM_GB;
    if (gb) {
        // Red / Blue / Yellow as a Super Game Boy would give 256x224 with a border: the plain 160x144.
        mCoreConfigSetIntValue(&core->config, "sgb.borders", 0);
        core->reloadConfigOption(core, "sgb.borders", &core->config);
    }
    core->setAudioBufferSize(core, AUDIO_CHUNK);
    if (!core->loadROM(core, rom)) {
        core->deinit(core);
        core = NULL;
        free(romBytes);
        romBytes = NULL;
        fail(error, errorLen, "The game didn't load.");
        return false;
    }
    backup_save(NULL);
    struct VFile* sv = VFileOpen(savePath, O_CREAT | O_RDWR);
    if (sv) core->loadSave(core, sv);

    // A frame a refresh: the GBA's 59.73 frames' sound in the 60 the screen shows (the Game Boys' frames are as long).
    atomic_store(&ringWrite, 0);
    atomic_store(&ringRead, 0);
    double ratio = GBAAudioCalculateRatio(1, 60, 1);
    if (gb) {
        gbAudioOn = pk_gb_audio_attach(&gbAudio, core, audioRate * ratio);
    } else {
        gbAudioOn = false;
        memset(&stream, 0, sizeof(stream));
        stream.postAudioBuffer = post_audio;
        core->setAVStream(core, &stream);
        blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), audioRate * ratio);
        blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), audioRate * ratio);
    }
    core->reset(core);
    pk_heal_rtc(core);

    struct pd_session_host files = { .statePath = state_path, .backupSave = backup_save, .settingsChanged = settings_changed,
                                     .cheatPath = cheat_path, .flightAreaPath = flight_area_path, .clock = clock_ns };
    session.host = (struct pd_host_info) { .count = 1, .batteryPct = -1, .hideScreenMode = true };
    session.host.labels[0] = "FILE";
    snprintf(session.host.values[0], sizeof(session.host.values[0]), "%s", slash ? slash + 1 : romPath);
    pd_session_start(&session, core, video, &settings, &files);
    running = true;
    leaveAsked = touching = false;
    companionDirty = true;
    session.redraw = true;
    return true;
}

void pd_ios_close(void) {
    if (!core) return;
    pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
    if (gbAudioOn) pk_gb_audio_detach(&gbAudio, core);
    gbAudioOn = false;
    core->deinit(core);
    core = NULL;
    free(romBytes);
    romBytes = NULL;
    running = false;
    pd_settings_save(&settings, settingsPath);
}

bool pd_ios_running(void) {
    return running;
}

const char* pd_ios_title(void) {
    return running && session.game.title ? session.game.title : "";
}

const char* pd_ios_unsupported(void) {
    if (!running || session.game.cfg) return "";
    return session.game.unsupported ? session.game.unsupported : "not supported";
}

void pd_ios_touch(int x, int y, bool down) {
    if (!running) return;
    if (down) {
        touching = true;
        touchX = x, touchY = y;
        session.redraw |= pd_ui_touch(&session.ui, x, y, true);
    } else if (touching) {
        touching = false;
        session.redraw |= pd_ui_touch(&session.ui, 0, 0, false);
    }
}

void pd_ios_set_battery(int percent) {
    if (session.host.batteryPct != percent) session.redraw = true;
    session.host.batteryPct = percent;
}

void pd_ios_tick(uint32_t keys, bool quickMenu, bool ffHeld, int stickAngle, unsigned ms) {
    if (!running) return;
    // A held finger counts every refresh, as the 3DS's stylus does (a drag scrolls the lists).
    if (touching) session.redraw |= pd_ui_touch(&session.ui, touchX, touchY, true);
    enum pd_action action = pd_ui_take_action(&session.ui);
    if (!pd_session_action(&session, action)) {
        switch (action) {
        case PD_ACTION_LEAVE_GAME:
            leaveAsked = true;
            break;
        case PD_ACTION_RESTART_GAME:
            pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
            core->reset(core);
            pk_heal_rtc(core);
            break;
        case PD_ACTION_SETTINGS_CHANGED:
            settings.screenMode = PD_SCREEN_SHARP;
            pd_settings_save(&settings, settingsPath);
            break;
        default:
            break;
        }
    }
    struct pd_input in = { .held = keys, .down = 0, .quickMenu = quickMenu, .ffHeld = ffHeld, .stickAngle = stickAngle };
    static uint32_t last;
    in.down = keys & ~last;
    last = keys;
    pd_session_tick(&session, &in, ms);
    gb_audio_pump();
    if (session.redraw) {
        struct pd_canvas c;
        pd_canvas_init(&c, PD_UI_WIDTH, PD_UI_HEIGHT, companionPx);
        pd_session_draw(&session, &c);
        companionDirty = true;
    }
}

bool pd_ios_take_leave(void) {
    bool l = leaveAsked;
    leaveAsked = false;
    return l;
}

void pd_ios_suspend(void) {
    if (!core) return;
    pk_finish_save(core, PK_FINISH_SAVE_FRAMES, NULL);
    pd_settings_save(&settings, settingsPath);
}

const uint32_t* pd_ios_frame(int* w, int* h, int* stride, bool* fills, uint32_t* barRgb) {
    if (!running) {
        *w = *h = *stride = 0;
        *fills = false;
        *barRgb = 0;
        return NULL;
    }
    if (session.wideReady) {
        // EXPAND's window, with the game's own frame put in its hole (what the 3DS's draw thread does).
        int ww = session.wideW, wh = session.wideH;
        if (ww > PD_IOS_TOP_W) ww = PD_IOS_TOP_W;
        if (wh > PD_IOS_TOP_H) wh = PD_IOS_TOP_H;
        const uint32_t* src = pd_session_wide(&session);
        for (int y = 0; y < wh; y++) memcpy(filled + y * ww, src + y * session.wideW, (size_t) ww * sizeof(uint32_t));
        const struct pd_wide_hole* hole = &session.wideHole;
        if (hole->w)
            for (int y = 0; y < hole->h && hole->y + y < wh; y++)
                memcpy(filled + (hole->y + y) * ww + hole->x, video + (hole->gameY + y) * PD_GAME_STRIDE + hole->gameX,
                       (size_t) hole->w * sizeof(uint32_t));
        *w = ww, *h = wh, *stride = ww;
        *fills = true;
        *barRgb = 0;
        return filled;
    }
    unsigned cw = 240, ch = 160;
    core->desiredVideoDimensions(core, &cw, &ch);
    *w = (int) cw, *h = (int) ch, *stride = PD_GAME_STRIDE;
    *fills = false;
    *barRgb = pd_session_game_boy(&session) ? 0xFFFFFF : 0;
    return video;
}

const uint32_t* pd_ios_overlay(bool* shown, bool* dim, bool* dirty) {
    *shown = running && session.overlayShown;
    *dim = session.overlayDim;
    *dirty = session.overlayDirty;
    return pd_session_overlay(&session);
}

const uint32_t* pd_ios_companion(bool* dirty) {
    *dirty = companionDirty;
    companionDirty = false;
    return companionPx;
}
