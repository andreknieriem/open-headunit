package com.andrerinas.openheadunit.utils.adb;

import org.junit.Test;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Local protocol peers exercise approval waits without a device or an ADB daemon. */
public class AdbConnectionTimeoutTest {
    @Test(timeout = 5000) public void unrelatedWakeDoesNotRejectPendingApproval() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket peer = listener.accept();
             AdbConnection connection = AdbConnection.create(client, null)) {
            Future<Void> pending = worker.submit(() -> { connection.connect(2000); return null; });
            assertEquals(AdbProtocol.CMD_CNXN, AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream()).command);
            synchronized (connection) { connection.notifyAll(); }
            assertThrows(TimeoutException.class, () -> pending.get(50, TimeUnit.MILLISECONDS));
            peer.getOutputStream().write(AdbProtocol.generateConnect());
            peer.getOutputStream().flush();
            pending.get(1, TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    @Test(timeout = 5000) public void callerCanBoundUnansweredApproval() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket peer = listener.accept();
             AdbConnection connection = AdbConnection.create(client, null)) {
            assertThrows(IOException.class, () -> connection.connect(50));
        }
    }

    @Test(timeout = 5000) public void closedTransportEndsLongApprovalWaitPromptly() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket peer = listener.accept();
             AdbConnection connection = AdbConnection.create(client, null)) {
            Future<Void> pending = worker.submit(() -> { connection.connect(30000); return null; });
            AdbProtocol.AdbMessage.parseAdbMessage(peer.getInputStream());
            peer.close();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IOException);
        } finally { worker.shutdownNow(); }
    }
}
