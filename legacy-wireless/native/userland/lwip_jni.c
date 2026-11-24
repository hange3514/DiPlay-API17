#include <jni.h>
#include <pthread.h>
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <unistd.h>
#include <sys/time.h>

#include "lwip/err.h"
#include "lwip/ethip6.h"
#include "lwip/errno.h"
#include "lwip/inet.h"
#include "lwip/ip6_addr.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/sockets.h"
#include "lwip/sys.h"
#include "lwip/tcpip.h"
#include "netif/ethernet.h"
#include "ipv6_frame_inspect.h"
#include "ipv4_frame_inspect.h"
#include "mdns_service_inspect.h"

/* Android API 17 ARM32 uses Bionic's historical 32-bit sigset_t and sigaction
 * layout. Zig's auxiliary musl headers use a different layout, so declare the
 * small Bionic ABI explicitly instead of passing a musl struct to Bionic. */
typedef int sig_atomic_t;
typedef struct bionic_siginfo_api17 {
    int si_signo;
    int si_errno;
    int si_code;
    union {
        char padding[128 - 3 * sizeof(int)];
        struct { void *si_addr; } fault;
    } fields;
} bionic_siginfo_api17;
typedef struct bionic_sigaction_api17 {
    union {
        void (*sa_handler)(int);
        void (*sa_sigaction)(int, bionic_siginfo_api17 *, void *);
    } handler;
    unsigned long sa_mask;
    unsigned long sa_flags;
    void (*sa_restorer)(void);
} bionic_sigaction_api17;
extern int bionic_sigaction_api17_call(int, const bionic_sigaction_api17 *, bionic_sigaction_api17 *) __asm__("sigaction");
extern int kill(int, int);
#define SIGILL 4
#define SIGABRT 6
#define SIGBUS 7
#define SIGFPE 8
#define SIGSEGV 11
#define SIG_DFL ((void (*)(int))0)
#define SIG_IGN ((void (*)(int))1)
#define SI_KERNEL 0x80
#define SA_SIGINFO 0x00000004UL
#define SA_ONSTACK 0x08000000UL
#define SA_RESETHAND 0x80000000UL

/* Zig's ARM Android driver may leave the musl errno accessor in the dynamic
 * symbol table even though this API 17 build uses Bionic's __errno(). Keep
 * that compatibility entry local to the library so the loader never needs a
 * newer libc symbol. */
__attribute__((visibility("hidden"))) int *__errno_location(void) {
    return __errno();
}

#define MAX_ETH_FRAME 16384
#define TX_QUEUE_MAX_FRAMES 96
#define TX_QUEUE_MAX_BYTES (1024U * 1024U)
#define STAT_COUNT 36
#define EXTRA_STAT_COUNT 19
#define CRASH_STAGE_CAPACITY 64

typedef struct packet_node {
    struct packet_node *next;
    u16_t length;
    u8_t bytes[];
} packet_node;

typedef struct user_stack {
    struct netif netif;
    u8_t mac[6];
    int running;
    pthread_mutex_t queue_lock;
    pthread_cond_t queue_ready;
    packet_node *head;
    packet_node *tail;
    u32_t queued_frames;
    u32_t queued_bytes;
    u64_t stats[STAT_COUNT];
    u16_t tracked_tcp_syn_port;
    u16_t last_rx_src_port;
    u16_t last_rx_dst_port;
    u16_t last_tx_src_port;
    u16_t last_tx_dst_port;
    u32_t last_rx_port_pair;
    u32_t last_tx_port_pair;
    u32_t last_rx_ipv6_syn_port_pair;
    u32_t last_rx_ipv4_syn_port_pair;
    u32_t last_rx_ipv6_tracked_syn_at_ms;
    int has_rx_ipv6_tracked_syn;
    u32_t last_rx_ipv4_tracked_syn_at_ms;
    int has_rx_ipv4_tracked_syn;
    u64_t tx_mdns_packets;
    u32_t mdns_service_masks[6];
    u32_t last_rx_mdns_service_at_ms;
    u32_t last_rx_mdns_service_mask;
    u32_t last_rx_mdns_service_query_mask;
    u32_t last_rx_mdns_service_response_mask;
    int has_rx_mdns_service;
    u8_t last_rx_tcp_flags;
    u8_t last_tx_tcp_flags;
    u32_t active_calls;
} user_stack;

/* mDNS service masks are cumulative and contain only four fixed service-type
 * bits. Layout: IPv6 inbound query/response, IPv4 inbound query/response,
 * then IPv6 outbound query/response. */
static void observe_mdns_services(user_stack *stack, const u8_t *udp, size_t udp_length,
                                  int outbound, int ipv4) {
    if (stack == NULL || !diplay_udp_is_mdns(udp, udp_length)) return;
    if (outbound) __sync_fetch_and_add(&stack->tx_mdns_packets, 1);
    u8_t query_mask = 0;
    u8_t response_mask = 0;
    if (!diplay_mdns_service_masks(udp, udp_length, &query_mask, &response_mask)) return;
    if (!outbound && (query_mask != 0 || response_mask != 0)) {
        __sync_lock_test_and_set(&stack->last_rx_mdns_service_at_ms, sys_now());
        __sync_lock_test_and_set(&stack->last_rx_mdns_service_mask,
            (u32_t)(query_mask | response_mask));
        __sync_lock_test_and_set(&stack->last_rx_mdns_service_query_mask, (u32_t)query_mask);
        __sync_lock_test_and_set(&stack->last_rx_mdns_service_response_mask, (u32_t)response_mask);
        __sync_lock_test_and_set(&stack->has_rx_mdns_service, 1);
    }
    unsigned int base = outbound ? 4U : (ipv4 ? 2U : 0U);
    unsigned int query_index = base;
    unsigned int response_index = base + 1U;
    if (query_mask != 0) __sync_fetch_and_or(&stack->mdns_service_masks[query_index], query_mask);
    if (response_mask != 0) __sync_fetch_and_or(&stack->mdns_service_masks[response_index], response_mask);
}

