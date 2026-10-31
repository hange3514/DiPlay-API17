/* Minimal ALSA capture + mixer probe for /dev/snd nodes.
 * Bypasses AudioFlinger/HAL policy entirely: raw open + ioctls + read.
 * The capture path is proven (0.53.67); this adds controlC* mixer enumeration
 * and capture-path enabling (capture switch/volume/mux) so the mic input
 * actually reaches the ADC. Struct layouts from include/sound/asound.h,
 * identical from kernel v3.0 through master. */
#include <jni.h>

extern int open(const char *path, int flags, ...);
extern int close(int fd);
extern int read(int fd, void *buf, unsigned int count);
extern int ioctl(int fd, unsigned long request, ...);
extern long syscall(long number, ...);
extern int poll(void *fds, unsigned int count, int timeout);
extern int snprintf(char *buf, unsigned int size, const char *fmt, ...);
extern void *memset(void *s, int c, unsigned int n);
extern void *memcpy(void *d, const void *s, unsigned int n);
extern int *__errno(void);

/* ioctl through the libc stub first; on ENOTTY retry via the raw syscall
 * (__NR_ioctl=54 on ARM32) in case the stub itself is broken. */
static int raw_ioctl(int fd, unsigned long req, void *arg) {
    int r = ioctl(fd, req, arg);
    if (r < 0 && *__errno() == 25) r = (int)syscall(54, fd, req, arg);
    return r;
}

#define ALSA_PVERSION   0x80044100UL /* _IOR('A',0x00,int) */
#define ALSA_INFO       0x81204101UL /* _IOR('A',0x01,struct pcm_info[288]) */
#define ALSA_HW_REFINE  0xC25C4110UL /* _IOWR('A',0x10,struct hw_params[604]) */
#define ALSA_HW_PARAMS  0xC25C4111UL
#define ALSA_SW_PARAMS  0xC0684113UL /* _IOWR('A',0x13,struct sw_params[104]) */
#define ALSA_PREPARE    0x00004140UL /* _IO('A',0x40) */
#define ALSA_START      0x00004142UL /* _IO('A',0x42) */

#define CTL_CARD_INFO   0x81785501UL /* _IOR('U',0x01,struct card_info[376]) */
#define CTL_ELEM_LIST   0xC0485510UL /* _IOWR('U',0x10,struct elem_list[72]) */
#define CTL_ELEM_INFO   0xC1105511UL /* _IOWR('U',0x11,struct elem_info[272]) */
#define CTL_ELEM_READ   0xC2C45512UL /* _IOWR('U',0x12,struct elem_value[708]) */
#define CTL_ELEM_WRITE  0xC2C45513UL /* _IOWR('U',0x13,struct elem_value[708]) */

struct alsa_hw {
    unsigned int flags;
    unsigned int masks[3][8];        /* snd_mask = 8 u32 (SNDRV_MASK_MAX=256) */
    unsigned int mreserved[5][8];
    unsigned int intervals[12][3];   /* min, max, flags(openmin|openmax|integer|empty) */
    unsigned int ireserved[9][3];
    unsigned int rmask, cmask, info, msbits, rate_num, rate_den, fifo_size;
    unsigned char reserved[64];
};                                   /* 604 bytes */

struct alsa_sw {
    int tstamp_mode;
    unsigned int period_step, sleep_min, avail_min, xfer_align;
    unsigned int start_threshold, stop_threshold, silence_threshold, silence_size, boundary;
    unsigned char reserved[64];
};                                   /* 104 bytes */

struct ctl_id {
    unsigned int numid, iface, device, subdevice;
    unsigned char name[44];
    unsigned int index;
};                                   /* 64 bytes */

struct ctl_list {
    unsigned int offset, space, used, count;
    struct ctl_id *pids;
    unsigned char reserved[50];
};                                   /* 72 bytes */

