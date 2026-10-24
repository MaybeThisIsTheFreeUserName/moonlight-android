package com.limelight.binding.video;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-overhead, fixed-capacity frame timing recorder for diagnostic builds.
 * Events use primitive arrays; JSON formatting and disk I/O happen during cleanup only.
 */
final class FrameTimingLog {
    private static final String TAG = "FrameTimingLog";
    private static final int CAPACITY = 65536;

    static final int SESSION_START = 1;
    static final int DECODE_UNIT_SUBMIT = 2;
    static final int CODEC_INPUT_QUEUE = 3;
    static final int CODEC_OUTPUT = 4;
    static final int OUTPUT_BUFFER_DROP = 5;
    static final int SURFACE_RELEASE = 6;
    static final int OUTPUT_QUEUE_DROP = 7;
    static final int NATIVE_CALLBACK_TIMING = 8;

    private final Context context;
    private final AtomicLong nextSequence = new AtomicLong();
    private final long[] sequence = new long[CAPACITY];
    private final long[] timeNs = new long[CAPACITY];
    private final long[] ptsUs = new long[CAPACITY];
    private final long[] a = new long[CAPACITY];
    private final long[] b = new long[CAPACITY];
    private final long[] c = new long[CAPACITY];
    private final long[] d = new long[CAPACITY];
    private final int[] event = new int[CAPACITY];
    private final int[] frameNumber = new int[CAPACITY];
    private volatile boolean dumped;

    FrameTimingLog(Context context) {
        this.context = context.getApplicationContext();
    }

    void record(int eventCode, int frameNo, long codecPtsUs,
                long valueA, long valueB, long valueC, long valueD) {
        if (dumped) {
            return;
        }

        long seq = nextSequence.getAndIncrement();
        int slot = (int) (seq % CAPACITY);
        timeNs[slot] = System.nanoTime();
        event[slot] = eventCode;
        frameNumber[slot] = frameNo;
        ptsUs[slot] = codecPtsUs;
        a[slot] = valueA;
        b[slot] = valueB;
        c[slot] = valueC;
        d[slot] = valueD;
        // Publish the slot after all fields have been written.
        sequence[slot] = seq + 1;
    }

    File dump() {
        if (dumped) {
            return null;
        }
        dumped = true;

        File directory = context.getExternalFilesDir(null);
        if (directory == null) {
            directory = context.getFilesDir();
        }

        File file = new File(directory, "moonlight-frame-timing-" + System.currentTimeMillis() + ".jsonl");
        long end = nextSequence.get();
        long start = Math.max(0, end - CAPACITY);
        long overwritten = Math.max(0, end - CAPACITY);

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8), 65536)) {
            writer.write("{\"v\":1,\"type\":\"session\",\"clock\":\"System.nanoTime\",");
            writer.write("\"capacity\":" + CAPACITY + ",\"events_total\":" + end +
                    ",\"events_overwritten\":" + overwritten + "}\n");

            for (long seq = start; seq < end; seq++) {
                int slot = (int) (seq % CAPACITY);
                if (sequence[slot] != seq + 1) {
                    continue;
                }

                writer.write("{\"v\":1,\"t_ns\":" + timeNs[slot] +
                        ",\"event\":\"" + eventName(event[slot]) + "\",\"frame_no\":");
                if (frameNumber[slot] < 0) {
                    writer.write("null");
                } else {
                    writer.write(Integer.toString(frameNumber[slot]));
                }
                writer.write(",\"codec_pts_us\":");
                if (ptsUs[slot] < 0) {
                    writer.write("null");
                } else {
                    writer.write(Long.toString(ptsUs[slot]));
                }
                writer.write(",\"data\":");
                writeEventData(writer, event[slot], a[slot], b[slot], c[slot], d[slot]);
                writer.write("}\n");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to write frame timing log", e);
            return null;
        }

        Log.i(TAG, "Frame timing log written: " + file.getAbsolutePath());
        return file;
    }

    private static void writeEventData(BufferedWriter writer, int eventCode,
                                       long a, long b, long c, long d) throws java.io.IOException {
        switch (eventCode) {
            case SESSION_START:
                writer.write("{\"width\":" + a + ",\"height\":" + b +
                        ",\"fps\":" + c + ",\"frame_pacing_mode\":" + d);
                break;
            case DECODE_UNIT_SUBMIT:
                writer.write("{\"decode_unit_type\":" + a + ",\"length\":" + b +
                        ",\"native_receive_rel_us\":" + c + ",\"native_enqueue_rel_us\":" + d);
                break;
            case CODEC_INPUT_QUEUE:
                writer.write("{\"input_size\":" + a + ",\"codec_flags\":" + b +
                        ",\"decode_unit_type\":" + c + ",\"reserved\":" + d);
                break;
            case CODEC_OUTPUT:
                writer.write("{\"output_index\":" + a + ",\"flags\":" + b +
                        ",\"size\":" + c + ",\"reserved\":" + d);
                break;
            case OUTPUT_BUFFER_DROP:
                writer.write("{\"output_index\":" + a + ",\"reason_code\":" + b +
                        ",\"detail\":" + c + ",\"reserved\":" + d);
                break;
            case SURFACE_RELEASE:
                writer.write("{\"output_index\":" + a + ",\"release_mode\":" + b +
                        ",\"reserved\":" + c + ",\"render_timestamp_ns\":" + d);
                break;
            case OUTPUT_QUEUE_DROP:
                writer.write("{\"output_index\":" + a + ",\"queue_size_after_drop\":" + b +
                        ",\"queue_capacity\":" + c + ",\"reserved\":" + d);
                break;
            case NATIVE_CALLBACK_TIMING:
                writer.write("{\"native_receive_rel_us\":" + a + ",\"native_enqueue_rel_us\":" + b +
                        ",\"native_callback_rel_us\":" + c + ",\"callback_minus_enqueue_us\":" + d);
                break;
            default:
                writer.write("{\"a\":" + a + ",\"b\":" + b +
                        ",\"c\":" + c + ",\"d\":" + d);
                break;
        }
        writer.write("}");
    }

    private static String eventName(int eventCode) {
        switch (eventCode) {
            case SESSION_START: return "session_start";
            case DECODE_UNIT_SUBMIT: return "decode_unit_submit";
            case CODEC_INPUT_QUEUE: return "codec_input_queue";
            case CODEC_OUTPUT: return "codec_output";
            case OUTPUT_BUFFER_DROP: return "output_buffer_drop";
            case SURFACE_RELEASE: return "surface_release";
            case OUTPUT_QUEUE_DROP: return "output_queue_drop";
            case NATIVE_CALLBACK_TIMING: return "native_callback_timing";
            default: return "unknown";
        }
    }
}
