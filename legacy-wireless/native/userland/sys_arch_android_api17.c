/*
 * Copyright (c) 2001-2003 Swedish Institute of Computer Science.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT
 * SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT
 * OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING
 * IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY
 * OF SUCH DAMAGE.
 *
 * This file is part of the lwIP TCP/IP stack.
 *
 * Author: Adam Dunkels <adam@sics.se>
 *
 */

/*
 * Wed Apr 17 16:05:29 EDT 2002 (James Roth)
 *
 *  - Fixed an unlikely sys_thread_new() race condition.
 *
 *  - Made current_thread() work with threads which where
 *    not created with sys_thread_new().  This includes
 *    the main thread and threads made with pthread_create().
 *
 *  - Catch overflows where more than SYS_MBOX_SIZE messages
 *    are waiting to be read.  The sys_mbox_post() routine
 *    will block until there is more room instead of just
 *    leaking messages.
 */
#define _GNU_SOURCE /* pull in pthread_setname_np() on Linux */

#include "lwip/debug.h"

#include <string.h>
#include <sys/time.h>
#include <stdlib.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <errno.h>

#include "lwip/def.h"

#ifdef LWIP_UNIX_MACH
#include <mach/mach.h>
#include <mach/mach_time.h>
#endif

#include "lwip/sys.h"
#include "lwip/opt.h"
#include "lwip/stats.h"
#include "lwip/tcpip.h"

#if LWIP_NETCONN_SEM_PER_THREAD
/* pthread key to *our* thread local storage entry */
static pthread_key_t sys_thread_sem_key;
#endif

/* Return code for an interrupted timed wait */
#define SYS_ARCH_INTR 0xfffffffeUL

u32_t
lwip_port_rand(void)
{
  static volatile u32_t state;
  u32_t current = __sync_val_compare_and_swap(&state, 0, 0);
  if (current == 0) {
    u32_t seed = 0;
    int fd = open("/dev/urandom", O_RDONLY);
    if (fd >= 0) {
      u8_t *out = (u8_t *)&seed;
      size_t remaining = sizeof(seed);
      while (remaining > 0) {
        ssize_t count = read(fd, out, remaining);
        if (count <= 0) break;
        out += count;
        remaining -= (size_t)count;
      }
      close(fd);
      if (remaining != 0) seed = 0;
    }
    if (seed == 0) {
      struct timespec ts;
      clock_gettime(CLOCK_MONOTONIC, &ts);
      seed = (u32_t)ts.tv_nsec ^ (u32_t)ts.tv_sec ^ (u32_t)(uintptr_t)&state;
      if (seed == 0) seed = 0x9e3779b9U;
    }
    __sync_bool_compare_and_swap(&state, 0, seed);
  }

  do {
    current = __sync_val_compare_and_swap(&state, 0, 0);
    if (current == 0) continue;
    u32_t next = current;
    next ^= next << 13;
    next ^= next >> 17;
    next ^= next << 5;
    if (next == 0) next = 0x6d2b79f5U;
    if (__sync_bool_compare_and_swap(&state, current, next)) return next;
  } while (1);
}

static void
get_monotonic_time(struct timespec *ts)
{
#ifdef LWIP_UNIX_MACH
  /* darwin impl (no CLOCK_MONOTONIC) */
  u64_t t = mach_absolute_time();
  mach_timebase_info_data_t timebase_info = {0, 0};
  mach_timebase_info(&timebase_info);
  u64_t nano = (t * timebase_info.numer) / (timebase_info.denom);
  u64_t sec = nano/1000000000L;
  nano -= sec * 1000000000L;
  ts->tv_sec = sec;
  ts->tv_nsec = nano;
#else
  clock_gettime(CLOCK_MONOTONIC, ts);
#endif
}

#if SYS_LIGHTWEIGHT_PROT
static pthread_mutex_t lwprot_mutex;
static pthread_t lwprot_thread = (pthread_t)0xDEAD;
static int lwprot_count = 0;
#endif /* SYS_LIGHTWEIGHT_PROT */

#if !NO_SYS

static struct sys_thread *threads = NULL;
static pthread_mutex_t threads_mutex;

struct sys_mbox_msg {
  struct sys_mbox_msg *next;
  void *msg;
};

#define SYS_MBOX_SIZE 128

