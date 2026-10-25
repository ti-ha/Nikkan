// SPDX-License-Identifier: LGPL-3.0-only
// Ported from edge-tts (https://github.com/rany2/edge-tts, src/edge_tts/drm.py),
// licensed under the GNU LGPL v3. This file stays under that licence.
package com.nhkdaily;

import java.nio.charset.StandardCharsets;

/**
 * The "Sec-MS-GEC" token Microsoft Edge's Read Aloud service requires.
 * A direct port of edge-tts 7.3.1 (github.com/rany2/edge-tts, drm.py,
 * LGPL-3.0), checked against its output for 2,000 timestamps. If Microsoft changes the
 * scheme, update from that project; the app falls back to other voices
 * meanwhile.
 */
final class EdgeToken {

    static final String TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4";
    static final String CHROMIUM_FULL_VERSION = "143.0.3650.75";
    static final String SEC_MS_GEC_VERSION = "2-" + CHROMIUM_FULL_VERSION;
    private static final long WIN_EPOCH = 11644473600L;
    private static final long M = 0xFFFFFFFFL;

    private static final long[] SHA_INITIAL = {
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L,
    };
    private static final long[] SHA_STATE = {
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L,
    };
    private static final long[] SHA_SCHEDULE = {
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L,
    };
    private static final long[] K = {
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L,
    };
    private static final long[] CS64_COEF = {
            0xE82E91B1L, 0x44DE24C6L, 0x3BEB65BCL, 0xC89C5933L,
            0x8D1EC211L, 0x314EF5D5L, 0xE92491B1L, 0xF0A5B9D5L,
            0x06064CF3L, 0xDA79FA60L, 0xBED20560L, 0x89F9C9F8L,
            0x35FA38C6L, 0xCB49E3C6L,
    };
    private static final long[] CS64_OFF = {
            0xB32700D0L, 0x88DFF51DL, 0x29514038L, 0xB328CBDFL,
            0xD405B4D5L, 0x26E55F3CL, 0x607801C6L, 0x82B2C1FBL,
            0xFA51A3DAL, 0x207CC464L, 0x3C3C74F3L, 0xD2D14B61L,
            0xBB04A2C1L, 0xC5626167L,
    };
    private static final long SHA_ADJ = 0xC9DC06A7L, SIGMA0_ADJ = 0x059C0B80L, SIGMA1_ADJ = 0x000B92C0L;
    private static final long CS64_FACTOR = 0x82893933L, CS64_ADJ0 = 0xF2F90B1AL, CS64_ADJ1 = 0x0B1A0000L;

    private EdgeToken() {
    }

    /** Token for the given Unix time in seconds. */
    static String generate(long unixSeconds) {
        long secs = unixSeconds + WIN_EPOCH;
        secs -= secs % 300;
        String toHash = (secs * 10_000_000L) + TRUSTED_CLIENT_TOKEN;
        byte[] data = toHash.getBytes(StandardCharsets.US_ASCII);
        byte[] digest = sha256(data);
        long[] cs = cs64(data, digest);
        byte[] csBytes = new byte[8];
        putLe(csBytes, 0, cs[0]);
        putLe(csBytes, 4, cs[1]);
        return hex(csBytes) + "-" + hex(digest);
    }

    // ---- SHA-256 over a secret prefix (its state is baked into the tables) ----

    private static long rotr(long x, int n) {
        return ((x >>> n) | (x << (32 - n))) & M;
    }

    private static long sigma0(long v) {
        return ((v >>> 7) ^ (v << 25) ^ (v >>> 18) ^ (v << 14) ^ (v >>> 3)) & M;
    }

    private static long sigma1(long v) {
        return ((v >>> 17) ^ (v << 15) ^ (v >>> 19) ^ (v << 13) ^ (v >>> 10)) & M;
    }

    private static long[] rounds(long[] state, long[] w, int first) {
        long a = state[0], b = state[1], c = state[2], d = state[3];
        long e = state[4], f = state[5], g = state[6], h = state[7];
        for (int i = first; i < 64; i++) {
            long k = (first == 14 && i == 14) ? SHA_ADJ : K[i];
            long s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            long ch = g ^ (e & (f ^ g));
            long t1 = (h + s1 + ch + k + w[i]) & M;
            long s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            long maj = (a & b) | (c & (a | b));
            long t2 = (s0 + maj) & M;
            h = g;
            g = f;
            f = e;
            e = (d + t1) & M;
            d = c;
            c = b;
            b = a;
            a = (t1 + t2) & M;
        }
        return new long[]{a, b, c, d, e, f, g, h};
    }

    private static void expand(long[] w, int firstWord) {
        for (int i = firstWord; i < 64; i++) {
            w[i] = (sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) & M;
        }
    }

    private static long[] init(byte[] data) {
        long[] w = new long[64];
        w[14] = ((data[0] & 0xFFL) << 16) | ((data[1] & 0xFFL) << 8) | (data[2] & 0xFFL);
        w[15] = ((data[3] & 0xFFL) << 24) | ((data[4] & 0xFFL) << 16)
                | ((data[5] & 0xFFL) << 8) | (data[6] & 0xFFL);
        for (int i = 16; i < 30; i++) {
            long v = SHA_SCHEDULE[i - 16];
            if (i - 2 >= 14) v += i == 16 ? (sigma1(w[i - 2]) ^ SIGMA1_ADJ) : sigma1(w[i - 2]);
            if (i - 7 >= 14) v += w[i - 7];
            if (i - 15 >= 14) v += i == 29 ? (sigma0(w[i - 15]) ^ SIGMA0_ADJ) : sigma0(w[i - 15]);
            w[i] = v & M;
        }
        w[30] = (sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA_ADJ - K[14]) & M;
        expand(w, 31);
        long[] working = rounds(SHA_STATE, w, 14);
        long[] out = new long[8];
        for (int i = 0; i < 8; i++) out[i] = (SHA_INITIAL[i] + working[i]) & M;
        return out;
    }

