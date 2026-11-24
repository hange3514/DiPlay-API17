#ifndef DIPLAY_API17_SYS_SELECT_H
#define DIPLAY_API17_SYS_SELECT_H

#include <stddef.h>
#include <sys/time.h>

#define FD_SETSIZE 1024
typedef unsigned long fd_mask;
typedef struct { unsigned long fds_bits[FD_SETSIZE / (8 * sizeof(long))]; } fd_set;

#define FD_ZERO(set) do { size_t __i; unsigned long *__p = (set)->fds_bits; \
    for (__i = 0; __i < sizeof(fd_set) / sizeof(long); ++__i) __p[__i] = 0; } while (0)
#define FD_SET(fd, set) ((set)->fds_bits[(fd) / (8 * sizeof(long))] |= (1UL << ((fd) % (8 * sizeof(long)))))
#define FD_CLR(fd, set) ((set)->fds_bits[(fd) / (8 * sizeof(long))] &= ~(1UL << ((fd) % (8 * sizeof(long)))))
#define FD_ISSET(fd, set) (((set)->fds_bits[(fd) / (8 * sizeof(long))] & (1UL << ((fd) % (8 * sizeof(long))))) != 0)

#ifdef __cplusplus
extern "C" {
#endif
int select(int nfds, fd_set *readfds, fd_set *writefds, fd_set *exceptfds, struct timeval *timeout);
#ifdef __cplusplus
}
#endif

#endif
