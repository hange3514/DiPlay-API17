/* API17 USB configuration shim. Only the UsbManager-granted descriptor is used.
 * Linux usbdevice_fs.h: _IOR('U',5,unsigned int), _IOR('U',4,struct {uint32,uint32}).
 * No root, device enumeration, networking or persistent state.
 */
#include <jni.h>
extern int ioctl(int fd, unsigned long request, ...);
extern int *__errno(void);
__attribute__((visibility("default"))) int Java_local_headunit_diplay_NativeUsb_configure(void *env, void *cls, int fd, int value) {
    (void)env; (void)cls;
    if(value<1 || value>255) return -22;
    unsigned int configuration=(unsigned int)value;
    return ioctl(fd,0x80045505UL,&configuration)==0 ? 0 : -*__errno();
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_driver(JNIEnv *env,jclass cls,jint fd,jint number,jbyteArray output) {
    (void)cls;
    if(number<0 || number>255 || !output || (*env)->GetArrayLength(env,output)<256) return -22;
    struct { unsigned int interface; char driver[256]; } info={0};
    info.interface=(unsigned int)number;
    if(ioctl(fd,0x41045508UL,&info)<0) return -*__errno();
    info.driver[255]=0;
    (*env)->SetByteArrayRegion(env,output,0,256,(jbyte*)info.driver);
    return 0;
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_bind(JNIEnv *env,jclass cls,jint fd,jint number,jboolean connect) {
    (void)env;(void)cls;
    if(number<0 || number>255) return -22;
    struct { int interface; int code; void *data; } control={number,connect?0x5517:0x5516,0};
    return ioctl(fd,0xc00c5512UL,&control)>=0?0:-*__errno();
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_claim(JNIEnv *env,jclass cls,jint fd,jint number) {
    (void)env;(void)cls;
    if(number<0 || number>255) return -22;
    unsigned int value=(unsigned int)number;
    return ioctl(fd,0x8004550fUL,&value)==0?0:-*__errno();
}
__attribute__((visibility("default"))) int Java_local_headunit_diplay_NativeUsb_alternate(void *env, void *cls, int fd, int number, int alternate) {
    (void)env; (void)cls;
    if(number<0 || number>255 || alternate<0 || alternate>255) return -22;
    struct { unsigned int interface; unsigned int altsetting; } setting={(unsigned int)number,(unsigned int)alternate};
    return ioctl(fd,0x80085504UL,&setting)==0 ? 0 : -*__errno();
}

extern int dup(int fd);
extern int close(int fd);
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_duplicate(JNIEnv *env,jclass cls,jint fd) {
 (void)env;(void)cls;int out=dup(fd);return out<0?-*__errno():out;
}
JNIEXPORT void JNICALL Java_local_headunit_diplay_NativeUsb_closeDescriptor(JNIEnv *env,jclass cls,jint fd) {
 (void)env;(void)cls;if(fd>=0)close(fd);
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_read(JNIEnv *env,jclass cls,jint fd,jint endpoint,jbyteArray output,jint timeout) {
 (void)cls;
 if(!output||endpoint<0x81||endpoint>0x8f||timeout<1||timeout>1000)return -22;
 jint length=(*env)->GetArrayLength(env,output);if(length<1||length>16384)return -22;
 /* Never retain/pin a Dalvik array across a blocking ioctl. */
 jbyte bytes[16384];
 struct {unsigned int ep,len,timeout;void *data;} transfer={(unsigned int)endpoint,(unsigned int)length,(unsigned int)timeout,bytes};
 int result=ioctl(fd,0xc0105502UL,&transfer);if(result<0)result=-*__errno();
 if(result>0&&result<=length)(*env)->SetByteArrayRegion(env,output,0,result,bytes);
 return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_write(JNIEnv *env,jclass cls,jint fd,jint endpoint,jbyteArray input,jint timeout) {
 (void)cls;
 if(!input||endpoint<1||endpoint>15||timeout<1||timeout>2000)return -22;
 jint length=(*env)->GetArrayLength(env,input);if(length<1||length>16384)return -22;
 jbyte bytes[16384];(*env)->GetByteArrayRegion(env,input,0,length,bytes);
 if((*env)->ExceptionCheck(env))return -22;
 struct {unsigned int ep,len,timeout;void *data;} transfer={(unsigned int)endpoint,(unsigned int)length,(unsigned int)timeout,bytes};
 int result=ioctl(fd,0xc0105502UL,&transfer);return result<0?-*__errno():result;
}

/* TUN uses O_NONBLOCK. Each worker owns a dup and polls with a finite deadline;
 * close in a different thread cannot cancel a Linux blocking read reliably. */
extern int poll(void *fds,unsigned int count,int timeout);
extern int read(int fd,void *buf,unsigned int count);
extern int write(int fd,const void *buf,unsigned int count);
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_pollRead(JNIEnv *env,jclass cls,jint fd,jbyteArray output,jint timeout) {
 (void)cls;if(!output||timeout<1||timeout>1000)return -22;
 int length=(*env)->GetArrayLength(env,output);if(length<1||length>16384)return -22;
 struct {int fd;short events,revents;} p={fd,1,0};
 int ready=poll(&p,1,timeout);if(ready==0)return -110;if(ready<0)return -*__errno();
 jbyte bytes[16384];int n=read(fd,bytes,length);if(n<0)return -*__errno();
 if(n>0)(*env)->SetByteArrayRegion(env,output,0,n,bytes);return n;
}
JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeUsb_pollWrite(JNIEnv *env,jclass cls,jint fd,jbyteArray input,jint offset,jint length,jint timeout) {
 (void)cls;if(!input||offset<0||length<1||length>16384||timeout<1||timeout>1000||offset>(*env)->GetArrayLength(env,input)-length)return -22;
 jbyte bytes[16384];(*env)->GetByteArrayRegion(env,input,offset,length,bytes);if((*env)->ExceptionCheck(env))return -22;
 struct {int fd;short events,revents;} p={fd,4,0};
 int ready=poll(&p,1,timeout);if(ready==0)return -110;if(ready<0)return -*__errno();
 int n=write(fd,bytes,length);return n<0?-*__errno():n;
}
