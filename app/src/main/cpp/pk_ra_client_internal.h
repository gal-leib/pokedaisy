#ifndef PK_RA_CLIENT_INTERNAL_H
#define PK_RA_CLIENT_INTERNAL_H

#include "rc_client.h"

/** Between client operations on the owning thread, for the existing list adapters. */
rc_client_t* pk_ra_client_handle(void);

#endif
