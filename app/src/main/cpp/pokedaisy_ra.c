// PokeDaisy — RetroAchievements (rcheevos rc_client) over the main mGBA core.
//
// One rc_client for the app's lifetime. Kotlin (achievements/RaNative.kt) does
// the HTTP: rc_client hands us a request in raServerCall, we pass it up as
// RaNative.serverCall(id, ...), and the answer comes back through
// raQueueResponse(id, ...). Answers are *queued*, not handed straight to
// rc_client, and raPump() delivers them: rc_client reads game memory while
// finishing a game load (address validation), so while a game runs the emu
// thread pumps between frames, the same as every other core memory read here.
// With no game running Kotlin pumps from its HTTP thread (login only).
//
// Only ever reads the player's core — never the FF-music render core.

#include <jni.h>
#include <android/log.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "pk_api.h"
#include "pk_ra_client.h"
#include "pk_ra_client_internal.h"

#include "rc_consoles.h"

#include "nds/pk_nds.h"

#define LOG_TAG "pokedaisy/ra"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static JavaVM* ra_vm;
static jclass ra_class;                 // com.pokedaisy.app.achievements.RaNative
static jmethodID ra_serverCall, ra_onLogin, ra_onGameLoaded, ra_onEvent, ra_onLog, ra_onLeaderboardEntries, ra_onAllProgress;
static jclass ra_stringClass;
static jmethodID ra_stringFromBytes;    // String(byte[], String charsetName)
static jstring ra_utf8;
static rc_client_t* ra_client;

static JNIEnv* raEnv(void) {
    JNIEnv* env = NULL;
    if ((*ra_vm)->GetEnv(ra_vm, (void**) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*ra_vm)->AttachCurrentThread(ra_vm, &env, NULL);
    }
    return env;
}

// Real UTF-8 -> String. Not NewStringUTF: that takes modified UTF-8, and an
// emoji (4-byte UTF-8) in a title aborts the app under CheckJNI.
static jstring raStr(JNIEnv* env, const char* s) {
    if (!s) {
        return NULL;
    }
    jsize len = (jsize) strlen(s);
    jbyteArray bytes = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, bytes, 0, len, (const jbyte*) s);
    jstring out = (*env)->NewObject(env, ra_stringClass, ra_stringFromBytes, bytes, ra_utf8);
    (*env)->DeleteLocalRef(env, bytes);
    return out;
}

