#ifndef DIPLAY_API17_ERRNO_H
#define DIPLAY_API17_ERRNO_H

#include_next <errno.h>
#undef errno
extern int *__errno(void);
#define errno (*__errno())

#endif
