#ifndef PK_RA_CLIENT_H
#define PK_RA_CLIENT_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** All strings passed to callbacks are borrowed for the duration of the call. */
struct pk_ra_user {
    const char* username;
    const char* display_name;
    const char* token;
    const char* avatar_url;
    uint32_t score;
    uint32_t softcore_score;
};

struct pk_ra_game {
    uint32_t id;
    const char* title;
    const char* badge_url;
    const char* hash;
    uint32_t achievements;
    uint32_t unlocked;
    uint32_t points;
    uint32_t points_unlocked;
};

struct pk_ra_event {
    int type;
    uint32_t id;
    const char* title;
    const char* description;
    const char* badge_url;
    const char* extra;
    uint32_t points;
};

struct pk_ra_callbacks {
    void (*request)(int id, const char* url, const char* post, const char* content_type);
    void (*login)(int result, const char* error, const struct pk_ra_user* user);
    void (*game_loaded)(int result, const char* error, const struct pk_ra_game* game);
    void (*event)(const struct pk_ra_event* event);
    void (*log)(const char* message);
    /** Optional alternate console mapping. Null uses the current GB/GBC/GBA player core. */
    uint32_t (*read_memory)(uint32_t address, uint8_t* out, uint32_t bytes);
};

/** One softcore client per process. Call from the client thread except for pk_ra_queue_response. */
bool pk_ra_init(const struct pk_ra_callbacks* callbacks);
bool pk_ra_ready(void);
void pk_ra_user_agent(char* out, size_t capacity);
void pk_ra_queue_response(int id, int status, const char* body, size_t length);
void pk_ra_pump(void);
void pk_ra_login_password(const char* username, const char* password);
void pk_ra_login_token(const char* username, const char* token);
void pk_ra_logout(void);
/** Pass the original cartridge's hash, before any emulated changes. */
bool pk_ra_load_game(const char* hash);
void pk_ra_unload_game(void);
void pk_ra_frame(void);
void pk_ra_idle(void);
void pk_ra_reset(void);
/** Caller frees the returned bytes. Null with no loaded set or failed serialization. */
uint8_t* pk_ra_serialize(size_t* size);
void pk_ra_deserialize(const uint8_t* data, size_t size);
void pk_ra_debug_status(char* out, size_t size);

#endif
