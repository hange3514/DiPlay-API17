package local.headunit.diplay;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only evidence for the vehicle's own AP, independent of DiPlay's station connection. */
final class HotspotEvidence {
    private static final String TRAFFIC_PACKAGE = "com.byd.trafficstats";
    private static final String AP_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String TETHER_CHANGED = "android.net.conn.TETHER_STATE_CHANGED";
    private static final String PREFS = "hotspot_evidence";
    private static final String HISTORY = "recent";
    private static final String AP_GATEWAY = "192.168.43.1";
    private static final int AP_DISABLING = 10;
    private static final int AP_DISABLED = 11;
    private static final int AP_ENABLING = 12;
    private static final int AP_ENABLED = 13;
    private static final int AP_FAILED = 14;
    private static final long RETENTION_MS = 24L * 60 * 60 * 1000;
    private static final int HISTORY_LIMIT = 16;
    private static final int MAX_HISTORY_CHARS = 16 * 1024;
    private static final int LOGCAT_BYTES = 24 * 1024;
    private static final long LOGCAT_TIME_MS = 1200;
    private static final Pattern IFACE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,15}");
    private static final Pattern LOG_LINE = Pattern.compile(
            "^\\s*(\\d\\d-\\d\\d\\s+\\d\\d:\\d\\d:\\d\\d\\.\\d{3})\\s+(\\d+)\\s+\\d+\\s+[VDIWEAF]\\s+([^:]{1,48}):\\s?(.*)$");
    private static final Pattern EXCEPTION = Pattern.compile(
            "^(?:Caused by: )?((?:[A-Za-z_$][A-Za-z0-9_$]{0,47}\\.){1,11}[A-Za-z0-9_$]{0,55}(?:Exception|Error))\\b");
    private static final Pattern FRAME = Pattern.compile(
            "^\\s*at ((?:[A-Za-z_$][A-Za-z0-9_$]{0,47}\\.){1,12}[A-Za-z_$<>][A-Za-z0-9_$<>]{0,47})\\((?:[A-Za-z0-9_$]+\\.java(?::(\\d{1,6}))?|Native Method|Unknown Source)\\)$");
    private static final Pattern TRAFFIC_NAME = Pattern.compile(
            "(?<![A-Za-z0-9_.])com\\.byd\\.trafficstats(?=$|[:\\s,)])");
    private static final Pattern PROCESS_PID = Pattern.compile("\\bpid\\s*[=:]?\\s*(\\d{1,8})\\b");
    private static volatile int broadcastApState = -1;
    private static volatile TetherState tetherState = new TetherState();
    private static String lastError = "";
    private static boolean initialized;

    private HotspotEvidence() {}

    static boolean supported() {
        return Build.VERSION.SDK_INT == 17 && "Freescale".equalsIgnoreCase(Build.MANUFACTURER)
                && "MX9A0".equalsIgnoreCase(Build.MODEL);
    }

    static synchronized void initialize(Context context) {
        if (initialized || !supported()) return;
        initialized = true;
        final Context app = context.getApplicationContext();
        HandlerThread thread = new HandlerThread("diplay-hotspot-evidence");
        thread.start();
        final Handler worker = new Handler(thread.getLooper());
        final Runnable followupSample = new Runnable() {
            @Override public void run() { remember(app, "hotspot_followup", -1); }
        };
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (AP_CHANGED.equals(intent.getAction())) {
                    int received = validApState(intent.getIntExtra("wifi_state", -1));
                    int before = broadcastApState;
                    broadcastApState = received;
                    remember(app, "ap_broadcast", received);
                    worker.removeCallbacks(followupSample);
                    if (received == AP_ENABLED) {
                        worker.postDelayed(followupSample, 3000);
                        worker.postDelayed(followupSample, 12000);
                        worker.postDelayed(followupSample, 30000);
                        worker.postDelayed(followupSample, 65000);
                        worker.postDelayed(followupSample, 105000);
                    } else if (received == AP_FAILED || (received == AP_DISABLED &&
                            (before == AP_DISABLING || before == AP_ENABLING || before == AP_ENABLED))) {
                        // The platform can stop the AP automatically after a tethering timeout.
                        worker.postDelayed(followupSample, 1000);
                        worker.postDelayed(followupSample, 5000);
                    }
                } else if (TETHER_CHANGED.equals(intent.getAction())) {
                    TetherState prior = tetherState;
                    TetherState next = new TetherState(intent);
                    if (next.observed) tetherState = next;
                    if (next.observed && !next.sameAs(prior)) {
                        remember(app, "tether_broadcast", -1);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(AP_CHANGED);
        filter.addAction(TETHER_CHANGED);
        try {
            app.registerReceiver(receiver, filter, null, worker);
        } catch (RuntimeException unavailable) {
            thread.quit();
        }
    }

    /** Invoke on the report worker. No root or protected log permissions are requested. */
    static JSONObject snapshot(Context context) throws JSONException {
        JSONObject current = sample(context, "report", -1);
        // Save visible crash state before gathering logs, including reports made immediately.
        if (current.has("trafficError")) store(context, new JSONObject(current.toString()));
        JSONArray timeline;
        synchronized (HotspotEvidence.class) {
            timeline = history(context, System.currentTimeMillis());
        }
        return new JSONObject()
                .put("version", 1)
                .put("current", current)
                .put("timeline", timeline)
                .put("trafficPackage", trafficPackage(context))
                .put("systemLog", systemLog(context));
    }

    private static JSONObject trafficPackage(Context context) throws JSONException {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TRAFFIC_PACKAGE, 0);
            ApplicationInfo app = info.applicationInfo;
            JSONObject found = new JSONObject().put("status", "installed").put("versionCode", info.versionCode);
            if (info.versionName != null && info.versionName.matches("[A-Za-z0-9_.-]{1,48}")) {
                found.put("versionName", info.versionName);
            }
            if (app != null) {
                found.put("systemApp", (app.flags & (ApplicationInfo.FLAG_SYSTEM
                        | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0);
                String path = app.sourceDir;
                if (path != null && path.endsWith(".apk")) {
                    appendArtifactFact(found, "apk", new File(path));
                    appendArtifactFact(found, "odex", new File(path.substring(0, path.length() - 4) + ".odex"));
                }
            }
            return found;
        } catch (PackageManager.NameNotFoundException absent) {
            return new JSONObject().put("status", "not_installed");
        } catch (RuntimeException denied) {
            return new JSONObject().put("status", "unavailable");
        }
    }

    private static void appendArtifactFact(JSONObject target, String kind, File file) throws JSONException {
        boolean readable = file.isFile() && file.canRead();
        target.put(kind + "Readable", readable);
        if (readable) target.put(kind + "Bytes", file.length());
    }

    static synchronized void clear(Context context) {
        prefs(context).edit().remove(HISTORY).apply();
        lastError = "";
    }

    private static void remember(Context context, String source, int statedApState) {
        try {
            store(context, sample(context, source, statedApState));
        } catch (JSONException | RuntimeException ignored) {
            // Evidence is optional. The car's hotspot and DiPlay can continue with no snapshot.
        }
    }

    private static synchronized void store(Context context, JSONObject row) throws JSONException {
        JSONObject error = row.optJSONObject("trafficError");
        if (error != null) {
            String signature = error.toString();
            if (signature.equals(lastError)) row.remove("trafficError");
            lastError = signature;
        } else if ("no_error_observed".equals(row.optString("trafficErrorState"))) {
            lastError = "";
        }
        if ("report".equals(row.optString("source")) && !row.has("trafficError")) return;
        JSONArray previous = history(context, System.currentTimeMillis());
        List<JSONObject> rows = new ArrayList<JSONObject>(HISTORY_LIMIT + 1);
        for (int i = 0; i < previous.length(); i++) rows.add(previous.getJSONObject(i));
        rows.add(row);
        // Keep the most recent error when noisy network callbacks push older snapshots out.
        JSONObject lastErrorRow = null;
        for (JSONObject item : rows) if (item.has("trafficError")) lastErrorRow = item;
        String encoded = new JSONArray(rows).toString();
        while ((rows.size() > HISTORY_LIMIT || encoded.length() > MAX_HISTORY_CHARS) && rows.size() > 1) {
            rows.remove(rows.get(0) == lastErrorRow ? 1 : 0);
            encoded = new JSONArray(rows).toString();
        }
        if (encoded.length() <= MAX_HISTORY_CHARS) {
            prefs(context).edit().putString(HISTORY, encoded).apply();
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray history(Context context, long now) {
        JSONArray retained = new JSONArray();
        String encoded = prefs(context).getString(HISTORY, "");
        if (encoded.isEmpty() || encoded.length() > MAX_HISTORY_CHARS) return retained;
        try {
            JSONArray previous = new JSONArray(encoded);
            for (int i = Math.max(0, previous.length() - HISTORY_LIMIT); i < previous.length(); i++) {
                JSONObject row = previous.optJSONObject(i);
                if (row == null) continue;
                long at = row.optLong("timeMs", -1);
                if (at > 0 && now - at <= RETENTION_MS && at - now <= RETENTION_MS) retained.put(row);
            }
        } catch (JSONException ignored) {}
        return retained;
    }

    private static JSONObject sample(Context context, String source, int statedApState) throws JSONException {
        JSONObject row = new JSONObject()
                .put("source", source)
                .put("timeMs", System.currentTimeMillis())
                .put("elapsedMs", SystemClock.elapsedRealtime());
        int apState = statedApState;
        String apSource = "broadcast";
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                if (apState < 0) {
                    apState = wifiApState(wifi);
                    apSource = "query";
                }
                WifiInfo station = wifi.getConnectionInfo();
                boolean enabled = wifi.isWifiEnabled();
                boolean associated = enabled && station != null
                        && station.getSupplicantState() == SupplicantState.COMPLETED;
                row.put("stationWifiEnabled", enabled).put("stationAssociated", associated)
                        .put("stationIpv4Assigned", associated && station.getIpAddress() != 0);
            }
        } catch (RuntimeException ignored) {}
        if (apState < 0 && broadcastApState >= 0) {
            apState = broadcastApState;
            apSource = "last_broadcast";
        }
        row.put("apState", apState).put("apStateSource", apState < 0 ? "unavailable" : apSource);

        TetherState tether = tetherState;
        if (!tether.observed) {
            try {
                Intent sticky = context.registerReceiver(null, new IntentFilter(TETHER_CHANGED));
                if (sticky != null) tether = new TetherState(sticky);
            } catch (RuntimeException ignored) {}
        }
        row.put("tetherStateObserved", tether.observed).put("tetherAvailable", names(tether.available))
                .put("tetherActive", names(tether.active)).put("tetherErrored", names(tether.errored));
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo mobile = manager == null ? null : manager.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
            if (mobile != null) row.put("mobileConnected", mobile.isConnected());
        } catch (RuntimeException ignored) {}
        appendInterfaces(row, tether, apState == AP_ENABLED);
        row.put("dhcpServerSocket", apState == AP_ENABLED ? dhcpServerSocket() : "not_sampled");
        appendError(context, row);
        return row;
    }

    private static int wifiApState(WifiManager wifi) {
        try {
            Object value = wifi.getClass().getMethod("getWifiApState").invoke(wifi);
            if (value instanceof Integer) return validApState((Integer) value);
        } catch (Exception ignored) {}
        return -1;
    }

    private static int validApState(int state) { return state >= 10 && state <= 14 ? state : -1; }

    private static final class TetherState {
        final List<String> available, active, errored;
        final boolean observed;

        TetherState() {
            available = active = errored = Collections.emptyList();
            observed = false;
        }

        TetherState(Intent intent) {
            available = tetherNames(intent, "availableArray");
            active = tetherNames(intent, "activeArray");
            errored = tetherNames(intent, "erroredArray");
            observed = intent.hasExtra("availableArray") || intent.hasExtra("activeArray")
                    || intent.hasExtra("erroredArray");
        }

        boolean sameAs(TetherState other) {
            return observed == other.observed && active.equals(other.active)
                    && errored.equals(other.errored) && available.equals(other.available);
        }
    }

    private static List<String> tetherNames(Intent intent, String key) {
        List<String> output = new ArrayList<String>(8);
        try {
            List<String> input = intent.getStringArrayListExtra(key);
            if (input == null) {
                String[] array = intent.getStringArrayExtra(key);
                if (array != null) input = java.util.Arrays.asList(array);
            }
            if (input != null) {
                for (String name : input) {
                    if (output.size() == 8) break;
                    if (name != null && IFACE.matcher(name).matches() && !output.contains(name)) output.add(name);
                }
            }
        } catch (RuntimeException ignored) {}
        Collections.sort(output);
        return output;
    }

    private static JSONArray names(List<String> values) {
        return new JSONArray(values);
    }

    private static void appendInterfaces(JSONObject row, TetherState tether, boolean apEnabled) throws JSONException {
        JSONArray details = new JSONArray();
        boolean expectedGateway = false;
        String status = "available";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements() && details.length() < 8) {
                NetworkInterface item = interfaces.nextElement();
                String name = item.getName();
                if (name == null || !IFACE.matcher(name).matches()) continue;
                String lower = name.toLowerCase(Locale.US);
                if (!(lower.startsWith("wlan") || lower.startsWith("wifi") || lower.startsWith("ap")
                        || lower.startsWith("softap") || lower.startsWith("swlan") || lower.startsWith("p2p")
                        || tether.active.contains(name) || tether.available.contains(name)
                        || tether.errored.contains(name))) continue;
                JSONArray ipv4 = new JSONArray();
                int ipv4Count = 0;
                for (InterfaceAddress address : item.getInterfaceAddresses()) {
                    if (!(address.getAddress() instanceof Inet4Address)) continue;
                    ipv4Count++;
                    String ip = address.getAddress().getHostAddress();
                    if (AP_GATEWAY.equals(ip)) expectedGateway = true;
                    // A station address and nonlocal routes are not needed to diagnose local DHCP.
                    if (apEnabled && (AP_GATEWAY.equals(ip) || (tether.active.contains(name)
                            && address.getAddress().isSiteLocalAddress())) && ipv4.length() < 4) {
                        ipv4.put(new JSONObject().put("address", ip).put("prefix", address.getNetworkPrefixLength()));
                    }
                }
                details.put(new JSONObject().put("name", name).put("up", item.isUp())
                        .put("ipv4Count", ipv4Count).put("ips", ipv4));
            }
        } catch (java.net.SocketException | RuntimeException unavailable) {
            status = "partially_available";
        }
        row.put("interfacesStatus", status).put("wifiInterfaces", details)
                .put("expectedApGatewayPresent", expectedGateway);
    }

    private static String dhcpServerSocket() {
        long deadline = SystemClock.elapsedRealtime() + 60L;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/net/udp"));
            reader.readLine();
            for (int i = 0; i < 128 && SystemClock.elapsedRealtime() < deadline; i++) {
                String line = reader.readLine();
                if (line == null) return "not_observed";
                String[] columns = line.trim().split("\\s+", 3);
                if (columns.length > 1 && columns[1].matches("[A-Fa-f0-9]{8}:0043")) return "listening";
            }
            return "scan_limit";
        } catch (Exception unavailable) {
            return "unavailable";
        } finally {
            closeQuietly(reader);
        }
    }

    private static void appendError(Context context, JSONObject row) throws JSONException {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                row.put("trafficErrorState", "unavailable");
                return;
            }
            List<ActivityManager.ProcessErrorStateInfo> errors = manager.getProcessesInErrorState();
            if (errors != null) for (ActivityManager.ProcessErrorStateInfo err : errors) {
                if (err.processName == null || !(err.processName.equals(TRAFFIC_PACKAGE)
                        || err.processName.startsWith(TRAFFIC_PACKAGE + ":"))) continue;
                JSONObject event = new JSONObject().put("condition", err.condition == ActivityManager.ProcessErrorStateInfo.CRASHED
                        ? "crashed" : err.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING ? "anr" : "other");
                JSONArray stack = new JSONArray();
                addStackToken(stack, err.shortMsg);
                if (err.stackTrace != null) {
                    for (String line : err.stackTrace.substring(0, Math.min(err.stackTrace.length(), 8192)).split("\\r?\\n")) {
                        if (stack.length() == 10) break;
                        addStackToken(stack, line);
                    }
                }
                event.put("stack", stack).put("detailCaptured", stack.length() > 0);
                row.put("trafficErrorState", "observed").put("trafficError", event);
                return;
            }
            row.put("trafficErrorState", "no_error_observed");
        } catch (RuntimeException denied) {
            row.put("trafficErrorState", "unavailable");
        }
    }

    private static void addStackToken(JSONArray tokens, String input) {
        String token = stackToken(input);
        if (token != null) tokens.put(token);
    }

    private static String stackToken(String input) {
        if (input == null || input.length() > 640) return null;
        Matcher exception = EXCEPTION.matcher(input.trim());
        if (exception.find()) return exception.group(1);
        Matcher frame = FRAME.matcher(input.trim());
        return frame.matches() ? frame.group(1) + (frame.group(2) == null ? "" : ":" + frame.group(2)) : null;
    }

    private static JSONObject systemLog(Context context) throws JSONException {
        boolean readPermission = context.checkCallingOrSelfPermission("android.permission.READ_LOGS")
                == PackageManager.PERMISSION_GRANTED;
        JSONObject result = new JSONObject().put("readLogsGranted", readPermission);
        JSONArray events = new JSONArray();
        Process process = null;
        InputStream stream = null;
        try {
            process = new ProcessBuilder("/system/bin/logcat", "-b", "main", "-b", "system",
                    "-d", "-t", "200", "-v", "threadtime", "-s",
                    "AndroidRuntime:E", "ActivityManager:I", "WifiStateMachine:D", "Tethering:D",
                    "NetworkManagementService:D", "NetdConnector:D", "hostapd:D", "dnsmasq:D", "dnsmasq-dhcp:D")
                    .redirectErrorStream(true).start();
            ByteArrayOutputStream data = new ByteArrayOutputStream(4096);
            long deadline = SystemClock.elapsedRealtime() + LOGCAT_TIME_MS;
            stream = process.getInputStream();
            byte[] buffer = new byte[2048];
            while (data.size() < LOGCAT_BYTES && SystemClock.elapsedRealtime() < deadline) {
                int readable = stream.available();
                if (readable == 0) {
                    if (exitCode(process) != null) break;
                    Thread.sleep(20);
                    continue;
                }
                int size = stream.read(buffer, 0, Math.min(buffer.length, Math.min(readable, LOGCAT_BYTES - data.size())));
                if (size < 0) break;
                data.write(buffer, 0, size);
            }
            Integer exit = exitCode(process);
            events = summarizeLog(new String(data.toByteArray(), "UTF-8"));
            String status = !readPermission
                    ? (events.length() == 0 ? "restricted_no_matching_evidence" : "restricted_matching_evidence")
                    : exit == null ? (data.size() == LOGCAT_BYTES ? "size_limit" : "timeout")
                    : exit != 0 ? "logcat_failed" : events.length() == 0 ? "no_matching_evidence" : "observed";
            result.put("status", status).put("captureLimited", exit == null || data.size() == LOGCAT_BYTES);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result.put("status", "interrupted");
        } catch (Exception unavailable) {
            result.put("status", "unavailable");
        } finally {
            if (process != null) process.destroy();
            closeQuietly(stream);
            if (process != null) {
                closeQuietly(process.getOutputStream());
                closeQuietly(process.getErrorStream());
            }
        }
        return result.put("lines", events);
    }

    private static void closeQuietly(Closeable stream) {
        if (stream != null) try { stream.close(); } catch (Exception ignored) {}
    }

    private static Integer exitCode(Process process) {
        try { return process.exitValue(); }
        catch (IllegalThreadStateException running) { return null; }
    }

    private static final class CrashBlock {
        int latestLine;
        boolean target;
    }

    private static final class LogEvidence {
        final JSONObject value;
        final CrashBlock owner;

        LogEvidence(JSONObject value, CrashBlock owner) {
            this.value = value;
            this.owner = owner;
        }
    }

    /** Never send logcat free text, network names, addresses, MACs, or exception messages. */
    static JSONArray summarizeLog(String text) throws JSONException {
        List<LogEvidence> candidates = new ArrayList<LogEvidence>();
        Map<String, CrashBlock> runtime = new HashMap<String, CrashBlock>();
        int lineIndex = 0;
        for (String line : text.split("\\r?\\n")) {
            lineIndex++;
            if (line.length() > 640) continue;
            Matcher event = LOG_LINE.matcher(line);
            if (!event.matches()) continue;
            String time = event.group(1).replace(' ', 'T');
            String pid = event.group(2);
            String tag = event.group(3).trim();
            String content = event.group(4);
            String lower = content.toLowerCase(Locale.US);
            String category = null;
            String detail = null;
            CrashBlock owner = null;
            if ("AndroidRuntime".equals(tag)) {
                detail = stackToken(content);
                boolean target = (content.startsWith("Process:") && TRAFFIC_NAME.matcher(content).find())
                        || (detail != null && detail.startsWith(TRAFFIC_PACKAGE + "."));
                CrashBlock active = runtime.get(pid);
                if (content.startsWith("FATAL EXCEPTION")) {
                    active = new CrashBlock();
                    runtime.put(pid, active);
                    category = "traffic_runtime";
                } else if (target || (active != null && detail != null && lineIndex - active.latestLine <= 40)) {
                    if (active == null || lineIndex - active.latestLine > 40) {
                        active = new CrashBlock();
                        runtime.put(pid, active);
                    }
                    category = detail == null ? "traffic_runtime" : "traffic_stack";
                }
                if (category != null) {
                    owner = active;
                    owner.latestLine = lineIndex;
                    owner.target |= target;
                }
            } else if ("ActivityManager".equals(tag) && TRAFFIC_NAME.matcher(content).find()) {
                if (lower.contains("has died") || lower.contains("crash") || lower.contains("killing")) {
                    category = "traffic_process_exit";
                    Matcher process = PROCESS_PID.matcher(content);
                    if (process.find()) {
                        CrashBlock active = runtime.remove(process.group(1));
                        if (active != null && lineIndex - active.latestLine <= 50) active.target = true;
                    }
                } else if (lower.contains("anr")) category = "traffic_process_anr";
                else if (lower.contains("force finishing")) category = "traffic_activity_forced_finish";
                else if (lower.contains("start proc")) {
                    category = "traffic_process_start";
                    Matcher process = PROCESS_PID.matcher(content);
                    if (process.find()) runtime.remove(process.group(1));
                }
            } else if ("WifiStateMachine".equals(tag) || "Tethering".equals(tag)
                    || "NetworkManagementService".equals(tag) || "NetdConnector".equals(tag)
                    || "hostapd".equals(tag) || "dnsmasq".equals(tag) || "dnsmasq-dhcp".equals(tag)) {
                category = hotspotLogCategory(lower, tag);
            }
            if (category == null) continue;
            JSONObject evidence = new JSONObject().put("time", time).put("tag", tag).put("event", category);
            if (detail != null) evidence.put("detail", detail.substring(0, Math.min(detail.length(), 250)));
            candidates.add(new LogEvidence(evidence, owner));
        }
        List<JSONObject> newest = new ArrayList<JSONObject>(24);
        for (int i = candidates.size() - 1; i >= 0 && newest.size() < 24; i--) {
            LogEvidence candidate = candidates.get(i);
            if (candidate.owner == null || candidate.owner.target) newest.add(candidate.value);
        }
        Collections.reverse(newest);
        return new JSONArray(newest);
    }

    private static String hotspotLogCategory(String message, String tag) {
        if (message.contains("dhcpack")) return "dhcp_ack";
        if (message.contains("dhcpoffer")) return "dhcp_offer";
        if (message.contains("dhcprequest")) return "dhcp_request";
        if (message.contains("dhcpdiscover")) return "dhcp_discover";
        if (message.contains("ap-sta-connected")) return "station_associated";
        if (message.contains("ap-sta-disconnected")) return "station_disassociated";
        boolean ap = message.contains("softap") || message.contains("soft ap") || message.contains("ap-");
        boolean sharing = message.contains("tether") || message.contains("dhcp");
        if (ap || sharing) {
            if (message.contains("fail") || message.contains("error") || message.contains("timeout")) {
                return ap ? "ap_failed" : "tether_failed";
            }
            if (message.contains("stop") || message.contains("disabled")) return ap ? "ap_stopped" : "tether_stopped";
            if (message.contains("start") || message.contains("enabled")) return ap ? "ap_started" : "tether_started";
        }
        if ("Tethering".equals(tag) && message.contains("upstream")) return "tether_upstream";
        return null;
    }
}
