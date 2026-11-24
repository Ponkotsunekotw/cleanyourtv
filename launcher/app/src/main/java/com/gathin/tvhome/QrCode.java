package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.nio.charset.StandardCharsets;

/**
 * Minimal pure-Java QR Code generator (Byte mode, ECC Level L, Versions 1-4)
 * with zero external dependencies.
 */
final class QrCode {
    private static final int[] TOTAL_CODEWORDS = {0, 26, 44, 70, 100};
    private static final int[] ECC_CODEWORDS = {0, 7, 10, 15, 20};
    private static final int[] ALIGNMENT_POS = {0, 0, 18, 22, 26};

    private static final int[] EXP = new int[512];
    private static final int[] LOG = new int[256];

    static {
        int x = 1;
        for (int i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if ((x & 0x100) != 0) x ^= 0x11D;
        }
        for (int i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
    }

    private QrCode() {}

    static Bitmap encode(String text, int sizePx) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int ver = 1;
        while (ver <= 4) {
            int dataCap = TOTAL_CODEWORDS[ver] - ECC_CODEWORDS[ver];
            if (data.length + 2 <= dataCap) break;
            ver++;
        }
        if (ver > 4) throw new IllegalArgumentException("URL too long for QR v1-4");

        int dataCap = TOTAL_CODEWORDS[ver] - ECC_CODEWORDS[ver];
        int eccCount = ECC_CODEWORDS[ver];
        byte[] codewords = new byte[TOTAL_CODEWORDS[ver]];

        // Bit buffer: 4-bit mode (0100), 8-bit length, payload, terminator, pad bytes
        int[] bits = new int[dataCap * 8];
        int bp = 0;
        bp = writeBits(bits, bp, 0b0100, 4);
        bp = writeBits(bits, bp, data.length, 8);
        for (byte b : data) bp = writeBits(bits, bp, b & 0xFF, 8);
        int rem = Math.min(4, bits.length - bp);
        bp = writeBits(bits, bp, 0, rem);
        while ((bp & 7) != 0) bits[bp++] = 0;
        int padIdx = 0;
        while (bp < bits.length) {
            bp = writeBits(bits, bp, (padIdx++ & 1) == 0 ? 0xEC : 0x11, 8);
        }
        for (int i = 0; i < dataCap; i++) {
            int v = 0;
            for (int b = 0; b < 8; b++) v = (v << 1) | bits[i * 8 + b];
            codewords[i] = (byte) v;
        }

        // Reed-Solomon ECC
        int[] gen = rsGenerator(eccCount);
        int[] ecc = new int[eccCount];
        for (int i = 0; i < dataCap; i++) {
            int factor = (codewords[i] & 0xFF) ^ ecc[0];
            System.arraycopy(ecc, 1, ecc, 0, eccCount - 1);
            ecc[eccCount - 1] = 0;
            if (factor != 0) {
                for (int j = 0; j < eccCount; j++) {
                    ecc[j] ^= gfMul(gen[j], factor);
                }
            }
        }
        for (int i = 0; i < eccCount; i++) codewords[dataCap + i] = (byte) ecc[i];

        int n = ver * 4 + 17;
        boolean[][] modules = new boolean[n][n];
        boolean[][] reserved = new boolean[n][n];

        placeFinder(modules, reserved, 0, 0);
        placeFinder(modules, reserved, n - 7, 0);
        placeFinder(modules, reserved, 0, n - 7);
        if (ALIGNMENT_POS[ver] > 0) {
            placeAlignment(modules, reserved, ALIGNMENT_POS[ver], ALIGNMENT_POS[ver]);
        }
        for (int i = 8; i < n - 8; i++) {
            setReserved(modules, reserved, i, 6, (i & 1) == 0);
            setReserved(modules, reserved, 6, i, (i & 1) == 0);
        }
        setReserved(modules, reserved, 8, n - 8, true);
        reserveFormat(reserved, n);

        // Place data bits (up/down zigzag)
        int byteIdx = 0, bitIdx = 7;
        boolean up = true;
        for (int right = n - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < n; vert++) {
                int y = up ? (n - 1 - vert) : vert;
                for (int dx = 0; dx < 2; dx++) {
                    int x = right - dx;
                    if (!reserved[x][y]) {
                        boolean bit = byteIdx < codewords.length && ((codewords[byteIdx] >> bitIdx) & 1) != 0;
                        modules[x][y] = bit;
                        if (--bitIdx < 0) {
                            bitIdx = 7;
                            byteIdx++;
                        }
                    }
                }
            }
            up = !up;
        }

