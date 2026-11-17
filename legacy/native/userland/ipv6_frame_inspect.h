#ifndef DIPLAY_IPV6_FRAME_INSPECT_H
#define DIPLAY_IPV6_FRAME_INSPECT_H

#include <stddef.h>
#include <stdint.h>

static inline uint16_t diplay_ipv6_read_be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}

/* DNS-SD/mDNS uses UDP port 5353. This classifies headers only. */
static inline int diplay_udp_is_mdns(const uint8_t *payload, size_t payload_length) {
    if (payload == NULL || payload_length < 8) return 0;
    return diplay_ipv6_read_be16(payload) == 5353 || diplay_ipv6_read_be16(payload + 2) == 5353;
}

/* Locate an IPv6 upper-layer payload, following the extension headers most
 * relevant to TCP setup. The fixed depth bound protects diagnostics from
 * pathological chains. Non-initial fragments are not transport-inspectable.
 */
static inline int diplay_ipv6_upper_layer(
        const uint8_t *ip6, size_t available, uint8_t *protocol,
        const uint8_t **payload, size_t *payload_length) {
    if (ip6 == NULL || protocol == NULL || payload == NULL || payload_length == NULL ||
            available < 40 || (ip6[0] >> 4) != 6) return 0;

    size_t length = diplay_ipv6_read_be16(ip6 + 4);
    if (length > available - 40) return 0;
    size_t offset = 40;
    uint8_t next = ip6[6];

    for (int headers = 0; ; headers++) {
        if (headers >= 8 && (next == 0 || next == 43 || next == 44 || next == 51 || next == 60)) {
            /* Valid but unusually deep chain: report it as unknown, not malformed. */
            *protocol = next;
            *payload = ip6 + offset;
            *payload_length = length - (offset - 40);
            return 1;
        }
        size_t extension_length;
        switch (next) {
            case 0:   /* Hop-by-Hop Options */
            case 43:  /* Routing */
            case 60:  /* Destination Options */
                if (length - (offset - 40) < 2) return 0;
                extension_length = ((size_t)ip6[offset + 1] + 1U) * 8U;
                break;
            case 44: { /* Fragment */
                if (length - (offset - 40) < 8) return 0;
                uint16_t fragment = diplay_ipv6_read_be16(ip6 + offset + 2);
                if ((fragment & 0xfff8U) != 0) {
                    /* A valid later fragment has no TCP header to inspect. */
                    *protocol = 59;
                    *payload = ip6 + offset + 8;
                    *payload_length = length - (offset - 40) - 8;
                    return 1;
                }
                extension_length = 8;
                break;
            }
            case 51: /* Authentication Header */
                if (length - (offset - 40) < 2) return 0;
                extension_length = ((size_t)ip6[offset + 1] + 2U) * 4U;
                break;
            default:
                *protocol = next;
                *payload = ip6 + offset;
                *payload_length = length - (offset - 40);
                return 1;
        }
        if (extension_length == 0 || extension_length > length - (offset - 40)) return 0;
        next = ip6[offset];
        offset += extension_length;
    }
}

typedef struct {
    uint16_t source_port;
    uint16_t destination_port;
    uint8_t flags;
} diplay_ipv6_tcp_segment;

static inline int diplay_ipv6_tcp_segment_info(
        uint8_t protocol, const uint8_t *payload, size_t payload_length,
        diplay_ipv6_tcp_segment *segment) {
    if (protocol != 6 || payload == NULL || segment == NULL || payload_length < 20) return 0;
    size_t header_length = (size_t)((payload[12] >> 4) & 0x0f) * 4U;
    if (header_length < 20 || header_length > payload_length) return 0;
    segment->source_port = diplay_ipv6_read_be16(payload);
    segment->destination_port = diplay_ipv6_read_be16(payload + 2);
    segment->flags = payload[13];
    return 1;
}

static inline int diplay_ipv6_is_initial_syn(const diplay_ipv6_tcp_segment *segment) {
    return segment != NULL && (segment->flags & 0x02U) != 0 && (segment->flags & 0x10U) == 0;
}

static inline int diplay_ipv6_is_initial_syn_to(
        const diplay_ipv6_tcp_segment *segment, uint16_t destination_port) {
    return diplay_ipv6_is_initial_syn(segment) && segment->destination_port == destination_port;
}

#endif
