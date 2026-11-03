package com.erik.camhouse.direto;

// G.711 A-law (o formato de som das cameras Xiongmai): PCM 16 bits <-> 1 byte por amostra.
public final class Alaw {
    private static final short[] DECODIFICA = new short[256];
    static {
        for (int i = 0; i < 256; i++) {
            int a = i ^ 0x55, t = (a & 0x0f) << 4, seg = (a & 0x70) >> 4;
            if (seg == 0) t += 8; else if (seg == 1) t += 0x108; else t = (t + 0x108) << (seg - 1);
            DECODIFICA[i] = (short) ((a & 0x80) != 0 ? t : -t);
        }
    }

    public static short decodifica(byte b) { return DECODIFICA[b & 0xff]; }

    public static byte codifica(short pcm) {
        int s = pcm, mascara;
        if (s >= 0) mascara = 0xD5; else { mascara = 0x55; s = -s - 1; }
        int seg = 0;
        for (int fim = 0xFF; seg < 8 && s > fim; seg++) fim = (fim << 1) | 1;
        if (seg >= 8) return (byte) (0x7F ^ mascara);
        int a = seg << 4;
        a |= seg < 2 ? (s >> 4) & 0x0f : (s >> (seg + 3)) & 0x0f;
        return (byte) (a ^ mascara);
    }
}