struct ctl_info {
    struct ctl_id id;
    int type;                        /* 1=BOOLEAN 2=INTEGER 3=ENUMERATED 4=BYTES 6=INTEGER64 */
    unsigned int access, count;
    int owner;
    union {
        struct { long min, max, step; } integer;
        struct { long long min, max, step; } integer64;
        struct { unsigned int items, item; char name[64]; } enumerated;
        unsigned char reserved[128];
    } value;
    unsigned short dimen[4];
    unsigned char reserved[56];
};                                   /* 272 bytes */

struct ctl_value {
    struct ctl_id id;
    unsigned int indirect;
    union {
        long iv[128];
        long long lv[64];
        unsigned int ev[128];
        unsigned char data[512];
    } value;
    long tstamp[2];
    unsigned char reserved[120];
};                                   /* 708 bytes */

struct capture_diag {
    int stage; /* 1=open, 2=hw_refine, 3=invalid_range, 4=hw_params, 5=sw_params, 6=prepare, 7=ready, 8=start failed */
    int error;
    unsigned int rmin, rmax, cmin, cmax, rate, channels;
};

/* ---------- shared helpers ---------- */

static void hw_init_open(void *buf) {
    struct alsa_hw *p = (struct alsa_hw *)buf;
    memset(buf, 0, 768);
    for (int m = 0; m < 3; m++) for (int w = 0; w < 8; w++) p->masks[m][w] = 0xFFFFFFFFu;
    for (int i = 0; i < 12; i++) { p->intervals[i][0] = 0; p->intervals[i][1] = 0xFFFFFFFFu; p->intervals[i][2] = 0; }
    p->rmask = 0xFFFFFFFFu;
    p->masks[0][0] = 1u << 3;   /* ACCESS = RW_INTERLEAVED(3) */
    p->masks[1][0] = 1u << 2;   /* FORMAT = S16_LE(2) */
    p->masks[2][0] = 1u;        /* SUBFORMAT = STD(0) */
}

static void copy_field(char *dst, unsigned int dst_size, const unsigned char *src, unsigned int len) {
    unsigned int i = 0;
    for (; i + 1 < dst_size && i < len && src[i]; i++) {
        unsigned char ch = src[i];
        dst[i] = (ch >= 32 && ch < 127) ? (char)ch : '.';
    }
    dst[i] = 0;
}

static int name_has(const unsigned char *name, const char *needle) {
    for (unsigned int i = 0; name[i]; i++) {
        unsigned int j = 0;
        while (needle[j] && name[i + j]) {
            unsigned char a = name[i + j], b = (unsigned char)needle[j];
            if (a >= 'A' && a <= 'Z') a += 32;
            if (b >= 'A' && b <= 'Z') b += 32;
            if (a != b) break;
            j++;
        }
        if (needle[j] == 0) return 1;
    }
    return 0;
}

static int is_captureish(const unsigned char *name) {
    if (name_has(name, "playback") || name_has(name, "speaker")
            || name_has(name, "headphone") || name_has(name, "hdmi")) return 0;
    return name_has(name, "capture") || name_has(name, "mic") || name_has(name, "adc")
        || name_has(name, "pga") || name_has(name, "boost") || name_has(name, "input")
        || name_has(name, "mux") || name_has(name, "route") || name_has(name, "select")
        || name_has(name, "source") || name_has(name, " rec");
}

/* ---------- pcm capture ---------- */