// Calls a RaNative static callback and clears any exception it threw: a Java
// exception left pending makes the next JNI call abort the process (CheckJNI),
// or surfaces in whatever Kotlin frame called into rc_client.
static void raCallback(JNIEnv* env, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    (*env)->CallStaticVoidMethodV(env, ra_class, method, args);
    va_end(args);
    if ((*env)->ExceptionCheck(env)) {
        LOGW("RaNative callback threw");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void raDeleteLocal(JNIEnv* env, jobject o) {
    if (o) {
        (*env)->DeleteLocalRef(env, o);
    }
}

// --- rc_client callbacks -----------------------------------------------------

// RA's DS map (rcheevos consoleinfo.c): $0000000-$03FFFFF main RAM (0x02000000), $0400000-$0FFFFFF the DSi's
// extra RAM (none on a DS: zeros), $1000000-$1003FFF the ARM9's data TCM.
static uint32_t raReadMemoryNds(uint32_t address, uint8_t* buffer, uint32_t num_bytes) {
    uint32_t done = 0;
    while (done < num_bytes) {
        uint32_t a = address + done;
        uint32_t n;
        if (a < 0x400000) {
            n = 0x400000 - a;
            if (n > num_bytes - done) {
                n = num_bytes - done;
            }
            pk_nds_read(0x02000000u + a, buffer + done, (int) n);
        } else if (a < 0x1000000) {
            n = 0x1000000 - a;
            if (n > num_bytes - done) {
                n = num_bytes - done;
            }
            memset(buffer + done, 0, n);
        } else if (a < 0x1004000) {
            const uint8_t* dtcm = pk_nds_dtcm();
            if (!dtcm) {
                break;
            }
            n = 0x1004000 - a;
            if (n > num_bytes - done) {
                n = num_bytes - done;
            }
            memcpy(buffer + done, dtcm + (a - 0x1000000), n);
        } else {
            break;
        }
        done += n;
    }
    return done;
}

static uint32_t raReadMemoryImpl(uint32_t address, uint8_t* buffer, uint32_t num_bytes) {
    if (pk_nds_active()) {
        return raReadMemoryNds(address, buffer, num_bytes);
    }
    return pk_api_ra_read_memory(address, buffer, num_bytes);
}

static void raServerCall(int id, const char* url, const char* post, const char* contentType) {
    JNIEnv* env = raEnv();
    jstring u = raStr(env, url);
    jstring p = raStr(env, post);
    jstring t = raStr(env, contentType);
    raCallback(env, ra_serverCall, (jint) id, u, p, t);
    raDeleteLocal(env, u);
    raDeleteLocal(env, p);
    raDeleteLocal(env, t);
}

static void raLog(const char* message) {
    JNIEnv* env = raEnv();
    jstring text = raStr(env, message);
    raCallback(env, ra_onLog, text);
    raDeleteLocal(env, text);
}

static void raEvent(const struct pk_ra_event* event) {
    JNIEnv* env = raEnv();
    jstring title = raStr(env, event->title), description = raStr(env, event->description);
    jstring badge = raStr(env, event->badge_url), extra = raStr(env, event->extra);
    raCallback(env, ra_onEvent, (jint) event->type, (jint) event->id, title, description, badge, extra, (jint) event->points);
    raDeleteLocal(env, title);
    raDeleteLocal(env, description);
    raDeleteLocal(env, badge);
    raDeleteLocal(env, extra);
}

static void raLoginDone(int result, const char* error, const struct pk_ra_user* user) {
    JNIEnv* env = raEnv();
    jstring err = raStr(env, error);
    jstring name = raStr(env, user ? user->username : NULL);
    jstring display = raStr(env, user ? user->display_name : NULL);
    jstring token = raStr(env, user ? user->token : NULL);
    jstring avatar = raStr(env, user ? user->avatar_url : NULL);
    raCallback(env, ra_onLogin, (jint) result, err, name, display, token, avatar,
               (jint) (user ? user->score : 0), (jint) (user ? user->softcore_score : 0));
    raDeleteLocal(env, err);
    raDeleteLocal(env, name);
    raDeleteLocal(env, display);
    raDeleteLocal(env, token);
    raDeleteLocal(env, avatar);
}

static void raGameLoaded(int result, const char* error, const struct pk_ra_game* game) {
    JNIEnv* env = raEnv();
    jstring err = raStr(env, error);
    jstring title = raStr(env, game->title);
    jstring badge = raStr(env, game->badge_url);
    jstring hash = raStr(env, game->hash);
    raCallback(env, ra_onGameLoaded, (jint) result, err, (jint) game->id, title, badge, hash,
               (jint) game->achievements, (jint) game->unlocked, (jint) game->points, (jint) game->points_unlocked);
    raDeleteLocal(env, err);
    raDeleteLocal(env, title);
    raDeleteLocal(env, badge);
    raDeleteLocal(env, hash);
}

// --- JNI ---------------------------------------------------------------------

#define RA_FN(name) Java_com_pokedaisy_app_achievements_RaNative_##name

// rc_client.c's RC_CLIENT_ACHIEVEMENT_WARNING_ID: ids from here up are its placeholders.
#define RA_WARNING_ACHIEVEMENT_ID 101000001u

JNIEXPORT jboolean JNICALL
RA_FN(raInit)(JNIEnv* env, jobject thiz) {
    if (ra_client) {
        return JNI_TRUE;
    }
    (*env)->GetJavaVM(env, &ra_vm);
    jclass cls = (*env)->GetObjectClass(env, thiz);
    ra_class = (*env)->NewGlobalRef(env, cls);
    ra_serverCall = (*env)->GetStaticMethodID(env, cls, "serverCall",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    ra_onLogin = (*env)->GetStaticMethodID(env, cls, "onLogin",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;II)V");
    ra_onGameLoaded = (*env)->GetStaticMethodID(env, cls, "onGameLoaded",
        "(ILjava/lang/String;ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;IIII)V");
    ra_onEvent = (*env)->GetStaticMethodID(env, cls, "onEvent",
        "(IILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    ra_onLog = (*env)->GetStaticMethodID(env, cls, "onLog", "(Ljava/lang/String;)V");
    ra_onLeaderboardEntries = (*env)->GetStaticMethodID(env, cls, "onLeaderboardEntries", "(IZILjava/lang/String;[BII)V");
    ra_onAllProgress = (*env)->GetStaticMethodID(env, cls, "onAllProgress", "(II[B)V");
    jclass str = (*env)->FindClass(env, "java/lang/String");
    ra_stringClass = (*env)->NewGlobalRef(env, str);
    ra_stringFromBytes = (*env)->GetMethodID(env, str, "<init>", "([BLjava/lang/String;)V");
    ra_utf8 = (*env)->NewGlobalRef(env, (*env)->NewStringUTF(env, "UTF-8"));
    if (!ra_serverCall || !ra_onLogin || !ra_onGameLoaded || !ra_onEvent || !ra_onLog || !ra_onLeaderboardEntries || !ra_onAllProgress) {
        return JNI_FALSE;               // NoSuchMethodError is pending for Kotlin
    }
    const struct pk_ra_callbacks callbacks = {
        .request = raServerCall, .login = raLoginDone, .game_loaded = raGameLoaded,
        .event = raEvent, .log = raLog, .read_memory = raReadMemoryImpl,
    };
    if (!pk_ra_init(&callbacks)) return JNI_FALSE;
    ra_client = pk_ra_client_handle();
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
RA_FN(raUserAgentClause)(JNIEnv* env, jobject thiz) {
    char buf[64];
    pk_ra_user_agent(buf, sizeof(buf));
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL
RA_FN(raQueueResponse)(JNIEnv* env, jobject thiz, jint id, jint status, jbyteArray jBody) {
    jsize len = jBody ? (*env)->GetArrayLength(env, jBody) : 0;
    jbyte* bytes = len ? (*env)->GetByteArrayElements(env, jBody, NULL) : NULL;
    pk_ra_queue_response(id, bytes || !len ? status : RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR,
                           (const char*) bytes, bytes ? (size_t) len : 0);
    if (bytes) (*env)->ReleaseByteArrayElements(env, jBody, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
RA_FN(raPump)(JNIEnv* env, jobject thiz) {
    pk_ra_pump();
}

JNIEXPORT void JNICALL
RA_FN(raLoginWithPassword)(JNIEnv* env, jobject thiz, jstring jUser, jstring jPassword) {
    const char* user = (*env)->GetStringUTFChars(env, jUser, NULL);
    const char* pass = (*env)->GetStringUTFChars(env, jPassword, NULL);
    pk_ra_login_password(user, pass);
    (*env)->ReleaseStringUTFChars(env, jUser, user);
    (*env)->ReleaseStringUTFChars(env, jPassword, pass);
}

JNIEXPORT void JNICALL
RA_FN(raLoginWithToken)(JNIEnv* env, jobject thiz, jstring jUser, jstring jToken) {
    const char* user = (*env)->GetStringUTFChars(env, jUser, NULL);
    const char* token = (*env)->GetStringUTFChars(env, jToken, NULL);
    pk_ra_login_token(user, token);
    (*env)->ReleaseStringUTFChars(env, jUser, user);
    (*env)->ReleaseStringUTFChars(env, jToken, token);
}

JNIEXPORT void JNICALL
RA_FN(raLogout)(JNIEnv* env, jobject thiz) {
    pk_ra_logout();
}

// Emu thread, after pkInit: asks the server which game [jHash] is - RetroAchievements' hash of the ROM *file*
// (RomIdentity.raHash: the MD5 for GB / GBA, RA's DS method for a DS cart). Never the ROM the core runs: mGBA writes
// the cart's GPIO registers into it (0xC4-0xC9, an RTC game's) when a state loads, and cheats patch it, so after a
// resume its hash was one the server didn't know - NO ACHIEVEMENTS FOR THIS ROM until the game was restarted (GitHub #197).
JNIEXPORT jboolean JNICALL
RA_FN(raLoadGame)(JNIEnv* env, jobject thiz, jstring jHash) {
    if (!ra_client || !jHash) {
        return JNI_FALSE;
    }
    const char* hash = (*env)->GetStringUTFChars(env, jHash, NULL);
    if (!hash) {
        return JNI_FALSE;
    }
    bool started = pk_ra_load_game(hash);
    (*env)->ReleaseStringUTFChars(env, jHash, hash);
    return started;
}

JNIEXPORT void JNICALL
RA_FN(raUnloadGame)(JNIEnv* env, jobject thiz) {
    pk_ra_unload_game();
}

JNIEXPORT void JNICALL
RA_FN(raDoFrame)(JNIEnv* env, jobject thiz) {
    pk_ra_frame();
}

JNIEXPORT void JNICALL
RA_FN(raIdle)(JNIEnv* env, jobject thiz) {
    pk_ra_idle();
}

JNIEXPORT void JNICALL
RA_FN(raReset)(JNIEnv* env, jobject thiz) {
    pk_ra_reset();
}

/** Achievement / leaderboard progress to keep beside a savestate, or null (no game). */
JNIEXPORT jbyteArray JNICALL
RA_FN(raSerializeProgress)(JNIEnv* env, jobject thiz) {
    size_t size;
    uint8_t* bytes = pk_ra_serialize(&size);
    jbyteArray out = NULL;
    if (bytes && size <= INT32_MAX) {
        out = (*env)->NewByteArray(env, (jsize) size);
        if (out) (*env)->SetByteArrayRegion(env, out, 0, (jsize) size, (const jbyte*) bytes);
    }
    free(bytes);
    return out;
}

/** After a state load: restores [jData]'s progress, or resets it when null (a state from before). */
JNIEXPORT void JNICALL
RA_FN(raDeserializeProgress)(JNIEnv* env, jobject thiz, jbyteArray jData) {
    if (!jData) {
        pk_ra_deserialize(NULL, 0);
        return;
    }
    jsize len = (*env)->GetArrayLength(env, jData);
    jbyte* data = (*env)->GetByteArrayElements(env, jData, NULL);
    pk_ra_deserialize((const uint8_t*) data, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, jData, data, JNI_ABORT);
}

// The loaded game's achievements as UTF-8 text for RaNative to parse: records
// split by 0x1E, fields by 0x1F - bucket type, bucket label, id, title,
// description, points, unlocked, badge URL, locked badge URL, measured progress,
// measured percent, rarity, type. In rc_client's PROGRESS grouping order
// (recently unlocked, active challenges, almost there, locked, ..., unlocked).
JNIEXPORT jbyteArray JNICALL
RA_FN(raAchievementList)(JNIEnv* env, jobject thiz) {
    if (!ra_client || !rc_client_has_achievements(ra_client)) {
        return NULL;
    }
    rc_client_achievement_list_t* list = rc_client_create_achievement_list(ra_client,
        RC_CLIENT_ACHIEVEMENT_CATEGORY_CORE, RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_PROGRESS);
    if (!list) {
        return NULL;
    }
    size_t cap = 4096, len = 0;
    char* out = malloc(cap);
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_achievement_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_achievements; i++) {
            const rc_client_achievement_t* a = bucket->achievements[i];
            // rc_client's own placeholders ("Warning: Unknown Emulator" for a client the
            // server doesn't recognise yet) - its game summary skips them too.
            if (a->id >= RA_WARNING_ACHIEVEMENT_ID) {
                continue;
            }
            for (;;) {
                int n = snprintf(out + len, cap - len, "%u\x1F%s\x1F%u\x1F%s\x1F%s\x1F%u\x1F%u\x1F%s\x1F%s\x1F%s\x1F%.3f\x1F%.3f\x1F%u\x1E",
                    (unsigned) bucket->bucket_type, bucket->label ? bucket->label : "",
                    (unsigned) a->id, a->title ? a->title : "", a->description ? a->description : "",
                    (unsigned) a->points, (unsigned) a->unlocked,
                    a->badge_url ? a->badge_url : "", a->badge_locked_url ? a->badge_locked_url : "",
                    a->measured_progress, a->measured_percent, a->rarity, (unsigned) a->type);
                if (n >= 0 && (size_t) n < cap - len) {
                    len += (size_t) n;
                    break;
                }
                cap *= 2;
                out = realloc(out, cap);
            }
        }
    }
    rc_client_destroy_achievement_list(list);
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) len);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize) len, (const jbyte*) out);
    free(out);
    return arr;
}

// Debug: one line on what rc_client sees - rich presence (built from game memory, so
// it shows whether reads land), memory reads so far / short ones, and achievements by
// state (active / unlocked / disabled / inactive), naming every one that isn't active.
JNIEXPORT jstring JNICALL
RA_FN(raDebugStatus)(JNIEnv* env, jobject thiz) {
    char out[2048];
    pk_ra_debug_status(out, sizeof(out));
    return raStr(env, out);
}

// A growable text buffer for the record lists below.
typedef struct { char* p; size_t len, cap; } ra_text;

static void raTextAppend(ra_text* t, const char* fmt, ...) __attribute__((format(printf, 2, 3)));
static void raTextAppend(ra_text* t, const char* fmt, ...) {
    for (;;) {
        va_list ap;
        va_start(ap, fmt);
        int n = vsnprintf(t->p + t->len, t->cap - t->len, fmt, ap);
        va_end(ap);
        if (n >= 0 && (size_t) n < t->cap - t->len) {
            t->len += (size_t) n;
            return;
        }
        t->cap *= 2;
        t->p = realloc(t->p, t->cap);
    }
}

static jbyteArray raTextToBytes(JNIEnv* env, ra_text* t) {
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) t->len);
    (*env)->SetByteArrayRegion(env, arr, 0, (jsize) t->len, (const jbyte*) t->p);
    free(t->p);
    return arr;
}