static pthread_mutex_t g_lock;
static pthread_cond_t g_stack_idle;
static pthread_mutex_t g_init_lock;
static pthread_cond_t g_init_cond;
static int g_tcpip_started = 0;
static int g_tcpip_ready = 0;
static int g_tcpip_start_error = 0;
static volatile int g_create_error = 0;
static user_stack *g_active = NULL;
static struct netif g_netif;
static volatile sig_atomic_t g_crash_capture_fd = -1;
static volatile sig_atomic_t g_crash_handler_active = 0;
static int g_crash_handlers_installed = 0;
/* Publish complete, signal-safe stage snapshots; a fatal signal can interrupt a stage update. */
static volatile sig_atomic_t g_crash_stage_active = 0;
static volatile sig_atomic_t g_crash_stage_slots[2][CRASH_STAGE_CAPACITY] = {
    { 'n', 'a', 't', 'i', 'v', 'e', '_', 'l', 'o', 'a', 'd', 'e', 'd', '\0' },
    { '\0' }
};
static bionic_sigaction_api17 g_previous_crash_actions[5];
static bionic_sigaction_api17 g_default_crash_action;
static const int g_crash_signals[5] = { SIGABRT, SIGSEGV, SIGBUS, SIGILL, SIGFPE };

static int append_char(char *out, int used, int capacity, char value) {
    if (used < capacity) out[used++] = value;
    return used;
}

static int append_text(char *out, int used, int capacity, const char *value, int limit) {
    if (value == NULL) return used;
    for (int i = 0; value[i] != '\0' && i < limit && used < capacity; i++) {
        char c = value[i];
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-' ||
              c == ':' || c == '/' || c == ' ' || c == '=' || c == '(' || c == ')')) c = '_';
        out[used++] = c;
    }
    return used;
}

static int append_unsigned(char *out, int used, int capacity, unsigned long long value, unsigned int base) {
    char digits[24];
    int count = 0;
    do {
        unsigned int digit = (unsigned int)(value % base);
        digits[count++] = (char)(digit < 10 ? '0' + digit : 'a' + (digit - 10));
        value /= base;
    } while (value != 0 && count < (int)sizeof(digits));
    while (count > 0 && used < capacity) out[used++] = digits[--count];
    return used;
}

static int append_signed(char *out, int used, int capacity, int value) {
    if (value < 0) {
        used = append_char(out, used, capacity, '-');
        return append_unsigned(out, used, capacity, (unsigned long long)(-(long long)value), 10);
    }
    return append_unsigned(out, used, capacity, (unsigned long long)value, 10);
}

static void write_crash_record(const char *data, size_t length) {
    int fd = (int)g_crash_capture_fd;
    if (fd < 0 || data == NULL) return;
    size_t written = 0;
    while (written < length) {
        ssize_t result = write(fd, data + written, length - written);
        if (result <= 0) break;
        written += (size_t)result;
    }
}

static int crash_signal_index(int signal_number) {
    for (int i = 0; i < 5; i++) if (g_crash_signals[i] == signal_number) return i;
    return -1;
}

static void fatal_signal_handler(int signal_number, bionic_siginfo_api17 *info, void *context) {
    if (g_crash_handler_active) _exit(128 + signal_number);
    g_crash_handler_active = 1;
    char line[256];
    int used = 0;
    int signal_code = info == NULL ? 0 : info->si_code;
    used = append_text(line, used, sizeof(line), "signal=", 7);
    used = append_signed(line, used, sizeof(line), signal_number);
    used = append_text(line, used, sizeof(line), " code=", 6);
    used = append_signed(line, used, sizeof(line), signal_code);
    if (signal_code == SI_KERNEL) {
        used = append_text(line, used, sizeof(line), " address=unknown", 16);
    } else {
        used = append_text(line, used, sizeof(line), " address=0x", 11);
        used = append_unsigned(line, used, sizeof(line), (unsigned long long)(uintptr_t)(info == NULL ? NULL : info->fields.fault.si_addr), 16);
    }
    used = append_text(line, used, sizeof(line), " pid=", 5);
    used = append_unsigned(line, used, sizeof(line), (unsigned long long)getpid(), 10);
    used = append_text(line, used, sizeof(line), " stage=", 7);
    int active_stage = (int)g_crash_stage_active;
    for (int i = 0; i < CRASH_STAGE_CAPACITY && used < (int)sizeof(line); i++) {
        char stage_char = (char)g_crash_stage_slots[active_stage][i];
        if (stage_char == '\0') break;
        used = append_char(line, used, sizeof(line), stage_char);
    }
    used = append_char(line, used, sizeof(line), '\n');
    write_crash_record(line, (size_t)used);

    int index = crash_signal_index(signal_number);
    if (index >= 0) {
        bionic_sigaction_api17 previous = g_previous_crash_actions[index];
        if ((previous.sa_flags & SA_SIGINFO) != 0 && previous.handler.sa_sigaction != NULL &&
            previous.handler.sa_sigaction != fatal_signal_handler) {
            previous.handler.sa_sigaction(signal_number, info, context);
        } else if (previous.handler.sa_handler != SIG_DFL && previous.handler.sa_handler != SIG_IGN &&
                   previous.handler.sa_handler != NULL) {
            previous.handler.sa_handler(signal_number);
        }
    }
    bionic_sigaction_api17_call(signal_number, &g_default_crash_action, NULL);
    kill(getpid(), signal_number);
    _exit(128 + signal_number);
}

static void set_crash_stage(const char *stage) {
    char safe_stage[CRASH_STAGE_CAPACITY];
    int i = 0;
    if (stage == NULL) stage = "unknown";
    while (stage[i] != '\0' && i < (int)sizeof(safe_stage) - 1) {
        char c = stage[i];
        safe_stage[i] = ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
            (c >= '0' && c <= '9') || c == '_' || c == '-') ? c : '_';
        i++;
    }
    safe_stage[i] = '\0';
    int next_stage = 1 - (int)g_crash_stage_active;
    for (int j = 0; j <= i; j++) g_crash_stage_slots[next_stage][j] = (sig_atomic_t)safe_stage[j];
    g_crash_stage_active = (sig_atomic_t)next_stage;
    char line[96];
    int used = append_text(line, 0, sizeof(line), "stage=", 6);
    used = append_text(line, used, sizeof(line), safe_stage, sizeof(safe_stage));
    used = append_char(line, used, sizeof(line), '\n');
    write_crash_record(line, (size_t)used);
}

void diplay_lwip_stage(const char *stage) {
    set_crash_stage(stage);
}

void diplay_lwip_assert(const char *message, const char *file, int line_number) {
    char line[320];
    int used = append_text(line, 0, sizeof(line), "assert file=", 12);
    const char *base = file == NULL ? "unknown" : file;
    for (const char *p = base; *p != '\0'; p++) if (*p == '/' || *p == '\\') base = p + 1;
    used = append_text(line, used, sizeof(line), base, 80);
    used = append_text(line, used, sizeof(line), " line=", 6);
    used = append_signed(line, used, sizeof(line), line_number);
    used = append_text(line, used, sizeof(line), " message=", 9);
    used = append_text(line, used, sizeof(line), message, 120);
    used = append_char(line, used, sizeof(line), '\n');
    write_crash_record(line, (size_t)used);
    abort();
}