struct sys_mbox {
  int first, last;
  void *msgs[SYS_MBOX_SIZE];
  struct sys_sem *not_empty;
  struct sys_sem *not_full;
  struct sys_sem *mutex;
  int wait_send;
};

struct sys_sem {
  unsigned int c;
  pthread_condattr_t condattr;
  pthread_cond_t cond;
  pthread_mutex_t mutex;
};

struct sys_mutex {
  pthread_mutex_t mutex;
};

struct sys_thread {
  struct sys_thread *next;
  pthread_t pthread;
};

static struct sys_sem *sys_sem_new_internal(u8_t count);
static void sys_sem_free_internal(struct sys_sem *sem);
static volatile int sys_arch_error_code;

void
sys_arch_reset_error(void)
{
  __sync_lock_test_and_set(&sys_arch_error_code, 0);
}

int
sys_arch_last_error(void)
{
  return __sync_val_compare_and_swap(&sys_arch_error_code, 0, 0);
}

static void
sys_arch_set_error(int code)
{
  __sync_lock_test_and_set(&sys_arch_error_code, code > 0 ? code : EIO);
}

static u32_t cond_wait(pthread_cond_t * cond, pthread_mutex_t * mutex,
                       u32_t timeout);

/*-----------------------------------------------------------------------------------*/
/* Threads */
static void
introduce_thread(struct sys_thread *thread, pthread_t id)
{
  pthread_mutex_lock(&threads_mutex);
  thread->next = threads;
  thread->pthread = id;
  threads = thread;
  pthread_mutex_unlock(&threads_mutex);
}

struct thread_wrapper_data
{
  lwip_thread_fn function;
  void *arg;
};

static void *
thread_wrapper(void *arg)
{
  struct thread_wrapper_data *thread_data = (struct thread_wrapper_data *)arg;

  thread_data->function(thread_data->arg);

  /* we should never get here */
  free(arg);
  return NULL;
}

sys_thread_t
sys_thread_new(const char *name, lwip_thread_fn function, void *arg, int stacksize, int prio)
{
  int code;
  pthread_t tmp;
  struct sys_thread *st;
  struct thread_wrapper_data *thread_data;
  LWIP_UNUSED_ARG(name);
  LWIP_UNUSED_ARG(stacksize);
  LWIP_UNUSED_ARG(prio);

  if (function == NULL) {
    sys_arch_set_error(EINVAL);
    return NULL;
  }
  st = (struct sys_thread *)malloc(sizeof(struct sys_thread));
  thread_data = (struct thread_wrapper_data *)malloc(sizeof(struct thread_wrapper_data));
  if (st == NULL || thread_data == NULL) {
    free(st);
    free(thread_data);
    sys_arch_set_error(ENOMEM);
    return NULL;
  }
  thread_data->arg = arg;
  thread_data->function = function;
  diplay_lwip_stage("sys_thread_pthread_create_enter");
  code = pthread_create(&tmp,
                        NULL,
                        thread_wrapper,
                        thread_data);
  diplay_lwip_stage(code == 0 ? "sys_thread_pthread_create_returned" : "sys_thread_pthread_create_failed");

  if (code != 0) {
    free(thread_data);
    free(st);
    sys_arch_set_error(code);
    return NULL;
  }

#ifdef LWIP_UNIX_LINUX
  pthread_setname_np(tmp, name);
#endif

  introduce_thread(st, tmp);
  return st;
}

#if LWIP_TCPIP_CORE_LOCKING
static pthread_t lwip_core_lock_holder_thread_id;
void sys_lock_tcpip_core(void)
{
  sys_mutex_lock(&lock_tcpip_core);
  lwip_core_lock_holder_thread_id = pthread_self();
}

void sys_unlock_tcpip_core(void)
{
  lwip_core_lock_holder_thread_id = 0;
  sys_mutex_unlock(&lock_tcpip_core);
}
#endif /* LWIP_TCPIP_CORE_LOCKING */

static pthread_t lwip_tcpip_thread_id;
void sys_mark_tcpip_thread(void)
{
  lwip_tcpip_thread_id = pthread_self();
}

void sys_check_core_locking(void)
{
  /* Embedded systems should check we are NOT in an interrupt context here */

  if (lwip_tcpip_thread_id != 0) {
    pthread_t current_thread_id = pthread_self();

#if LWIP_TCPIP_CORE_LOCKING
    LWIP_ASSERT("Function called without core lock", current_thread_id == lwip_core_lock_holder_thread_id);
#else /* LWIP_TCPIP_CORE_LOCKING */
    LWIP_ASSERT("Function called from wrong thread", current_thread_id == lwip_tcpip_thread_id);
#endif /* LWIP_TCPIP_CORE_LOCKING */
  }
}

