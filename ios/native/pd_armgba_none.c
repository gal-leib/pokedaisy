// The iPad build runs every game on mGBA (an interpreter: iOS gives no app the executable memory a recompiler needs).
// 3ds/host's shared C asks pd_armgba_is() before it reaches into armGBA; here it never is, so the rest are never
// called. They're defined only so the shared C links unchanged.
#include "../../3ds/emu/pd_armgba.h"

bool pd_armgba_is(const struct mCore* core) {
    (void) core;
    return false;
}

void pd_armgba_skip_video(bool skip) {
    (void) skip;
}

bool pd_armgba_write(uint32_t addr, const void* data, size_t len) {
    (void) addr; (void) data; (void) len;
    return false;
}

bool pd_armgba_save_state(const char* path) {
    (void) path;
    return false;
}

enum pd_armgba_state pd_armgba_load_state(const char* path) {
    (void) path;
    return PD_ARMGBA_STATE_FOREIGN;
}

void pd_armgba_set_bkpt(bool (*handler)(uint32_t address, unsigned immediate)) {
    (void) handler;
}

uint32_t pd_armgba_reg(int n) {
    (void) n;
    return 0;
}

void pd_armgba_set_reg(int n, uint32_t value) {
    (void) n; (void) value;
}

void pd_armgba_jump(uint32_t to) {
    (void) to;
}

int pd_armgba_vcount(void) {
    return 0;
}
