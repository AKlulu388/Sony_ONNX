package com.sony.sdm.engine;

import com.sony.sdm.io.ArwMeta;
import com.sony.sdm.io.DngWriter;
import com.sony.sdm.io.PreviewWriter;
import com.sony.sdm.io.TiffWriter;
import com.sony.sdm.jni.RawReader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 处理编排层，对应 worker.py / export.py：
 * 解码 RAW → 相位拆分 → 瓦片推理 → 锐化/降噪 → 写输出。
 */
public class SdmEngine {

    public static class Options {
        public String mode = "hires";      // hires | nr | convert
        public String format = "dng";      // dng | tiff | jpeg
        public double cas = 0.30;
        public double nrStrength = 1.0;
        public int detail = 3;
        public String noise = "std";
        public boolean precise = false;
        public float exposure = 1.0f;
    }

    public static class OutputFile {
        public String name;
        public String path;
        public String mime;
        public OutputFile(String name, String path, String mime) {
            this.name = name; this.path = path; this.mime = mime;
        }
    }

    public interface Log { void log(String msg, String tag); }
    public interface Progress { void onProgress(int done, int total); }
    public interface Result { void onResult(List<OutputFile> files, int w, int h, double seconds); }

    private final File modelFile;
    private final File outputDir;

    public SdmEngine(File modelFile, File outputDir) {
        this.modelFile = modelFile;
        this.outputDir = outputDir;
    }

