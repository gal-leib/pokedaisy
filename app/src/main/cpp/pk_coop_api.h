#ifndef PK_COOP_API_H
#define PK_COOP_API_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

/** Co-op's retail check and battle bridge for the player's GBA core. Call mutations between frames. */
bool pk_api_coop_enable(void);
bool pk_api_coop_link_enable(void);
void pk_api_coop_disable(void);
/** The most recent refusal, as NUL-terminated ASCII. Empty when the check has not reported a refusal. */
void pk_api_coop_refusal(bool link, char* out, size_t capacity);
bool pk_api_coop_battle_starting(void);
bool pk_api_coop_on_free_field(void);
bool pk_api_coop_partner_free(void);
/** 0 = no join, 1 = wild, 2 = two trainers, 3 = one trainer with enough Pokémon. */
int pk_api_coop_join_kind(void);
/** Writes the two trainer IDs, or zeros. */
void pk_api_coop_join_trainers(int out[2]);

/* Post-battle calls. See pk_coop_after.h for the per-operation return values. */
enum { PK_API_COOP_MON_SIZE = 100 };
int pk_api_coop_give_mon(const uint8_t* mon, size_t length);
int pk_api_coop_remove_item(int item, int count);
int pk_api_coop_set_trainer_flag(int trainer_id);
int pk_api_coop_prize(int trainer_id, bool single);
int pk_api_coop_add_money(int amount);
int pk_api_coop_write_party_mon(int slot, const uint8_t* mon, size_t length);
int pk_api_coop_white_out(void);
int pk_api_coop_evolve(int slot);
int pk_api_coop_save_game(void);
int pk_api_coop_release_script(void);

#endif
