#include <jni.h>
#include "startup_transfer.h"
extern int ioctl(int,unsigned long,...);
extern int poll(void *,unsigned int,int);
extern int *__errno(void);
struct startup_timespec { long sec,nsec; };
extern int clock_gettime(int,struct startup_timespec *);
/* Linux usbdevice_fs.h ARM32 ABI. No async transfer from another owner may
 * share this file description. Receive experiments disable asynchronous OUT;
 * they must never run startupRead and startupWrite on the same connection. */
struct startup_urb {
 unsigned char type,endpoint; int status; unsigned int flags;
 void *buffer; int buffer_length,actual_length,start_frame,number_of_packets;
 int error_count; unsigned int signr; void *usercontext;
};
_Static_assert(sizeof(void*)==4,"API17 ARM32 only");
_Static_assert(sizeof(struct startup_urb)==44,"usbdevfs_urb ABI");
struct startup_context {int fd;struct startup_urb urb;JNIEnv *env;jobject flag;jmethodID get;};
static int err(int rc){return rc<0?-*__errno():rc;}
static int submit(void *p){struct startup_context *c=p;return err(ioctl(c->fd,0x802c550aUL,&c->urb));}
static int reap(void *p,int *status,int *actual){
 struct startup_context *c=p;void *completed=0;
 int rc=err(ioctl(c->fd,0x4004550dUL,&completed));
 if(rc<0)return rc;
 if(completed!=&c->urb)return -71;
 *status=c->urb.status;*actual=c->urb.actual_length;return 0;
}
static int cancel(void *p){struct startup_context *c=p;return err(ioctl(c->fd,0x550bUL,&c->urb));}
static int wait_for_completion(void *p,int ms){
 struct startup_context *c=p;struct {int fd;short events,revents;} f={c->fd,4,0};
 int rc=err(poll(&f,1,ms));
 if(rc<0)return rc;
 /* POLLERR/HUP: reap once more to collect completion before cancellation. */
 return rc;
}
static long long now(void *unused){
 (void)unused;struct startup_timespec t={0};
 if(clock_gettime(1,&t)!=0)return 0;
 return (long long)t.sec*1000+t.nsec/1000000;
}
static int keep(void *p){
 struct startup_context *c=p;
 jboolean result=(*c->env)->CallBooleanMethod(c->env,c->flag,c->get);
 return !(*c->env)->ExceptionCheck(c->env)&&result;
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_startupWrite(
 JNIEnv *env,jclass cls,jint fd,jint endpoint,jbyteArray input,jint timeout,jobject flag,jintArray metrics){
 (void)cls;
 if(fd<0||endpoint<1||endpoint>15||!input||!flag||!metrics||timeout<1||timeout>15000)return -22;
 int length=(*env)->GetArrayLength(env,input);
 if(length<1||length>512||(*env)->GetArrayLength(env,metrics)<6)return -22;
 jclass type=(*env)->GetObjectClass(env,flag);if(!type)return -22;
 jmethodID get=(*env)->GetMethodID(env,type,"get","()Z");
 (*env)->DeleteLocalRef(env,type);if(!get)return -22;
 jbyte bytes[512];(*env)->GetByteArrayRegion(env,input,0,length,bytes);
 if((*env)->ExceptionCheck(env))return -22;
 if(now(0)<=0)return -5;
 struct startup_context c={0};c.fd=fd;c.env=env;c.flag=flag;c.get=get;
 c.urb.type=3;c.urb.endpoint=(unsigned char)endpoint;c.urb.buffer=bytes;c.urb.buffer_length=length;
 startup_ops ops={&c,submit,reap,cancel,wait_for_completion,now,keep};startup_stats stats;
 int result=startup_transfer(&ops,length,timeout,&stats);
 if(!(*env)->ExceptionCheck(env)){
  jint values[6]={stats.submitted,stats.polls,stats.elapsed,stats.cancelled,stats.actual,stats.status};
  (*env)->SetIntArrayRegion(env,metrics,0,6,values);
 }
 return result;
}

/* Only the first IN request is kept queued across empty periods. Subsequent
 * media reads retain the proven synchronous path. The caller serializes all
 * IN operations and retains the descriptor until cancellation has completed. */
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_startupRead(
 JNIEnv *env,jclass cls,jint fd,jint endpoint,jbyteArray output,jint timeout,jobject flag,jintArray metrics){
 (void)cls;
 if(fd<0||endpoint<0x81||endpoint>0x8f||!output||!flag||!metrics||timeout<1||timeout>15000)return -22;
 int length=(*env)->GetArrayLength(env,output);
 if(length<1||length>16384||(*env)->GetArrayLength(env,metrics)<6)return -22;
 jclass type=(*env)->GetObjectClass(env,flag);if(!type)return -22;
 jmethodID get=(*env)->GetMethodID(env,type,"get","()Z");
 (*env)->DeleteLocalRef(env,type);if(!get)return -22;
 if(now(0)<=0)return -5;
 jbyte bytes[16384];
 struct startup_context c={0};c.fd=fd;c.env=env;c.flag=flag;c.get=get;
 c.urb.type=3;c.urb.endpoint=(unsigned char)endpoint;c.urb.buffer=bytes;c.urb.buffer_length=length;
 startup_ops ops={&c,submit,reap,cancel,wait_for_completion,now,keep};startup_stats stats;
 int result=startup_transfer(&ops,length,timeout,&stats);
 /* A zero-length IN completion is legal; continue with the ordinary reader. */
 result=startup_read_result(result,&stats);
 if(!(*env)->ExceptionCheck(env)){
  if(result>0)(*env)->SetByteArrayRegion(env,output,0,result,bytes);
  jint values[6]={stats.submitted,stats.polls,stats.elapsed,stats.cancelled,stats.actual,stats.status};
  (*env)->SetIntArrayRegion(env,metrics,0,6,values);
 }
 return result;
}
