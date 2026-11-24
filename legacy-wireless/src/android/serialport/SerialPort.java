package android.serialport;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Minimal binding to the head unit's existing serial_port JNI shim. No su/chmod path. */
public final class SerialPort {
    private FileDescriptor mFd;
    private FileInputStream mFileInputStream;
    private FileOutputStream mFileOutputStream;

    private static native FileDescriptor open(String path, int baudrate, int flags);
    public native void close();

    public SerialPort(File device, int baudrate, int flags) throws IOException {
        if (device == null || !device.canRead() || !device.canWrite()) {
            throw new IOException("serial node is not readable and writable");
        }
        mFd = open(device.getAbsolutePath(), baudrate, flags);
        if (mFd == null) throw new IOException("native serial open returned null");
        mFileInputStream = new FileInputStream(mFd);
        mFileOutputStream = new FileOutputStream(mFd);
    }

    public InputStream getInputStream() { return mFileInputStream; }
    public OutputStream getOutputStream() { return mFileOutputStream; }

    static {
        try {
            System.loadLibrary("serial_port");
        } catch (UnsatisfiedLinkError first) {
            System.load("/system/lib/libserial_port.so");
        }
    }
}
