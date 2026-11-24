#include "ipv6_frame_inspect.h"
#include "ipv4_frame_inspect.h"

#include <assert.h>
#include <string.h>

static void tcp_header(uint8_t *out, uint16_t destination, uint8_t flags) {
    memset(out, 0, 20);
    out[0] = 0x30; out[1] = 0x39; /* source port 12345 */
    out[2] = (uint8_t)(destination >> 8); out[3] = (uint8_t)destination;
    out[12] = 0x50; out[13] = flags;
}

static int inspect(uint8_t *packet, size_t packet_length, uint16_t expected_destination,
        int expected_valid) {
    uint8_t protocol = 0;
    const uint8_t *payload = NULL;
    size_t payload_length = 0;
    int valid = diplay_ipv6_upper_layer(packet, packet_length, &protocol, &payload, &payload_length);
    assert(valid == expected_valid);
    if (!expected_valid) return valid;
    assert(protocol == 6);
    assert(payload_length >= 20);
    diplay_ipv6_tcp_segment segment;
    assert(diplay_ipv6_tcp_segment_info(protocol, payload, payload_length, &segment));
    assert(segment.source_port == 12345);
    assert(segment.destination_port == expected_destination);
    assert(diplay_ipv6_is_initial_syn_to(&segment, 7000));
    assert(!diplay_ipv6_is_initial_syn_to(&segment, 7001));
    return valid;
}