/* Opens + negotiates + prepares a capture stream. Returns fd or -1 (err set). */
static int pcm_open_capture(const char *path, unsigned int want_r, unsigned int want_c,
                            unsigned int *r_out, unsigned int *c_out,
                            char *err, unsigned int errsz, struct capture_diag *diag) {
    if (diag) memset(diag, 0, sizeof(*diag));
    if (diag) diag->stage = 1;
    int fd = open(path, 0x800); /* O_RDONLY | O_NONBLOCK */
    if (fd < 0) fd = open(path, 0x802); /* O_RDWR | O_NONBLOCK */
    if (fd < 0) {
        int e = -*__errno();
        if (diag) diag->error = e;
        snprintf(err, errsz, "open errno=%d", e);
        return -1;
    }
    if (diag) diag->stage = 2;
    static const int sizes[] = { 604, 608, 600, 412 };
    unsigned char hwbuf[768];
    struct alsa_hw *hw = (struct alsa_hw *)hwbuf;
    int hwsize = 0;
    int last = 0;
    for (unsigned int s = 0; s < sizeof(sizes) / sizeof(sizes[0]) && !hwsize; s++) {
        hw_init_open(hwbuf);
        if (raw_ioctl(fd, ALSA_HW_REFINE, hwbuf) == 0) { hwsize = sizes[s]; break; }
        last = -*__errno();
        if (last != -25) break; /* ENOTTY: try next size; real error: stop */
    }
    if (!hwsize) {
        if (diag) diag->error = last;
        snprintf(err, errsz, "refine errno=%d", last);
        close(fd);
        return -1;
    }
    unsigned int rmin = hw->intervals[3][0], rmax = hw->intervals[3][1];
    unsigned int cmin = hw->intervals[2][0], cmax = hw->intervals[2][1];
    if (diag) { diag->rmin = rmin; diag->rmax = rmax; diag->cmin = cmin; diag->cmax = cmax; }
    unsigned int r = want_r;
    if (r < rmin || r > rmax) r = (48000u >= rmin && 48000u <= rmax) ? 48000u : rmin;
    unsigned int c = want_c;
    if (c < cmin || c > cmax) c = cmin;
    if (r == 0 || c == 0 || rmax == 0 || cmax == 0) {
        if (diag) { diag->stage = 3; diag->error = 22; }
        snprintf(err, errsz, "refine_empty rmin=%u rmax=%u cmin=%u cmax=%u", rmin, rmax, cmin, cmax);
        close(fd);
        return -1;
    }
    hw->intervals[3][0] = hw->intervals[3][1] = r; hw->intervals[3][2] = 4;
    hw->intervals[2][0] = hw->intervals[2][1] = c; hw->intervals[2][2] = 4;
    if (diag) { diag->stage = 4; diag->rate = r; diag->channels = c; }
    if (raw_ioctl(fd, ALSA_HW_PARAMS, hwbuf) < 0) {
        int e = -*__errno();
        if (diag) diag->error = e;
        snprintf(err, errsz, "params errno=%d r=%u c=%u", e, r, c);
        close(fd);
        return -1;
    }
    struct alsa_sw sw;
    memset(&sw, 0, sizeof(sw));
    sw.avail_min = 64;
    sw.start_threshold = 1;
    sw.stop_threshold = 0x40000000u;
    sw.boundary = 0x40000000u;
    if (diag) diag->stage = 5;
    if (raw_ioctl(fd, ALSA_SW_PARAMS, &sw) < 0) {
        int e = -*__errno();
        if (diag) diag->error = e;
        snprintf(err, errsz, "sw errno=%d", e);
        close(fd);
        return -1;
    }
    if (diag) diag->stage = 6;
    if (raw_ioctl(fd, ALSA_PREPARE, 0) < 0) {
        int e = -*__errno();
        if (diag) diag->error = e;
        snprintf(err, errsz, "prepare errno=%d", e);
        close(fd);
        return -1;
    }
    if (diag) diag->stage = 7;
    if (raw_ioctl(fd, ALSA_START, 0) < 0 && diag) {
        diag->stage = 8;
        diag->error = -*__errno();
    }
    *r_out = r;
    *c_out = c;
    return fd;
}