JNIEXPORT jboolean JNICALL Java_local_headunit_diplay_NativeIpStack_installCrashCapture(
        JNIEnv *env, jclass cls, jstring private_file_path) {
    (void)cls;
    if (private_file_path == NULL) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, private_file_path, NULL);
    if (path == NULL) return JNI_FALSE;
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_SYNC, 0600);
    (*env)->ReleaseStringUTFChars(env, private_file_path, path);
    if (fd < 0) return JNI_FALSE;
    if (g_crash_capture_fd >= 0) close((int)g_crash_capture_fd);
    g_crash_capture_fd = fd;
    g_crash_handler_active = 0;
    memset(&g_default_crash_action, 0, sizeof(g_default_crash_action));
    g_default_crash_action.handler.sa_handler = SIG_DFL;
    if (!g_crash_handlers_installed) {
        for (int i = 0; i < 5; i++) {
            bionic_sigaction_api17 previous;
            bionic_sigaction_api17 action;
            memset(&previous, 0, sizeof(previous));
            memset(&action, 0, sizeof(action));
            if (bionic_sigaction_api17_call(g_crash_signals[i], NULL, &previous) != 0) {
                for (int j = i - 1; j >= 0; j--) bionic_sigaction_api17_call(g_crash_signals[j], &g_previous_crash_actions[j], NULL);
                g_crash_capture_fd = -1;
                close(fd);
                return JNI_FALSE;
            }
            action.handler.sa_sigaction = fatal_signal_handler;
            action.sa_flags = SA_SIGINFO | SA_RESETHAND | (previous.sa_flags & SA_ONSTACK);
            if (bionic_sigaction_api17_call(g_crash_signals[i], &action, &g_previous_crash_actions[i]) != 0) {
                for (int j = i - 1; j >= 0; j--) bionic_sigaction_api17_call(g_crash_signals[j], &g_previous_crash_actions[j], NULL);
                g_crash_capture_fd = -1;
                close(fd);
                return JNI_FALSE;
            }
        }
        g_crash_handlers_installed = 1;
    }
    set_crash_stage("capture_armed");
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_local_headunit_diplay_NativeIpStack_setCrashStage(
        JNIEnv *env, jclass cls, jstring stage) {
    (void)cls;
    if (stage == NULL) return;
    const char *value = (*env)->GetStringUTFChars(env, stage, NULL);
    if (value == NULL) return;
    set_crash_stage(value);
    (*env)->ReleaseStringUTFChars(env, stage, value);
}

/* Implemented by the API 17 sys_arch port; used to report pthread/alloc
 * failures without allowing lwIP startup to terminate the Android process. */
extern void sys_arch_reset_error(void);
extern int sys_arch_last_error(void);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    if (pthread_mutex_init(&g_lock, NULL) != 0) return JNI_ERR;
    if (pthread_mutex_init(&g_init_lock, NULL) != 0) {
        pthread_mutex_destroy(&g_lock);
        return JNI_ERR;
    }
    if (pthread_cond_init(&g_init_cond, NULL) != 0) {
        pthread_mutex_destroy(&g_init_lock);
        pthread_mutex_destroy(&g_lock);
        return JNI_ERR;
    }
    if (pthread_cond_init(&g_stack_idle, NULL) != 0) {
        pthread_cond_destroy(&g_init_cond);
        pthread_mutex_destroy(&g_init_lock);
        pthread_mutex_destroy(&g_lock);
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}

enum {
    ST_RX_FRAMES = 0, ST_RX_BYTES, ST_RX_IPV6, ST_RX_OTHER, ST_RX_NDP,
    ST_RX_NS, ST_RX_NA, ST_RX_TCP, ST_RX_UDP, ST_RX_DROP,
    ST_TX_FRAMES, ST_TX_BYTES, ST_TX_QUEUE_DROP, ST_TX_OUTPUT_COPY_ERROR,
    ST_TCP_SYN_IN, ST_TCP_SYNACK_OUT, ST_TCP_RST, ST_TCP_FIN,
    ST_UDP_IN, ST_UDP_OUT, ST_TX_NDP, ST_TX_NS, ST_TX_NA,
    ST_TCP_SYN_TO_TRACKED_PORT_IN,
    ST_RX_IPV4_ETH, ST_RX_IPV4_TCP, ST_RX_IPV4_SYN,
    ST_RX_IPV4_SYN_TO_TRACKED_PORT, ST_RX_IPV4_UDP, ST_RX_IPV4_FRAGMENTED,
    ST_RX_IPV4_MALFORMED, ST_RX_ARP, ST_RX_OTHER_ETH, ST_RX_MDNS,
    ST_RX_IPV6_MDNS, ST_RX_IPV4_MDNS
};

