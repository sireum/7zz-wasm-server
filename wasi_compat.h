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

/* WASI has no resource limits.  7-zip 26.01 added a startup hook in
   UI/Console/Main.cpp that bumps RLIMIT_NOFILE; stub getrlimit and
   setrlimit so the call compiles and silently no-ops at runtime.
   rlim_t / struct rlimit / RLIMIT_NOFILE come from <sys/resource.h>
   on POSIX, which WASI does not provide. */
typedef unsigned long long rlim_t;
struct rlimit {
    rlim_t rlim_cur;
    rlim_t rlim_max;
};
#define RLIMIT_NOFILE 7
#define RLIM_INFINITY (~(rlim_t)0)
static inline int getrlimit(int resource, struct rlimit *rlim) {
    (void)resource;
    if (rlim) { rlim->rlim_cur = RLIM_INFINITY; rlim->rlim_max = RLIM_INFINITY; }
    return 0;
}
static inline int setrlimit(int resource, const struct rlimit *rlim) {
    (void)resource; (void)rlim; return 0;
}

#ifdef __cplusplus
}
#endif

#endif /* __wasi__ */
#endif /* WASI_COMPAT_H */