        // Apply mask 0 ((x + y) % 2 == 0) — simple and reliable
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                if (!reserved[x][y] && ((x + y) & 1) == 0) {
                    modules[x][y] = !modules[x][y];
                }
            }
        }
        placeFormatBits(modules, n, 0); // Level L (01) + Mask 0 (000)

        int quiet = 4;
        int total = n + quiet * 2;
        int scale = Math.max(1, sizePx / total);
        int imgSize = total * scale;
        Bitmap bmp = Bitmap.createBitmap(imgSize, imgSize, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[imgSize * imgSize];
        for (int py = 0; py < imgSize; py++) {
            int my = py / scale - quiet;
            for (int px = 0; px < imgSize; px++) {
                int mx = px / scale - quiet;
                boolean dark = mx >= 0 && mx < n && my >= 0 && my < n && modules[mx][my];
                pixels[py * imgSize + px] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        bmp.setPixels(pixels, 0, imgSize, 0, 0, imgSize, imgSize);
        return bmp;
    }

    private static int writeBits(int[] buf, int pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) buf[pos++] = (val >> i) & 1;
        return pos;
    }

    private static int gfMul(int a, int b) {
        return (a == 0 || b == 0) ? 0 : EXP[LOG[a] + LOG[b]];
    }

    private static int[] rsGenerator(int degree) {
        int[] poly = new int[degree];
        poly[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                poly[j] = gfMul(poly[j], root);
                if (j + 1 < degree) poly[j] ^= poly[j + 1];
            }
            root = gfMul(root, 2);
        }
        return poly;
    }

    private static void setReserved(boolean[][] m, boolean[][] r, int x, int y, boolean dark) {
        m[x][y] = dark;
        r[x][y] = true;
    }

    private static void placeFinder(boolean[][] m, boolean[][] r, int x0, int y0) {
        int n = m.length;
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int x = x0 + dx, y = y0 + dy;
                if (x < 0 || x >= n || y < 0 || y >= n) continue;
                boolean dark = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6 &&
                        (dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                setReserved(m, r, x, y, dark);
            }
        }
    }

    private static void placeAlignment(boolean[][] m, boolean[][] r, int cx, int cy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                boolean dark = Math.max(Math.abs(dx), Math.abs(dy)) != 1;
                setReserved(m, r, cx + dx, cy + dy, dark);
            }
        }
    }

    private static void reserveFormat(boolean[][] r, int n) {
        for (int i = 0; i <= 8; i++) {
            if (i != 6) {
                r[8][i] = true;
                r[i][8] = true;
            }
        }
        for (int i = n - 8; i < n; i++) {
            r[8][i] = true;
            r[i][8] = true;
        }
    }

    private static void placeFormatBits(boolean[][] m, int n, int mask) {
        int data = (0b01 << 3) | mask; // ECC Level L = 01
        int rem = data << 10;
        for (int i = 0; i < 5; i++) {
            if (((rem >> (14 - i)) & 1) != 0) rem ^= 0x537 << (4 - i);
        }
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) m[8][i] = ((bits >> i) & 1) != 0;
        m[8][7] = ((bits >> 6) & 1) != 0;
        m[8][8] = ((bits >> 7) & 1) != 0;
        m[7][8] = ((bits >> 8) & 1) != 0;
        for (int i = 9; i < 15; i++) m[14 - i][8] = ((bits >> i) & 1) != 0;
        for (int i = 0; i < 8; i++) m[n - 1 - i][8] = ((bits >> i) & 1) != 0;
        for (int i = 8; i < 15; i++) m[8][n - 15 + i] = ((bits >> i) & 1) != 0;
    }
}
