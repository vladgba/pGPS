package com.vladgba.gpstools.qr;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1–10
 * (up to 213 bytes). Pure Java, no Android imports, so it can be tested on a desktop JDK.
 * Structure follows the well-known reference layout (finder/timing/alignment patterns,
 * BCH-coded format and version info, Reed–Solomon over GF(256) with polynomial 0x11D).
 */
public final class QrCode {

    /** Error correction codewords per block, level M, index = version. */
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    /** Number of error correction blocks, level M, index = version. */
    private static final int[] BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};
    private static final int MAX_VERSION = 10;
    private static final int FORMAT_ECC_M = 0;

    public final int version;
    public final int size;
    public final int mask;
    private final boolean[][] modules;     // [y][x], true = dark
    private final boolean[][] isFunction;

    /** @throws IllegalArgumentException when the text does not fit in version 10-M */
    public static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int v = 1; v <= MAX_VERSION; v++) {
            int capacityBits = dataCodewords(v) * 8;
            int countBits = v <= 9 ? 8 : 16;
            int needed = 4 + countBits + data.length * 8;
            if (needed <= capacityBits) return new QrCode(v, data);
        }
        throw new IllegalArgumentException("Text too long for a version " + MAX_VERSION + " QR code");
    }

    public boolean get(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size && modules[y][x];
    }

    private QrCode(int version, byte[] data) {
        this.version = version;
        size = version * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];
        drawFunctionPatterns();
        byte[] codewords = addEccAndInterleave(encodeData(data));
        drawCodewords(codewords);

        int bestMask = 0;
        int bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m);
            drawFormatBits(m);
            int penalty = penalty();
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                bestMask = m;
            }
            applyMask(m); // XOR again to undo
        }
        mask = bestMask;
        applyMask(mask);
        drawFormatBits(mask);
    }

    // ---- Data -----------------------------------------------------------------------------------

    private byte[] encodeData(byte[] data) {
        int capacity = dataCodewords(version);
        BitBuffer bb = new BitBuffer();
        bb.append(0b0100, 4);                                   // byte mode
        bb.append(data.length, version <= 9 ? 8 : 16);          // character count
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));    // terminator
        bb.append(0, (8 - bb.length % 8) % 8);                  // byte align
        for (int pad = 0xEC; bb.length < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        return bb.toBytes();
    }

    private byte[] addEccAndInterleave(byte[] data) {
        int numBlocks = BLOCKS[version];
        int eccLen = ECC_PER_BLOCK[version];
        int rawCodewords = rawDataModules(version) / 8;
        int numShortBlocks = numBlocks - rawCodewords % numBlocks;
        int shortBlockLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortBlockLen - eccLen + (i < numShortBlocks ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortBlockLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }
        byte[] result = new byte[rawCodewords];
        int n = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                // Short blocks have no data codeword at the padding position.
                if (i != shortBlockLen - eccLen || j >= numShortBlocks) result[n++] = blocks[j][i];
            }
        }
        return result;
    }

    // ---- Function patterns --------------------------------------------------------------------

    private void drawFunctionPatterns() {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);
        int[] align = alignmentPositions();
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                boolean corner = (i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0);
                if (!corner) drawAlignment(align[i], align[j]);
            }
        }
        drawFormatBits(0); // reserve the area; rewritten after masking
        drawVersion();
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawAlignment(int x, int y) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) setFunction(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
    }

    private void drawFormatBits(int maskPattern) {
        int data = FORMAT_ECC_M << 3 | maskPattern;
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true); // dark module
    }

    private void drawVersion() {
        if (version < 7) return;
        int rem = version;
        for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        int bits = version << 12 | rem;
        for (int i = 0; i < 18; i++) {
            boolean b = bit(bits, i);
            int a = size - 11 + i % 3;
            int c = i / 3;
            setFunction(a, c, b);
            setFunction(c, a, b);
        }
    }

    private int[] alignmentPositions() {
        if (version == 1) return new int[0];
        int numAlign = version / 7 + 2;
        int step = (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2;
        int[] result = new int[numAlign];
        result[0] = 6;
        for (int i = result.length - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    // ---- Data placement and masking -------------------------------------------------------------

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5; // skip the vertical timing column
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int m) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert;
                switch (m) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert;
            }
        }
    }

    /** Penalty rules N1 (runs), N2 (2x2 blocks), N3 (finder-like patterns) and N4 (balance). */
    private int penalty() {
        int result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean color = false;
                for (int b = 0; b < size; b++) {
                    boolean c = pass == 0 ? modules[a][b] : modules[b][a];
                    if (b > 0 && c == color) {
                        run++;
                    } else {
                        if (run >= 5) result += 3 + (run - 5);
                        run = 1;
                        color = c;
                    }
                    // N3: 1:1:3:1:1 dark pattern with 4 light modules on one side.
                    if (b >= 10) {
                        boolean match1 = true;
                        boolean match2 = true;
                        String p1 = "10111010000";
                        String p2 = "00001011101";
                        for (int k = 0; k < 11; k++) {
                            boolean m = pass == 0 ? modules[a][b - 10 + k] : modules[b - 10 + k][a];
                            if (m != (p1.charAt(k) == '1')) match1 = false;
                            if (m != (p2.charAt(k) == '1')) match2 = false;
                        }
                        if (match1) result += 40;
                        if (match2) result += 40;
                    }
                }
                if (run >= 5) result += 3 + (run - 5);
            }
        }
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
            }
        }
        int dark = 0;
        for (boolean[] row : modules) for (boolean c : row) if (c) dark++;
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(0, k) * 10;
        return result;
    }

    // ---- Tables and Reed-Solomon ------------------------------------------------------------------

    static int rawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    static int dataCodewords(int ver) {
        return rawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * BLOCKS[ver];
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) gfMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = gfMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) gfMultiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int gfMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(int value, int i) {
        return ((value >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        private byte[] bytes = new byte[256];
        int length;

        void append(int value, int bits) {
            for (int i = bits - 1; i >= 0; i--) {
                if ((length >>> 3) >= bytes.length) bytes = java.util.Arrays.copyOf(bytes, bytes.length * 2);
                if (((value >>> i) & 1) != 0) bytes[length >>> 3] |= (byte) (1 << (7 - (length & 7)));
                length++;
            }
        }

        byte[] toBytes() {
            return java.util.Arrays.copyOf(bytes, length / 8);
        }
    }
}
