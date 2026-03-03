/* WASI stub for sys/sysmacros.h — device number macros */
#ifndef _WASI_SYS_SYSMACROS_H
#define _WASI_SYS_SYSMACROS_H

#define major(dev) ((unsigned)((dev) >> 8) & 0xFF)
#define minor(dev) ((unsigned)(dev) & 0xFF)
#define makedev(maj, min) (((unsigned)(maj) << 8) | (unsigned)(min))

#endif /* _WASI_SYS_SYSMACROS_H */
