#include "pk_coop_api.h"

#include <stdio.h>

#include "pk_call.h"
#include "pk_coop.h"
#include "pk_coop_after.h"
#include "pk_coop_field.h"
#include "pk_session.h"

uint32_t pk_coop_game_call(struct mCore* core, uint32_t fn, const uint32_t args[4]) {
    uint32_t r0;
    return pk_call(core, fn, args, &r0) ? r0 : 0xFFFFFFFFu;
}

bool pk_api_coop_enable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    bool enabled = g.core && pk_is_gba(g.core) && pk_coop_install(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return enabled;
}

bool pk_api_coop_link_enable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    bool enabled = g.core && pk_is_gba(g.core) && pk_coop_link_install(g.core, pk_coop_game_call);
    pthread_rwlock_unlock(&pk_coreLock);
    return enabled;
}

void pk_api_coop_disable(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    if (g.core) pk_coop_uninstall(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
}

void pk_api_coop_refusal(bool link, char* out, size_t capacity) {
    if (!out || !capacity) return;
    pthread_rwlock_rdlock(&pk_coreLock);
    const char* refusal = link ? pk_coop_link_refused() : pk_coop_refused();
    snprintf(out, capacity, "%s", refusal ? refusal : "");
    pthread_rwlock_unlock(&pk_coreLock);
}

bool pk_api_coop_battle_starting(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool starting = g.core && pk_is_gba(g.core) && pk_coop_battle_starting(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return starting;
}

bool pk_api_coop_on_free_field(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool free = g.core && pk_is_gba(g.core) && pk_coop_on_free_field(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return free;
}

bool pk_api_coop_partner_free(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    bool free = g.core && pk_is_gba(g.core) && pk_coop_partner_free(g.core);
    pthread_rwlock_unlock(&pk_coreLock);
    return free;
}

int pk_api_coop_join_kind(void) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int kind = g.core && pk_is_gba(g.core) ? pk_coop_join_kind(g.core) : 0;
    pthread_rwlock_unlock(&pk_coreLock);
    return kind;
}

void pk_api_coop_join_trainers(int out[2]) {
    if (!out) return;
    out[0] = out[1] = 0;
    pthread_rwlock_rdlock(&pk_coreLock);
    if (g.core && pk_is_gba(g.core)) pk_coop_join_trainers(g.core, out);
    pthread_rwlock_unlock(&pk_coreLock);
}

/* Post-battle operations call the game at its next safe point, returning -1 when it must retry. */
#define COOP_AFTER_CALL(expr)                                  \
    pthread_rwlock_rdlock(&pk_coreLock);                        \
    int result = -1;                                           \
    if (g.core && pk_is_gba(g.core)) {                          \
        pk_coop_after_set_call(pk_coop_game_call);              \
        pk_coop_after_set_cap(pk_call_set_cap);                 \
        result = (expr);                                       \
    }                                                          \
    pthread_rwlock_unlock(&pk_coreLock);                        \
    return result;

int pk_api_coop_give_mon(const uint8_t* mon, size_t length) {
    if (!mon || length != PK_API_COOP_MON_SIZE) return 2;
    COOP_AFTER_CALL(pk_coop_give_mon(g.core, mon))
}

int pk_api_coop_remove_item(int item, int count) {
    COOP_AFTER_CALL(pk_coop_remove_item(g.core, item, count))
}

int pk_api_coop_set_trainer_flag(int trainer_id) {
    COOP_AFTER_CALL(pk_coop_set_trainer_flag(g.core, trainer_id))
}

int pk_api_coop_prize(int trainer_id, bool single) {
    pthread_rwlock_rdlock(&pk_coreLock);
    int prize = -1;
    if (g.core && pk_is_gba(g.core)) {
        prize = single ? pk_coop_prize_single(g.core, trainer_id) : pk_coop_prize(g.core, trainer_id);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return prize;
}

int pk_api_coop_add_money(int amount) {
    COOP_AFTER_CALL(pk_coop_add_money(g.core, amount))
}

int pk_api_coop_write_party_mon(int slot, const uint8_t* mon, size_t length) {
    if (!mon || length != PK_API_COOP_MON_SIZE) return 0;
    COOP_AFTER_CALL(pk_coop_write_party_mon(g.core, slot, mon))
}

int pk_api_coop_white_out(void) {
    COOP_AFTER_CALL(pk_coop_white_out(g.core))
}

int pk_api_coop_evolve(int slot) {
    COOP_AFTER_CALL(pk_coop_evolve(g.core, slot))
}

int pk_api_coop_save_game(void) {
    COOP_AFTER_CALL(pk_coop_save_game(g.core))
}

int pk_api_coop_release_script(void) {
    pthread_rwlock_wrlock(&pk_coreLock);
    int result = -1;
    if (g.core && pk_is_gba(g.core)) {
        pk_coop_after_set_call(pk_coop_game_call);
        result = pk_coop_release_script(g.core);
    }
    pthread_rwlock_unlock(&pk_coreLock);
    return result;
}

#undef COOP_AFTER_CALL