// The loaded game's leaderboards, records split by 0x1E, fields by 0x1F: bucket label,
// id, title, description, lower is better.
JNIEXPORT jbyteArray JNICALL
RA_FN(raLeaderboardList)(JNIEnv* env, jobject thiz) {
    if (!ra_client || !rc_client_has_leaderboards(ra_client)) {
        return NULL;
    }
    rc_client_leaderboard_list_t* list = rc_client_create_leaderboard_list(ra_client, RC_CLIENT_LEADERBOARD_LIST_GROUPING_NONE);
    if (!list) {
        return NULL;
    }
    ra_text t = { malloc(4096), 0, 4096 };
    for (uint32_t b = 0; b < list->num_buckets; b++) {
        const rc_client_leaderboard_bucket_t* bucket = &list->buckets[b];
        for (uint32_t i = 0; i < bucket->num_leaderboards; i++) {
            const rc_client_leaderboard_t* l = bucket->leaderboards[i];
            raTextAppend(&t, "%s\x1F%u\x1F%s\x1F%s\x1F%u\x1E", bucket->label ? bucket->label : "", (unsigned) l->id,
                l->title ? l->title : "", l->description ? l->description : "", (unsigned) l->lower_is_better);
        }
    }
    rc_client_destroy_leaderboard_list(list);
    return raTextToBytes(env, &t);
}

