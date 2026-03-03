/* Minimal C++ exception ABI stubs and POSIX compat for WASI.
   7-zip only throws on fatal errors (OOM), so aborting is acceptable.
   chmod/fchmod/fchmodat are no-ops since WASI has no file permission model. */
#include <cstdlib>
#include <cstddef>
#include <sys/types.h>
#include <sys/stat.h>

extern "C" {

/* C++ exception ABI stubs */
void *__cxa_allocate_exception(size_t) {
    abort();
    return nullptr; // unreachable
}

void __cxa_throw(void *, void *, void (*)(void *)) {
    abort();
}

void __cxa_rethrow(void) {
    abort();
}

void *__cxa_begin_catch(void *exn) {
    return exn;
}

void __cxa_end_catch(void) {}

/* WASI has no file permission model — make chmod always succeed.
   Uses --wrap linker feature to intercept calls. */
int __wrap_chmod(const char *path, mode_t mode) {
    (void)path; (void)mode;
    return 0;
}

int __wrap_fchmod(int fd, mode_t mode) {
    (void)fd; (void)mode;
    return 0;
}

int __wrap_fchmodat(int dirfd, const char *path, mode_t mode, int flags) {
    (void)dirfd; (void)path; (void)mode; (void)flags;
    return 0;
}

} // extern "C"