int main(void) {
    uint8_t udp[8] = { 0x14, 0xe9, 0x14, 0xe9, 0, 8, 0, 0 }; /* 5353 -> 5353 */
    assert(diplay_udp_is_mdns(udp, sizeof(udp)));
    udp[0] = 0x14; udp[1] = 0xe9; udp[2] = 0x1b; udp[3] = 0x58; /* 5353 -> 7000 */
    assert(diplay_udp_is_mdns(udp, sizeof(udp)));
    udp[0] = 0x1b; udp[1] = 0x58; udp[2] = 0x1b; udp[3] = 0x59; /* 7000 -> 7001 */
    assert(!diplay_udp_is_mdns(udp, sizeof(udp)));
    assert(!diplay_udp_is_mdns(udp, 7));

    uint8_t packet[192];
    memset(packet, 0, sizeof(packet));
    /* IPv6 mDNS may follow an extension header; the passive classifier must
     * identify the UDP ports without inspecting or retaining the DNS payload. */
    packet[0] = 0x60; packet[4] = 0; packet[5] = 16; packet[6] = 0;
    packet[40] = 17; packet[41] = 0;
    packet[48] = 0x14; packet[49] = 0xe9; packet[50] = 0x14; packet[51] = 0xe9;
    packet[52] = 0; packet[53] = 8;
    uint8_t protocol = 0;
    const uint8_t *payload = NULL;
    size_t payload_length = 0;
    assert(diplay_ipv6_upper_layer(packet, 56, &protocol, &payload, &payload_length));
    assert(protocol == 17 && diplay_udp_is_mdns(payload, payload_length));
    packet[48] = 0x1b; packet[49] = 0x58; packet[50] = 0x1b; packet[51] = 0x59;
    assert(!diplay_udp_is_mdns(payload, payload_length));

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[4] = 0; packet[5] = 20; packet[6] = 6;
    tcp_header(packet + 40, 7000, 0x02);
    inspect(packet, 60, 7000, 1);

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[4] = 0; packet[5] = 28; packet[6] = 0;
    packet[40] = 6; packet[41] = 0;
    tcp_header(packet + 48, 7000, 0x02);
    inspect(packet, 68, 7000, 1);

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[4] = 0; packet[5] = 36; packet[6] = 60;
    packet[40] = 44; packet[41] = 0;
    packet[48] = 6; /* Fragment next-header; offset is zero. */
    tcp_header(packet + 56, 7000, 0x02);
    inspect(packet, 76, 7000, 1);

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[4] = 0; packet[5] = 32; packet[6] = 51;
    packet[40] = 6; packet[41] = 1; /* AH length = (1 + 2) * 4 = 12. */
    tcp_header(packet + 52, 7000, 0x02);
    inspect(packet, 72, 7000, 1);

    packet[64] = 0x80; /* TCP data offset declares a 32-byte header in a 20-byte payload. */
    diplay_ipv6_tcp_segment segment;
    assert(diplay_ipv6_upper_layer(packet, 72, &protocol, &payload, &payload_length));
    assert(!diplay_ipv6_tcp_segment_info(protocol, payload, payload_length, &segment));

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[5] = 28; packet[6] = 44;
    packet[40] = 6; packet[42] = 0; packet[43] = 8; /* Non-initial fragment. */
    assert(diplay_ipv6_upper_layer(packet, 68, &protocol, &payload, &payload_length));
    assert(protocol == 59); /* valid fragment, deliberately not treated as TCP */

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[5] = 8; packet[6] = 0;
    packet[40] = 6; packet[41] = 1; /* Extension header overruns payload. */
    assert(!diplay_ipv6_upper_layer(packet, 48, &protocol, &payload, &payload_length));

    /* Eight extension headers are supported; a ninth is preserved as an
     * unknown chain rather than mislabeled malformed or walked without bound. */
    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[5] = 84; packet[6] = 60;
    for (int i = 0; i < 8; i++) {
        size_t offset = 40U + (size_t)i * 8U;
        packet[offset] = (uint8_t)(i == 7 ? 6 : 60);
        packet[offset + 1] = 0;
    }
    tcp_header(packet + 104, 7000, 0x02);
    inspect(packet, 124, 7000, 1);

    memset(packet, 0, sizeof(packet));
    packet[0] = 0x60; packet[5] = 92; packet[6] = 60;
    for (int i = 0; i < 9; i++) {
        size_t offset = 40U + (size_t)i * 8U;
        packet[offset] = (uint8_t)(i == 8 ? 6 : 60);
        packet[offset + 1] = 0;
    }
    tcp_header(packet + 112, 7000, 0x02);
    assert(diplay_ipv6_upper_layer(packet, 132, &protocol, &payload, &payload_length));
    assert(protocol == 60 && payload_length == 28);

    /* IPv4 telemetry parser: direct TCP, options, fragmentation and bounds. */
    memset(packet, 0, sizeof(packet));
    uint8_t *ip4 = packet + 14;
    ip4[0] = 0x45; ip4[2] = 0; ip4[3] = 40; ip4[9] = 6;
    tcp_header(ip4 + 20, 7000, 0x02);
    diplay_ipv4_payload_view view;
    diplay_ipv4_tcp_segment tcp4;
    assert(diplay_ipv4_payload(ip4, 40, &view));
    assert(view.protocol == 6 && view.initial_fragment && view.payload_length == 20);
    assert(diplay_ipv4_tcp_segment_info(&view, &tcp4));
    assert(tcp4.source_port == 12345 && tcp4.destination_port == 7000);
    assert(diplay_ipv4_is_initial_syn_to(&tcp4, 7000));
    assert(!diplay_ipv4_is_initial_syn_to(&tcp4, 7001));

    /* IPv4 mDNS is diagnostic-only but must be distinguished from unrelated
     * UDP and parsed at the actual IHL when options are present. */
    memset(ip4, 0, 32);
    ip4[0] = 0x45; ip4[2] = 0; ip4[3] = 28; ip4[9] = 17;
    ip4[20] = 0x14; ip4[21] = 0xe9; ip4[22] = 0x14; ip4[23] = 0xe9;
    ip4[24] = 0; ip4[25] = 8;
    assert(diplay_ipv4_payload(ip4, 28, &view));
    assert(view.protocol == 17 && diplay_udp_is_mdns(view.payload, view.payload_length));
    ip4[20] = 0x1b; ip4[21] = 0x58; ip4[22] = 0x1b; ip4[23] = 0x59;
    assert(!diplay_udp_is_mdns(view.payload, view.payload_length));

    memset(ip4, 0, 32);
    ip4[0] = 0x46; ip4[2] = 0; ip4[3] = 32; ip4[9] = 17;
    ip4[24] = 0x14; ip4[25] = 0xe9; ip4[26] = 0x1b; ip4[27] = 0x58;
    ip4[28] = 0; ip4[29] = 8;
    assert(diplay_ipv4_payload(ip4, 32, &view));
    assert(view.payload == ip4 + 24 && diplay_udp_is_mdns(view.payload, view.payload_length));

    memset(ip4, 0, 44);
    ip4[0] = 0x46; ip4[2] = 0; ip4[3] = 44; ip4[9] = 6;
    tcp_header(ip4 + 24, 7000, 0x02);
    assert(diplay_ipv4_payload(ip4, 44, &view));
    assert(view.payload == ip4 + 24 && diplay_ipv4_tcp_segment_info(&view, &tcp4));

    ip4[6] = 0; ip4[7] = 1; /* Valid non-initial fragment: no TCP ports to inspect. */
    assert(diplay_ipv4_payload(ip4, 44, &view) && view.fragmented && !view.initial_fragment);
    assert(!diplay_ipv4_tcp_segment_info(&view, &tcp4));

    memset(ip4, 0, 40);
    ip4[0] = 0x45; ip4[2] = 0; ip4[3] = 60; ip4[9] = 6;
    assert(!diplay_ipv4_payload(ip4, 40, &view)); /* total length exceeds frame */

    memset(ip4, 0, 40);
    ip4[0] = 0x45; ip4[2] = 0; ip4[3] = 40; ip4[9] = 6;
    tcp_header(ip4 + 20, 7000, 0x02);
    ip4[20 + 12] = 0x80; /* Invalid 32-byte TCP header in a 20-byte payload. */
    assert(diplay_ipv4_payload(ip4, 40, &view));
    assert(!diplay_ipv4_tcp_segment_info(&view, &tcp4));

    return 0;
}
