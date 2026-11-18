#ifndef PK_COOP_API_H
#define PK_COOP_API_H

#include <stdbool.h>
#include <stddef.h>

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

#endif