static void classify_ipv6(user_stack *stack, const u8_t *frame, u16_t length, int outbound) {
    if (length < 14 || frame[12] != 0x86 || frame[13] != 0xdd) {
        return;
    }
    __sync_fetch_and_add(&stack->stats[outbound ? ST_TX_FRAMES : ST_RX_FRAMES], 1);
    __sync_fetch_and_add(&stack->stats[outbound ? ST_TX_BYTES : ST_RX_BYTES], length);
    if (length < 14 + 40 || (frame[14] >> 4) != 6) {
        if (!outbound) __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
        return;
    }
    if (!outbound) __sync_fetch_and_add(&stack->stats[ST_RX_IPV6], 1);
    u8_t next;
    const u8_t *payload;
    size_t payload_length;
    if (!diplay_ipv6_upper_layer(frame + 14, length - 14, &next, &payload, &payload_length)) {
        if (!outbound) __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
        return;
    }
    if (next == 58 && payload_length >= 1) {
        u8_t type = payload[0];
        if (outbound && (type == 133 || type == 135 || type == 136)) {
            __sync_fetch_and_add(&stack->stats[ST_TX_NDP], 1);
            if (type == 135) __sync_fetch_and_add(&stack->stats[ST_TX_NS], 1);
            if (type == 136) __sync_fetch_and_add(&stack->stats[ST_TX_NA], 1);
        } else if (!outbound && (type == 135 || type == 136)) {
            __sync_fetch_and_add(&stack->stats[ST_RX_NDP], 1);
            __sync_fetch_and_add(&stack->stats[type == 135 ? ST_RX_NS : ST_RX_NA], 1);
        }
    } else if (next == 6) {
        diplay_ipv6_tcp_segment tcp;
        if (!diplay_ipv6_tcp_segment_info(next, payload, payload_length, &tcp)) {
            if (!outbound) __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
            return;
        }
        if (outbound) {
            __sync_lock_test_and_set(&stack->last_tx_port_pair, ((u32_t)tcp.source_port << 16) | tcp.destination_port);
            stack->last_tx_tcp_flags = tcp.flags;
            if ((tcp.flags & 0x12) == 0x12) __sync_fetch_and_add(&stack->stats[ST_TCP_SYNACK_OUT], 1);
        } else {
            __sync_lock_test_and_set(&stack->last_rx_port_pair, ((u32_t)tcp.source_port << 16) | tcp.destination_port);
            stack->last_rx_tcp_flags = tcp.flags;
            __sync_fetch_and_add(&stack->stats[ST_RX_TCP], 1);
            if (diplay_ipv6_is_initial_syn(&tcp)) {
                __sync_fetch_and_add(&stack->stats[ST_TCP_SYN_IN], 1);
                __sync_lock_test_and_set(&stack->last_rx_ipv6_syn_port_pair,
                    ((u32_t)tcp.source_port << 16) | tcp.destination_port);
            }
            if (diplay_ipv6_is_initial_syn_to(&tcp, stack->tracked_tcp_syn_port)) {
                __sync_fetch_and_add(&stack->stats[ST_TCP_SYN_TO_TRACKED_PORT_IN], 1);
                __sync_lock_test_and_set(&stack->last_rx_ipv6_tracked_syn_at_ms, sys_now());
                __sync_lock_test_and_set(&stack->has_rx_ipv6_tracked_syn, 1);
            }
            if ((tcp.flags & 0x04) != 0) __sync_fetch_and_add(&stack->stats[ST_TCP_RST], 1);
            if ((tcp.flags & 0x01) != 0) __sync_fetch_and_add(&stack->stats[ST_TCP_FIN], 1);
        }
    } else if (next == 17 && payload_length >= 8) {
        observe_mdns_services(stack, payload, payload_length, outbound, 0);
        if (outbound) {
            __sync_fetch_and_add(&stack->stats[ST_UDP_OUT], 1);
            __sync_lock_test_and_set(&stack->last_tx_port_pair, ((u32_t)diplay_ipv6_read_be16(payload) << 16) | diplay_ipv6_read_be16(payload + 2));
        } else {
            __sync_fetch_and_add(&stack->stats[ST_RX_UDP], 1);
            __sync_fetch_and_add(&stack->stats[ST_UDP_IN], 1);
            __sync_lock_test_and_set(&stack->last_rx_port_pair, ((u32_t)diplay_ipv6_read_be16(payload) << 16) | diplay_ipv6_read_be16(payload + 2));
            if (diplay_udp_is_mdns(payload, payload_length)) {
                __sync_fetch_and_add(&stack->stats[ST_RX_MDNS], 1);
                __sync_fetch_and_add(&stack->stats[ST_RX_IPV6_MDNS], 1);
            }
        }
    } else if (!outbound) {
        __sync_fetch_and_add(&stack->stats[ST_RX_OTHER], 1);
    }
}

/* Passive Ethernet/IP-family observation runs before lwIP input. The stack is
 * intentionally IPv6-only today; count IPv4 and other EtherTypes so a later
 * field report can prove whether the phone tried a different network path.
 */
static void classify_inbound_ethernet(user_stack *stack, const u8_t *frame, u16_t length) {
    if (length < 14) return;
    u16_t ether_type = (u16_t)(((u16_t)frame[12] << 8) | frame[13]);
    if (ether_type == 0x0800) {
        __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_ETH], 1);
        diplay_ipv4_payload_view ip;
        if (!diplay_ipv4_payload(frame + 14, length - 14, &ip)) {
            __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_MALFORMED], 1);
            return;
        }
        if (ip.fragmented) __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_FRAGMENTED], 1);
        if (ip.protocol == 6) {
            if (!ip.initial_fragment) return;
            diplay_ipv4_tcp_segment tcp;
            if (!diplay_ipv4_tcp_segment_info(&ip, &tcp)) {
                __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_MALFORMED], 1);
                return;
            }
            __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_TCP], 1);
            if (diplay_ipv4_is_initial_syn(&tcp)) {
                __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_SYN], 1);
                __sync_lock_test_and_set(&stack->last_rx_ipv4_syn_port_pair,
                    ((u32_t)tcp.source_port << 16) | tcp.destination_port);
                if (diplay_ipv4_is_initial_syn_to(&tcp, stack->tracked_tcp_syn_port)) {
                    __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_SYN_TO_TRACKED_PORT], 1);
                    __sync_lock_test_and_set(&stack->last_rx_ipv4_tracked_syn_at_ms, sys_now());
                    __sync_lock_test_and_set(&stack->has_rx_ipv4_tracked_syn, 1);
                }
            }
        } else if (ip.protocol == 17 && ip.initial_fragment && ip.payload_length >= 8) {
            observe_mdns_services(stack, ip.payload, ip.payload_length, 0, 1);
            __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_UDP], 1);
            if (diplay_udp_is_mdns(ip.payload, ip.payload_length)) {
                __sync_fetch_and_add(&stack->stats[ST_RX_MDNS], 1);
                __sync_fetch_and_add(&stack->stats[ST_RX_IPV4_MDNS], 1);
            }
        }
        return;
    }
    if (ether_type == 0x0806) {
        __sync_fetch_and_add(&stack->stats[ST_RX_ARP], 1);
        return;
    }
    if (ether_type != 0x86dd) {
        __sync_fetch_and_add(&stack->stats[ST_RX_OTHER_ETH], 1);
        return;
    }
    classify_ipv6(stack, frame, length, 0);
}

static int stack_is_running(user_stack *stack) {
    return stack != NULL && __sync_fetch_and_add(&stack->running, 0) != 0;
}

static void stack_set_running(user_stack *stack, int running) {
    __sync_lock_test_and_set(&stack->running, running);
}

