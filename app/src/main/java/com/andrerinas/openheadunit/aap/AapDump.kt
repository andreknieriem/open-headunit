package com.andrerinas.openheadunit.aap

import android.util.Log
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.AppLog
import java.util.Locale

internal object AapDump {
    private val MAX_HEX_DUMP = 64//32;
    private val HD_MW = 16

    fun logd(prefix: String, src: String, chan: Int, flags: Int, buf: ByteArray, len: Int): Int {
        // Source:  HU = HU > AA   AA = AA > HU

        if (!AppLog.LOG_DEBUG) {
            return 0
        }

        if (len < 2) {
            // If less than 2 bytes, needed for msg_type...
            AppLog.e("hu_aad_dmp len: %d", len)
            return 0
        }

        var rmv = 0
        var lft = len
        val msg_type = (buf[0].toInt() shl 8) + buf[1].toInt() and 0xFFFF

        var is_media = false
        if (Channel.isVideo(chan) || chan == Channel.ID_MIC || Channel.isAudio(chan))
            is_media = true

        if (is_media && (flags == 8 || flags == 0x0a || msg_type == 0))
        // || msg_type ==1)    // Ignore Video/Audio Data
            return rmv

        if (is_media && msg_type == 32768 + 4)
        // Ignore Video/Audio Ack
            return rmv

        // msg_type = 2 bytes
        rmv += 2
        lft -= 2

        val msg_type_str = iaad_msg_type_str_get(msg_type, src[0], lft)   // Get msg_type string
        if (flags == 0x08)
            Log.d(AppLog.TAG, String.format("%s src: %s  lft: %5d  Media Data Mid", prefix, src, lft))
        else if (flags == 0x0a)
            Log.d(AppLog.TAG, String.format("%s src: %s  lft: %5d  Media Data End", prefix, src, lft))
        else
            Log.d(AppLog.TAG, String.format("%s src: %s  lft: %5d  msg_type: %5d %s", prefix, src, lft, msg_type, msg_type_str))

        logvHex(prefix, 2, buf, lft)                                  // Hexdump

        if (flags == 0x08)
        // If Media Data Mid...
            return len                                                     // Done

        if (flags == 0x0a)
        // If Media Data End...
            return len                                                     // Done

        if (msg_type_enc_get(msg_type) == null) {                          // If encrypted data...
            return len                                                     // Done
        }

        if (lft == 0)
        // If no content
            return rmv                                                     // Done

        if (lft < 2) {                                                      // If less than 2 bytes for content (impossible if content; need at least 1 byte for 0x08 and 1 byte for varint)
            AppLog.e("hu_aad_dmp len: %d  lft: %d", len, lft)
            return rmv                                                     // Done with error
        }

        //adj = iaad_dmp_n(1, 1, buf, lft);                                  // Dump the content w/ n=1

        // iaad_adj(&rmv, &buf, &lft, adj);                                // Adjust past the content (to nothing, presumably)

        //        if (lft != 0 || rmv != len || rmv < 0)                              // If content left... (must be malformed)
        //            AppLog.e ("hu_aad_dmp after content len: %d  lft: %d  rmv: %d  buf: %p", len, lft, rmv, buf);

        AppLog.i("--------------------------------------------------------")  // Empty line / 56 characters

        return rmv
    }


