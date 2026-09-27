package com.andrerinas.openheadunit.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {
    fun generateQrCode(content: String, size: Int): Bitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            AppLog.e("QrCodeGenerator: Failed to generate QR code: ${e.message}", e)
            null
        } catch (e: LinkageError) {
            // ZXing 3.5 needs StandardCharsets, which Android added in API 19; below that it throws an Error.
            AppLog.w("QrCodeGenerator: QR codes cannot be drawn on this Android version (${e.javaClass.simpleName})")
            null
        }
    }
}
