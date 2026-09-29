package com.yuanbao.earbuds;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * 小米 TWS 的蓝牙定制 SAFER+（128 bit key / 8 rounds）实现。
 *
 * 完全照抄 Gadgetbridge 的开源实现，不是猜的：
 *   app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/
 *     redmibuds5pro/protocol/Authentication.java
 *     redmibuds5pro/protocol/AuthData.java
 *
 * 为什么要这个类：
 *   Redmi Buds 5 Pro 的 RFCOMM 控制通道（0000fd2d）在拿到电量前必须先完成
 *   双向挑战应答。之前几版直接发 GET_DEVICE_INFO，耳机根本不回 —— 那是
 *   "连上了但没数据" 的真正原因，不是 UUID 找错。
 */
final class XiaomiSaferAuth {

    private static final int PATTERN = 0x9999;
    private static final int BLOCK_SIZE = 16;

    /** AuthData.SEQ */
    private static final byte[] SEQ = {
            0x11, 0x22, 0x33, 0x33, 0x22, 0x11, 0x11, 0x22,
            0x33, 0x33, 0x22, 0x11, 0x11, 0x22, 0x33, 0x33
    };

    /** AuthData.COEFFICIENTS */
    private static final int[][] COEFFICIENTS = {
            {2, 1, 1, 1, 4, 2, 1, 1, 2, 2, 4, 2, 4, 4, 16, 8},
            {2, 1, 1, 1, 4, 2, 1, 1, 1, 1, 2, 1, 2, 2, 8, 4},
            {1, 1, 4, 2, 2, 2, 4, 2, 16, 8, 4, 4, 2, 1, 1, 1},
            {1, 1, 4, 2, 1, 1, 2, 1, 8, 4, 2, 2, 2, 1, 1, 1},
            {16, 8, 2, 2, 4, 2, 4, 4, 1, 1, 4, 2, 1, 1, 2, 1},
            {8, 4, 1, 1, 2, 1, 2, 2, 1, 1, 4, 2, 1, 1, 2, 1},
            {2, 2, 4, 2, 4, 4, 16, 8, 2, 1, 1, 1, 4, 2, 1, 1},
            {1, 1, 2, 1, 2, 2, 8, 4, 2, 1, 1, 1, 4, 2, 1, 1},
            {4, 2, 4, 4, 16, 8, 2, 2, 1, 1, 2, 1, 1, 1, 4, 2},
            {2, 1, 2, 2, 8, 4, 1, 1, 1, 1, 2, 1, 1, 1, 4, 2},
            {4, 4, 16, 8, 1, 1, 2, 1, 4, 2, 1, 1, 4, 2, 2, 2},
            {2, 2, 8, 4, 1, 1, 2, 1, 4, 2, 1, 1, 2, 1, 1, 1},
            {1, 1, 2, 1, 1, 1, 4, 2, 4, 4, 16, 8, 2, 2, 4, 2},
            {1, 1, 2, 1, 1, 1, 4, 2, 2, 2, 8, 4, 1, 1, 2, 1},
            {4, 2, 1, 1, 2, 1, 1, 1, 4, 2, 2, 2, 16, 8, 4, 4},
            {4, 2, 1, 1, 2, 1, 1, 1, 2, 1, 1, 1, 8, 4, 2, 2}
    };

    private final byte[][] biasMatrix = new byte[16][];
    private final byte[] expTab = new byte[256];
    private final byte[] logTab = new byte[256];

    XiaomiSaferAuth() {
        generateBiasMatrix();
        generateExpTab();
        generateLogTab();
    }

    private void generateBiasMatrix() {
        BigInteger base = BigInteger.valueOf(45);
        BigInteger mod = BigInteger.valueOf(257);
        for (int i = 0; i < 16; i++) {
            byte[] biasVec = new byte[16];
            for (int j = 0; j < 16; j++) {
                int exponent = (17 * (i + 2) + (j + 1));
                int inner = base.pow(exponent).mod(mod).intValue();
                int modExp = base.pow(inner).mod(mod).intValue();
                biasVec[j] = (byte) (modExp == 256 ? 0 : modExp);
            }
            biasMatrix[i] = biasVec;
        }
    }

