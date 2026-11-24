#ifndef DIPLAY_API17_LWIP_CC_H
#define DIPLAY_API17_LWIP_CC_H

#include <sys/time.h>

#define LWIP_UNIX_ANDROID 1
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_ERRNO_STDINCLUDE 1
typedef unsigned int sys_prot_t;

/* Persist lwIP assertions before aborting so API 17 process death is diagnosable. */
void diplay_lwip_assert(const char *message, const char *file, int line);
void diplay_lwip_stage(const char *stage);
#define LWIP_PLATFORM_ASSERT(message) diplay_lwip_assert((message), __FILE__, __LINE__)

/* API 17's small link stub omits rand(), so keep lwIP's ephemeral-port and
 * protocol jitter source inside the app. */
unsigned int lwip_port_rand(void);
#define LWIP_RAND() lwip_port_rand()

#endif
