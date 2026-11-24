package local.headunit.diplay;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

/** Compares process lifetimes without adding devices' boot or process identifiers to reports. */
final class BootContinuity {
    static final BootContinuity UNKNOWN = new BootContinuity("", "", "");
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Pattern BOOT_UUID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_PROCESSES = 512;
    private static final long MAX_SCAN_NS = 100_000_000L;

    final String bootId;
    final String systemServer;
    final String zygote;

    BootContinuity(String bootId, String systemServer, String zygote) {
        this.bootId = bootId;
        this.systemServer = systemServer;
        this.zygote = zygote;
    }

    static BootContinuity sample(File proc) {
        byte[] bytes = new byte[512];
        String boot = read(new File(proc, "sys/kernel/random/boot_id"), bytes).trim();
        if (!BOOT_UUID.matcher(boot).matches()) boot = "";

        ProcStat own = ProcStat.parse(read(new File(proc, "self/stat"), bytes));
        String zygote = "";
        if (own != null && own.parent > 1) {
            ProcStat parent = ProcStat.parse(read(new File(proc, own.parent + "/stat"), bytes));
            if (parent != null && (parent.name.equals("zygote") || parent.name.equals("zygote32") ||
                    parent.name.equals("zygote64"))) zygote = parent.identity;
        }

        String server = "";
        long started = System.nanoTime();
        String[] entries;
        try { entries = proc.list(); } catch (SecurityException denied) { entries = null; }
        if (entries != null) {
            int checked = 0;
            for (String entry : entries) {
                if (entry.isEmpty() || entry.charAt(0) < '0' || entry.charAt(0) > '9') continue;
                if (++checked > MAX_PROCESSES || System.nanoTime() - started >= MAX_SCAN_NS) break;
                String line = read(new File(proc, entry + "/stat"), bytes);
                if (!line.contains(" (system_server) ")) continue;
                ProcStat system = ProcStat.parse(line);
                if (system != null && system.name.equals("system_server")) {
                    server = system.identity;
                    break;
                }
            }
        }
        return new BootContinuity(boot, server, zygote);
    }

    String classify(BootContinuity previous, long previousElapsed, long currentElapsed) {
        boolean elapsedRegressed = previousElapsed >= 0L && currentElapsed >= 0L && currentElapsed < previousElapsed;
        if (comparable(bootId, previous.bootId)) {
            if (!bootId.equals(previous.bootId)) return "kernel_boot_changed";
            if (comparable(systemServer, previous.systemServer) && !systemServer.equals(previous.systemServer)) {
                return "system_server_changed";
            }
            if (comparable(zygote, previous.zygote) && !zygote.equals(previous.zygote)) return "zygote_changed";
            if (elapsedRegressed) return "same_kernel_elapsed_inconsistent";
            if (comparable(systemServer, previous.systemServer)) return "same_kernel_and_system_server";
            return "same_kernel_boot";
        }
        return elapsedRegressed ? "boot_elapsed_reset" : "unknown";
    }

    static boolean comparable(String first, String second) {
        return !first.isEmpty() && !second.isEmpty();
    }

    static Boolean identityChanged(String first, String second) {
        return comparable(first, second) ? !first.equals(second) : null;
    }

    private static String read(File path, byte[] bytes) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(path);
            int count = in.read(bytes);
            return count > 0 ? new String(bytes, 0, count, ASCII) : "";
        } catch (IOException | SecurityException unavailable) {
            return "";
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) { }
        }
    }

    private static final class ProcStat {
        final String name, identity;
        final int parent;

        ProcStat(String name, int parent, String identity) {
            this.name = name;
            this.parent = parent;
            this.identity = identity;
        }

        static ProcStat parse(String line) {
            int open = line.indexOf('('), close = line.lastIndexOf(')');
            if (open < 1 || close <= open || close + 1 >= line.length()) return null;
            // The comm field may contain spaces or parentheses. State is field 3, start time is field 22.
            String[] fields = line.substring(close + 1).trim().split(" +", 21);
            if (fields.length < 20) return null;
            try {
                int pid = Integer.parseInt(line.substring(0, open).trim());
                int ppid = Integer.parseInt(fields[1]);
                long start = Long.parseLong(fields[19]);
                return pid > 0 && start > 0 ? new ProcStat(line.substring(open + 1, close), ppid, pid + ":" + start) : null;
            } catch (NumberFormatException malformed) {
                return null;
            }
        }
    }
}
