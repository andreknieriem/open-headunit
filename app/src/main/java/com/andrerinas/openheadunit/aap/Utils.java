package com.andrerinas.openheadunit.aap;

import com.andrerinas.openheadunit.utils.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class Utils {

    public static long ms_sleep(long ms) {

        try {
            Thread.sleep(ms);                                                // Wait ms milliseconds
            return (ms);
        } catch (InterruptedException e) {
            //Thread.currentThread().interrupt();
            e.printStackTrace();
            AppLog.e("Exception e: " + e);
            return (0);
        }
    }

    public static long tmr_ms_get() {        // Current timestamp of the most precise timer available on the local system, in nanoseconds. Equivalent to Linux's CLOCK_MONOTONIC.
        // Values returned by this method do not have a defined correspondence to wall clock times; the zero value is typically whenever the device last booted
        //AppLog.i ("ms: " + ms);           // Changing system time will not affect results.
        return (System.nanoTime() / 1000000);
    }

    public static String hex_get(byte b) {
        byte c1 = (byte) ((b & 0x00F0) >> 4);
        byte c2 = (byte) ((b & 0x000F) >> 0);

        byte[] buffer = new byte[2];

        if (c1 < 10)
            buffer[0] = (byte) (c1 + '0');
        else
            buffer[0] = (byte) (c1 + 'A' - 10);
        if (c2 < 10)
            buffer[1] = (byte) (c2 + '0');
        else
            buffer[1] = (byte) (c2 + 'A' - 10);

        return new String(buffer);
    }

    public static String hex_get(short s) {
        byte byte_lo = (byte) (s >> 0 & 0xFF);
        byte byte_hi = (byte) (s >> 8 & 0xFF);
        return (hex_get(byte_hi) + hex_get(byte_lo));
    }

    public static byte[] toByteArray(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        int buffSize = 16384 * 1024; // 16M
        byte[] data = new byte[buffSize];

        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    public static void put_time(int offset, byte[] arr, long time) {
        for (int ctr = 7; ctr >= 0; ctr--) {                           // Fill 8 bytes backwards
            arr[offset + ctr] = (byte) (time & 0xFF);
            time = time >> 8;
        }
    }

    public static void intToBytes(int value, int offset, byte[] buf) {
        buf[offset] = (byte) (value / 256);                                            // Encode length of following data:
        buf[offset+1] = (byte) (value % 256);
    }

    public static int bytesToInt(byte[] buf,int idx, boolean isShort)
    {
        if (isShort)
        {
            return ((buf[idx] & 0xFF) << 8) + (buf[idx + 1] & 0xFF);
        }
        return ((buf[idx] & 0xFF) << 24) + ((buf[idx + 1] & 0xFF) << 16) + ((buf[idx + 2] & 0xFF) << 8) + (buf[idx + 3] & 0xFF);
    }

    public static int getAccVersion(byte[] buffer)
    {
       return (buffer[1] << 8) | buffer[0];
    }
}
