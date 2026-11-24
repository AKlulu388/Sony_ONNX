package com.sony.sdm.engine;

/**
 * 瓦片拼图流水线，对应 pipeline.process_phases。
 * 输入四相位 uint16，输出线性 sensor RGB（[3][Ho*Wo]，Ho=2*H2）。
 */
public class TileProcessor {

    public interface Progress {
        void onProgress(int done, int total);
    }

    private final DemosaicNet net;
    private final int overlap;

    public TileProcessor(DemosaicNet net, int overlap) {
        this.net = net;
        this.overlap = overlap;
    }

    public float[][] process(short[][] phases, double black, double white,
                             double sigma, Progress progress) throws Exception {
        int h2 = phases[0].length;
        // 由相位长度反推 H2/W2：相位为方形时近似；调用方应保证 H2、W2 已知
        int w2 = (int) Math.round(Math.sqrt(h2));
        h2 = (int) Math.round(h2 / (double) w2);
        return process(phases, h2, w2, black, white, sigma, progress);
    }

    public float[][] process(short[][] phases, int h2, int w2,
                              double black, double white, double sigma,
                              Progress progress) throws Exception {
        int T = DemosaicNet.TILE_IN;
        int S = DemosaicNet.SCALE;
        int ov = overlap;
        int step = T - 2 * ov;
        if (step <= 0) throw new IllegalArgumentException("overlap too large");

        // 8 通道平面：0..3 相位，4..7 噪声引导（超分填 0）
        float[][] planes = new float[8][h2 * w2];
        double denom = Math.max(white - black, 1.0);
        for (int c = 0; c < 4; c++) {
            for (int i = 0; i < h2 * w2; i++) {
                float v = (float) ((phases[c][i] - black) / denom);
                planes[c][i] = Math.min(1f, Math.max(0f, Float.isNaN(v) ? 0f : v));
            }
        }
        if (sigma > 0) {
            for (int c = 4; c < 8; c++) java.util.Arrays.fill(planes[c], (float) sigma);
        }

        int ny = h2 > T ? (int) Math.ceil((h2 - T) / (double) step) + 1 : 1;
        int nx = w2 > T ? (int) Math.ceil((w2 - T) / (double) step) + 1 : 1;
        int padY = Math.max(0, (ny - 1) * step + T - h2);
        int padX = Math.max(0, (nx - 1) * step + T - w2);
        int pH = h2 + padY;
        int pW = w2 + padX;

        int Ho = h2 * S, Wo = w2 * S;
        float[][] out = new float[3][Ho * Wo];
        float[] wsum = new float[Ho * Wo];

        float[] fx = ImageMath.feather(T, ov);
        float[] fy = ImageMath.feather(T, ov);

        int total = ny * nx;
        int done = 0;

        for (int ty = 0; ty < ny; ty++) {
            for (int tx = 0; tx < nx; tx++) {
                int y0 = ty * step, x0 = tx * step;
                float[][][] tile = new float[8][T][T];
                for (int c = 0; c < 8; c++) {
                    for (int y = 0; y < T; y++) {
                        int sy = Math.min(y0 + y, h2 - 1);
                        for (int x = 0; x < T; x++) {
                            int sx = Math.min(x0 + x, w2 - 1);
                            tile[c][y][x] = planes[c][sy * w2 + sx];
                        }
                    }
                }

                float[][][] o = net.runTile(tile);
                if (!DemosaicNet.tileOk(o)) {
                    // 保留与 Python 一致的策略：异常瓦片不修补，交由上层回退 CPU 重跑
                    throw new IllegalStateException("瓦片推理输出异常（NaN/Inf/越界）");
                }

                int oy = y0 * S, ox = x0 * S;
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < T * S; y++) {
                        float[] srcRow = o[c][y];
                        int dstRow = oy + y;
                        if (dstRow >= Ho) break;
                        float weight = fy[y / S];
                        for (int x = 0; x < T * S; x++) {
                            int dstCol = ox + x;
                            if (dstCol >= Wo) break;
                            float w = weight * fx[x / S];
                            int idx = dstRow * Wo + dstCol;
                            out[c][idx] += srcRow[x] * w;
                            if (c == 0) wsum[idx] += w;
                        }
                    }
                }

                done++;
                if (progress != null) progress.onProgress(done, total);
            }
        }

        for (int i = 0; i < Ho * Wo; i++) {
            float w = Math.max(wsum[i], 1e-9f);
            for (int c = 0; c < 3; c++) out[c][i] /= w;
        }
        return out;
    }

    /** 整图有限性校验，对应 process_phases 末尾的兜底判断。 */
    public static boolean isFinite(float[][] rgb) {
        for (float[] ch : rgb) {
            for (float v : ch) {
                if (!Float.isFinite(v) || v >= 4.0f || v <= -0.5f) return false;
            }
        }
        return true;
    }
}