/*-----------------------------------------------------------------------------------*/
/* Mailbox */
err_t
sys_mbox_new(struct sys_mbox **mb, int size)
{
  struct sys_mbox *mbox;
  LWIP_UNUSED_ARG(size);

  if (mb == NULL) {
    sys_arch_set_error(EINVAL);
    return ERR_ARG;
  }
  *mb = NULL;
  mbox = (struct sys_mbox *)malloc(sizeof(struct sys_mbox));
  if (mbox == NULL) {
    sys_arch_set_error(ENOMEM);
    return ERR_MEM;
  }
  mbox->first = mbox->last = 0;
  mbox->not_empty = NULL;
  mbox->not_full = NULL;
  mbox->mutex = NULL;
  mbox->wait_send = 0;
  mbox->not_empty = sys_sem_new_internal(0);
  if (mbox->not_empty == NULL) goto allocation_failed;
  mbox->not_full = sys_sem_new_internal(0);
  if (mbox->not_full == NULL) goto allocation_failed;
  mbox->mutex = sys_sem_new_internal(1);
  if (mbox->mutex == NULL) goto allocation_failed;

  SYS_STATS_INC_USED(mbox);
  *mb = mbox;
  return ERR_OK;

allocation_failed:
  sys_sem_free_internal(mbox->not_empty);
  sys_sem_free_internal(mbox->not_full);
  sys_sem_free_internal(mbox->mutex);
  free(mbox);
  sys_arch_set_error(ENOMEM);
  return ERR_MEM;
}

void
sys_mbox_free(struct sys_mbox **mb)
{
  if ((mb != NULL) && (*mb != SYS_MBOX_NULL)) {
    struct sys_mbox *mbox = *mb;
    SYS_STATS_DEC(mbox.used);
    sys_arch_sem_wait(&mbox->mutex, 0);

    sys_sem_free_internal(mbox->not_empty);
    sys_sem_free_internal(mbox->not_full);
    sys_sem_free_internal(mbox->mutex);
    mbox->not_empty = mbox->not_full = mbox->mutex = NULL;
    /*  LWIP_DEBUGF("sys_mbox_free: mbox 0x%lx\n", mbox); */
    free(mbox);
  }
}

err_t
sys_mbox_trypost(struct sys_mbox **mb, void *msg)
{
  u8_t first;
  struct sys_mbox *mbox;
  LWIP_ASSERT("invalid mbox", (mb != NULL) && (*mb != NULL));
  mbox = *mb;

  sys_arch_sem_wait(&mbox->mutex, 0);

  LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_trypost: mbox %p msg %p\n",
                          (void *)mbox, (void *)msg));

  if ((mbox->last + 1) >= (mbox->first + SYS_MBOX_SIZE)) {
    sys_sem_signal(&mbox->mutex);
    return ERR_MEM;
  }

  mbox->msgs[mbox->last % SYS_MBOX_SIZE] = msg;

  if (mbox->last == mbox->first) {
    first = 1;
  } else {
    first = 0;
  }

  mbox->last++;

  if (first) {
    sys_sem_signal(&mbox->not_empty);
  }

  sys_sem_signal(&mbox->mutex);

  return ERR_OK;
}

err_t
sys_mbox_trypost_fromisr(sys_mbox_t *q, void *msg)
{
  return sys_mbox_trypost(q, msg);
}

void
sys_mbox_post(struct sys_mbox **mb, void *msg)
{
  u8_t first;
  struct sys_mbox *mbox;
  LWIP_ASSERT("invalid mbox", (mb != NULL) && (*mb != NULL));
  mbox = *mb;

  sys_arch_sem_wait(&mbox->mutex, 0);

  LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_post: mbox %p msg %p\n", (void *)mbox, (void *)msg));

  while ((mbox->last + 1) >= (mbox->first + SYS_MBOX_SIZE)) {
    mbox->wait_send++;
    sys_sem_signal(&mbox->mutex);
    sys_arch_sem_wait(&mbox->not_full, 0);
    sys_arch_sem_wait(&mbox->mutex, 0);
    mbox->wait_send--;
  }

  mbox->msgs[mbox->last % SYS_MBOX_SIZE] = msg;

  if (mbox->last == mbox->first) {
    first = 1;
  } else {
    first = 0;
  }

  mbox->last++;

  if (first) {
    sys_sem_signal(&mbox->not_empty);
  }

  sys_sem_signal(&mbox->mutex);
}

