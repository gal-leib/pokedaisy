// pd_ios_smoke: the iPad app's C side with no screens, to check it builds and runs where there's no Mac.
//
//   pd_ios_smoke ROM SAVE DATADIR [FRAMES]
//
// Runs FRAMES refreshes (default 600) holding nothing, then taps the companion's middle and prints what it saw. The save
// is used where it is: pass a copy.
#include <stdio.h>
#include <stdlib.h>

#include "pd_ios.h"

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: pd_ios_smoke ROM SAVE DATADIR [FRAMES]\n");
        return 2;
    }
    pd_ios_init(argv[3], 48000);
    char error[128];
    if (!pd_ios_open(argv[1], argv[2], error, sizeof(error))) {
        fprintf(stderr, "open: %s\n", error);
        return 1;
    }
    printf("%s%s%s\n", pd_ios_title(), *pd_ios_unsupported() ? ": " : "", pd_ios_unsupported());
    int n = argc > 4 ? atoi(argv[4]) : 600;
    size_t sound = 0;
    static int16_t buf[4096 * 2];
    for (int i = 0; i < n; i++) {
        // START now and then, to get past the title.
        pd_ios_tick(i % 120 == 100 ? PD_IOS_KEY_START : 0, false, false, -1, 16);
        sound += pd_ios_audio_read(buf, 4096);
    }
    pd_ios_touch(160, 120, true);
    pd_ios_tick(0, false, false, -1, 16);
    pd_ios_touch(0, 0, false);
    pd_ios_tick(0, false, false, -1, 16);
    int w, h, stride;
    bool fills;
    uint32_t bar;
    const uint32_t* frame = pd_ios_frame(&w, &h, &stride, &fills, &bar);
    unsigned long sum = 0;
    for (int y = 0; frame && y < h; y++)
        for (int x = 0; x < w; x++) sum += frame[y * stride + x] & 0xFFFFFF;
    bool dirty;
    const uint32_t* comp = pd_ios_companion(&dirty);
    unsigned long csum = 0;
    for (int i = 0; i < PD_IOS_COMPANION_W * PD_IOS_COMPANION_H; i++) csum += comp[i] & 0xFFFFFF;
    printf("frame %dx%d sum %lu, companion sum %lu, %zu sound frames over %d refreshes\n", w, h, sum, csum, sound, n);
    pd_ios_close();
    return 0;
}
