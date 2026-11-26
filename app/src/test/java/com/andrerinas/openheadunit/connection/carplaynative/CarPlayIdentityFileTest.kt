package com.andrerinas.openheadunit.connection.carplaynative

import android.app.Application
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayIdentityFixtures as F
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Robolectric puts Conscrypt first, so this also runs the signing path on the provider Android uses.
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CarPlayIdentityFileTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val b64: (String) -> ByteArray = { java.util.Base64.getMimeDecoder().decode(it) }

    private fun ok(cert: String, key: String) =
        CarPlayIdentityImport.parse(cert.toByteArray(), key.toByteArray(), b64) as CarPlayIdentityImport.Result.Ok

    @Test
    fun `the stored files are DER and give a signer after a restart`() {
        val saved = ok(F.EC_PKCS7, F.EC_KEY)
        CarPlayIdentityFile.save(context, saved)
        assertArrayEquals(F.der(F.EC_PKCS7), CarPlayIdentityFile.certificateFile(context).readBytes())
        assertEquals("certificate.p7b", CarPlayIdentityFile.certificateFile(context).name)
        assertEquals("identity.pk8", CarPlayIdentityFile.keyFile(context).name)
        // Both files sit outside the cloud backup.
        for (f in listOf(CarPlayIdentityFile.certificateFile(context), CarPlayIdentityFile.keyFile(context))) {
            assertEquals(java.io.File(context.noBackupFilesDir, "carplay"), f.parentFile)
        }
        assertTrue(CarPlayIdentityFile.isValid(context, b64))
        val signer = CarPlayIdentityFile.signer(context, b64)!!
        assertEquals(3, signer.protocolMajor())
        assertEquals(64, signer.sign(MessageDigest.getInstance("SHA-256").digest(byteArrayOf(1))).size)
    }

    @Test
    fun `an RSA identity signs through Conscrypt`() {
        CarPlayIdentityFile.save(context, ok(F.RSA_PKCS7, F.RSA_KEY))
        val signer = CarPlayIdentityFile.signer(context, b64)!!
        assertEquals(2, signer.protocolMajor())
        assertEquals(256, signer.sign(MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1))).size)
    }

    @Test
    fun `a missing or damaged file is no signer`() {
        CarPlayIdentityFile.certificateFile(context).delete()
        CarPlayIdentityFile.keyFile(context).delete()
        assertNull(CarPlayIdentityFile.signer(context, b64))
        CarPlayIdentityFile.save(context, ok(F.EC_PKCS7, F.EC_KEY))
        assertNotNull(CarPlayIdentityFile.signer(context, b64))
        CarPlayIdentityFile.keyFile(context).writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(CarPlayIdentityFile.isValid(context, b64))
    }

    @Test
    fun `below API 21 nothing is stored and nothing is read`() {
        CarPlayIdentityFile.certificateFile(context).delete()
        CarPlayIdentityFile.keyFile(context).delete()
        assertFalse(CarPlayIdentityFile.save(context, ok(F.EC_PKCS7, F.EC_KEY), sdk = 20))
        assertFalse(CarPlayIdentityFile.certificateFile(context).exists())
        assertFalse(CarPlayIdentityFile.keyFile(context).exists())
        // Files a newer system left behind are not read either.
        assertTrue(CarPlayIdentityFile.save(context, ok(F.EC_PKCS7, F.EC_KEY), sdk = 21))
        assertNull(CarPlayIdentityFile.signer(context, b64, sdk = 20))
        assertFalse(CarPlayIdentityFile.isValid(context, b64, sdk = 20))
        assertFalse(CarPlayIdentityFile.present(context, sdk = 20))
        assertTrue(CarPlayIdentityFile.present(context, sdk = 21))
    }
}