    public void process(String arwPath, Options opts, Log log, Progress progress, Result result) {
        long t0 = System.currentTimeMillis();
        if (modelFile == null || !modelFile.exists()) {
            if (!"convert".equals(opts.mode) && !"jpeg".equals(opts.format)) {
                log.log("缺少 ONNX 模型：请把 *.onnx 放入 assets/models/", "err");
            }
        }

        ArwMeta meta = ArwMeta.read(arwPath);
        RawReader reader = new RawReader();
        long handle = reader.open(arwPath);
        if (handle == 0) {
            log.log("无法打开 RAW 文件：" + arwPath, "err");
            result.onResult(new ArrayList<>(), 0, 0, 0);
            return;
        }
        try {
            int[] dims = reader.getDimensions(handle);
            int w = dims[0], h = dims[1];
            short[] raw = reader.getRawPlane(handle);
            int[] pat = reader.getCfaPattern(handle);
            String desc = reader.getColorDesc(handle);
            double[] blackArr = reader.getBlackLevels(handle);
            double black = avg(blackArr);
            double white = reader.getWhiteLevel(handle);
            float[] wb = reader.getWhiteBalance(handle);
            String make = reader.getMake(handle);
            String model = reader.getModel(handle);
            float[] cam2xyz = reader.getCameraMatrix(handle);
            int[][] pattern = {{pat[0], pat[1]}, {pat[2], pat[3]}};
            float[] neutral = neutralFromWb(wb);

            RawSource src = new RawSource(raw, w, h, black, white, wb, pattern, desc);
            src.phases = PhaseSplitter.split(raw, h, w, pattern, desc);
            src.make = make == null || make.isEmpty() ? "SONY" : make;
            src.model = model == null || model.isEmpty() ? "SONY" : model;
            src.cam2xyz = cam2xyz;
            src.neutral = neutral;
            log.log(src.model + "：" + w + "×" + h, "");

            // ---- convert：ARW → CFA DNG，不跑模型 ----
            if ("convert".equals(opts.mode)) {
                int[][] cfa = dngCfa(pattern, desc);
                double[] black4 = blackPerCell(blackArr, pattern, black);
                String stem = stripExt(new File(arwPath).getName());
                File dng = new File(outputDir, stem + ".dng");
                DngWriter.writeCfaDng(dng, raw, w, h, cfa, black4, white,
                        cam2xyz, neutral, meta, new File(arwPath).getName());
                DngWriter.verifyCfa(dng, raw, w, h);
                List<OutputFile> files = new ArrayList<>();
                files.add(new OutputFile(dng.getName(), dng.getAbsolutePath(), "image/x-adobe-dng"));
                log.log("DNG：" + dng.getName(), "ok");
                result.onResult(files, w, h, (System.currentTimeMillis() - t0) / 1000.0);
                return;
            }

            if (modelFile == null || !modelFile.exists()) {
                log.log("缺少 ONNX 模型，无法推理", "err");
                result.onResult(new ArrayList<>(), 0, 0, 0);
                return;
            }

            int h2 = h / 2, w2 = w / 2;
            double sigma = 0;
            if ("nr".equals(opts.mode)) {
                sigma = ImageMath.estimateSigma(src.phases, h2, w2, black, white) * opts.nrStrength;
                sigma = Math.min(Math.max(sigma, 3e-4), 4e-2);
                log.log("NR 模式 sigma=" + String.format("%.5f", sigma), "");
            }

            float[][] rgb;
            try (DemosaicNet net = new DemosaicNet(modelFile, opts.precise)) {
                TileProcessor processor = new TileProcessor(net, 32);
                rgb = processor.process(src.phases, h2, w2, black, white, sigma,
                        done -> { if (progress != null) progress.onProgress(done, h2 * w2); });
                if (!TileProcessor.isFinite(rgb)) {
                    log.log("整图输出异常，需 CPU 回退重跑", "err");
                }
            }

            int outH = h2 * 2, outW = w2 * 2;
            if ("nr".equals(opts.mode)) {
                rgb = ImageMath.downsample2(rgb, outH, outW);
                outH /= 2; outW /= 2;
            }
            if (opts.cas > 0) {
                ImageMath.casSharpen(rgb, outH, outW, (float) opts.cas);
            }

            List<OutputFile> files = new ArrayList<>();
            String stem = stripExt(new File(arwPath).getName());

            if ("dng".equals(opts.format)) {
                File dng = new File(outputDir, stem + "_sdm.dng");
                DngWriter.writeLinearDng(dng, rgb, outH, outW, cam2xyz, neutral,
                        meta, src.model, src.make);
                files.add(new OutputFile(dng.getName(), dng.getAbsolutePath(), "image/x-adobe-dng"));
                log.log("DNG：" + dng.getName(), "ok");
            } else if ("tiff".equals(opts.format)) {
                File tiff = new File(outputDir, stem + "_sdm.tif");
                TiffWriter.write16(tiff, rgb, outH, outW);
                files.add(new OutputFile(tiff.getName(), tiff.getAbsolutePath(), "image/tiff"));
                log.log("TIFF：" + tiff.getName(), "ok");
            }

            File preview = new File(outputDir, stem + "_preview.jpg");
            PreviewWriter.write(preview, rgb, outH, outW, wb, opts.exposure);
            files.add(new OutputFile(preview.getName(), preview.getAbsolutePath(), "image/jpeg"));

            double seconds = (System.currentTimeMillis() - t0) / 1000.0;
            result.onResult(files, outW, outH, seconds);
        } catch (Exception e) {
            log.log("处理失败：" + e.getMessage(), "err");
            throw new RuntimeException(e);
        } finally {
            reader.close(handle);
        }
    }

    private static float[] neutralFromWb(float[] wb) {
        float[] n = new float[]{1f, 1f, 1f};
        if (wb == null || wb.length < 3) return n;
        for (int i = 0; i < 3; i++) if (!Float.isFinite(wb[i]) || wb[i] <= 0) return n;
        for (int i = 0; i < 3; i++) n[i] = 1f / wb[i];
        float g = n[1];
        if (g <= 0) return n;
        for (int i = 0; i < 3; i++) n[i] /= g;
        return n;
    }

    private static int[][] dngCfa(int[][] pattern, String desc) {
        int[][] cfa = new int[2][2];
        String d = desc == null ? "RGBG" : desc;
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int idx = pattern[y][x];
                char c = idx >= 0 && idx < d.length() ? d.charAt(idx) : 'G';
                if (c == 'R') cfa[y][x] = 0;
                else if (c == 'B') cfa[y][x] = 2;
                else cfa[y][x] = 1;
            }
        }
        return cfa;
    }

    private static double[] blackPerCell(double[] blackArr, int[][] pattern, double black) {
        double[] bl = new double[4];
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                int idx = pattern[y][x];
                bl[y * 2 + x] = blackArr != null && idx < blackArr.length ? blackArr[idx] : black;
            }
        }
        return bl;
    }

    private static double avg(double[] a) {
        if (a == null || a.length == 0) return 0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