    private static byte[] sha256(byte[] data) {
        int length = 57 + data.length;
        int zeros = Math.floorMod(55 - length, 64);
        int restLen = data.length - 7;
        byte[] padded = new byte[restLen + 1 + zeros + 8];
        System.arraycopy(data, 7, padded, 0, restLen);
        padded[restLen] = (byte) 0x80;
        long bits = (long) length * 8;
        for (int i = 0; i < 8; i++) padded[padded.length - 1 - i] = (byte) (bits >>> (8 * i));

        long[] state = init(data);
        for (int off = 0; off < padded.length; off += 64) {
            long[] w = new long[64];
            for (int i = 0; i < 16; i++) {
                int p = off + i * 4;
                w[i] = ((padded[p] & 0xFFL) << 24) | ((padded[p + 1] & 0xFFL) << 16)
                        | ((padded[p + 2] & 0xFFL) << 8) | (padded[p + 3] & 0xFFL);
            }
            expand(w, 16);
            long[] working = rounds(state, w, 0);
            for (int i = 0; i < 8; i++) state[i] = (state[i] + working[i]) & M;
        }
        byte[] out = new byte[32];
        for (int i = 0; i < 8; i++) {
            out[i * 4] = (byte) (state[i] >>> 24);
            out[i * 4 + 1] = (byte) (state[i] >>> 16);
            out[i * 4 + 2] = (byte) (state[i] >>> 8);
            out[i * 4 + 3] = (byte) state[i];
        }
        return out;
    }

    // ---- CS64 ------------------------------------------------------------------

    private static long rol16(long v) {
        v &= M;
        return ((v << 16) | (v >>> 16)) & M;
    }

    private static long[] cs64(byte[] data, byte[] digest) {
        long keyA = getLe(digest, 0) | 1;
        long keyB = getLe(digest, 4) | 1;
        long[] factors = {(keyA * CS64_FACTOR) & M, (keyB * CS64_FACTOR) & M};

        // little-endian words of (0x00 + data), zero-padded, even count
        int n = 1 + data.length;
        int padded = (n + 3) / 4 * 4;
        byte[] buf = new byte[padded];
        System.arraycopy(data, 0, buf, 1, data.length);
        int words = padded / 4;
        if (words % 2 == 1) words++;
        int total = CS64_COEF.length + words;
        long[] terms = new long[total];
        long[] offsets = new long[total];
        for (int i = 0; i < CS64_COEF.length; i++) {
            terms[i] = (CS64_COEF[i] * factors[i % 2]) & M;
            offsets[i] = CS64_OFF[i];
        }
        for (int i = 0; i < words; i++) {
            long word = i * 4 < padded ? getLe(buf, i * 4) : 0;
            long term = word * (i % 2 == 0 ? keyA : keyB);
            long offset = rol16(word) * (i % 2 == 0 ? 0xEF0569FBL : 0xC31713DBL);
            if (i == 0) {
                term += CS64_ADJ0 * factors[0];
                offset += CS64_ADJ1;
            }
            terms[CS64_COEF.length + i] = term & M;
            offsets[CS64_COEF.length + i] = offset & M;
        }

        long swapAcc = 0, swapLast = 0, state = 0, acc = 0;
        for (int i = 0; i < total; i += 2) {
            long x = terms[i], y = terms[i + 1];
            long t = rol16((offsets[i] + x) & M);
            long q = (t * 0x76F50F34L) & M;
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) & M;
            t = rol16((offsets[i + 1] + y) & M);
            q = (t * 0x1881CE3CL) & M;
            swapLast = (rol16(q) * 0x35BD1EC9L + q) & M;
            swapAcc = (swapAcc + swapLast) & M;

            state = (state * keyA + x) & M;
            state = (rol16(state) * 0xCF98B111L) & M;
            state = (rol16(state) * 0x87085B9FL) & M;
            state = (rol16(state) * 0x12CEB96DL) & M;
            state = (rol16(state) * 0x257E1D83L) & M;
            acc += state;
            t = (state * keyB + y) & M;
            t = (rol16(t) * 0xA27416F5L) & M;
            t = (rol16(t) * 0xD38396FFL) & M;
            state = (rol16((rol16(t) * 0x7C932B89L) & M) * 0xBFA49F69L) & M;
            acc = (acc + state) & M;
        }
        return new long[]{(swapLast ^ state) & M, (swapAcc ^ acc) & M};
    }

    // ---- bytes ------------------------------------------------------------------

    private static long getLe(byte[] b, int p) {
        return (b[p] & 0xFFL) | ((b[p + 1] & 0xFFL) << 8) | ((b[p + 2] & 0xFFL) << 16)
                | ((b[p + 3] & 0xFFL) << 24);
    }

    private static void putLe(byte[] b, int p, long v) {
        b[p] = (byte) v;
        b[p + 1] = (byte) (v >>> 8);
        b[p + 2] = (byte) (v >>> 16);
        b[p + 3] = (byte) (v >>> 24);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02X", x & 0xFF));
        return sb.toString();
    }
}
