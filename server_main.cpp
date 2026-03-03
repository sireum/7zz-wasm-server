/* 7zz WASM Server — replaces MainAr.cpp with a stdin/stdout server loop.

   Protocol (binary, big-endian u32 lengths):

   Request:
     u32  workDir_len
     byte workDir[workDir_len]      (UTF-8, absolute path for chdir)
     u32  argc
     for each arg:
       u32  arg_len
       byte arg[arg_len]            (UTF-8)

   Response:
     i32  exitCode                  (7zz exit code, or -1 on internal error)
     u32  stdout_len
     byte stdout[stdout_len]
     u32  stderr_len
     byte stderr[stderr_len]

   The server loops until stdin is closed (read returns 0/EOF).
   Send argc=0 to request a clean shutdown (exitCode=0, empty stdout/stderr).

   7zz output capture: Main2() writes through g_StdStream/g_ErrStream
   (CStdOutStream wrapping FILE*). We redirect these to open_memstream()
   backed buffers, keeping the real stdout/stderr for protocol I/O. */

#include "StdAfx.h"

#include "../../../../C/CpuArch.h"

#include "../../../Common/MyException.h"
#include "../../../Common/StdOutStream.h"

#include "../../../Windows/ErrorMsg.h"

#include "../Common/ArchiveCommandLine.h"
#include "../Common/ExitCode.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unistd.h>

using namespace NWindows;

/* These global pointers are used by Main2() and all 7zz subsystems.
   We redirect them per-invocation to capture output. */
extern CStdOutStream *g_StdStream;
CStdOutStream *g_StdStream = NULL;
extern CStdOutStream *g_ErrStream;
CStdOutStream *g_ErrStream = NULL;

extern int Main2(int numArgs, char *args[]);

/* ---- Protocol I/O (big-endian u32 on raw fd 0/1) ---- */

static bool read_exact(void *buf, size_t n) {
    unsigned char *p = (unsigned char *)buf;
    while (n > 0) {
        ssize_t r = read(STDIN_FILENO, p, n);
        if (r <= 0) return false;
        p += (size_t)r;
        n -= (size_t)r;
    }
    return true;
}

static bool write_exact(const void *buf, size_t n) {
    const unsigned char *p = (const unsigned char *)buf;
    while (n > 0) {
        ssize_t w = write(STDOUT_FILENO, p, n);
        if (w <= 0) return false;
        p += (size_t)w;
        n -= (size_t)w;
    }
    return true;
}

static bool read_u32(uint32_t *out) {
    unsigned char b[4];
    if (!read_exact(b, 4)) return false;
    *out = ((uint32_t)b[0] << 24) | ((uint32_t)b[1] << 16) |
           ((uint32_t)b[2] << 8)  |  (uint32_t)b[3];
    return true;
}

static bool write_u32(uint32_t v) {
    unsigned char b[4];
    b[0] = (unsigned char)(v >> 24);
    b[1] = (unsigned char)(v >> 16);
    b[2] = (unsigned char)(v >> 8);
    b[3] = (unsigned char)(v);
    return write_exact(b, 4);
}

static bool write_i32(int32_t v) {
    return write_u32((uint32_t)v);
}

/* Read a length-prefixed string, NUL-terminated. Caller must free(). */
static char *read_str(uint32_t *out_len) {
    uint32_t len;
    if (!read_u32(&len)) return NULL;
    char *s = (char *)malloc(len + 1);
    if (!s) return NULL;
    if (len > 0 && !read_exact(s, len)) { free(s); return NULL; }
    s[len] = '\0';
    if (out_len) *out_len = len;
    return s;
}

/* ---- Server loop ---- */

int main() {
    for (;;) {
        /* Read working directory */
        uint32_t wd_len;
        char *workDir = read_str(&wd_len);
        if (!workDir) break;  /* EOF — clean exit */

        /* Read argc */
        uint32_t argc;
        if (!read_u32(&argc)) { free(workDir); break; }

        /* Shutdown request */
        if (argc == 0) {
            free(workDir);
            write_i32(0);
            write_u32(0);
            write_u32(0);
            break;
        }

        /* Read args */
        char **argv = (char **)malloc(sizeof(char *) * (argc + 1));
        if (!argv) { free(workDir); break; }
        uint32_t i;
        bool ok = true;
        for (i = 0; i < argc; i++) {
            argv[i] = read_str(NULL);
            if (!argv[i]) { ok = false; break; }
        }
        argv[argc] = NULL;

        if (!ok) {
            for (uint32_t j = 0; j < i; j++) free(argv[j]);
            free(argv);
            free(workDir);
            break;
        }

        /* chdir to working directory */
        if (wd_len > 0) {
            chdir(workDir);
        }

        /* Set up output capture via open_memstream.
           Main2() writes to g_StdStream/g_ErrStream which wrap FILE*.
           We create in-memory FILE* so output goes to buffers,
           keeping the real stdout/stderr for protocol communication. */
        char *out_buf = NULL;
        size_t out_len = 0;
        char *err_buf = NULL;
        size_t err_len = 0;
        FILE *out_fp = open_memstream(&out_buf, &out_len);
        FILE *err_fp = open_memstream(&err_buf, &err_len);

        CStdOutStream capOut(out_fp);
        CStdOutStream capErr(err_fp);
        g_StdStream = &capOut;
        g_ErrStream = &capErr;

        /* Also redirect the global g_StdOut/g_StdErr FILE* pointers.
           Main2() uses g_StdOut for percents/progress and warnings/errors
           output (separate from g_StdStream). Without this redirect,
           that output goes to the real stdout (fd 1), corrupting the
           length-prefixed protocol stream. */
        extern CStdOutStream g_StdOut;
        extern CStdOutStream g_StdErr;
        FILE *saved_stdout_fp = (FILE *)g_StdOut;
        FILE *saved_stderr_fp = (FILE *)g_StdErr;
        g_StdOut = CStdOutStream(out_fp);
        g_StdErr = CStdOutStream(err_fp);

        /* Call Main2 */
        int exitCode = 0;
        try {
            exitCode = Main2((int)argc, argv);
        } catch (const CNewException &) {
            exitCode = NExitCode::kMemoryError;
        } catch (const CMessagePathException &) {
            exitCode = NExitCode::kUserError;
        } catch (const CSystemException &e) {
            if (e.ErrorCode == E_OUTOFMEMORY)
                exitCode = NExitCode::kMemoryError;
            else
                exitCode = NExitCode::kFatalError;
        } catch (...) {
            exitCode = NExitCode::kFatalError;
        }

        /* Restore g_StdOut/g_StdErr so protocol I/O uses real stdout/stderr */
        g_StdOut = CStdOutStream(saved_stdout_fp);
        g_StdErr = CStdOutStream(saved_stderr_fp);
        g_StdStream = NULL;
        g_ErrStream = NULL;

        /* Flush and finalize captured output */
        fflush(out_fp);
        fflush(err_fp);
        fclose(out_fp);
        fclose(err_fp);

        /* Write response via protocol on raw stdout (fd 1) */
        write_i32((int32_t)exitCode);
        write_u32((uint32_t)out_len);
        if (out_len > 0)
            write_exact(out_buf, out_len);
        write_u32((uint32_t)err_len);
        if (err_len > 0)
            write_exact(err_buf, err_len);

        /* Clean up */
        free(out_buf);
        free(err_buf);
        for (uint32_t j = 0; j < argc; j++) free(argv[j]);
        free(argv);
        free(workDir);
    }

    return 0;
}
