package com.sony.sdm.engine;

import java.util.Arrays;

/** 纯数值工具，对应 pipeline.py 中的 numpy 片段。 */
public final class ImageMath {

    private ImageMath() {}

    /** 余弦羽化窗，对应 _feather。 */
    public static float[] feather(int n, int ov) {
        float[] w = new float[n];
        Arrays.fill(w, 1f);
        if (ov <= 0) return w;
        for (int i = 0; i < ov; i++) {
            double t = Math.PI * (i + 1) / (ov + 1);
            float ramp = (float) (0.5 - 0.5 * Math.cos(t));
            w[i] = ramp;
            w[n - 1 - i] = ramp;
        }
        return w;
    }

    private static double median(double[] a) {
        double[] b = Arrays.copyOf(a, a.length);
        Arrays.sort(b);
        int n = b.length;
        return n % 2 == 0 ? (b[n / 2 - 1] + b[n / 2]) / 2 : b[n / 2];
    }

    /** 单相位噪声 sigma（MAD 稳健估计），对应 estimate_noise。 */
    public static double estimateNoise(short[] plane, int h2, int w2,
                                       double black, double white) {
        int h = h2 - (h2 % 4);
        int w = w2 - (w2 % 4);
        double scale = Math.max(white - black, 1.0);
        double[] r = new double[h * w];
        int k = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double s = (plane[y * w2 + x] - black) / scale;
                double up = y > 0 ? (plane[(y - 1) * w2 + x] - black) / scale : s;
                double left = x > 0 ? (plane[y * w2 + x - 1] - black) / scale : s;
                double ul = (y > 0 && x > 0) ? (plane[(y - 1) * w2 + x - 1] - black) / scale : s;
                double v = (s - up) - (left - ul);
                r[k++] = v;
            }
        }
        double med = median(r);
        double[] abs = new double[r.length];
        for (int i = 0; i < r.length; i++) abs[i] = Math.abs(r[i] - med);
        return median(abs) * 1.4826 / 2.0;
    }

    public static double estimateSigma(short[][] phases, int h2, int w2,
                                       double black, double white) {
        double sum = 0;
        for (short[] p : phases) sum += estimateNoise(p, h2, w2, black, white);
        return sum / phases.length;
    }

    /** AMD FidelityFX CAS，对应 cas_sharpen。rgb: [3][H*W]。 */
    public static void casSharpen(float[][] rgb, int h, int w, float strength) {
        if (strength <= 0) return;
        float peak = -1f / 8f * strength;
        for (int c = 0; c < 3; c++) {
            float[] x = rgb[c];
            for (int y = 0; y < h; y++) {
                for (int i = y * w; i < y * w + w; i++) {
                    float n = y < h - 1 ? x[i + w] : x[i];
                    float s = y > 0 ? x[i - w] : x[i];
                    float e = i % w < w - 1 ? x[i + 1] : x[i];
                    float west = i % w > 0 ? x[i - 1] : x[i];
                    float mn = Math.min(Math.min(n, s), Math.min(e, west));
                    float mx = Math.max(Math.max(n, s), Math.max(e, west));
                    float amp = (float) Math.sqrt(Math.min(Math.min(mn, 2f - mx) / Math.max(mx, 1e-6), 1f));
                    float wt = amp * peak;
                    x[i] = (x[i] + wt * (n + s + e + west)) / (1f + 4f * wt);
                }
            }
        }
    }

    /** 应用相机白平衡，对应 apply_wb。 */
    public static void applyWb(float[][] rgb, float[] wb) {
        if (wb == null) return;
        float g = Math.max(wb[1], 1e-6f);
        for (int c = 0; c < 3; c++) {
            float gain = wb[c] / g;
            float[] ch = rgb[c];
            for (int i = 0; i < ch.length; i++) ch[i] *= gain;
        }
    }

    /** 线性 sensor RGB → 8bit ARGB 像素数组，对应 to_display。 */
    public static int[] toDisplay(float[][] rgb, int h, int w, float[] wb, float exposure) {
        float[][] out = new float[3][h * w];
        for (int c = 0; c < 3; c++) System.arraycopy(rgb[c], 0, out[c], 0, h * w);
        applyWb(out, wb);
        int[] pixels = new int[h * w];
        double invGamma = 1.0 / 2.2;
        for (int i = 0; i < h * w; i++) {
            int r = clamp8(out[0][i] * exposure, invGamma);
            int g = clamp8(out[1][i] * exposure, invGamma);
            int b = clamp8(out[2][i] * exposure, invGamma);
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        return pixels;
    }

    private static int clamp8(float v, double invGamma) {
        v = (float) Math.min(Math.max(v, 0f), 1f);
        v = (float) Math.pow(v, invGamma);
        return Math.min(255, Math.max(0, Math.round(v * 255f + 0.5f)));
    }

    /** 2×2 盒平均降采样，对应 downsample2。 */
    public static float[][] downsample2(float[][] rgb, int h, int w) {
        int hh = h - h % 2, ww = w - w % 2;
        int nh = hh / 2, nw = ww / 2;
        float[][] out = new float[3][nh * nw];
        for (int c = 0; c < 3; c++) {
            float[] x = rgb[c];
            for (int y = 0; y < nh; y++) {
                for (int x2 = 0; x2 < nw; x2++) {
                    int i00 = (y * 2) * w + x2 * 2;
                    out[c][y * nw + x2] = (x[i00] + x[i00 + 1] + x[i00 + w] + x[i00 + w + 1]) / 4f;
                }
            }
        }
        return out;
    }
}
