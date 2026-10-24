package com.andrerinas.openheadunit.decoder.audio;

/** Host ABI smoke for the same entry points used by the Kotlin singleton. */
public final class NativeTimeStretch {
    private native long open(int engine);
    private native void process(long handle, short[] data, int frames, double tempo);
    private native int receive(long handle, short[] data);
    private native void finish(long handle);
    private native boolean finished(long handle);
    private native void close(long handle);

    private static void rejects(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("invalid native request was accepted");
    }

    public static void main(String[] args) {
        System.loadLibrary("hur_time_stretch");
        NativeTimeStretch bridge = new NativeTimeStretch();
        rejects(() -> bridge.open(0));
        rejects(() -> bridge.open(4));
        rejects(() -> bridge.process(0, new short[960], 480, 1.0));
        rejects(() -> bridge.receive(0, new short[960]));
        rejects(() -> bridge.finish(0));
        rejects(() -> bridge.finished(0));
        for (int engine = 1; engine <= 3; engine++) {
            long handle = bridge.open(engine);
            if (handle == 0) throw new AssertionError("missing owner");
            try {
                short[] pcm = new short[960];
                // Reject bad JNI callers before touching the engine. A failed request
                // must leave this owner usable for the following valid PCM and Stop.
                rejects(() -> bridge.process(handle, null, 1, 1.0));
                rejects(() -> bridge.process(handle, pcm, -1, 1.0));
                rejects(() -> bridge.process(handle, pcm, 0, 1.0));
                rejects(() -> bridge.process(handle, pcm, 481, 1.0));
                rejects(() -> bridge.process(handle, new short[479], 240, 1.0));
                for (double tempo : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.99, 1.02}) {
                    rejects(() -> bridge.process(handle, pcm, 240, tempo));
                }
                rejects(() -> bridge.receive(handle, null));
                rejects(() -> bridge.receive(handle, new short[959]));
                java.util.Arrays.fill(pcm, (short) 1000);
                bridge.process(handle, pcm, 240, 1.0);
                bridge.finish(handle);
                int total = 0, frames;
                do {
                    frames = bridge.receive(handle, pcm);
                    if (frames < 0 || frames > 480) throw new AssertionError("invalid output size");
                    total += frames;
                } while (frames > 0);
                if (total == 0 || !bridge.finished(handle)) throw new AssertionError("undrained tail");
                bridge.finish(handle);
                if (bridge.receive(handle, pcm) != 0) throw new AssertionError("duplicate tail");
                rejects(() -> bridge.process(handle, pcm, 240, 1.0));
            } finally { bridge.close(handle); }
        }
        System.out.println("PASS JNI owner/process/receive/final/drain/close for all native engines");
    }
}
