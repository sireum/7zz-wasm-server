/* WASI stub for pwd.h — no user/group database */
#ifndef _WASI_PWD_H
#define _WASI_PWD_H

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

struct passwd {
    char   *pw_name;
    char   *pw_passwd;
    uid_t   pw_uid;
    gid_t   pw_gid;
    char   *pw_gecos;
    char   *pw_dir;
    char   *pw_shell;
};

static inline struct passwd *getpwuid(uid_t uid) { (void)uid; return (struct passwd *)0; }
static inline struct passwd *getpwnam(const char *name) { (void)name; return (struct passwd *)0; }

#ifdef __cplusplus
}
#endif

#endif /* _WASI_PWD_H */