static err_t user_linkoutput(struct netif *netif, struct pbuf *p) {
    user_stack *stack = (user_stack *)netif->state;
    if (!stack_is_running(stack) || p == NULL || p->tot_len < 14 || p->tot_len > MAX_ETH_FRAME) {
        if (stack != NULL) __sync_fetch_and_add(&stack->stats[ST_TX_QUEUE_DROP], 1);
        return ERR_IF;
    }
    packet_node *node = (packet_node *)malloc(sizeof(packet_node) + p->tot_len);
    if (node == NULL) {
        __sync_fetch_and_add(&stack->stats[ST_TX_QUEUE_DROP], 1);
        return ERR_MEM;
    }
    node->next = NULL;
    node->length = p->tot_len;
    if (pbuf_copy_partial(p, node->bytes, p->tot_len, 0) != p->tot_len) {
        free(node);
        __sync_fetch_and_add(&stack->stats[ST_TX_QUEUE_DROP], 1);
        return ERR_BUF;
    }
    pthread_mutex_lock(&stack->queue_lock);
    if (!stack_is_running(stack) || stack->queued_frames >= TX_QUEUE_MAX_FRAMES ||
        stack->queued_bytes + node->length > TX_QUEUE_MAX_BYTES) {
        pthread_mutex_unlock(&stack->queue_lock);
        free(node);
        __sync_fetch_and_add(&stack->stats[ST_TX_QUEUE_DROP], 1);
        return ERR_MEM;
    }
    if (stack->tail != NULL) stack->tail->next = node;
    else stack->head = node;
    stack->tail = node;
    stack->queued_frames++;
    stack->queued_bytes += node->length;
    /* Keep the node private until diagnostics have read it. The consumer can
     * dequeue and free it as soon as queue_lock is released. */
    classify_ipv6(stack, node->bytes, node->length, 1);
    pthread_cond_signal(&stack->queue_ready);
    pthread_mutex_unlock(&stack->queue_lock);
    return ERR_OK;
}

static err_t user_netif_init(struct netif *netif) {
    user_stack *stack = (user_stack *)netif->state;
    if (stack == NULL) return ERR_ARG;
    netif->name[0] = 'u';
    netif->name[1] = 's';
    netif->hwaddr_len = 6;
    memcpy(netif->hwaddr, stack->mac, 6);
    netif->mtu = 1500;
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHERNET;
#if LWIP_IPV6_MLD
    netif->flags |= NETIF_FLAG_MLD6;
#endif
    netif->output_ip6 = ethip6_output;
    netif->linkoutput = user_linkoutput;
    return ERR_OK;
}

typedef struct add_netif_args {
    user_stack *stack;
    ip6_addr_t address;
    err_t result;
} add_netif_args;

static void add_netif_on_core(void *argument) {
    add_netif_args *args = (add_netif_args *)argument;
    if (netif_add_noaddr(&g_netif, args->stack, user_netif_init, tcpip_input) == NULL) {
        args->result = ERR_IF;
        return;
    }
    netif_ip6_addr_set(&g_netif, 0, &args->address);
    netif_ip6_addr_set_state(&g_netif, 0, IP6_ADDR_PREFERRED);
    netif_set_up(&g_netif);
    netif_set_link_up(&g_netif);
    netif_set_default(&g_netif);
    args->result = ERR_OK;
}

static void remove_netif_on_core(void *argument) {
    (void)argument;
    if (netif_is_up(&g_netif)) netif_set_down(&g_netif);
    if (netif_is_link_up(&g_netif)) netif_set_link_down(&g_netif);
    if (netif_default == &g_netif) netif_set_default(NULL);
    netif_remove(&g_netif);
}

static void tcpip_ready_callback(void *argument) {
    (void)argument;
    pthread_mutex_lock(&g_init_lock);
    g_tcpip_ready = 1;
    pthread_cond_broadcast(&g_init_cond);
    pthread_mutex_unlock(&g_init_lock);
}

