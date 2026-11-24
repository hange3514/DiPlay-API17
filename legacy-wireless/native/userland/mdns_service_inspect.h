#ifndef DIPLAY_MDNS_SERVICE_INSPECT_H
#define DIPLAY_MDNS_SERVICE_INSPECT_H

#include <stddef.h>
#include <stdint.h>

#include "ipv6_frame_inspect.h"

/* These are service-type suffixes only. The inspector never retains or emits
 * any instance name, host name, TXT record, address, or packet payload. */
#define DIPLAY_MDNS_SERVICE_CARPLAY_CTRL 0x01U
#define DIPLAY_MDNS_SERVICE_AIRPLAY      0x02U
#define DIPLAY_MDNS_SERVICE_RAOP         0x04U
#define DIPLAY_MDNS_SERVICE_CARPLAY      0x08U

static inline int diplay_dns_name_has_suffix(const char *name, size_t name_length,
                                              const char *suffix, size_t suffix_length) {
    if (name_length < suffix_length) return 0;
    size_t start = name_length - suffix_length;
    if (start != 0 && name[start - 1] != '.') return 0;
    for (size_t i = 0; i < suffix_length; i++) {
        if (name[start + i] != suffix[i]) return 0;
    }
    return 1;
}

/* Decode a DNS wire-format name with compression. Bounds are deliberately
 * tight because this runs on untrusted USB input on an API 17 ARM device. */
static inline int diplay_dns_read_name(const uint8_t *message, size_t message_length,
                                       size_t *cursor, char output[256], size_t *output_length) {
    if (message == NULL || cursor == NULL || output == NULL || output_length == NULL ||
            *cursor >= message_length) return 0;
    size_t position = *cursor;
    size_t next_cursor = position;
    size_t used = 0;
    int jumped = 0;
    int pointer_hops = 0;
    for (;;) {
        if (position >= message_length) return 0;
        uint8_t label_length = message[position];
        if ((label_length & 0xc0U) == 0xc0U) {
            if (position + 1 >= message_length || ++pointer_hops > 16) return 0;
            size_t target = ((size_t)(label_length & 0x3fU) << 8) | message[position + 1];
            if (target >= position || target >= message_length) return 0;
            if (!jumped) next_cursor = position + 2;
            jumped = 1;
            position = target;
            continue;
        }
        if ((label_length & 0xc0U) != 0) return 0;
        position++;
        if (label_length == 0) {
            if (!jumped) next_cursor = position;
            output[used] = '\0';
            *cursor = next_cursor;
            *output_length = used;
            return 1;
        }
        if (label_length > 63 || (size_t)label_length > message_length - position) return 0;
        size_t separator = used == 0 ? 0 : 1;
        if (used + separator + label_length >= 255) return 0;
        if (separator != 0) output[used++] = '.';
        for (size_t i = 0; i < label_length; i++) {
            uint8_t value = message[position + i];
            /* A dot inside a label is not a DNS label boundary. Replace it
             * with a non-matching byte rather than creating a false suffix. */
            if (value == '.' || value == 0 || value < 0x20 || value > 0x7e) {
                output[used++] = (char)0x7f;
            } else if (value >= 'A' && value <= 'Z') {
                output[used++] = (char)(value + ('a' - 'A'));
            } else {
                output[used++] = (char)value;
            }
        }
        position += label_length;
        if (!jumped) next_cursor = position;
    }
}

static inline uint8_t diplay_dns_service_bit(const char *name, size_t length) {
    if (diplay_dns_name_has_suffix(name, length, "_carplay-ctrl._tcp.local", 24))
        return DIPLAY_MDNS_SERVICE_CARPLAY_CTRL;
    if (diplay_dns_name_has_suffix(name, length, "_airplay._tcp.local", 19))
        return DIPLAY_MDNS_SERVICE_AIRPLAY;
    if (diplay_dns_name_has_suffix(name, length, "_raop._tcp.local", 16))
        return DIPLAY_MDNS_SERVICE_RAOP;
    if (diplay_dns_name_has_suffix(name, length, "_carplay._tcp.local", 19))
        return DIPLAY_MDNS_SERVICE_CARPLAY;
    return 0;
}

/* Inspect only DNS question/owner names in UDP/5353 messages. Returns 1 for a
 * structurally valid standard DNS-SD message and sets one service bitmask for
 * all recognized names, separated by the DNS query/response bit. */
static inline int diplay_mdns_service_masks(const uint8_t *udp, size_t udp_length,
                                             uint8_t *query_mask, uint8_t *response_mask) {
    if (udp == NULL || query_mask == NULL || response_mask == NULL || udp_length < 20 ||
            !diplay_udp_is_mdns(udp, udp_length)) return 0;
    *query_mask = 0;
    *response_mask = 0;
    uint16_t declared_udp_length = diplay_ipv6_read_be16(udp + 4);
    if (declared_udp_length < 20 || declared_udp_length > udp_length) return 0;
    const uint8_t *message = udp + 8;
    size_t message_length = declared_udp_length - 8;
    if (message_length < 12) return 0;
    uint16_t flags = diplay_ipv6_read_be16(message + 2);
    if ((flags & 0x7800U) != 0) return 0; /* DNS-SD uses opcode QUERY. */
    uint16_t question_count = diplay_ipv6_read_be16(message + 4);
    uint16_t answer_count = diplay_ipv6_read_be16(message + 6);
    uint16_t authority_count = diplay_ipv6_read_be16(message + 8);
    uint16_t additional_count = diplay_ipv6_read_be16(message + 10);
    uint32_t record_count = (uint32_t)question_count + answer_count + authority_count + additional_count;
    if (record_count == 0 || record_count > 128) return 0;

    size_t cursor = 12;
    uint8_t services = 0;
    char name[256];
    size_t name_length;
    for (uint16_t i = 0; i < question_count; i++) {
        if (!diplay_dns_read_name(message, message_length, &cursor, name, &name_length) ||
                cursor > message_length || message_length - cursor < 4) return 0;
        services |= diplay_dns_service_bit(name, name_length);
        cursor += 4; /* QTYPE and QCLASS */
    }
    uint32_t rr_count = (uint32_t)answer_count + authority_count + additional_count;
    for (uint32_t i = 0; i < rr_count; i++) {
        if (!diplay_dns_read_name(message, message_length, &cursor, name, &name_length) ||
                cursor > message_length || message_length - cursor < 10) return 0;
        services |= diplay_dns_service_bit(name, name_length);
        uint16_t rdata_length = diplay_ipv6_read_be16(message + cursor + 8);
        cursor += 10;
        if ((size_t)rdata_length > message_length - cursor) return 0;
        cursor += rdata_length;
    }
    if (services == 0) return 0;
    if ((flags & 0x8000U) != 0) *response_mask = services;
    else *query_mask = services;
    return 1;
}

#endif