/* Reads for millis; returns frames (>=0) or -1 (err set). */
static long pcm_read_for(int fd, unsigned int r, unsigned int c, int millis,
                         long *peak_out, char *err, unsigned int errsz) {
    short buf[1024];
    long frames = 0, peak = 0, waits = 0;
    long want = (long)r * millis / 1000;
    struct { int fd; short events, revents; } p = { fd, 1, 0 };
    while (frames < want && waits < 24000) {
        p.revents = 0;
        int ready = poll(&p, 1, 500);
        if (ready <= 0) { waits += 500; continue; }
        int n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            int e = *__errno();
            if (e == 11) { waits += 5; continue; }
            if (e == 32 || e == 77) {
                raw_ioctl(fd, ALSA_PREPARE, 0);
                raw_ioctl(fd, ALSA_START, 0);
                continue;
            }
            snprintf(err, errsz, "read errno=%d frames=%ld peak=%ld", -e, frames, peak);
            *peak_out = peak;
            return -1;
        }
        if (n == 0) { waits += 5; continue; }
        int samples = n / 2;
        frames += samples / (long)c;
        for (int i = 0; i < samples; i++) {
            int a = buf[i] < 0 ? -buf[i] : buf[i];
            if (a > peak) peak = a;
        }
    }
    *peak_out = peak;
    if (frames == 0) {
        snprintf(err, errsz, "read_timeout");
        return -1;
    }
    return frames;
}

static void pcm_path(char *dst, unsigned int size, int card) {
    snprintf(dst, size, "/dev/snd/pcmC%dD0c", card);
}

/* ---------- JNI: plain capture test (kept from 0.53.67) ---------- */

JNIEXPORT jstring JNICALL Java_local_headunit_diplay_AlsaProbe_captureTest(
        JNIEnv *env, jclass cls, jstring device, jint rate, jint channels, jint millis) {
    (void)cls;
    char out[256], err[96];
    const char *path = (*env)->GetStringUTFChars(env, device, 0);
    if (!path) return 0;
    if (rate < 8000 || rate > 48000 || (channels != 1 && channels != 2) || millis < 500 || millis > 9000) {
        (*env)->ReleaseStringUTFChars(env, device, path);
        return (*env)->NewStringUTF(env, "fail step=args errno=-22");
    }
    int fd = open(path, 0x800);
    if (fd < 0) fd = open(path, 0x802);
    if (fd < 0) {
        int e = -*__errno();
        (*env)->ReleaseStringUTFChars(env, device, path);
        snprintf(out, sizeof(out), "fail step=open errno=%d", e);
        return (*env)->NewStringUTF(env, out);
    }
    int version = 0;
    raw_ioctl(fd, ALSA_PVERSION, &version);
    close(fd);
    unsigned int r, c;
    fd = pcm_open_capture(path, (unsigned int)rate, (unsigned int)channels, &r, &c, err, sizeof(err), 0);
    (*env)->ReleaseStringUTFChars(env, device, path);
    if (fd < 0) {
        snprintf(out, sizeof(out), "fail step=%s ver=%x", err, version);
        return (*env)->NewStringUTF(env, out);
    }
    long peak = 0;
    long frames = pcm_read_for(fd, r, c, millis, &peak, err, sizeof(err));
    close(fd);
    if (frames < 0) {
        snprintf(out, sizeof(out), "fail step=%s ver=%x r=%u c=%u", err, version, r, c);
        return (*env)->NewStringUTF(env, out);
    }
    snprintf(out, sizeof(out), "ok frames=%ld peak=%ld rate=%u channels=%u ver=%x hwsize=604",
             frames, peak, r, c, version);
    return (*env)->NewStringUTF(env, out);
}

/* ---------- JNI: mixer dump + capture-path enable + capture retest ---------- */

static char dump[6144];
static unsigned int dump_len;
static struct ctl_id ids[160];
static int enum_idx[2]; /* indexes into ids[] of enumerated captureish controls */

