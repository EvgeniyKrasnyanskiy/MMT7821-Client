package com.openmedtronic.guardian;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class GuardianCrypto {

    // Default 128-bit protocol key for MMT-7821 packet framing
    public static final byte[] MASTER_KEY = new byte[] {
        (byte) 0xaf, (byte) 0xbc, (byte) 0xf4, (byte) 0xaf,
        (byte) 0x44, (byte) 0x59, (byte) 0xf8, (byte) 0x70,
        (byte) 0x7a, (byte) 0x17, (byte) 0xda, (byte) 0xd2,
        (byte) 0x42, (byte) 0x12, (byte) 0x32, (byte) 0xff
    };

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private GuardianCrypto() {}

    public static byte[] generateRandom16() {
        byte[] bytes = new byte[16];
        SECURE_RANDOM.nextBytes(bytes);
        return bytes;
    }

    public static byte[] encryptAesCtr(byte[] data, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES encryption failed: " + e.getMessage(), e);
        }
    }

    public static byte[] decryptAesCtr(byte[] data, byte[] key, byte[] iv) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("AES decryption failed: " + e.getMessage(), e);
        }
    }

    /**
     * CCITT-False CRC-16 checksum algorithm (poly=0x1021, init=0xFFFF)
     */
    public static int calcCrc16(byte[] bArr) {
        if (bArr == null) return 0;
        int i2 = 0xFFFF;
        for (byte b2 : bArr) {
            int i3 = (((i2 << 8) | (i2 >>> 8)) & 0xFFFF) ^ (b2 & 0xFF);
            int i4 = i3 ^ ((i3 & 0xFF) >> 4);
            int i5 = i4 ^ ((i4 << 12) & 0xFFFF);
            i2 = i5 ^ (((i5 & 0xFF) << 5) & 0xFFFF);
        }
        return i2 & 0xFFFF;
    }

    /**
     * Builds the 36-byte pairing payload sent to CRITICAL_OPS (f53cbf20-...)
     * [0] = 0x00 (Opcode 0x00)
     * [1] = 0x07 (Security level: GLUCOSE_AND_RATE_OF_CHANGE)
     * [2] = 0x01 (Married / persistent flag)
     * [3] = 0x01 (Security mode: ENCRYPTED)
     * [4..19] = 16 bytes IV
     * [20..35] = 16 bytes encrypted AppKey (encrypted with MASTER_KEY via AES-CTR)
     */
    public static byte[] buildSecurityPacket(byte[] appKey, byte[] ivPair) {
        byte[] encAppKey = encryptAesCtr(appKey, MASTER_KEY, ivPair);
        byte[] packet = new byte[36];
        packet[0] = 0x00; // Opcode 0
        packet[1] = 0x07; // Level: GLUCOSE_AND_RATE_OF_CHANGE
        packet[2] = 0x01; // Married
        packet[3] = 0x01; // Encrypted
        System.arraycopy(ivPair, 0, packet, 4, 16);
        System.arraycopy(encAppKey, 0, packet, 20, 16);
        return packet;
    }

    /**
     * Computes HMAC-SHA256 challenge response for pairing/session handshake.
     * Key is ALWAYS the APP_KEY (never MASTER_KEY).
     * HMAC = HMAC-SHA256(appKey, step1Packet || nonce_16bytes)
     */
    public static byte[] computeChallengeResponse(byte[] appKey, byte[] step1Packet, byte[] nonce) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appKey, "HmacSHA256"));
            mac.update(step1Packet);
            mac.update(nonce);
            byte[] hmac = mac.doFinal();
            byte[] response = new byte[33];
            response[0] = 0x03; // Opcode 3
            System.arraycopy(hmac, 0, response, 1, 32);
            return response;
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed: " + e.getMessage(), e);
        }
    }

    public static byte[] buildTimeSyncPacket(long epochSeconds) {
        byte[] packet = new byte[9];
        packet[0] = 0x06; // Opcode 6
        // packet[1..4] = 0 (int32 seconds = 0)
        packet[5] = (byte) (epochSeconds & 0xFF);
        packet[6] = (byte) ((epochSeconds >> 8) & 0xFF);
        packet[7] = (byte) ((epochSeconds >> 16) & 0xFF);
        packet[8] = (byte) ((epochSeconds >> 24) & 0xFF);
        return packet;
    }

    public static byte[] buildCalibrationPacket(int bgMgDl, long epochSeconds) {
        ByteBuffer buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x04); // Opcode 0x04
        buf.putShort((short) bgMgDl); // uint16 bg in mg/dL
        buf.putInt((int) epochSeconds); // uint32 epoch in seconds
        buf.put((byte) 0); // boolean flag = 0
        return buf.array();
    }

    public static byte[] buildSensorConfigurationPacket(boolean isNewSensor) {
        return new byte[] { 0x05, (byte) (isNewSensor ? 1 : 0) };
    }

    public static byte[] buildBackfillRequestPacket(long startCounter, long endCounter) {
        ByteBuffer buf = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 0x08);
        buf.putInt((int) startCounter);
        buf.putInt((int) endCounter);
        return buf.array();
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString().trim();
    }

    public static String bytesToHexNoSpace(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String s) {
        if (s == null || s.length() % 2 != 0) return new byte[0];
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int hi = Character.digit(s.charAt(i), 16);
            int lo = Character.digit(s.charAt(i + 1), 16);
            if (hi == -1 || lo == -1) return new byte[0];
            data[i / 2] = (byte) ((hi << 4) + lo);
        }
        return data;
    }
}
