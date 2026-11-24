package com.sony.sdm.engine;

/**
 * 把 Bayer 马赛克拆成 R / G1 / G2 / B 四个相位平面。
 * 对应 Python pipeline._phase_planes。
 */
public final class PhaseSplitter {

    private PhaseSplitter() {}

    public static short[][] split(short[] raw, int h, int w,
                                  int[][] pattern, String desc) {
        int h2 = h / 2;
        int w2 = w / 2;
        short[][] slots = new short[4][h2 * w2];
        int greens = 0;

        for (int dy = 0; dy < 2; dy++) {
            for (int dx = 0; dx < 2; dx++) {
                int ci = pattern[dy][dx];
                char c = (ci >= 0 && ci < desc.length()) ? desc.charAt(ci) : 'G';
                int slot;
                if (c == 'R') {
                    slot = 0;
                } else if (c == 'B') {
                    slot = 3;
                } else {
                    slot = (greens == 0) ? 1 : 2;
                    greens++;
                }
                int plane = 0;
                for (int y2 = 0; y2 < h2; y2++) {
                    for (int x2 = 0; x2 < w2; x2++) {
                        int src = (y2 * 2 + dy) * w + (x2 * 2 + dx);
                        slots[slot][plane++] = raw[src];
                    }
                }
            }
        }
        return slots;
    }
}