JNIEXPORT jstring JNICALL Java_local_headunit_diplay_AlsaProbe_mixerTest(
        JNIEnv *env, jclass cls, jint card, jint rate, jint channels, jint millis) {
    (void)cls;
    char out[256], err[96];
    if (card < 0 || card > 7 || millis < 500 || millis > 9000)
        return (*env)->NewStringUTF(env, "fail step=args errno=-22");
    char cpath[32];
    snprintf(cpath, sizeof(cpath), "/dev/snd/controlC%d", card);
    int ctl = open(cpath, 0x802); /* O_RDWR | O_NONBLOCK */
    if (ctl < 0) ctl = open(cpath, 0x800);
    if (ctl < 0) {
        snprintf(out, sizeof(out), "fail step=ctl_open errno=%d", -*__errno());
        return (*env)->NewStringUTF(env, out);
    }
    unsigned char cardinfo[376];
    memset(cardinfo, 0, sizeof(cardinfo));
    raw_ioctl(ctl, CTL_CARD_INFO, cardinfo);
    char card_name[20];
    copy_field(card_name, sizeof(card_name), cardinfo + 40, 19); /* name[32] at offset 40 */

    struct ctl_list lst;
    memset(&lst, 0, sizeof(lst));
    if (raw_ioctl(ctl, CTL_ELEM_LIST, &lst) < 0) {
        snprintf(out, sizeof(out), "fail step=elem_list errno=%d", -*__errno());
        close(ctl);
        return (*env)->NewStringUTF(env, out);
    }
    unsigned int total = lst.count;
    if (total > 160) total = 160;
    lst.space = total;
    lst.pids = ids;
    memset(ids, 0, sizeof(ids));
    if (raw_ioctl(ctl, CTL_ELEM_LIST, &lst) < 0) {
        snprintf(out, sizeof(out), "fail step=elem_list2 errno=%d", -*__errno());
        close(ctl);
        return (*env)->NewStringUTF(env, out);
    }
    unsigned int n = lst.used;

    dump_len = 0;
    dump[0] = 0;
    int enum_count = 0;
    int wrote = 0;
    for (unsigned int i = 0; i < n; i++) {
        struct ctl_info ei;
        memset(&ei, 0, sizeof(ei));
        memcpy(&ei.id, &ids[i], sizeof(struct ctl_id));
        if (raw_ioctl(ctl, CTL_ELEM_INFO, &ei) < 0) continue;
        char nm[45];
        copy_field(nm, sizeof(nm), ei.id.name, 44);
        struct ctl_value val;
        memset(&val, 0, sizeof(val));
        memcpy(&val.id, &ids[i], sizeof(struct ctl_id));
        int read_ok = raw_ioctl(ctl, CTL_ELEM_READ, &val) == 0;
        int cap = is_captureish(ei.id.name);
        int writable = (ei.access & 2) != 0;
        /* dump one compact line */
        char line[224];
        int ll = 0;
        if (ei.type == 1 || ei.type == 2) {
            long v0 = read_ok ? val.value.iv[0] : -1;
            long v1 = (read_ok && ei.count > 1) ? val.value.iv[1] : -1;
            ll = snprintf(line, sizeof(line), "#%u %s t%d c%d %ld-%ld v=%ld,%ld%s",
                          ei.id.numid, nm, ei.type, ei.count,
                          ei.value.integer.min, ei.value.integer.max, v0, v1,
                          cap ? " CAP" : "");
        } else if (ei.type == 3) {
            unsigned int items = ei.value.enumerated.items;
            ll = snprintf(line, sizeof(line), "#%u %s t3 items=%u v=%u%s",
                          ei.id.numid, nm, items,
                          read_ok ? val.value.ev[0] : 0, cap ? " CAP" : "");
            if (cap && writable && items >= 2 && items <= 8 && enum_count < 2) {
                enum_idx[enum_count++] = (int)i;
            }
            /* append item names (up to 6) */
            for (unsigned int it = 0; it < items && it < 6 && ll < 170; it++) {
                struct ctl_info e2;
                memset(&e2, 0, sizeof(e2));
                memcpy(&e2.id, &ids[i], sizeof(struct ctl_id));
                e2.value.enumerated.item = it;
                if (raw_ioctl(ctl, CTL_ELEM_INFO, &e2) < 0) break;
                char inm[20];
                copy_field(inm, sizeof(inm), (unsigned char *)e2.value.enumerated.name, 19);
                ll += snprintf(line + ll, sizeof(line) - ll, " %u:%s", it, inm);
            }
        } else {
            ll = snprintf(line, sizeof(line), "#%u %s t%d c%d%s",
                          ei.id.numid, nm, ei.type, ei.count, cap ? " CAP" : "");
        }
        /* auto-enable capture-ish booleans/integers */
        if (cap && writable && (ei.type == 1 || ei.type == 2)) {
            struct ctl_value w;
            memset(&w, 0, sizeof(w));
            memcpy(&w.id, &ids[i], sizeof(struct ctl_id));
            for (unsigned int k = 0; k < ei.count && k < 128; k++)
                w.value.iv[k] = ei.type == 1 ? 1 : ei.value.integer.max;
            if (raw_ioctl(ctl, CTL_ELEM_WRITE, &w) == 0) {
                wrote++;
                ll += snprintf(line + ll, sizeof(line) - ll, " WROTE");
            } else {
                ll += snprintf(line + ll, sizeof(line) - ll, " wfail%d", -*__errno());
            }
        }
        if (dump_len + ll + 2 < sizeof(dump)) {
            memcpy(dump + dump_len, line, ll);
            dump_len += ll;
            dump[dump_len++] = '\n';
            dump[dump_len] = 0;
        }
    }
    close(ctl);

    /* capture retest on the same card */
    char path[32];
    pcm_path(path, sizeof(path), card);
    unsigned int r, c;
    long peak0 = -1, peak1 = -1;
    int pfd = pcm_open_capture(path, (unsigned int)rate, (unsigned int)channels, &r, &c, err, sizeof(err), 0);
    if (pfd < 0) {
        snprintf(out, sizeof(out), "fail step=%s card=%d(%s) controls=%u wrote=%d dump=%s",
                 err, card, card_name, n, wrote, dump);
        return (*env)->NewStringUTF(env, out);
    }
    long frames = pcm_read_for(pfd, r, c, 800, &peak0, err, sizeof(err));
    (void)frames;

    /* enumerated input mux sweep if still silent */
    char mux_report[128];
    mux_report[0] = 0;
    int mux_len = 0;
    if (peak0 < 100) {
        ctl = open(cpath, 0x802);
        if (ctl < 0) ctl = open(cpath, 0x800);
        for (int e = 0; e < enum_count && ctl >= 0; e++) {
            struct ctl_info ei;
            memset(&ei, 0, sizeof(ei));
            memcpy(&ei.id, &ids[enum_idx[e]], sizeof(struct ctl_id));
            if (raw_ioctl(ctl, CTL_ELEM_INFO, &ei) < 0) continue;
            unsigned int items = ei.value.enumerated.items;
            if (items > 8) items = 8;
            long best_peak = -1;
            unsigned int best_item = 0;
            for (unsigned int it = 0; it < items; it++) {
                struct ctl_value w;
                memset(&w, 0, sizeof(w));
                memcpy(&w.id, &ids[enum_idx[e]], sizeof(struct ctl_id));
                w.value.ev[0] = it;
                if (raw_ioctl(ctl, CTL_ELEM_WRITE, &w) < 0) continue;
                close(pfd);
                unsigned int r2, c2;
                pfd = pcm_open_capture(path, r, c, &r2, &c2, err, sizeof(err), 0);
                if (pfd < 0) break;
                long pk = -1;
                pcm_read_for(pfd, r2, c2, 500, &pk, err, sizeof(err));
                if (mux_len < 110) mux_len += snprintf(mux_report + mux_len,
                        sizeof(mux_report) - mux_len, " %u:%ld", it, pk);
                if (pk > best_peak) { best_peak = pk; best_item = it; }
            }
            struct ctl_value w;
            memset(&w, 0, sizeof(w));
            memcpy(&w.id, &ids[enum_idx[e]], sizeof(struct ctl_id));
            w.value.ev[0] = best_item;
            raw_ioctl(ctl, CTL_ELEM_WRITE, &w);
            if (mux_len < 110) mux_len += snprintf(mux_report + mux_len,
                    sizeof(mux_report) - mux_len, " best=%u", best_item);
        }
        if (ctl >= 0) close(ctl);
        if (pfd < 0) {
            unsigned int r3, c3;
            pfd = pcm_open_capture(path, r, c, &r3, &c3, err, sizeof(err), 0);
        }
    }
    if (pfd >= 0) {
        long frames2 = pcm_read_for(pfd, r, c, millis, &peak1, err, sizeof(err));
        (void)frames2;
        close(pfd);
    }
    snprintf(out, sizeof(out), "controls=%u wrote=%d peak0=%ld peak1=%ld mux=[%s] card=%d(%s)\n%s",
             n, wrote, peak0, peak1, mux_report, card, card_name, dump);
    return (*env)->NewStringUTF(env, out);
}

