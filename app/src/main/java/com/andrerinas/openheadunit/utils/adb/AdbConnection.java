package com.andrerinas.openheadunit.utils.adb;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Socket;
import java.util.HashMap;

public class AdbConnection implements Closeable {
    private boolean connectAttempted = false;
    private boolean connected = false;
    private AdbCrypto crypto;
    private InputStream inputStream;
    OutputStream outputStream;
    private boolean sentSignature = false;
    private Socket socket;
    private final HashMap<Integer, AdbStream> openStreams = new HashMap<>();
    private int lastLocalId = 0;
    private int maxData = 4096;
    private final Thread connectionThread;

    private AdbConnection() {
        this.connectionThread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectionLoop();
            }
        }, "OpenHU-SelfAdb-Thread");
    }

    public static AdbConnection create(Socket socket, AdbCrypto adbCrypto) throws IOException {
        AdbConnection adbConnection = new AdbConnection();
        adbConnection.crypto = adbCrypto;
        adbConnection.socket = socket;
        adbConnection.inputStream = socket.getInputStream();
        adbConnection.outputStream = socket.getOutputStream();
        socket.setTcpNoDelay(true);
        return adbConnection;
    }

    private void cleanupStreams() {
        for (AdbStream stream : this.openStreams.values()) {
            try {
                stream.notifyClose();
            } catch (Exception ignored) {
            }
        }
        this.openStreams.clear();
    }

    private void connectionLoop() {
        try {
            while (!this.connectionThread.isInterrupted()) {
                AdbProtocol.AdbMessage msg = AdbProtocol.AdbMessage.parseAdbMessage(this.inputStream);
                if (!AdbProtocol.validateMessage(msg)) {
                    continue;
                }

                switch (msg.command) {
                    case AdbProtocol.CMD_OKAY:
                    case AdbProtocol.CMD_WRTE:
                    case AdbProtocol.CMD_CLSE: {
                        if (!this.connected) {
                            continue;
                        }
                        AdbStream stream;
                        synchronized (this.openStreams) {
                            stream = this.openStreams.get(msg.arg1);
                        }
                        if (stream == null) {
                            continue;
                        }

                        synchronized (stream) {
                            if (msg.command == AdbProtocol.CMD_OKAY) {
                                stream.updateRemoteId(msg.arg0);
                                stream.readyForWrite();
                                stream.notifyAll();
                            } else if (msg.command == AdbProtocol.CMD_WRTE) {
                                stream.addPayload(msg.payload);
                                stream.sendReady();
                            } else if (msg.command == AdbProtocol.CMD_CLSE) {
                                synchronized (this.openStreams) {
                                    this.openStreams.remove(msg.arg1);
                                }
                                stream.notifyClose();
                            }
                        }
                        break;
                    }

                    case AdbProtocol.CMD_AUTH: {
                        if (msg.arg0 == AdbProtocol.AUTH_TYPE_TOKEN) {
                            byte[] authPayload;
                            if (this.sentSignature) {
                                authPayload = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_RSA_PUBLIC, this.crypto.getAdbPublicKeyPayload());
                            } else {
                                authPayload = AdbProtocol.generateAuth(AdbProtocol.AUTH_TYPE_SIGNATURE, this.crypto.signAdbTokenPayload(msg.payload));
                                this.sentSignature = true;
                            }
                            this.outputStream.write(authPayload);
                            this.outputStream.flush();
                        }
                        break;
                    }

                    case AdbProtocol.CMD_CNXN: {
                        synchronized (this) {
                            this.maxData = msg.arg1;
                            this.connected = true;
                            notifyAll();
                        }
                        break;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            synchronized (this) {
                cleanupStreams();
                this.connected = false;
                this.connectAttempted = false;
                notifyAll();
            }
        }
    }

    public void connect() throws IOException, InterruptedException {
        connect(8000);
    }

    /** Interactive setup can allow more time for RSA approval without changing other callers. */
    public void connect(long timeoutMs) throws IOException, InterruptedException {
        if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
        if (this.connected) {
            throw new IllegalStateException("Already connected");
        }
        this.outputStream.write(AdbProtocol.generateConnect());
        this.outputStream.flush();
        this.connectAttempted = true;
        this.connectionThread.start();

        synchronized (this) {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while (!this.connected && this.connectAttempted) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this, remaining);
            }
            if (!this.connected) {
                throw new IOException("Self-ADB Connection to localhost:5555 timed out or failed");
            }
        }
    }

    public AdbStream open(String destination) throws IOException, InterruptedException {
        int localId = ++this.lastLocalId;
        if (!this.connectAttempted) {
            throw new IllegalStateException("connect() must be called first");
        }
        synchronized (this) {
            if (!this.connected) {
                throw new IOException("Not connected to ADB server");
            }
        }

        AdbStream stream = new AdbStream(this, localId);
        synchronized (this.openStreams) {
            this.openStreams.put(localId, stream);
        }

        this.outputStream.write(AdbProtocol.generateOpen(localId, destination));
        this.outputStream.flush();

        synchronized (stream) {
            stream.wait(5000);
        }

        if (stream.isClosed()) {
            throw new ConnectException("Stream open actively rejected by remote peer");
        }
        return stream;
    }

    @Override
    public void close() {
        try {
            if (this.socket != null) {
                this.socket.close();
            }
        } catch (Exception ignored) {}
        this.connectionThread.interrupt();
        try {
            this.connectionThread.join(2000);
        } catch (InterruptedException ignored) {}
    }
}
