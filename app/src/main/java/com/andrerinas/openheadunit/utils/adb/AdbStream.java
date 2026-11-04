package com.andrerinas.openheadunit.utils.adb;

import java.io.Closeable;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class AdbStream implements Closeable {
    private final AdbConnection adbConn;
    private final int localId;
    private int remoteId;
    private final Queue<byte[]> readQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean writeReady = new AtomicBoolean(false);
    private boolean isClosed = false;

    public AdbStream(AdbConnection adbConnection, int localId) {
        this.adbConn = adbConnection;
        this.localId = localId;
    }

    public void addPayload(byte[] payload) {
        synchronized (this.readQueue) {
            this.readQueue.add(payload);
            this.readQueue.notifyAll();
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            if (this.isClosed) {
                return;
            }
            notifyClose();
            this.adbConn.outputStream.write(AdbProtocol.generateClose(this.localId, this.remoteId));
            this.adbConn.outputStream.flush();
        }
    }

    public boolean isClosed() {
        return this.isClosed;
    }

    public void notifyClose() {
        this.isClosed = true;
        synchronized (this) {
            notifyAll();
        }
        synchronized (this.readQueue) {
            this.readQueue.notifyAll();
        }
    }

    public byte[] read() throws IOException, InterruptedException {
        byte[] payload = null;
        synchronized (this.readQueue) {
            while (!this.isClosed && (payload = this.readQueue.poll()) == null) {
                this.readQueue.wait();
            }
            if (payload != null) {
                return payload;
            }
            if (this.isClosed) {
                throw new IOException("Stream closed");
            }
        }
        return new byte[0];
    }

    public void readyForWrite() {
        this.writeReady.set(true);
    }

    public void sendReady() throws IOException {
        this.adbConn.outputStream.write(AdbProtocol.generateReady(this.localId, this.remoteId));
        this.adbConn.outputStream.flush();
    }

    public void updateRemoteId(int remoteId) {
        this.remoteId = remoteId;
    }

    public void write(String str) throws IOException, InterruptedException {
        if (str == null) return;
        write(str.getBytes(AdbProtocol.UTF_8), true);
    }

    public void write(byte[] data) throws IOException, InterruptedException {
        write(data, true);
    }

    public void write(byte[] data, boolean flush) throws IOException, InterruptedException {
        synchronized (this) {
            while (!this.isClosed && !this.writeReady.compareAndSet(true, false)) {
                wait();
            }
            if (this.isClosed) {
                throw new IOException("Stream closed");
            }
        }
        this.adbConn.outputStream.write(AdbProtocol.generateWrite(this.localId, this.remoteId, data));
        if (flush) {
            this.adbConn.outputStream.flush();
        }
    }
}