/* ---------- JNI: streaming capture handle for the call uplink ---------- */

/* Opens a capture stream like captureTest but keeps it open.
   out[0]=negotiated rate, out[1]=negotiated channels. Returns fd+1 or 0. */
JNIEXPORT jlong JNICALL Java_local_headunit_diplay_AlsaProbe_captureOpen(
        JNIEnv *env, jclass cls, jstring device, jint rate, jint channels, jintArray outParams, jintArray outStatus) {
    (void)cls;
    const char *dev = (*env)->GetStringUTFChars(env, device, 0);
    char err[96];
    struct capture_diag diag;
    unsigned int r = 0, c = 0;
    int fd = pcm_open_capture(dev, (unsigned int)rate, (unsigned int)channels, &r, &c, err, sizeof(err), &diag);
    (*env)->ReleaseStringUTFChars(env, device, dev);
    if (outStatus != 0 && (*env)->GetArrayLength(env, outStatus) >= 8) {
        jint vals[8];
        vals[0] = diag.stage; vals[1] = diag.error;
        vals[2] = (jint)diag.rmin; vals[3] = (jint)diag.rmax;
        vals[4] = (jint)diag.cmin; vals[5] = (jint)diag.cmax;
        vals[6] = (jint)diag.rate; vals[7] = (jint)diag.channels;
        (*env)->SetIntArrayRegion(env, outStatus, 0, 8, vals);
    }
    if (fd < 0) return 0;
    if (outParams != 0 && (*env)->GetArrayLength(env, outParams) >= 2) {
        jint vals[2];
        vals[0] = (jint)r;
        vals[1] = (jint)c;
        (*env)->SetIntArrayRegion(env, outParams, 0, 2, vals);
    }
    return (jlong)(fd + 1);
}