u32_t
sys_arch_mbox_tryfetch(struct sys_mbox **mb, void **msg)
{
  struct sys_mbox *mbox;
  LWIP_ASSERT("invalid mbox", (mb != NULL) && (*mb != NULL));
  mbox = *mb;

  sys_arch_sem_wait(&mbox->mutex, 0);

  if (mbox->first == mbox->last) {
    sys_sem_signal(&mbox->mutex);
    return SYS_MBOX_EMPTY;
  }

  if (msg != NULL) {
    LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_tryfetch: mbox %p msg %p\n", (void *)mbox, *msg));
    *msg = mbox->msgs[mbox->first % SYS_MBOX_SIZE];
  }
  else{
    LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_tryfetch: mbox %p, null msg\n", (void *)mbox));
  }

  mbox->first++;

  if (mbox->wait_send) {
    sys_sem_signal(&mbox->not_full);
  }

  sys_sem_signal(&mbox->mutex);

  return 0;
}

u32_t
sys_arch_mbox_fetch(struct sys_mbox **mb, void **msg, u32_t timeout)
{
  u32_t time_needed = 0;
  struct sys_mbox *mbox;
  LWIP_ASSERT("invalid mbox", (mb != NULL) && (*mb != NULL));
  mbox = *mb;

  /* The mutex lock is quick so we don't bother with the timeout
     stuff here. */
  sys_arch_sem_wait(&mbox->mutex, 0);

  while (mbox->first == mbox->last) {
    sys_sem_signal(&mbox->mutex);

    /* We block while waiting for a mail to arrive in the mailbox. We
       must be prepared to timeout. */
    if (timeout != 0) {
      time_needed = sys_arch_sem_wait(&mbox->not_empty, timeout);

      if (time_needed == SYS_ARCH_TIMEOUT) {
        return SYS_ARCH_TIMEOUT;
      }
    } else {
      sys_arch_sem_wait(&mbox->not_empty, 0);
    }

    sys_arch_sem_wait(&mbox->mutex, 0);
  }

  if (msg != NULL) {
    LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_fetch: mbox %p msg %p\n", (void *)mbox, *msg));
    *msg = mbox->msgs[mbox->first % SYS_MBOX_SIZE];
  }
  else{
    LWIP_DEBUGF(SYS_DEBUG, ("sys_mbox_fetch: mbox %p, null msg\n", (void *)mbox));
  }

  mbox->first++;

  if (mbox->wait_send) {
    sys_sem_signal(&mbox->not_full);
  }

  sys_sem_signal(&mbox->mutex);

  return time_needed;
}

/*-----------------------------------------------------------------------------------*/
/* Semaphore */
static struct sys_sem *
sys_sem_new_internal(u8_t count)
{
  struct sys_sem *sem;
  int result;

  sem = (struct sys_sem *)malloc(sizeof(struct sys_sem));
  if (sem == NULL) {
    sys_arch_set_error(ENOMEM);
    return NULL;
  }
  result = pthread_condattr_init(&(sem->condattr));
  if (result != 0) {
    free(sem);
    sys_arch_set_error(result);
    return NULL;
  }
#if !(defined(LWIP_UNIX_MACH) || (defined(LWIP_UNIX_ANDROID) && __ANDROID_API__ < 21))
  result = pthread_condattr_setclock(&(sem->condattr), CLOCK_MONOTONIC);
  if (result != 0) {
    pthread_condattr_destroy(&(sem->condattr));
    free(sem);
    sys_arch_set_error(result);
    return NULL;
  }
#endif
  result = pthread_cond_init(&(sem->cond), &(sem->condattr));
  if (result != 0) {
    pthread_condattr_destroy(&(sem->condattr));
    free(sem);
    sys_arch_set_error(result);
    return NULL;
  }
  result = pthread_mutex_init(&(sem->mutex), NULL);
  if (result != 0) {
    pthread_cond_destroy(&(sem->cond));
    pthread_condattr_destroy(&(sem->condattr));
    free(sem);
    sys_arch_set_error(result);
    return NULL;
  }
  sem->c = count;
  return sem;
}

