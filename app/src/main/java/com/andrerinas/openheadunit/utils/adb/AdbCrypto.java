package com.andrerinas.openheadunit.utils.adb;

import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

public class AdbCrypto {
    public static final int KEY_LENGTH_BITS = 2048;
    public static final int KEY_LENGTH_WORDS = 64;
    public static final byte[] SIGNATURE_PADDING;

    private static final int[] SIGNATURE_PADDING_AS_INT = {
        0, 1, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
        255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 0, 48, 33, 48, 9,
        6, 5, 43, 14, 3, 2, 26, 5, 0, 4, 20
    };

    static {
        SIGNATURE_PADDING = new byte[SIGNATURE_PADDING_AS_INT.length];
        for (int i = 0; i < SIGNATURE_PADDING_AS_INT.length; i++) {
            SIGNATURE_PADDING[i] = (byte) SIGNATURE_PADDING_AS_INT[i];
        }
    }

    private KeyPair keyPair;

    private static byte[] convertRsaPublicKeyToAdbFormat(RSAPublicKey rsaPublicKey) {
        BigInteger bit = BigInteger.ZERO.setBit(32);
        BigInteger modulus = rsaPublicKey.getModulus();
        BigInteger r32 = BigInteger.ZERO.setBit(KEY_LENGTH_BITS).modPow(BigInteger.valueOf(2L), modulus);
        BigInteger n0inv = modulus.remainder(bit).modInverse(bit);

        int[] n = new int[64];
        int[] rr = new int[64];

        for (int i = 0; i < 64; i++) {
            BigInteger[] rrDiv = r32.divideAndRemainder(bit);
            r32 = rrDiv[0];
            rr[i] = rrDiv[1].intValue();

            BigInteger[] nDiv = modulus.divideAndRemainder(bit);
            modulus = nDiv[0];
            n[i] = nDiv[1].intValue();
        }

        ByteBuffer buffer = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(64); // nwords
        buffer.putInt(n0inv.negate().intValue()); // n0inv
        for (int i = 0; i < 64; i++) {
            buffer.putInt(n[i]); // modulus
        }
        for (int i = 0; i < 64; i++) {
            buffer.putInt(rr[i]); // R^2 as little endian words
        }
        buffer.putInt(rsaPublicKey.getPublicExponent().intValue()); // exponent
        return buffer.array();
    }

    public static AdbCrypto generateAdbKeyPair() throws NoSuchAlgorithmException {
        AdbCrypto crypto = new AdbCrypto();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(KEY_LENGTH_BITS);
        crypto.keyPair = keyPairGenerator.genKeyPair();
        return crypto;
    }

    public static AdbCrypto loadAdbKeyPair(File privateKeyFile, File publicKeyFile) throws Exception {
        AdbCrypto crypto = new AdbCrypto();
        byte[] privBytes = new byte[(int) privateKeyFile.length()];
        byte[] pubBytes = new byte[(int) publicKeyFile.length()];

        try (FileInputStream privIn = new FileInputStream(privateKeyFile)) {
            privIn.read(privBytes);
        }
        try (FileInputStream pubIn = new FileInputStream(publicKeyFile)) {
            pubIn.read(pubBytes);
        }

        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        crypto.keyPair = new KeyPair(
            keyFactory.generatePublic(new X509EncodedKeySpec(pubBytes)),
            keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes))
        );
        return crypto;
    }

    public void saveAdbKeyPair(File privateKeyFile, File publicKeyFile) throws IOException {
        try (FileOutputStream privOut = new FileOutputStream(privateKeyFile)) {
            privOut.write(this.keyPair.getPrivate().getEncoded());
        }
        try (FileOutputStream pubOut = new FileOutputStream(publicKeyFile)) {
            pubOut.write(this.keyPair.getPublic().getEncoded());
        }
    }

    public byte[] getAdbPublicKeyPayload() {
        byte[] converted = convertRsaPublicKeyToAdbFormat((RSAPublicKey) this.keyPair.getPublic());
        String base64 = Base64.encodeToString(converted, Base64.NO_WRAP);
        String keyString = base64 + " OpenHU@localhost\u0000";
        return keyString.getBytes(AdbProtocol.UTF_8);
    }

    public byte[] signAdbTokenPayload(byte[] token) throws Exception {
        Cipher cipher = Cipher.getInstance("RSA/ECB/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, this.keyPair.getPrivate());
        cipher.update(SIGNATURE_PADDING);
        return cipher.doFinal(token);
    }
}
