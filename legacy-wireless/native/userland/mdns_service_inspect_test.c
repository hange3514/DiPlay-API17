#include <assert.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#include "mdns_service_inspect.h"

static void write_be16(uint8_t *p, uint16_t value) {
    p[0] = (uint8_t)(value >> 8);
    p[1] = (uint8_t)value;
}

static size_t append_label(uint8_t *message, size_t offset, const char *label) {
    size_t length = strlen(label);
    assert(length > 0 && length <= 63);
    message[offset++] = (uint8_t)length;
    memcpy(message + offset, label, length);
    return offset + length;
}

static size_t append_domain(uint8_t *message, size_t offset, const char *a,
                            const char *b, const char *c) {
    offset = append_label(message, offset, a);
    offset = append_label(message, offset, b);
    offset = append_label(message, offset, c);
    message[offset++] = 0;
    return offset;
}

static size_t wrap_udp(uint8_t *udp, size_t dns_length) {
    write_be16(udp, 5353);
    write_be16(udp + 2, 5353);
    write_be16(udp + 4, (uint16_t)(dns_length + 8));
    write_be16(udp + 6, 0);
    return dns_length + 8;
}

static void test_uncompressed_query_and_case_folding(void) {
    uint8_t udp[512] = {0};
    uint8_t *dns = udp + 8;
    write_be16(dns + 4, 1);
    size_t offset = append_domain(dns, 12, "_AIRPLAY", "_TCP", "LOCAL");
    write_be16(dns + offset, 12); /* PTR */
    write_be16(dns + offset + 2, 1);
    offset += 4;
    uint8_t query = 0, response = 0;
    size_t udp_length = wrap_udp(udp, offset);
    assert(diplay_mdns_service_masks(udp, udp_length, &query, &response));
    assert(query == DIPLAY_MDNS_SERVICE_AIRPLAY);
    assert(response == 0);
}

static void test_compressed_response_owner(void) {
    uint8_t udp[512] = {0};
    uint8_t *dns = udp + 8;
    write_be16(dns + 2, 0x8400); /* response */
    write_be16(dns + 4, 1);
    write_be16(dns + 6, 1);
    size_t offset = append_domain(dns, 12, "_carplay-ctrl", "_tcp", "local");
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    offset += 4;
    dns[offset++] = 0xc0;
    dns[offset++] = 0x0c; /* owner reuses the question name */
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    memset(dns + offset + 4, 0, 4); /* TTL */
    write_be16(dns + offset + 8, 0); /* empty RDATA is structurally valid here */
    offset += 10;
    uint8_t query = 0, response = 0;
    size_t udp_length = wrap_udp(udp, offset);
    assert(diplay_mdns_service_masks(udp, udp_length, &query, &response));
    assert(query == 0);
    assert(response == DIPLAY_MDNS_SERVICE_CARPLAY_CTRL);
}

static void test_service_masks_and_non_service_names(void) {
    uint8_t udp[512] = {0};
    uint8_t *dns = udp + 8;
    write_be16(dns + 4, 2);
    size_t offset = append_label(dns, 12, "speaker");
    offset = append_label(dns, offset, "_raop");
    offset = append_label(dns, offset, "_tcp");
    offset = append_label(dns, offset, "local");
    dns[offset++] = 0;
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    offset += 4;
    offset = append_label(dns, offset, "CarPlay");
    offset = append_label(dns, offset, "_airplay");
    offset = append_label(dns, offset, "_tcp");
    offset = append_label(dns, offset, "local");
    dns[offset++] = 0;
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    offset += 4;
    uint8_t query = 0, response = 0;
    size_t udp_length = wrap_udp(udp, offset);
    assert(diplay_mdns_service_masks(udp, udp_length, &query, &response));
    assert(query == (DIPLAY_MDNS_SERVICE_RAOP | DIPLAY_MDNS_SERVICE_AIRPLAY));
    assert(response == 0);

    memset(udp, 0, sizeof(udp));
    dns = udp + 8;
    write_be16(dns + 4, 1);
    offset = append_domain(dns, 12, "example", "_tcp", "local");
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    offset += 4;
    udp_length = wrap_udp(udp, offset);
    assert(!diplay_mdns_service_masks(udp, udp_length, &query, &response));
}

static void test_bad_pointer_truncation_and_port(void) {
    uint8_t udp[128] = {0};
    uint8_t *dns = udp + 8;
    write_be16(dns + 4, 1);
    dns[12] = 0xc0;
    dns[13] = 0x0c; /* self-referential compression pointer */
    write_be16(dns + 14, 12);
    write_be16(dns + 16, 1);
    uint8_t query = 0, response = 0;
    size_t udp_length = wrap_udp(udp, 18);
    assert(!diplay_mdns_service_masks(udp, udp_length, &query, &response));

    memset(udp, 0, sizeof(udp));
    dns = udp + 8;
    write_be16(dns + 4, 1);
    size_t offset = append_domain(dns, 12, "_airplay", "_tcp", "local");
    write_be16(dns + offset, 12);
    write_be16(dns + offset + 2, 1);
    offset += 4;
    udp_length = wrap_udp(udp, offset);
    write_be16(udp + 4, (uint16_t)(udp_length + 1)); /* declared datagram extends past frame */
    assert(!diplay_mdns_service_masks(udp, udp_length, &query, &response));
    write_be16(udp + 4, (uint16_t)udp_length);
    write_be16(udp, 5354);
    write_be16(udp + 2, 5354);
    assert(!diplay_mdns_service_masks(udp, udp_length, &query, &response));
}

int main(void) {
    test_uncompressed_query_and_case_folding();
    test_compressed_response_owner();
    test_service_masks_and_non_service_names();
    test_bad_pointer_truncation_and_port();
    return 0;
}