err_t
sys_sem_new(struct sys_sem **sem, u8_t count)
{
  if (sem == NULL) {
    sys_arch_set_error(EINVAL);
    return ERR_ARG;
  }
  *sem = NULL;
  *sem = sys_sem_new_internal(count);
  if (*sem == NULL) {
    return ERR_MEM;
  }
  SYS_STATS_INC_USED(sem);
  return ERR_OK;
}

static u32_t
cond_wait(pthread_cond_t *cond, pthread_mutex_t *mutex, u32_t timeout)
{
  struct timespec rtime1, rtime2, ts;
  int ret;

#ifdef LWIP_UNIX_HURD
  #define pthread_cond_wait pthread_hurd_cond_wait_np
  #define pthread_cond_timedwait pthread_hurd_cond_timedwait_np
#endif

  if (timeout == 0) {
    ret = pthread_cond_wait(cond, mutex);
    return
#ifdef LWIP_UNIX_HURD
    /* On the Hurd, ret == 1 means the RPC has been cancelled.
     * The thread is awakened (not terminated) and execution must continue */
    ret == 1 ? SYS_ARCH_INTR :
#endif
    (u32_t)ret;
  }

  /* Get a timestamp and add the timeout value. */
  get_monotonic_time(&rtime1);
#if defined(LWIP_UNIX_MACH) || (defined(LWIP_UNIX_ANDROID) && __ANDROID_API__ < 21)
  ts.tv_sec = timeout / 1000L;
  ts.tv_nsec = (timeout % 1000L) * 1000000L;
  ret = pthread_cond_timedwait_relative_np(cond, mutex, &ts);
#else
  ts.tv_sec = rtime1.tv_sec + timeout / 1000L;
  ts.tv_nsec = rtime1.tv_nsec + (timeout % 1000L) * 1000000L;
  if (ts.tv_nsec >= 1000000000L) {
    ts.tv_sec++;
    ts.tv_nsec -= 1000000000L;
  }

  ret = pthread_cond_timedwait(cond, mutex, &ts);
#endif
  if (ret == ETIMEDOUT) {
    return SYS_ARCH_TIMEOUT;
#ifdef LWIP_UNIX_HURD
    /* On the Hurd, ret == 1 means the RPC has been cancelled.
     * The thread is awakened (not terminated) and execution must continue */
  } else if (ret == EINTR) {
    return SYS_ARCH_INTR;
#endif
  }

  /* Calculate for how long we waited for the cond. */
  get_monotonic_time(&rtime2);
  ts.tv_sec = rtime2.tv_sec - rtime1.tv_sec;
  ts.tv_nsec = rtime2.tv_nsec - rtime1.tv_nsec;
  if (ts.tv_nsec < 0) {
    ts.tv_sec--;
    ts.tv_nsec += 1000000000L;
  }
  return (u32_t)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
}

u32_t
sys_arch_sem_wait(struct sys_sem **s, u32_t timeout)
{
  u32_t time_needed = 0;
  struct sys_sem *sem;
  LWIP_ASSERT("invalid sem", (s != NULL) && (*s != NULL));
  sem = *s;

  pthread_mutex_lock(&(sem->mutex));
  while (sem->c <= 0) {
    if (timeout > 0) {
      time_needed = cond_wait(&(sem->cond), &(sem->mutex), timeout);

      if (time_needed == SYS_ARCH_TIMEOUT) {
        pthread_mutex_unlock(&(sem->mutex));
        return SYS_ARCH_TIMEOUT;
#ifdef LWIP_UNIX_HURD
      } else if(time_needed == SYS_ARCH_INTR) {
        pthread_mutex_unlock(&(sem->mutex));
        return 0;
#endif
      }
      /*      pthread_mutex_unlock(&(sem->mutex));
              return time_needed; */
    } else if(cond_wait(&(sem->cond), &(sem->mutex), 0)) {
      /* Some error happened or the thread has been awakened but not by lwip */
      pthread_mutex_unlock(&(sem->mutex));
      return 0;
    }
  }
  sem->c--;
  pthread_mutex_unlock(&(sem->mutex));
  return (u32_t)time_needed;
}

void
sys_sem_signal(struct sys_sem **s)
{
  struct sys_sem *sem;
  LWIP_ASSERT("invalid sem", (s != NULL) && (*s != NULL));
  sem = *s;

  pthread_mutex_lock(&(sem->mutex));
  sem->c++;

  if (sem->c > 1) {
    sem->c = 1;
  }

  pthread_cond_broadcast(&(sem->cond));
  pthread_mutex_unlock(&(sem->mutex));
}