    private void generateExpTab() {
        BigInteger base = BigInteger.valueOf(45);
        BigInteger mod = BigInteger.valueOf(257);
        for (int i = 0; i < 256; i++) {
            int v = base.pow(i).mod(mod).intValue();
            expTab[i] = (byte) (i == 128 ? 0 : v);
        }
    }

    private void generateLogTab() {
        BigInteger base = BigInteger.valueOf(45);
        BigInteger mod = BigInteger.valueOf(257);
        logTab[0] = (byte) 128;
        for (int i = 1; i < 256; i++) {
            int v = base.pow(i).mod(mod).intValue();
            if (v != 256) logTab[v] = (byte) i;
        }
    }

    private byte[][] keySchedule(byte[] keyInit) {
        byte[] k0 = keyInit.clone();
        k0[15] ^= 6;

        byte[][] keys = new byte[17][];
        keys[0] = k0;

        List<Byte> register = new ArrayList<>();
        for (byte b : k0) register.add(b);
        byte xor = 0;
        for (byte b : k0) xor ^= b;
        register.add(xor);

        for (int keyIdx = 1; keyIdx < keys.length; keyIdx++) {
            for (int i = 0; i < 17; i++) {
                int cur = register.get(i) & 0xff;
                int rot = (cur >>> 5) | (cur << (8 - 5));
                register.set(i, (byte) rot);
            }
            byte[] keyI = new byte[16];
            for (int i = 0; i < 16; i++) {
                keyI[i] = (byte) (register.get((keyIdx + i) % 17) + biasMatrix[keyIdx - 1][i]);
            }
            keys[keyIdx] = keyI;
        }
        return keys;
    }

    private byte[] encrypt(byte[] plaintext, byte[][] keys) {
        byte[] ciphertext = plaintext.clone();
        for (int round = 0; round < 8; round++) {
            if (round == 2) {
                for (int i = 0; i < BLOCK_SIZE; i++) {
                    if ((1 << i & PATTERN) != 0) {
                        ciphertext[i] ^= plaintext[i];
                    } else {
                        ciphertext[i] += plaintext[i];
                    }
                }
            }
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if ((1 << i & PATTERN) != 0) {
                    ciphertext[i] ^= keys[round * 2][i];
                } else {
                    ciphertext[i] += keys[round * 2][i];
                }
            }
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if ((1 << i & PATTERN) != 0) {
                    ciphertext[i] = expTab[ciphertext[i] & 0xff];
                } else {
                    ciphertext[i] = logTab[ciphertext[i] & 0xff];
                }
            }
            for (int i = 0; i < BLOCK_SIZE; i++) {
                if ((1 << i & PATTERN) != 0) {
                    ciphertext[i] = (byte) (keys[round * 2 + 1][i] + ciphertext[i]);
                } else {
                    ciphertext[i] = (byte) (keys[round * 2 + 1][i] ^ ciphertext[i]);
                }
            }
            byte[] copy = ciphertext.clone();
            for (int i = 0; i < BLOCK_SIZE; i++) {
                byte cSum = 0;
                for (int j = 0; j < BLOCK_SIZE; j++) {
                    cSum += (byte) (COEFFICIENTS[i][j] * copy[j]);
                }
                ciphertext[i] = cSum;
            }
        }
        for (int i = 0; i < BLOCK_SIZE; i++) {
            if ((1 << i & PATTERN) != 0) {
                ciphertext[i] = (byte) (keys[16][i] ^ ciphertext[i]);
            } else {
                ciphertext[i] = (byte) (keys[16][i] + ciphertext[i]);
            }
        }
        return ciphertext;
    }

    static byte[] getRandomChallenge() {
        byte[] res = new byte[BLOCK_SIZE];
        new SecureRandom().nextBytes(res);
        return res;
    }

    /** 对耳机发来的 16 字节挑战计算应答 */
    byte[] computeChallengeResponse(byte[] challenge) {
        byte[][] keys = keySchedule(challenge);
        return encrypt(SEQ, keys);
    }
}