// userdata for a fetch: Kotlin's request token, and which half of the page it is.
typedef struct { int token; int aroundUser; } ra_fetch;

static void raEntriesFetched(int result, const char* error_message, rc_client_leaderboard_entry_list_t* list,
                             rc_client_t* client, void* userdata) {
    (void) client;
    ra_fetch* f = userdata;
    JNIEnv* env = raEnv();
    jbyteArray bytes = NULL;
    jint total = 0, userIndex = -1;
    if (list) {
        // Records (0x1E) of fields (0x1F): rank, user, score as the game shows it.
        ra_text t = { malloc(1024), 0, 1024 };
        for (uint32_t i = 0; i < list->num_entries; i++) {
            const rc_client_leaderboard_entry_t* e = &list->entries[i];
            raTextAppend(&t, "%u\x1F%s\x1F%s\x1E", (unsigned) e->rank, e->user ? e->user : "", e->display);
        }
        bytes = raTextToBytes(env, &t);
        total = (jint) list->total_entries;
        userIndex = (jint) list->user_index;
        rc_client_destroy_leaderboard_entry_list(list);
    }
    jstring err = raStr(env, error_message);
    raCallback(env, ra_onLeaderboardEntries, (jint) f->token, (jboolean) (f->aroundUser != 0),
                                 (jint) result, err, bytes, total, userIndex);
    raDeleteLocal(env, err);
    raDeleteLocal(env, bytes);
    free(f);
}

