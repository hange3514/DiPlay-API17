#ifndef DIPLAY_API17_TIME_H
#define DIPLAY_API17_TIME_H

#include <sys/types.h>
#include <sys/time.h>

#ifdef __cplusplus
extern "C" {
#endif

struct tm {
    int tm_sec;
    int tm_min;
    int tm_hour;
    int tm_mday;
    int tm_mon;
    int tm_year;
    int tm_wday;
    int tm_yday;
    int tm_isdst;
};

time_t time(time_t *result);
clock_t clock(void);
double difftime(time_t end, time_t start);
struct tm *gmtime(const time_t *value);
struct tm *localtime(const time_t *value);
struct tm *gmtime_r(const time_t *value, struct tm *result);
struct tm *localtime_r(const time_t *value, struct tm *result);
int nanosleep(const struct timespec *request, struct timespec *remaining);
int clock_gettime(clockid_t clock_id, struct timespec *value);
int clock_getres(clockid_t clock_id, struct timespec *value);

#ifdef __cplusplus
}
#endif

#endif
