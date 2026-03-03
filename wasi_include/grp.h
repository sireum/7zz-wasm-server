/* WASI stub for grp.h — no user/group database */
#ifndef _WASI_GRP_H
#define _WASI_GRP_H

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct group {
    char   *gr_name;
    char   *gr_passwd;
    gid_t   gr_gid;
    char  **gr_mem;
};

static inline struct group *getgrgid(gid_t gid) { (void)gid; return (struct group *)0; }
static inline struct group *getgrnam(const char *name) { (void)name; return (struct group *)0; }

#ifdef __cplusplus
}
#endif

#endif /* _WASI_GRP_H */