static void
sys_sem_free_internal(struct sys_sem *sem)
{
  if (sem == NULL) {
    return;
  }
  pthread_cond_destroy(&(sem->cond));
  pthread_condattr_destroy(&(sem->condattr));
  pthread_mutex_destroy(&(sem->mutex));
  free(sem);
}

void
sys_sem_free(struct sys_sem **sem)
{
  if ((sem != NULL) && (*sem != SYS_SEM_NULL)) {
    SYS_STATS_DEC(sem.used);
    sys_sem_free_internal(*sem);
  }
}

/*-----------------------------------------------------------------------------------*/
/* Mutex */
/** Create a new mutex
 * @param mutex pointer to the mutex to create
 * @return a new mutex */
err_t
sys_mutex_new(struct sys_mutex **mutex)
{
  struct sys_mutex *mtx;
  int result;

  if (mutex == NULL) {
    sys_arch_set_error(EINVAL);
    return ERR_ARG;
  }
  *mutex = NULL;
  mtx = (struct sys_mutex *)malloc(sizeof(struct sys_mutex));
  if (mtx != NULL) {
    result = pthread_mutex_init(&(mtx->mutex), NULL);
    if (result != 0) {
      free(mtx);
      sys_arch_set_error(result);
      return ERR_MEM;
    }
    *mutex = mtx;
    return ERR_OK;
  }
  else {
    sys_arch_set_error(ENOMEM);
    return ERR_MEM;
  }
}

/** Lock a mutex
 * @param mutex the mutex to lock */
void
sys_mutex_lock(struct sys_mutex **mutex)
{
  pthread_mutex_lock(&((*mutex)->mutex));
}

/** Unlock a mutex
 * @param mutex the mutex to unlock */
void
sys_mutex_unlock(struct sys_mutex **mutex)
{
  pthread_mutex_unlock(&((*mutex)->mutex));
}

/** Delete a mutex
 * @param mutex the mutex to delete */
void
sys_mutex_free(struct sys_mutex **mutex)
{
  pthread_mutex_destroy(&((*mutex)->mutex));
  free(*mutex);
}

#endif /* !NO_SYS */

#if LWIP_NETCONN_SEM_PER_THREAD
/*-----------------------------------------------------------------------------------*/
/* Semaphore per thread located TLS */

static void
sys_thread_sem_free(void* data)
{
  sys_sem_t *sem = (sys_sem_t*)(data);

  if (sem) {
    sys_sem_free(sem);
    free(sem);
  }
}

static sys_sem_t*
sys_thread_sem_alloc(void)
{
  sys_sem_t *sem;
  err_t err;
  int ret;

  sem = (sys_sem_t*)malloc(sizeof(sys_sem_t*));
  LWIP_ASSERT("failed to allocate memory for TLS semaphore", sem != NULL);
  err = sys_sem_new(sem, 0);
  LWIP_ASSERT("failed to initialise TLS semaphore", err == ERR_OK);
  ret = pthread_setspecific(sys_thread_sem_key, sem);
  LWIP_ASSERT("failed to initialise TLS semaphore storage", ret == 0);
  return sem;
}

sys_sem_t*
sys_arch_netconn_sem_get(void)
{
  sys_sem_t* sem = (sys_sem_t*)pthread_getspecific(sys_thread_sem_key);
  if (!sem) {
    sem = sys_thread_sem_alloc();
  }
  LWIP_DEBUGF(SYS_DEBUG, ("sys_thread_sem_get s=%p\n", (void*)sem));
  return sem;
}

void
sys_arch_netconn_sem_alloc(void)
{
  sys_sem_t* sem = sys_thread_sem_alloc();
  LWIP_DEBUGF(SYS_DEBUG, ("sys_thread_sem created s=%p\n", (void*)sem));
}

void
sys_arch_netconn_sem_free(void)
{
  int ret;

  sys_sem_t *sem = (sys_sem_t *)pthread_getspecific(sys_thread_sem_key);
  sys_thread_sem_free(sem);
  ret = pthread_setspecific(sys_thread_sem_key, NULL);
  LWIP_ASSERT("failed to de-init TLS semaphore storage", ret == 0);
}
#endif /* LWIP_NETCONN_SEM_PER_THREAD */

