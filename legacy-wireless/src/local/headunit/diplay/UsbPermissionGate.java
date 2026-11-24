package local.headunit.diplay;

/** Serializes the single app-owned USB permission request flow. */
final class UsbPermissionGate {
    private static boolean active;

    private UsbPermissionGate() { }

    static synchronized boolean beginPermissionFlow() {
        if (active) return false;
        active = true;
        return true;
    }

    static synchronized void endPermissionFlow() {
        active = false;
    }

    static synchronized boolean permissionFlowActive() {
        return active;
    }
}
