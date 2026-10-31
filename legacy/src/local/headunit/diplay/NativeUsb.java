package local.headunit.diplay;
/** ioctl on the descriptor already granted by Android's USB authorization dialog. */
public final class NativeUsb {
 static {System.loadLibrary("diplayusb");}
 public static native int configure(int fd,int configuration);
 public static native int alternate(int fd,int number,int alternate);
 public static native int driver(int fd,int number,byte[] name);
 public static native int bind(int fd,int number,boolean connect);
 public static native int claim(int fd,int number);
 public static native int duplicate(int fd);
 public static native void closeDescriptor(int fd);
 public static native int read(int fd,int endpoint,byte[] output,int timeout);
 public static native int write(int fd,int endpoint,byte[] input,int timeout);
 public static native int startupWrite(int fd,int endpoint,byte[] input,int timeout,java.util.concurrent.atomic.AtomicBoolean keepWaiting,int[] metrics);
 public static native int startupRead(int fd,int endpoint,byte[] output,int timeout,java.util.concurrent.atomic.AtomicBoolean keepWaiting,int[] metrics);
 public static native int pollRead(int fd,byte[] output,int timeout);
 public static native int pollWrite(int fd,byte[] input,int offset,int length,int timeout);
 private NativeUsb(){}
}
