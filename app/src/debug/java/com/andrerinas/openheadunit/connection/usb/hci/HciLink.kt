package com.andrerinas.openheadunit.connection.usb.hci

/** One controller session: commands go out, events come back. */
interface HciLink {
    /** Returns the transfer result, which equals the command size on success. */
    fun send(cmd: ByteArray): Int

    /** The next event, or null once [timeoutMs] passes. */
    fun read(timeoutMs: Int): HciCodec.Event?
}
