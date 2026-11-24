package local.headunit.diplay;

import java.util.regex.Pattern;

/** Keeps the no-VPN IPv6 experiment's bounded, non-payload diagnostics in issue reports. */
final class CompatibilityReportPolicy {
    private static final String COUNTERS = "(?:ageMs|rxUsbFrames|rxUsbBytes|rxTimeouts|rxRejected|rxIpv4EthFrames|rxIpv4Tcp|rxIpv4Syn|rxIpv4SynTrackedPort|rxIpv4Udp|rxIpv4Fragmented|rxIpv4Malformed|rxArp|rxOtherEtherType|rxMdns|rxIpv6Mdns|rxIpv4Mdns|rxIpv4SynPortPair|rxIpv6SynPortPair|lastTcpSynAgeMs|lastIpv4TcpSynAgeMs|rxIpv6EthFrames|rxIpv6EthBytes|rxIpv6|rxOther|rxNdp|rxNs|rxNa|rxTcp|rxUdp|rxDrop|txIpv6EthFrames|txIpv6EthBytes|txQueueDrop|txOutputCopyError|txDequeued|txUsbAttempt|txUsbOk|txUsbNoWrite|txUsbError|tcpSocketOpens|tcpAccepted|udpSocketOpens|socketOpErrors|tcpSynIn|tcpSynTrackedPort|tcpSynAckQueued|tcpRst|tcpFin|udpIn|udpOutQueued|lastRxPortPair|lastTxPortPair|txMdns|mdns6InQueryMask|mdns6InResponseMask|mdns4InQueryMask|mdns4InResponseMask|mdns6OutQueryMask|mdns6OutResponseMask|lastRxMdnsServiceAgeMs|lastRxMdnsServiceMask|lastRxMdnsServiceQueryMask|lastRxMdnsServiceResponseMask)=(?:[0-9]+|-1|[0-9]+->[0-9]+)";
    private static final Pattern SAFE_NETWORK_DETAIL = Pattern.compile(
        "(?:userland stack ready backend=lwIP ipv4=off ipv6=link-local local=fe80::2 tcp=on udp=on mtu=1500 ncm=USB noVpn=true|" +
        "userland init failed stage=tcpip_init nativeErrorCode=[0-9]+|" +
        "userland stack create result=(?:ready|failed) durationMs=[0-9]+ nativeErrorCode=[0-9]+|" +
        "userland socket op failed operation=(?:socket|bind|listen|accept|setsockopt_(?:SO_REUSEADDR|SO_RCVBUF|SO_SNDBUF|SO_KEEPALIVE|TCP_NODELAY|SO_RCVTIMEO|SO_SNDTIMEO|SO_LINGER)) errno=[0-9]+|" +
        "ncm ndp prewarm queued result=-?[0-9]+ expected=[0-9]+ submitted=[0-9]+ polls=[0-9]+ durationMs=[0-9]+ cancelled=[0-9]+ actualBytes=[0-9]+ urbStatus=-?[0-9]+ inbound=(?:true|false)|" +
        "ncm ndp prewarm queued unsupported before submit; short synchronous fallback|" +
        "ncm ndp prewarm result=-?[0-9]+ expected=[0-9]+ durationMs=[0-9]+|" +
        "ncm bulk-out accepted after [0-9]+ not-ready write calls bytes=[0-9]+ durationMs=[0-9]+|" +
        "ncm network readiness observed source=(?:carrier|first_ncm_frame) ageMs=[0-9]+|" +
        "userland NDP warmup (?:started ageMs=[0-9]+|attempt result=(?:sent|not_ready) durationMs=[0-9]+|summary frames=[0-9]+ bytes=[0-9]+ durationMs=[0-9]+)|" +
        "userland tx gate opened after iAP2 CarPlay session-start|" +
        "userland rx rejected frameBytes=[0-9]+ error=errno=[0-9]+|" +
        "userland forced socket cleanup count=[0-9]+|" +
        "userland stats ageMs=[0-9]+(?: " + COUNTERS + ")+|" +
        "userland (?:final|stack stopped)(?: " + COUNTERS + ")+)"
    );
    private static final int MAX_NETWORK_DETAIL_CHARS = 1200;

    private CompatibilityReportPolicy() {}

    static boolean isNetworkStackEvent(String type) {
        return "NETWORK_BACKEND_SELECTED".equals(type) || "NETWORK_BACKEND".equals(type) ||
            "NETWORK_LISTENER".equals(type) || "USERLAND_NETWORK".equals(type);
    }

    static boolean isNetworkStackMilestone(String type, String detail) {
        if ("NETWORK_BACKEND_SELECTED".equals(type) || "NETWORK_BACKEND".equals(type) ||
            "NETWORK_LISTENER".equals(type)) return true;
        if (!"USERLAND_NETWORK".equals(type) || detail == null) return false;
        return detail.contains("stack ready") || detail.contains("stack create") || detail.contains("init failed") || detail.contains("tx gate opened") || detail.contains("NDP warmup") || detail.contains("ncm ndp prewarm") || detail.contains("ncm bulk-out accepted") || detail.contains("ncm network readiness") ||
            detail.contains("socket op failed") ||
            detail.contains("rx rejected frame") || detail.contains("forced socket cleanup") ||
            detail.contains("userland final") || detail.contains("stack stopped");
    }

    /** Accept only known userland network milestones or bounded numeric counters; never forward payload text. */
    static String safeNetworkDetail(String detail) {
        if (detail == null || detail.length() == 0 || !SAFE_NETWORK_DETAIL.matcher(detail).matches()) return "";
        return detail.length() <= MAX_NETWORK_DETAIL_CHARS ? detail : detail.substring(0, MAX_NETWORK_DETAIL_CHARS);
    }
}
