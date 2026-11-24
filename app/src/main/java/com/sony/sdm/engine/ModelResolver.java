package com.sony.sdm.engine;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 五个索尼超分/降噪模型的选择器。
 *
 * 模型按「处理模式 + 降噪档位 + ISO」选用，对应 Imaging Edge 的 sdmsettings 分表逻辑；
 * 完整的逐机身/逐 ISO 表在 sdmsettings.dat 内，这里按 export.py 中已实测出的档位关系做
 * 可在离线 App 内运行的简化映射。
 *
 * 已知五个网络（文件名需带 8 位哈希，不区分大小写）：
 *   9981FFE2  HiRes 标准档默认网络（低/中 ISO 主力），平场增益 1.072
 *   5B5427F6  HiRes 备用网络，平场增益 1.316
 *   D9B16073  强降噪 / 中 ISO（ISO 400、1600 档），平场增益 1.038
 *   165F4CE8  高 ISO（≥12800）强档，按需下载的第五个网络
 *   FCED1D7F  备用槽位网络，平场增益 1.046
 *
 * 说明：当前管线不做平场增益补偿（与原 Python 版一致，self.gain=1.0），增益常数仅备查。
 */
public final class ModelResolver {

    private ModelResolver() {}

    /** 高 ISO 分界，达到此档位强档切换到 165F4CE8。 */
    private static final int HIGH_ISO = 12800;

    public static final String HASH_HIRES_DEFAULT = "9981FFE2";
    public static final String HASH_HIRES_FALLBACK = "5B5427F6";
    public static final String HASH_STRONG_MID = "D9B16073";
    public static final String HASH_STRONG_HIGHISO = "165F4CE8";
    public static final String HASH_EXTRA = "FCED1D7F";

    public static class Pick {
        /** 选中的模型文件；找不到任何 onnx 时为 null。 */
        public final File file;
        /** 匹配到的哈希；回退到任意可用模型时为 null。 */
        public final String hash;
        /** true = 首选表里没有命中，退回了目录里第一个可用 onnx。 */
        public final boolean fallback;

        Pick(File file, String hash, boolean fallback) {
            this.file = file;
            this.hash = hash;
            this.fallback = fallback;
        }
    }

    /**
     * 按任务条件从模型目录选一个 onnx。
     *
     * @param modelsDir 私有模型目录
     * @param mode      hires / nr / convert
     * @param noiseLevel off / std / strong
     * @param iso       从 ARW EXIF 读到的 ISO，读不到按 100 处理
     */
    public static Pick resolve(File modelsDir, String mode, String noiseLevel, int iso) {
        List<File> all = listOnnx(modelsDir);
        if (all.isEmpty()) return new Pick(null, null, false);

        boolean strong = "strong".equalsIgnoreCase(noiseLevel);
        boolean hires = !"nr".equalsIgnoreCase(mode);
        int isoNow = iso > 0 ? iso : 100;
        boolean highIso = isoNow >= HIGH_ISO;

        List<String> preferred = new ArrayList<>();
        if ("convert".equalsIgnoreCase(mode)) {
            // 转换模式不跑模型，这里仅返回第一个可用文件占位。
            preferred.add(HASH_HIRES_DEFAULT);
        } else if (hires) {
            if (strong) {
                if (highIso) {
                    preferred.add(HASH_STRONG_HIGHISO);
                    preferred.add(HASH_STRONG_MID);
                } else {
                    preferred.add(HASH_STRONG_MID);
                }
                preferred.add(HASH_HIRES_DEFAULT);
                preferred.add(HASH_HIRES_FALLBACK);
            } else {
                preferred.add(HASH_HIRES_DEFAULT);
                preferred.add(HASH_HIRES_FALLBACK);
                preferred.add(HASH_EXTRA);
            }
        } else {
            // nr 模式
            if (strong) {
                if (highIso) preferred.add(HASH_STRONG_HIGHISO);
                preferred.add(HASH_STRONG_MID);
                preferred.add(HASH_HIRES_DEFAULT);
            } else {
                preferred.add(HASH_HIRES_DEFAULT);
                preferred.add(HASH_STRONG_MID);
                preferred.add(HASH_HIRES_FALLBACK);
            }
        }

        for (String hash : preferred) {
            File hit = findByHash(all, hash);
            if (hit != null) return new Pick(hit, hash, false);
        }
        return new Pick(all.get(0), null, true);
    }

    private static List<File> listOnnx(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".onnx")) out.add(f);
        }
        return out;
    }

    private static File findByHash(List<File> files, String hash) {
        String h = hash.toUpperCase();
        for (File f : files) {
            if (f.getName().toUpperCase().contains(h)) return f;
        }
        return null;
    }
}
