package local.headunit.diplay;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Retains original broadcast vectors and spreads change samples across the diagnostic window. */
final class CanBroadcastCapture {
    private static final int MAX_LAYOUTS = 96;
    private static final int MAX_WORDS = 64;
    private static final int BUCKET_MS = 3_000;
    private static final int CHANGES_PER_BUCKET = 8;
    private static final int MAX_CHANGES = 160;
    private static final int MAX_CONTEXT = 32;

    private final String scan = UUID.randomUUID().toString();
    private final Map<Long, Layout> layouts = new HashMap<Long, Layout>();
    private final List<Layout> changedThisBucket = new ArrayList<Layout>();
    private final List<Sample> samples = new ArrayList<Sample>();
    private final List<String> context = new ArrayList<String>();
    private long bucket = -1;
    private int changes;
    private int observed;
    private int invalid;
    private int contextOmitted;

    void observe(long elapsedMs, int[] words) {
        if (words == null || words.length < 2 || words.length > MAX_WORDS) {
            invalid++;
            return;
        }
        observed++;
        long currentBucket = elapsedMs / BUCKET_MS;
        if (bucket != currentBucket) {
            finishBucket();
            bucket = currentBucket;
        }
        long key = ((long) words[0] << 32) | words.length;
        Layout layout = layouts.get(key);
        if (layout == null) {
            if (layouts.size() >= MAX_LAYOUTS) return;
            Sample first = new Sample(elapsedMs, observed, words.clone());
            layouts.put(key, new Layout(first.words));
            samples.add(first);
        } else if (layout.candidate == null && changes < MAX_CHANGES && !Arrays.equals(layout.lastSaved, words)) {
            layout.candidate = new Sample(elapsedMs, observed, words.clone());
            changedThisBucket.add(layout);
        }
    }

    void finishBucket() {
        if (changedThisBucket.isEmpty()) return;
        // Give an ID with fewer exported changes a chance alongside continuously changing IDs.
        Collections.sort(changedThisBucket, new Comparator<Layout>() {
            @Override public int compare(Layout a, Layout b) {
                int byCount = a.savedChanges - b.savedChanges;
                return byCount != 0 ? byCount : a.candidate.sequence - b.candidate.sequence;
            }
        });
        int keep = Math.min(CHANGES_PER_BUCKET, MAX_CHANGES - changes);
        for (int index = 0; index < changedThisBucket.size(); index++) {
            Layout layout = changedThisBucket.get(index);
            if (index < keep) {
                samples.add(layout.candidate);
                layout.lastSaved = layout.candidate.words;
                layout.savedChanges++;
                changes++;
            }
            layout.candidate = null;
        }
        changedThisBucket.clear();
    }

    void mark(long elapsedMs, String signal) {
        if (context.size() >= MAX_CONTEXT) {
            contextOmitted++;
            return;
        }
        context.add("scan=" + scan + " tMs=" + elapsedMs + " signal=" + signal);
    }

    String scanId() { return scan; }
    int observed() { return observed; }
    int saved() { return samples.size(); }

    String summary() {
        return "canScan=" + scan + " canEncoding=int32_hex canSaved=" + saved() +
            " canLayouts=" + layouts.size() + " canUnsaved=" + (observed - saved()) +
            " canInvalid=" + invalid + " canContextSaved=" + context.size() +
            " canContextOmitted=" + contextOmitted;
    }

    List<String> contextRows() { return context; }

    List<String> sampleRows() {
        List<String> rows = new ArrayList<String>(samples.size());
        Collections.sort(samples, new Comparator<Sample>() {
            @Override public int compare(Sample a, Sample b) {
                return a.sequence - b.sequence;
            }
        });
        for (Sample sample : samples) {
            StringBuilder row = new StringBuilder(128).append("scan=").append(scan)
                .append(" tMs=").append(sample.elapsedMs).append(" seq=").append(sample.sequence)
                .append(" id=0x").append(Integer.toHexString(sample.words[0]))
                .append(" payloadWords=").append(sample.words.length - 1).append(" raw=[");
            for (int index = 0; index < sample.words.length; index++) {
                if (index != 0) row.append(',');
                row.append(Integer.toHexString(sample.words[index]));
            }
            rows.add(row.append(']').toString());
        }
        return rows;
    }

    private static final class Layout {
        int[] lastSaved;
        int savedChanges;
        Sample candidate;

        Layout(int[] lastSaved) { this.lastSaved = lastSaved; }
    }

    private static final class Sample {
        final long elapsedMs;
        final int sequence;
        final int[] words;

        Sample(long elapsedMs, int sequence, int[] words) {
            this.elapsedMs = elapsedMs;
            this.sequence = sequence;
            this.words = words;
        }
    }
}
