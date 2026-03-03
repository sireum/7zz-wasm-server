/* WASI stub for sys/ioctl.h — no terminal ioctls */
#ifndef _WASI_SYS_IOCTL_H
#define _WASI_SYS_IOCTL_H

struct winsize {
    unsigned short ws_row;
    unsigned short ws_col;
    unsigned short ws_xpixel;
    unsigned short ws_ypixel;
};

#define TIOCGWINSZ 0x5413

static inline int ioctl(int fd, unsigned long request, ...) {
    (void)fd; (void)request;
    return -1; /* always fail — no terminal */
}

#endif /* _WASI_SYS_IOCTL_H */