static int ensure_tcpip_started(void) {
    err_t init_result = ERR_OK;
    pthread_mutex_lock(&g_init_lock);
    if (!g_tcpip_started) {
        g_tcpip_started = 1;
      g_tcpip_start_error = 0;
      sys_arch_reset_error();
      set_crash_stage("tcpip_init_enter");
      init_result = tcpip_init(tcpip_ready_callback, NULL);
      set_crash_stage("tcpip_init_returned");
      if (init_result != ERR_OK) {
          g_tcpip_start_error = sys_arch_last_error();
          if (g_tcpip_start_error == 0) g_tcpip_start_error = (int)(-init_result);
          g_tcpip_started = -1;
          pthread_cond_broadcast(&g_init_cond);
      }
    }
    set_crash_stage("tcpip_wait_ready");
    while (g_tcpip_started == 1 && !g_tcpip_ready) pthread_cond_wait(&g_init_cond, &g_init_lock);
    int result = g_tcpip_ready ? 0 : (g_tcpip_start_error != 0 ? g_tcpip_start_error : EIO);
    set_crash_stage(result == 0 ? "tcpip_ready" : "tcpip_start_failed");
    pthread_mutex_unlock(&g_init_lock);
    return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_prewarm(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    set_crash_stage("native_prewarm_enter");
    int result = ensure_tcpip_started();
    set_crash_stage(result == 0 ? "native_prewarm_ready" : "native_prewarm_failed");
    return result;
}

static user_stack *from_handle(jlong handle) {
    user_stack *stack = (user_stack *)(intptr_t)handle;
    if (stack == NULL || stack != g_active || !stack_is_running(stack)) return NULL;
    return stack;
}

/* Hold the stack lifetime across each JNI entry into lwIP. A short validation
 * lock alone is insufficient: stop() could otherwise free user_stack while
 * input(), nextOutput(), stats(), or a socket operation is still using lwIP. */
static user_stack *begin_active_call(jlong handle, int check_handle) {
    pthread_mutex_lock(&g_lock);
    user_stack *stack = check_handle ? from_handle(handle) : g_active;
    if (stack_is_running(stack)) stack->active_calls++;
    else stack = NULL;
    pthread_mutex_unlock(&g_lock);
    return stack;
}

static void end_active_call(user_stack *stack) {
    if (stack == NULL) return;
    pthread_mutex_lock(&g_lock);
    if (stack->active_calls > 0) stack->active_calls--;
    if (stack->active_calls == 0) pthread_cond_broadcast(&g_stack_idle);
    pthread_mutex_unlock(&g_lock);
}

static int copy_sockaddr(JNIEnv *env, jbyteArray address, jint port, struct sockaddr_in6 *out) {
    if (address == NULL || out == NULL || port < 0 || port > 65535) return -EINVAL;
    jsize length = (*env)->GetArrayLength(env, address);
    if (length != 16 && length != 4) return -EAFNOSUPPORT;
    memset(out, 0, sizeof(*out));
    out->sin6_family = AF_INET6;
    out->sin6_port = htons((u16_t)port);
    if (length == 16) {
        (*env)->GetByteArrayRegion(env, address, 0, 16, (jbyte *)&out->sin6_addr);
    } else {
        jbyte raw[4];
        (*env)->GetByteArrayRegion(env, address, 0, 4, raw);
        if ((*env)->ExceptionCheck(env)) return -EINVAL;
        if (raw[0] || raw[1] || raw[2] || raw[3]) return -EAFNOSUPPORT;
        memset(&out->sin6_addr, 0, sizeof(out->sin6_addr));
    }
    return (*env)->ExceptionCheck(env) ? -EINVAL : 0;
}

static int lwip_error(void) {
    int value = errno;
    return -(value > 0 ? value : EIO);
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_socket(
        JNIEnv *env, jclass cls, jint kind) {
    (void)env; (void)cls;
    if (kind != 1 && kind != 2) return -EINVAL;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result = kind == 1 ? lwip_socket(AF_INET6, SOCK_STREAM, IPPROTO_TCP)
                           : lwip_socket(AF_INET6, SOCK_DGRAM, IPPROTO_UDP);
    end_active_call(guard);
    return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_bind(
        JNIEnv *env, jclass cls, jint fd, jbyteArray address, jint port) {
    (void)cls;
    struct sockaddr_in6 target;
    int valid = copy_sockaddr(env, address, port, &target);
    if (valid != 0) return valid;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result = lwip_bind(fd, (struct sockaddr *)&target, sizeof(target));
    int error = result == 0 ? 0 : lwip_error();
    end_active_call(guard);
    return error;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_listen(
        JNIEnv *env, jclass cls, jint fd, jint backlog) {
    (void)env; (void)cls;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result = lwip_listen(fd, backlog);
    int error = result == 0 ? 0 : lwip_error();
    end_active_call(guard);
    return error;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_accept(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int accepted = lwip_accept(fd, NULL, NULL);
    int result = accepted >= 0 ? accepted : lwip_error();
    end_active_call(guard);
    return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_closeSocket(
        JNIEnv *env, jclass cls, jint fd) {
    (void)env; (void)cls;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result = lwip_close(fd);
    int error = result == 0 ? 0 : lwip_error();
    end_active_call(guard);
    return error;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_shutdown(
        JNIEnv *env, jclass cls, jint fd, jint how) {
    (void)env; (void)cls;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result = lwip_shutdown(fd, how);
    int error = result == 0 ? 0 : lwip_error();
    end_active_call(guard);
    return error;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_send(
        JNIEnv *env, jclass cls, jint fd, jbyteArray data, jint offset, jint length) {
    (void)cls;
    if (data == NULL || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, data) - length) return -EINVAL;
    if (length == 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return -ENOMEM;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) { (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT); return -ENODEV; }
    int sent = lwip_send(fd, bytes + offset, (size_t)length, 0);
    int result = sent >= 0 ? sent : lwip_error();
    end_active_call(guard);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_receive(
        JNIEnv *env, jclass cls, jint fd, jbyteArray data, jint offset, jint length) {
    (void)cls;
    if (data == NULL || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, data) - length) return -EINVAL;
    if (length == 0) return 0;
    size_t amount = (size_t)length;
    if (amount > MAX_ETH_FRAME) amount = MAX_ETH_FRAME;
    u8_t buffer[MAX_ETH_FRAME];
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int received = lwip_recv(fd, buffer, amount, 0);
    int result = received < 0 ? lwip_error() : received;
    end_active_call(guard);
    if (received < 0) return result;
    if (received > 0) (*env)->SetByteArrayRegion(env, data, offset, received, (jbyte *)buffer);
    return (*env)->ExceptionCheck(env) ? -EINVAL : received;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_sendTo(
        JNIEnv *env, jclass cls, jint fd, jbyteArray data, jint offset, jint length,
        jbyteArray address, jint port) {
    (void)cls;
    if (data == NULL || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, data) - length) return -EINVAL;
    struct sockaddr_in6 target;
    int valid = copy_sockaddr(env, address, port, &target);
    if (valid != 0) return valid;
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
    if (bytes == NULL) return -ENOMEM;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) { (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT); return -ENODEV; }
    int sent = lwip_sendto(fd, bytes + offset, (size_t)length, 0, (struct sockaddr *)&target, sizeof(target));
    int result = sent >= 0 ? sent : lwip_error();
    end_active_call(guard);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    return result;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_receiveFrom(
        JNIEnv *env, jclass cls, jint fd, jbyteArray data, jint offset, jint length, jbyteArray peer) {
    (void)cls;
    if (data == NULL || peer == NULL || (*env)->GetArrayLength(env, peer) < 18 || offset < 0 || length < 0 || offset > (*env)->GetArrayLength(env, data) - length) return -EINVAL;
    if (length > MAX_ETH_FRAME) length = MAX_ETH_FRAME;
    u8_t buffer[MAX_ETH_FRAME];
    struct sockaddr_in6 source;
    socklen_t source_length = sizeof(source);
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int received = lwip_recvfrom(fd, buffer, (size_t)length, 0, (struct sockaddr *)&source, &source_length);
    int result = received < 0 ? lwip_error() : received;
    end_active_call(guard);
    if (received < 0) return result;
    if (received > 0) (*env)->SetByteArrayRegion(env, data, offset, received, (jbyte *)buffer);
    (*env)->SetByteArrayRegion(env, peer, 0, 16, (jbyte *)&source.sin6_addr);
    u16_t port = ntohs(source.sin6_port);
    jbyte port_bytes[2] = {(jbyte)(port >> 8), (jbyte)port};
    (*env)->SetByteArrayRegion(env, peer, 16, 2, port_bytes);
    return (*env)->ExceptionCheck(env) ? -EINVAL : received;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_setOption(
        JNIEnv *env, jclass cls, jint fd, jint option, jint value) {
    (void)env; (void)cls;
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return -ENODEV;
    int result;
    switch (option) {
        case 1: result = lwip_setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &value, sizeof(value)); break;
        case 2: result = lwip_setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &value, sizeof(value)); break;
        case 3: result = lwip_setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &value, sizeof(value)); break;
        case 4: result = lwip_setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &value, sizeof(value)); break;
        case 5: result = lwip_setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &value, sizeof(value)); break;
        case 6: {
            struct timeval timeout;
            timeout.tv_sec = value / 1000;
            timeout.tv_usec = (value % 1000) * 1000;
            result = lwip_setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
            break;
        }
        case 7: {
            struct timeval timeout;
            timeout.tv_sec = value / 1000;
            timeout.tv_usec = (value % 1000) * 1000;
            result = lwip_setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
            break;
        }
        case 8: {
            if (value < -1 || value > 65535) { end_active_call(guard); return -EINVAL; }
            struct linger linger_value;
            linger_value.l_onoff = value >= 0 ? 1 : 0;
            linger_value.l_linger = value >= 0 ? value : 0;
            result = lwip_setsockopt(fd, SOL_SOCKET, SO_LINGER, &linger_value, sizeof(linger_value));
            break;
        }
        default: end_active_call(guard); return -ENOPROTOOPT;
    }
    int error = result == 0 ? 0 : lwip_error();
    end_active_call(guard);
    return error;
}

JNIEXPORT jbyteArray JNICALL Java_local_headunit_diplay_NativeIpStack_name(
        JNIEnv *env, jclass cls, jint fd, jboolean peer) {
    (void)cls;
    struct sockaddr_in6 address;
    socklen_t length = sizeof(address);
    memset(&address, 0, sizeof(address));
    user_stack *guard = begin_active_call(0, 0);
    if (guard == NULL) return NULL;
    int result = peer ? lwip_getpeername(fd, (struct sockaddr *)&address, &length)
                      : lwip_getsockname(fd, (struct sockaddr *)&address, &length);
    if (result != 0) { end_active_call(guard); return NULL; }
    jbyteArray output = (*env)->NewByteArray(env, 18);
    if (output == NULL) { end_active_call(guard); return NULL; }
    (*env)->SetByteArrayRegion(env, output, 0, 16, (jbyte *)&address.sin6_addr);
    u16_t port = ntohs(address.sin6_port);
    jbyte port_bytes[2] = {(jbyte)(port >> 8), (jbyte)port};
    (*env)->SetByteArrayRegion(env, output, 16, 2, port_bytes);
    end_active_call(guard);
    return output;
}

JNIEXPORT jlong JNICALL Java_local_headunit_diplay_NativeIpStack_create(
        JNIEnv *env, jclass cls, jstring address_text, jbyteArray mac_array, jint tracked_tcp_syn_port) {
    (void)cls;
    set_crash_stage("native_create_enter");
    g_create_error = 0;
    if (address_text == NULL || mac_array == NULL || (*env)->GetArrayLength(env, mac_array) != 6 || tracked_tcp_syn_port < 1 || tracked_tcp_syn_port > 65535) { g_create_error = 1; return 0; }
    pthread_mutex_lock(&g_lock);
    if (g_active != NULL) {
        pthread_mutex_unlock(&g_lock);
        g_create_error = 2; return 0;
    }
    user_stack *stack = (user_stack *)calloc(1, sizeof(user_stack));
    set_crash_stage("native_stack_allocated");
    if (stack == NULL) {
        pthread_mutex_unlock(&g_lock);
        g_create_error = 3; return 0;
    }
    stack->tracked_tcp_syn_port = (u16_t)tracked_tcp_syn_port;
    int sync_result = pthread_mutex_init(&stack->queue_lock, NULL);
    if (sync_result != 0) {
        free(stack);
        pthread_mutex_unlock(&g_lock);
        g_create_error = 3000 + sync_result;
        return 0;
    }
    sync_result = pthread_cond_init(&stack->queue_ready, NULL);
    if (sync_result != 0) {
        pthread_mutex_destroy(&stack->queue_lock);
        free(stack);
        pthread_mutex_unlock(&g_lock);
        g_create_error = 3000 + sync_result;
        return 0;
    }
    (*env)->GetByteArrayRegion(env, mac_array, 0, 6, (jbyte *)stack->mac);
    if ((*env)->ExceptionCheck(env)) {
        pthread_cond_destroy(&stack->queue_ready); pthread_mutex_destroy(&stack->queue_lock); free(stack);
        pthread_mutex_unlock(&g_lock); g_create_error = 4; return 0;
    }
    const char *text = (*env)->GetStringUTFChars(env, address_text, NULL);
    ip6_addr_t address;
    int parsed = text != NULL && ip6addr_aton(text, &address);
    if (text != NULL) (*env)->ReleaseStringUTFChars(env, address_text, text);
    if (!parsed || !ip6_addr_islinklocal(&address)) {
        pthread_cond_destroy(&stack->queue_ready); pthread_mutex_destroy(&stack->queue_lock); free(stack);
        pthread_mutex_unlock(&g_lock); g_create_error = 5; return 0;
    }
    set_crash_stage("native_before_tcpip_init");
    int tcpip_error = ensure_tcpip_started();
    if (tcpip_error != 0) {
        pthread_cond_destroy(&stack->queue_ready);
        pthread_mutex_destroy(&stack->queue_lock);
        free(stack);
        pthread_mutex_unlock(&g_lock);
        g_create_error = 2000 + tcpip_error;
        return 0;
    }
    set_crash_stage("native_before_netif_add");
    stack_set_running(stack, 1);
    add_netif_args args;
    args.stack = stack; args.address = address; args.result = ERR_IF;
    err_t queued = tcpip_callback_wait(add_netif_on_core, &args);
    if (queued != ERR_OK || args.result != ERR_OK) {
        stack_set_running(stack, 0);
        pthread_cond_destroy(&stack->queue_ready); pthread_mutex_destroy(&stack->queue_lock); free(stack);
        pthread_mutex_unlock(&g_lock); g_create_error = queued != ERR_OK ? (100 - queued) : (200 - args.result); return 0;
    }
    set_crash_stage("native_netif_ready");
    g_active = stack;
    pthread_mutex_unlock(&g_lock);
    return (jlong)(intptr_t)stack;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_input(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray frame) {
    (void)cls;
    if (frame == NULL) return -EINVAL;
    jsize length = (*env)->GetArrayLength(env, frame);
    if (length < 14 || length > MAX_ETH_FRAME) return -EMSGSIZE;
    u8_t buffer[MAX_ETH_FRAME];
    (*env)->GetByteArrayRegion(env, frame, 0, length, (jbyte *)buffer);
    if ((*env)->ExceptionCheck(env)) return -EINVAL;
    user_stack *stack = begin_active_call(handle, 1);
    if (stack == NULL) return -ENODEV;
    classify_inbound_ethernet(stack, buffer, (u16_t)length);
    struct pbuf *packet = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_POOL);
    if (packet == NULL) {
        __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
        end_active_call(stack);
        return -ENOMEM;
    }
    if (pbuf_take(packet, buffer, (u16_t)length) != ERR_OK) {
        pbuf_free(packet);
        __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
        end_active_call(stack);
        return -ENOMEM;
    }
    err_t result = tcpip_input(packet, &g_netif);
    if (result != ERR_OK) {
        pbuf_free(packet);
        __sync_fetch_and_add(&stack->stats[ST_RX_DROP], 1);
        end_active_call(stack);
        return -EAGAIN;
    }
    end_active_call(stack);
    return length;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_createError(
        JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    return g_create_error;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_nextOutput(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray output, jint timeout_ms) {
    (void)cls;
    if (output == NULL || timeout_ms < 0 || timeout_ms > 1000) return -EINVAL;
    jsize capacity = (*env)->GetArrayLength(env, output);
    if (capacity < MAX_ETH_FRAME) return -EINVAL;
    user_stack *stack = begin_active_call(handle, 1);
    if (stack == NULL) return -ENODEV;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += timeout_ms / 1000;
    deadline.tv_nsec += (timeout_ms % 1000) * 1000000L;
    if (deadline.tv_nsec >= 1000000000L) { deadline.tv_sec++; deadline.tv_nsec -= 1000000000L; }
    pthread_mutex_lock(&stack->queue_lock);
    while (stack->head == NULL && stack_is_running(stack)) {
        if (timeout_ms == 0 || pthread_cond_timedwait(&stack->queue_ready, &stack->queue_lock, &deadline) == ETIMEDOUT) {
            pthread_mutex_unlock(&stack->queue_lock);
            end_active_call(stack);
            return 0;
        }
    }
    packet_node *node = stack->head;
    if (node == NULL || !stack_is_running(stack)) {
        pthread_mutex_unlock(&stack->queue_lock);
        end_active_call(stack);
        return -ENODEV;
    }
    stack->head = node->next;
    if (stack->head == NULL) stack->tail = NULL;
    stack->queued_frames--;
    stack->queued_bytes -= node->length;
    pthread_mutex_unlock(&stack->queue_lock);
    (*env)->SetByteArrayRegion(env, output, 0, node->length, (jbyte *)node->bytes);
    jint length = node->length;
    free(node);
    if ((*env)->ExceptionCheck(env)) {
        __sync_fetch_and_add(&stack->stats[ST_TX_OUTPUT_COPY_ERROR], 1);
        end_active_call(stack);
        return -EINVAL;
    }
    end_active_call(stack);
    return length;
}

JNIEXPORT jlongArray JNICALL Java_local_headunit_diplay_NativeIpStack_stats(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    user_stack *stack = begin_active_call(handle, 1);
    if (stack == NULL) return NULL;
    jlong values[STAT_COUNT + EXTRA_STAT_COUNT];
    for (int i = 0; i < STAT_COUNT; i++) values[i] = (jlong)__sync_fetch_and_add(&stack->stats[i], 0);
    u32_t rx_pair = __sync_fetch_and_add(&stack->last_rx_port_pair, 0);
    u32_t tx_pair = __sync_fetch_and_add(&stack->last_tx_port_pair, 0);
    values[STAT_COUNT] = rx_pair >> 16;
    values[STAT_COUNT + 1] = rx_pair & 0xffff;
    values[STAT_COUNT + 2] = tx_pair >> 16;
    values[STAT_COUNT + 3] = tx_pair & 0xffff;
    values[STAT_COUNT + 4] = __sync_fetch_and_add(&stack->last_rx_ipv6_syn_port_pair, 0);
    values[STAT_COUNT + 5] = __sync_fetch_and_add(&stack->last_rx_ipv4_syn_port_pair, 0);
    if (__sync_fetch_and_add(&stack->has_rx_ipv6_tracked_syn, 0)) {
        u32_t last = __sync_fetch_and_add(&stack->last_rx_ipv6_tracked_syn_at_ms, 0);
        values[STAT_COUNT + 6] = (u32_t)(sys_now() - last);
    } else {
        values[STAT_COUNT + 6] = -1;
    }
    if (__sync_fetch_and_add(&stack->has_rx_ipv4_tracked_syn, 0)) {
        u32_t last = __sync_fetch_and_add(&stack->last_rx_ipv4_tracked_syn_at_ms, 0);
        values[STAT_COUNT + 7] = (u32_t)(sys_now() - last);
    } else {
        values[STAT_COUNT + 7] = -1;
    }
    values[STAT_COUNT + 8] = (jlong)__sync_fetch_and_add(&stack->tx_mdns_packets, 0);
    for (unsigned int i = 0; i < 6; i++) {
        values[STAT_COUNT + 9 + i] = (jlong)__sync_fetch_and_add(&stack->mdns_service_masks[i], 0);
    }
    if (__sync_fetch_and_add(&stack->has_rx_mdns_service, 0)) {
        u32_t last = __sync_fetch_and_add(&stack->last_rx_mdns_service_at_ms, 0);
        values[STAT_COUNT + 15] = (jlong)(u32_t)(sys_now() - last);
        values[STAT_COUNT + 16] = (jlong)__sync_fetch_and_add(&stack->last_rx_mdns_service_mask, 0);
    } else {
        values[STAT_COUNT + 15] = -1;
        values[STAT_COUNT + 16] = 0;
    }
    values[STAT_COUNT + 17] = (jlong)__sync_fetch_and_add(&stack->last_rx_mdns_service_query_mask, 0);
    values[STAT_COUNT + 18] = (jlong)__sync_fetch_and_add(&stack->last_rx_mdns_service_response_mask, 0);
    end_active_call(stack);
    jlongArray output = (*env)->NewLongArray(env, STAT_COUNT + EXTRA_STAT_COUNT);
    if (output != NULL) (*env)->SetLongArrayRegion(env, output, 0, STAT_COUNT + EXTRA_STAT_COUNT, values);
    return output;
}

JNIEXPORT jint JNICALL Java_local_headunit_diplay_NativeIpStack_stop(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    pthread_mutex_lock(&g_lock);
    user_stack *stack = (user_stack *)(intptr_t)handle;
    if (stack == NULL || stack != g_active) { pthread_mutex_unlock(&g_lock); return 0; }
    stack_set_running(stack, 0);
    pthread_mutex_lock(&stack->queue_lock);
    pthread_cond_broadcast(&stack->queue_ready);
    pthread_mutex_unlock(&stack->queue_lock);
    while (stack->active_calls != 0) pthread_cond_wait(&g_stack_idle, &g_lock);
    (void)tcpip_callback_wait(remove_netif_on_core, NULL);
    g_active = NULL;
    packet_node *node = stack->head;
    while (node != NULL) { packet_node *next = node->next; free(node); node = next; }
    pthread_cond_destroy(&stack->queue_ready);
    pthread_mutex_destroy(&stack->queue_lock);
    free(stack);
    pthread_mutex_unlock(&g_lock);
    return 0;
}
