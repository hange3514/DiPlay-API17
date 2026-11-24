#ifndef DIPLAY_API17_SYS_TIME_H
#define DIPLAY_API17_SYS_TIME_H

#include <stdint.h>
#include <sys/types.h>

/* Android API 17 is a 32-bit time_t ABI. Keep these layouts independent from
 * the host-side Zig headers used to compile this armv7 library. */
#ifndef __DEFINED_struct_timeval
struct timeval { int32_t tv_sec; int32_t tv_usec; };
#define __DEFINED_struct_timeval
#endif
#ifndef __DEFINED_struct_timespec
struct timespec { int32_t tv_sec; int32_t tv_nsec; };
#define __DEFINED_struct_timespec
#endif

#ifndef CLOCK_REALTIME
#define CLOCK_REALTIME 0
#endif
#ifndef CLOCK_MONOTONIC
#define CLOCK_MONOTONIC 1
#endif

#ifndef __DEFINED_struct_timespec
struct timespec { int32_t tv_sec; int32_t tv_nsec; };
#define __DEFINED_struct_timespec
#endif

int clock_gettime(int clock_id, struct timespec *value);
struct timezone;
int gettimeofday(struct timeval *value, struct timezone *timezone);

#ifndef timerclear
#define timerclear(value) ((value)->tv_sec = (value)->tv_usec = 0)
#endif
#ifndef timerisset
#define timerisset(value) ((value)->tv_sec || (value)->tv_usec)
#endif
#ifndef timercmp
#define timercmp(left, right, op) \
    (((left)->tv_sec == (right)->tv_sec) ? \
        ((left)->tv_usec op (right)->tv_usec) : \
        ((left)->tv_sec op (right)->tv_sec))
#endif

#endif
