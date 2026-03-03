/* WASI compatibility stubs for functions not available in WASI */
#ifndef WASI_COMPAT_H
#define WASI_COMPAT_H

#ifdef __wasi__

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/* WASI has no process hierarchy */
static inline pid_t getppid(void) { return 1; }

/* WASI has no file permission mask */
static inline mode_t umask(mode_t mask) { (void)mask; return 0022; }

/* WASI has no file ownership */
static inline int chown(const char *path, uid_t owner, gid_t group) {
    (void)path; (void)owner; (void)group; return 0;
}
static inline int fchown(int fd, uid_t owner, gid_t group) {
    (void)fd; (void)owner; (void)group; return 0;
}
static inline int lchown(const char *path, uid_t owner, gid_t group) {
    (void)path; (void)owner; (void)group; return 0;
}

#ifdef __cplusplus
}
#endif

#endif /* __wasi__ */
#endif /* WASI_COMPAT_H */