/*-----------------------------------------------------------------------------------*/
/* Time */
u32_t
sys_now(void)
{
  struct timespec ts;
  u32_t now;

  get_monotonic_time(&ts);
  now = (u32_t)(ts.tv_sec * 1000L + ts.tv_nsec / 1000000L);
#ifdef LWIP_FUZZ_SYS_NOW
  now += sys_now_offset;
#endif
  return now;
}

u32_t
sys_jiffies(void)
{
  struct timespec ts;

  get_monotonic_time(&ts);
  return (u32_t)(ts.tv_sec * 1000000000L + ts.tv_nsec);
}

/*-----------------------------------------------------------------------------------*/
/* Init */

void
sys_init(void)
{
  /* Use Bionic's runtime initialization; host-side pthread initializer bit
   * patterns are not ABI-compatible with Android API 17. */
  int result;
#if SYS_LIGHTWEIGHT_PROT
  diplay_lwip_stage("sys_init_lwprot_mutex_enter");
  result = pthread_mutex_init(&lwprot_mutex, NULL);
  if (result != 0) {
    sys_arch_set_error(result);
    return;
  }
  diplay_lwip_stage("sys_init_lwprot_mutex_ready");
#endif
  diplay_lwip_stage("sys_init_threads_mutex_enter");
  result = pthread_mutex_init(&threads_mutex, NULL);
  if (result != 0) {
#if SYS_LIGHTWEIGHT_PROT
    pthread_mutex_destroy(&lwprot_mutex);
#endif
    sys_arch_set_error(result);
    return;
  }
  diplay_lwip_stage("sys_init_threads_mutex_ready");
#if LWIP_NETCONN_SEM_PER_THREAD
  diplay_lwip_stage("sys_init_thread_key_enter");
  result = pthread_key_create(&sys_thread_sem_key, sys_thread_sem_free);
  if (result != 0) {
    pthread_mutex_destroy(&threads_mutex);
#if SYS_LIGHTWEIGHT_PROT
    pthread_mutex_destroy(&lwprot_mutex);
#endif
    sys_arch_set_error(result);
    return;
  }
  diplay_lwip_stage("sys_init_thread_key_ready");
#endif
}

/*-----------------------------------------------------------------------------------*/
/* Critical section */
#if SYS_LIGHTWEIGHT_PROT
/** sys_prot_t sys_arch_protect(void)

This optional function does a "fast" critical region protection and returns
the previous protection level. This function is only called during very short
critical regions. An embedded system which supports ISR-based drivers might
want to implement this function by disabling interrupts. Task-based systems
might want to implement this by using a mutex or disabling tasking. This
function should support recursive calls from the same task or interrupt. In
other words, sys_arch_protect() could be called while already protected. In
that case the return value indicates that it is already protected.

sys_arch_protect() is only required if your port is supporting an operating
system.
*/
sys_prot_t
sys_arch_protect(void)
{
    /* Note that for the UNIX port, we are using a lightweight mutex, and our
     * own counter (which is locked by the mutex). The return code is not actually
     * used. */
    if (lwprot_thread != pthread_self())
    {
        /* We are locking the mutex where it has not been locked before *
        * or is being locked by another thread */
        pthread_mutex_lock(&lwprot_mutex);
        lwprot_thread = pthread_self();
        lwprot_count = 1;
    }
    else
        /* It is already locked by THIS thread */
        lwprot_count++;
    return 0;
}

/** void sys_arch_unprotect(sys_prot_t pval)

This optional function does a "fast" set of critical region protection to the
value specified by pval. See the documentation for sys_arch_protect() for
more information. This function is only required if your port is supporting
an operating system.
*/
void
sys_arch_unprotect(sys_prot_t pval)
{
    LWIP_UNUSED_ARG(pval);
    if (lwprot_thread == pthread_self())
    {
        lwprot_count--;
        if (lwprot_count == 0)
        {
            lwprot_thread = (pthread_t) 0xDEAD;
            pthread_mutex_unlock(&lwprot_mutex);
        }
    }
}
#endif /* SYS_LIGHTWEIGHT_PROT */

#if !NO_SYS
/* get keyboard state to terminate the debug app by using select */
int
lwip_unix_keypressed(void)
{
  /* Console input polling is only for the upstream Unix demo application. */
  return 0;
}
#endif /* !NO_SYS */