// Asks the server for [count] entries of leaderboard [id]: from rank [first], or
// (aroundUser) centred on the signed-in player. Answers in RaNative.onLeaderboardEntries.
JNIEXPORT void JNICALL
RA_FN(raFetchLeaderboard)(JNIEnv* env, jobject thiz, jint token, jint id, jboolean aroundUser, jint first, jint count) {
    if (!ra_client) {
        return;
    }
    ra_fetch* f = malloc(sizeof(*f));
    f->token = token;
    f->aroundUser = aroundUser;
    if (aroundUser) {
        rc_client_begin_fetch_leaderboard_entries_around_user(ra_client, (uint32_t) id, (uint32_t) count, raEntriesFetched, f);
    } else {
        rc_client_begin_fetch_leaderboard_entries(ra_client, (uint32_t) id, (uint32_t) first, (uint32_t) count, raEntriesFetched, f);
    }
}

static void raAllProgressFetched(int result, const char* error_message, rc_client_all_user_progress_t* list,
                                 rc_client_t* client, void* userdata) {
    (void) error_message;
    (void) client;
    JNIEnv* env = raEnv();
    jbyteArray bytes = NULL;
    if (list) {
        // Records (0x1E) of fields (0x1F): game id, achievements, unlocked (softcore), unlocked (hardcore).
        ra_text t = { malloc(4096), 0, 4096 };
        for (uint32_t i = 0; i < list->num_entries; i++) {
            const rc_client_all_user_progress_entry_t* e = &list->entries[i];
            raTextAppend(&t, "%u\x1F%u\x1F%u\x1F%u\x1E", (unsigned) e->game_id, (unsigned) e->num_achievements,
                (unsigned) e->num_unlocked_achievements, (unsigned) e->num_unlocked_achievements_hardcore);
        }
        bytes = raTextToBytes(env, &t);
        rc_client_destroy_all_user_progress(list);
    }
    raCallback(env, ra_onAllProgress, (jint) (intptr_t) userdata, (jint) result, bytes);
    raDeleteLocal(env, bytes);
}

// The signed-in player's unlock counts for every game of [console] (an RC_CONSOLE_* id) they've played; answers in
// RaNative.onAllProgress (the library's INFO).
JNIEXPORT void JNICALL
RA_FN(raFetchAllProgress)(JNIEnv* env, jobject thiz, jint token, jint console) {
    if (ra_client) {
        rc_client_begin_fetch_all_user_progress(ra_client, (uint32_t) console, raAllProgressFetched, (void*) (intptr_t) token);
    }
}
