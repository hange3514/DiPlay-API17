#ifndef DIPLAY_IPV4_FRAME_INSPECT_H
#define DIPLAY_IPV4_FRAME_INSPECT_H

#include <stddef.h>
#include <stdint.h>

static inline uint16_t diplay_ipv4_read_be16(const uint8_t *p) {
    return (uint16_t)(((uint16_t)p[0] << 8) | p[1]);
}

typedef struct {
    uint8_t protocol;
    const uint8_t *payload;
    size_t payload_length;
    int initial_fragment;
    int fragmented;
} diplay_ipv4_payload_view;

typedef struct {
    uint16_t source_port;
    uint16_t destination_port;
    uint8_t flags;
} diplay_ipv4_tcp_segment;

/* Inspect IPv4 without consuming or modifying the frame. The available length
 * may include Ethernet padding; the IPv4 total-length field bounds the payload.
 */
static inline int diplay_ipv4_payload(
        const uint8_t *ip4, size_t available, diplay_ipv4_payload_view *view) {
    if (ip4 == NULL || view == NULL || available < 20 || (ip4[0] >> 4) != 4) return 0;

    size_t header_length = (size_t)(ip4[0] & 0x0fU) * 4U;
    size_t total_length = diplay_ipv4_read_be16(ip4 + 2);
    if (header_length < 20 || header_length > available ||
            total_length < header_length || total_length > available) return 0;

    uint16_t fragment = diplay_ipv4_read_be16(ip4 + 6);
    view->protocol = ip4[9];
    view->payload = ip4 + header_length;
    view->payload_length = total_length - header_length;
    view->initial_fragment = (fragment & 0x1fffU) == 0;
    view->fragmented = (fragment & 0x3fffU) != 0;
    return 1;
}

static inline int diplay_ipv4_tcp_segment_info(
        const diplay_ipv4_payload_view *view, diplay_ipv4_tcp_segment *segment) {
    if (view == NULL || segment == NULL || view->protocol != 6 ||
            !view->initial_fragment || view->payload == NULL || view->payload_length < 20) return 0;
    size_t header_length = (size_t)((view->payload[12] >> 4) & 0x0fU) * 4U;
    if (header_length < 20 || header_length > view->payload_length) return 0;
    segment->source_port = diplay_ipv4_read_be16(view->payload);
    segment->destination_port = diplay_ipv4_read_be16(view->payload + 2);
    segment->flags = view->payload[13];
    return 1;
}

static inline int diplay_ipv4_is_initial_syn(const diplay_ipv4_tcp_segment *segment) {
    return segment != NULL && (segment->flags & 0x02U) != 0 && (segment->flags & 0x10U) == 0;
}

static inline int diplay_ipv4_is_initial_syn_to(
        const diplay_ipv4_tcp_segment *segment, uint16_t destination_port) {
    return diplay_ipv4_is_initial_syn(segment) && segment->destination_port == destination_port;
}

#endif
