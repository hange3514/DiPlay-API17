#ifndef DIPLAY_API17_PTHREAD_ABI_H
#define DIPLAY_API17_PTHREAD_ABI_H

#include <stdint.h>
#include <stddef.h>
#include <sys/time.h>

/* pthread objects are opaque in Bionic. Oversized, correctly aligned storage
 * lets API 17's own init/lock functions construct and inspect their ABI layout. */
typedef uintptr_t pthread_t;
typedef int pthread_key_t;
typedef struct { uint64_t alignment; unsigned char storage[64]; } pthread_mutex_t;
typedef struct { uint64_t alignment; unsigned char storage[64]; } pthread_cond_t;
typedef struct { uint64_t alignment; unsigned char storage[64]; } pthread_mutexattr_t;
typedef struct { uint64_t alignment; unsigned char storage[64]; } pthread_condattr_t;
typedef struct { uint64_t alignment; unsigned char storage[64]; } pthread_attr_t;

#define PTHREAD_MUTEX_INITIALIZER {0}

int pthread_mutex_init(pthread_mutex_t *mutex, const pthread_mutexattr_t *attributes);
int pthread_mutex_destroy(pthread_mutex_t *mutex);
int pthread_mutex_lock(pthread_mutex_t *mutex);
int pthread_mutex_unlock(pthread_mutex_t *mutex);
int pthread_mutex_trylock(pthread_mutex_t *mutex);
int pthread_cond_init(pthread_cond_t *condition, const pthread_condattr_t *attributes);
int pthread_cond_destroy(pthread_cond_t *condition);
int pthread_cond_wait(pthread_cond_t *condition, pthread_mutex_t *mutex);
int pthread_cond_timedwait(pthread_cond_t *condition, pthread_mutex_t *mutex, const struct timespec *deadline);
int pthread_cond_timedwait_relative_np(pthread_cond_t *condition, pthread_mutex_t *mutex, const struct timespec *timeout);
int pthread_cond_signal(pthread_cond_t *condition);
int pthread_cond_broadcast(pthread_cond_t *condition);
int pthread_condattr_init(pthread_condattr_t *attributes);
int pthread_condattr_destroy(pthread_condattr_t *attributes);
int pthread_condattr_setclock(pthread_condattr_t *attributes, int clock_id);
int pthread_create(pthread_t *thread, const pthread_attr_t *attributes, void *(*entry)(void *), void *argument);
int pthread_join(pthread_t thread, void **result);
pthread_t pthread_self(void);
int pthread_equal(pthread_t first, pthread_t second);
int pthread_key_create(pthread_key_t *key, void (*destructor)(void *));
int pthread_key_delete(pthread_key_t key);
int pthread_setspecific(pthread_key_t key, const void *value);
void *pthread_getspecific(pthread_key_t key);

#endif
