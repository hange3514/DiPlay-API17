package local.headunit.diplay;

/** JNI facade for the isolated lwIP instance used by the no-VPN wired experiment. */
final class NativeIpStack {
    static {
        System.loadLibrary("diplayusb");
    }

    private NativeIpStack() {}

    /** Load JNI and start lwIP's dormant core thread while the USB watcher is idle. */
    static void preload() {
        int error = prewarm();
        if (error != 0) throw new IllegalStateException("lwIP core prewarm failed nativeErrorCode=" + error);
    }

    static native boolean installCrashCapture(String privateFilePath);
    static native void setCrashStage(String stage);
    private static native int prewarm();
    static native long create(String localAddress, byte[] mac, int trackedTcpSynPort);
    static native int createError();
    static native int input(long handle, byte[] ethernetFrame);
    static native int nextOutput(long handle, byte[] destination, int timeoutMillis);
    static native long[] stats(long handle);
    static native int stop(long handle);

    static native int socket(int kind);
    static native int bind(int socket, byte[] address, int port);
    static native int listen(int socket, int backlog);
    static native int accept(int socket);
    static native int closeSocket(int socket);
    static native int shutdown(int socket, int how);
    static native int send(int socket, byte[] data, int offset, int length);
    static native int receive(int socket, byte[] data, int offset, int length);
    static native int sendTo(int socket, byte[] data, int offset, int length, byte[] address, int port);
    static native int receiveFrom(int socket, byte[] data, int offset, int length, byte[] peer);
    static native int setOption(int socket, int option, int value);
    static native byte[] name(int socket, boolean peer);
}