/* Reads up to len bytes of interleaved S16_LE PCM. Returns bytes read (>0),
   0 on repeated timeout, or negative errno-like code on poll failure. */
JNIEXPORT jint JNICALL Java_local_headunit_diplay_AlsaProbe_captureRead(
        JNIEnv *env, jclass cls, jlong handle, jbyteArray buf, jint off, jint len) {
    (void)cls;
    int fd = (int)handle - 1;
    if (fd < 0 || buf == 0 || len <= 0) return -22;
    jbyte *p = (*env)->GetByteArrayElements(env, buf, 0);
    if (p == 0) return -12;
    int total = 0, empty = 0;
    while (total < len && empty < 15) {
        struct { int fd; short events, revents; } pfd = { fd, 1, 0 };
        int pr = poll(&pfd, 1, 100);
        if (pr < 0) {
            int e = -*__errno();
            (*env)->ReleaseByteArrayElements(env, buf, p, 0);
            return total > 0 ? total : e;
        }
        if (pr == 0 || (pfd.revents & 1) == 0) { empty++; continue; }
        int n = read(fd, p + off + total, (unsigned int)(len - total));
        if (n > 0) { total += n; empty = 0; }
        else empty++;
    }
    (*env)->ReleaseByteArrayElements(env, buf, p, 0);
    return total;
}

JNIEXPORT void JNICALL Java_local_headunit_diplay_AlsaProbe_captureClose(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env; (void)cls;
    int fd = (int)handle - 1;
    if (fd >= 0) close(fd);
}