    private val MSG_TYPE_32 = 32768
    private fun iaad_msg_type_str_get(msg_type: Int, src: Char, len: Int): String {   // Source:  HU = HU > AA   AA = AA > HU

        when (msg_type) {
            0 -> return "Media Data"
            1 -> {
                if (src == 'H')
                    return "Version Request"    // Start AA Protocol
                else if (src == 'A')
                    return "Codec Data"         // First Video packet, respond with Media Ack
                else
                    return "1 !"
            }
            2 -> return "Version Response"
            3 -> return "SSL Handshake Data"                          // First/Request from HU, Second/Response from AA
        //case 5123:return ("SSL Change Cipher Spec");                      // 0x1403
        //case 5379:return ("SSL Alert");                                   // 0x1503
        //case 5635:return ("SSL Handshake");                               // 0x1603
        //case 5891:return ("SSL App Data");                                // 0x1703
            4 -> return "SSL Authentication Complete Notification"
            5 -> return "Service Discovery Request"
            6 -> return "Service Discovery Response"
            7 -> return "Channel Open Request"
            8 -> return "Channel Open Response"                       // byte:status
            9 -> return "9 ??"
            10 -> return "10 ??"
            11 -> return "Ping Request"
            12 -> return "Ping Response"
            13 -> return "Navigation Focus Request"
            14 -> return "Navigation Focus Notification"               // NavFocusType
            15 -> return "Byebye Request"
            16 -> return "Byebye Response"
            17 -> return "Voice Session Notification"
            18 -> return "Audio Focus Request"
            19 -> return "Audio Focus Notification"                    // AudioFocusType   (AudioStreamType ?)

            MSG_TYPE_32// + 0:
            -> return "Media Setup Request"                        // Video and Audio sinks receive this and send k3 3 / 32771
            MSG_TYPE_32 + 1 -> {
                if (src == 'H')
                    return "Touch Notification"
                else if (src == 'A')
                    return "Sensor/Media Start Request"
                else
                    return "32769 !"            // src AA also Media Start Request ????
            }
            MSG_TYPE_32 + 2 -> {
                if (src == 'H')
                    return "Sensor Start Response"
                else if (src == 'A')
                    return "Touch/Input/Audio Start/Stop Request"
                else
                    return "32770 !"
            }
            MSG_TYPE_32 + 3 -> {
                if (len == 6)
                    return "Media Setup Response"
                else if (len == 2)
                    return "Key Binding Response"
                else
                    return "Sensor Notification"
            }
            MSG_TYPE_32 + 4 -> return "Codec/Media Data Ack"
            MSG_TYPE_32 + 5 -> return "Mic Start/Stop Request"
            MSG_TYPE_32 + 6 -> return "k3 6 ?"
            MSG_TYPE_32 + 7 -> return "Media Video ? Request"
            MSG_TYPE_32 + 8 -> return "Video Focus Notification"

            65535 -> return "Framing Error Notification"
        }
        return "Unknown"
    }

    private fun msg_type_enc_get(msg_type: Int): String? {

        when (msg_type) {
            5123 -> return "SSL Change Cipher Spec"                    // 0x1403
            5379 -> return "SSL Alert"                                 // 0x1503
            5635 -> return "SSL Handshake"                             // 0x1603
            5891 -> return "SSL App Data"                              // 0x1703
        }
        return null
    }

    fun logvHex(prefix: String, start: Int, buf: ByteArray, len: Int) {

        if (!AppLog.LOG_VERBOSE) {
            return
        }

        Log.v(AppLog.TAG, logHex(prefix, start, buf, len, StringBuilder()).toString())
    }

    fun logHex(message: AapMessage): String {
        return AapDump.logHex("", 0, message.data, message.size, StringBuilder()).toString()
    }

    fun logHex(prefix: String, start: Int, buf: ByteArray, length: Int, sb: StringBuilder): StringBuilder {
        var len = length

        if (len + start > MAX_HEX_DUMP)
            len = MAX_HEX_DUMP + start

        var i: Int = start
        var n: Int

        sb.append(prefix)
        var line = String.format(Locale.US, " %08d ", 0)
        sb.append(line)

        n = 1
        while (i < len) {                           // i keeps incrementing, n gets reset to 0 each line

            val hex = String.format(Locale.US, "%02X ", buf[i])
            sb.append(hex)

            if (n == HD_MW) {                                                 // If at specified line width
                n = 0                                                          // Reset position in line counter
                Log.v(AppLog.TAG, sb.toString())                                                    // Log line

                sb.setLength(0)

                sb.append(prefix)
                line = String.format(Locale.US, "     %04d ", i + 1)
                sb.append(line)
            }
            i++
            n++
        }

        return sb
    }
}
