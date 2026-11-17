#ifndef DIPLAY_API17_SYS_TIME_H
#define DIPLAY_API17_SYS_TIME_H

#include <stdint.h>

/* Android API 17 is a 32-bit time_t ABI. Keep these layouts independent from
 * the host-side Zig headers used to compile this armv7 library. */
struct timeval { int32_t tv_sec; int32_t tv_usec; };
struct timespec { int32_t tv_sec; int32_t tv_nsec; };

#ifndef CLOCK_REALTIME
#define CLOCK_REALTIME 0
#endif
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif

int clock_gettime(int clock_id, struct timespec *value);
int gettimeofday(struct timeval *value, void *timezone);

#endif