/* ---------- JNI: read-only control snapshot (no writes, no capture) ---------- */

/* Lets the caller diff mixer state while an AudioFlinger capture is open
   versus baseline, to catch whatever actually mutes playback. */
JNIEXPORT jstring JNICALL Java_local_headunit_diplay_AlsaProbe_dumpControls(
        JNIEnv *env, jclass cls, jint card) {
    (void)cls;
    char out[6208];
    if (card < 0 || card > 7)
        return (*env)->NewStringUTF(env, "fail step=args errno=-22");
    char cpath[32];
    snprintf(cpath, sizeof(cpath), "/dev/snd/controlC%d", card);
    int ctl = open(cpath, 0x800);
    if (ctl < 0) {
        snprintf(out, sizeof(out), "fail step=ctl_open errno=%d", -*__errno());
        return (*env)->NewStringUTF(env, out);
    }
    struct ctl_list lst;
    memset(&lst, 0, sizeof(lst));
    if (raw_ioctl(ctl, CTL_ELEM_LIST, &lst) < 0) {
        snprintf(out, sizeof(out), "fail step=elem_list errno=%d", -*__errno());
        close(ctl);
        return (*env)->NewStringUTF(env, out);
    }
    unsigned int total = lst.count;
    if (total > 160) total = 160;
    lst.space = total;
    lst.pids = ids;
    memset(ids, 0, sizeof(ids));
    if (raw_ioctl(ctl, CTL_ELEM_LIST, &lst) < 0) {
        snprintf(out, sizeof(out), "fail step=elem_list2 errno=%d", -*__errno());
        close(ctl);
        return (*env)->NewStringUTF(env, out);
    }
    unsigned int n = lst.used;

    dump_len = 0;
    dump[0] = 0;
    for (unsigned int i = 0; i < n; i++) {
        struct ctl_info ei;
        memset(&ei, 0, sizeof(ei));
        memcpy(&ei.id, &ids[i], sizeof(struct ctl_id));
        if (raw_ioctl(ctl, CTL_ELEM_INFO, &ei) < 0) continue;
        char nm[45];
        copy_field(nm, sizeof(nm), ei.id.name, 44);
        struct ctl_value val;
        memset(&val, 0, sizeof(val));
        memcpy(&val.id, &ids[i], sizeof(struct ctl_id));
        int read_ok = raw_ioctl(ctl, CTL_ELEM_READ, &val) == 0;
        char line[224];
        int ll;
        if (ei.type == 1 || ei.type == 2) {
            long v0 = read_ok ? val.value.iv[0] : -1;
            long v1 = (read_ok && ei.count > 1) ? val.value.iv[1] : -1;
            ll = snprintf(line, sizeof(line), "#%u %s t%d c%d %ld-%ld v=%ld,%ld%s",
                          ei.id.numid, nm, ei.type, ei.count,
                          ei.value.integer.min, ei.value.integer.max, v0, v1,
                          is_captureish(ei.id.name) ? " CAP" : "");
        } else if (ei.type == 3) {
            ll = snprintf(line, sizeof(line), "#%u %s t3 items=%u v=%u%s",
                          ei.id.numid, nm, ei.value.enumerated.items,
                          read_ok ? val.value.ev[0] : 0,
                          is_captureish(ei.id.name) ? " CAP" : "");
        } else {
            ll = snprintf(line, sizeof(line), "#%u %s t%d c%d%s",
                          ei.id.numid, nm, ei.type, ei.count,
                          is_captureish(ei.id.name) ? " CAP" : "");
        }
        if (dump_len + ll + 2 < sizeof(dump)) {
            memcpy(dump + dump_len, line, ll);
            dump_len += ll;
            dump[dump_len++] = '\n';
            dump[dump_len] = 0;
        }
    }
    close(ctl);
    snprintf(out, sizeof(out), "controls=%u card=%d\n%s", n, card, dump);
    return (*env)->NewStringUTF(env, out);
}
