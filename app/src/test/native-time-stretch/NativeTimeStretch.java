package com.andrerinas.openheadunit.decoder.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Host ABI smoke for the same entry points used by the Kotlin singleton. */
public final class NativeTimeStretch {
    private native long open(int engine);
    private native void bind(long handle, ByteBuffer input, ByteBuffer output);
    private native void process(long handle, int frames, double tempo);
    private native int receive(long handle);
    private native void finish(long handle);
    private native boolean finished(long handle);
    private native void close(long handle);
    private native long referenceOpen(int engine);
    private native void referenceProcess(long handle, short[] data, int frames, double tempo);
    private native int referenceReceive(long handle, short[] data);
    private native void referenceFinish(long handle);
    private native void referenceClose(long handle);
    private native void referenceBindFail(long handle, ByteBuffer input, ByteBuffer output,
                                          int failCall, OutOfMemoryError error);
    private native int[] referenceBindFailureCounts();

    private static ByteBuffer block(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    private static void rejects(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError("invalid native request was accepted");
    }

    private void compareOutput(long h, long reference, ByteBuffer output, short[] expected) {
        int actualFrames;
        do {
            actualFrames = receive(h);
            int expectedFrames = referenceReceive(reference, expected);
            if (actualFrames != expectedFrames) throw new AssertionError("JNI output length changed");
            for (int i = 0; i < actualFrames * 2; i++)
                if (output.getShort(i * 2) != expected[i]) throw new AssertionError("JNI PCM changed at " + i);
        } while (actualFrames > 0);
    }

    private void parity() {
        for (int engine = 1; engine <= 4; engine++) for (int pattern = 0; pattern < 4; pattern++)
            for (double speed : new double[] {0.5, 0.8, 1.0, 1.01, 1.05, 1.1, 1.25, 1.5, 2.0})
            for (int length : new int[] {1, 479, 480, 3072, 24000}) {
                long h = open(engine), reference = referenceOpen(engine);
                try {
                    ByteBuffer input = block(1920), output = block(1920);
                    short[] source = new short[960], expected = new short[960];
                    bind(h, input, output);
                    int position = 0;
                    while (position < length) {
                        int frames = Math.min(length - position, new int[] {17, 480, 239}[position % 3]);
                        for (int i = 0; i < frames * 2; i++) {
                            short value = (short) (pattern == 0 ? 0 : pattern == 1 ? (i % 2 == 0 ? 32767 : -32768) :
                                pattern == 2 ? (int) (Math.sin((position + i / 2) * .091) * 22000) * (i % 2 == 0 ? 1 : -1) :
                                ((position * 17 + i * 8191) ^ 0x25d7));
                            source[i] = value;
                            input.putShort(i * 2, value);
                        }
                        double tempo = position < length / 2 ? speed : 2.5 - speed;
                        process(h, frames, tempo);
                        referenceProcess(reference, source, frames, tempo);
                        compareOutput(h, reference, output, expected);
                        position += frames;
                    }
                    finish(h); referenceFinish(reference);
                    compareOutput(h, reference, output, expected);
                    if (!finished(h)) throw new AssertionError("retained tail");
                } finally { close(h); referenceClose(reference); }
            }
        System.out.println("PASS 720 actual-JNI array/direct PCM and output-length comparisons");
    }

    private java.lang.ref.WeakReference<ByteBuffer> bindTemporaryBuffers(long h) {
        ByteBuffer input = block(1920), output = block(1920);
        bind(h, input, output);
        return new java.lang.ref.WeakReference<>(output);
    }

    private void bufferLifetime() {
        long h = open(1);
        try {
            java.lang.ref.WeakReference<ByteBuffer> output = bindTemporaryBuffers(h);
            for (int i = 0; i < 3; i++) { System.gc(); }
            if (output.get() == null) throw new AssertionError("bound native storage was collected");
            process(h, 480, 1.0); finish(h);
            while (receive(h) > 0) {}
            if (!finished(h)) throw new AssertionError("GC interrupted final drain");
        } finally { close(h); }
        // Closing an owner that never bound buffers is valid too.
        close(open(1));
    }

    private void bindAllocationFailures() {
        int cases = 0;
        for (int engine = 1; engine <= 4; engine++)
            for (int failCall = 1; failCall <= 2; failCall++)
                for (boolean pendingOome : new boolean[] {false, true})
                    for (boolean recover : new boolean[] {false, true}) {
                        long h = open(engine);
                        try {
                            ByteBuffer input = block(1920), output = block(1920);
                            // Allocate the throwable before injection, never by exhausting
                            // the JVM heap or calling an allocator with an exception pending.
                            OutOfMemoryError marker = pendingOome
                                ? new OutOfMemoryError("injected NewGlobalRef " + failCall) : null;
                            try {
                                referenceBindFail(h, input, output, failCall, marker);
                                throw new AssertionError("failed NewGlobalRef accepted");
                            } catch (OutOfMemoryError actual) {
                                if (actual != marker)
                                    throw new AssertionError("pending OOME was replaced", actual);
                            } catch (IllegalStateException actual) {
                                if (marker != null)
                                    throw new AssertionError("pending OOME was translated", actual);
                            }
                            int[] counts = referenceBindFailureCounts();
                            int retained = failCall - 1;
                            int[] expected = {failCall, retained, retained, 0};
                            if (!java.util.Arrays.equals(counts, expected))
                                throw new AssertionError("NewGlobalRef ownership counts: "
                                    + java.util.Arrays.toString(counts) + ", expected "
                                    + java.util.Arrays.toString(expected));
                            // Neither pointer may be committed, even if the first global
                            // reference succeeded. These are real production entry points.
                            rejects(() -> process(h, 480, 1.0));
                            rejects(() -> receive(h));
                            if (recover) {
                                // A retained partial owner also fails this normal rebind.
                                bind(h, input, output);
                                for (int i = 0; i < 960; i++)
                                    input.putShort(i * 2, (short) (i * 31 - 14000));
                                process(h, 480, 1.0);
                                finish(h);
                                int total = 0;
                                boolean drained = false;
                                // One unity-tempo input block must drain in a bounded
                                // number of receives; a broken tail cannot hang the suite.
                                for (int i = 0; i < 4; i++) {
                                    int frames = receive(h);
                                    if (frames < 0 || frames > 480)
                                        throw new AssertionError("invalid recovered output size");
                                    total += frames;
                                    if (frames == 0) { drained = true; break; }
                                }
                                if (!drained || total != 480 || !finished(h))
                                    throw new AssertionError("failed-bind recovery lost final audio");
                            }
                            cases++;
                        } finally {
                            // Exercise both immediate close after failure and close after
                            // successful recovery. A stale reference must not be deleted twice.
                            close(h);
                        }
                    }
        System.out.println("PASS " + cases + " actual-JNI global-reference failure/ownership/recovery cases");
    }

    public static void main(String[] args) {
        System.loadLibrary("hur_time_stretch");
        System.loadLibrary("hur_time_stretch_reference");
        NativeTimeStretch bridge = new NativeTimeStretch();
        bridge.parity();
        bridge.bufferLifetime();
        bridge.bindAllocationFailures();
        rejects(() -> bridge.open(0));
        rejects(() -> bridge.open(5));
        rejects(() -> bridge.process(0, 480, 1.0));
        rejects(() -> bridge.receive(0));
        rejects(() -> bridge.finish(0));
        rejects(() -> bridge.finished(0));
        for (int engine = 1; engine <= 4; engine++) {
            long handle = bridge.open(engine);
            if (handle == 0) throw new AssertionError("missing owner");
            try {
                ByteBuffer pcm = block(1920);
                ByteBuffer output = block(1920);
                // Failed binding must leave the owner unbound and reusable. No PCM
                // can enter before both fixed buffers have been validated and retained.
                rejects(() -> bridge.process(handle, 240, 1.0));
                rejects(() -> bridge.receive(handle));
                rejects(() -> bridge.bind(handle, null, output));
                rejects(() -> bridge.bind(handle, pcm, null));
                rejects(() -> bridge.bind(handle, block(1918), output));
                rejects(() -> bridge.bind(handle, pcm, block(1918)));
                rejects(() -> bridge.bind(handle, ByteBuffer.allocate(1920), output));
                rejects(() -> bridge.bind(handle, pcm, ByteBuffer.allocate(1920)));
                rejects(() -> bridge.bind(handle, pcm, output.asReadOnlyBuffer()));
                ByteBuffer unaligned = block(1921);
                unaligned.position(1);
                rejects(() -> bridge.bind(handle, unaligned.slice(), output));
                rejects(() -> bridge.bind(handle, pcm, unaligned.slice()));
                bridge.bind(handle, pcm, output);
                rejects(() -> bridge.bind(handle, pcm, output));
                rejects(() -> bridge.process(handle, -1, 1.0));
                rejects(() -> bridge.process(handle, 0, 1.0));
                rejects(() -> bridge.process(handle, 481, 1.0));
                for (double tempo : new double[] {Double.NaN, Double.POSITIVE_INFINITY, 0.49, 2.01}) {
                    rejects(() -> bridge.process(handle, 240, tempo));
                }
                for (int i = 0; i < 960; i++) pcm.putShort(i * 2, (short) 1000);
                bridge.process(handle, 240, 1.0);
                bridge.finish(handle);
                int total = 0, frames;
                do {
                    frames = bridge.receive(handle);
                    if (frames < 0 || frames > 480) throw new AssertionError("invalid output size");
                    total += frames;
                } while (frames > 0);
                if (total == 0 || !bridge.finished(handle)) throw new AssertionError("undrained tail");
                bridge.finish(handle);
                if (bridge.receive(handle) != 0) throw new AssertionError("duplicate tail");
                rejects(() -> bridge.process(handle, 240, 1.0));
            } finally { bridge.close(handle); }
        }
        System.out.println("PASS JNI owner/process/receive/final/drain/close for all native engines");
    }
}
