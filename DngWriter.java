package com.sony.sdm.io;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * DNG 写入层，移植自 Python dng.py。
 *
 * 布局与 Adobe DNG Converter 一致：
 *   IFD0    8-bit 预览 + DNG 色彩档案 + ExifIFD 指针
 *   SubIFD0 CFA 马赛克（convert）或 LinearRaw（超分/降噪输出）
 *
 * 色彩矩阵：传入 LibRaw 的 camera→XYZ(D65)，DNG ColorMatrix1 取其逆矩阵。
 */
public final class DngWriter {

    private DngWriter() {}

    private static final int BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4,
            RATIONAL = 5, UNDEFINED = 7, SRATIONAL = 10;

    private static final int[] TYPE_SIZE = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8};

    // ---- IFD 容器 ----

    private static class Entry {
        int type;
        int count;
        byte[] data;
        Entry(int type, int count, byte[] data) {
            this.type = type; this.count = count; this.data = data;
        }
    }

    private static class Ifd {
        final Map<Integer, Entry> entries = new TreeMap<>();
        void add(int tag, int type, int count, byte[] data) {
            entries.put(tag, new Entry(type, count, data));
        }
        void addShort(int tag, int... v) { add(tag, SHORT, v.length, packShort(v)); }
        void addLong(int tag, int... v) { add(tag, LONG, v.length, packLong(v)); }
        void addAscii(int tag, String s) {
            byte[] b = (s + "\0").getBytes(StandardCharsets.UTF_8);
            add(tag, ASCII, b.length, b);
        }
        void addRational(int tag, double[] v, int den) {
            add(tag, RATIONAL, v.length, packRational(v, den));
        }
        void addSRational(int tag, double[] v, int den) {
            add(tag, SRATIONAL, v.length, packSRational(v, den));
        }
        int size() { return 2 + entries.size() * 12 + 4; }
        int extraSize() {
            int s = 0;
            for (Entry e : entries.values()) {
                if (e.data.length > 4) s += e.data.length + (e.data.length & 1);
            }
            return s;
        }
    }

    // ---- 打包 ----

    private static byte[] packShort(int[] v) {
        byte[] b = new byte[v.length * 2];
        for (int i = 0; i < v.length; i++) {
            b[i * 2] = (byte) (v[i] & 0xFF);
            b[i * 2 + 1] = (byte) ((v[i] >> 8) & 0xFF);
        }
        return b;
    }

    private static byte[] packLong(int[] v) {
        byte[] b = new byte[v.length * 4];
        for (int i = 0; i < v.length; i++) {
            int x = v[i];
            b[i * 4] = (byte) (x & 0xFF);
            b[i * 4 + 1] = (byte) ((x >> 8) & 0xFF);
            b[i * 4 + 2] = (byte) ((x >> 16) & 0xFF);
            b[i * 4 + 3] = (byte) ((x >> 24) & 0xFF);
        }
        return b;
    }

    private static byte[] packRational(double[] v, int den) {
        byte[] b = new byte[v.length * 8];
        for (int i = 0; i < v.length; i++) {
            long num = Math.round(v[i] * den);
            putU32(b, i * 8, (int) num);
            putU32(b, i * 8 + 4, den);
        }
        return b;
    }

    private static byte[] packSRational(double[] v, int den) {
        byte[] b = new byte[v.length * 8];
        for (int i = 0; i < v.length; i++) {
            int num = (int) Math.round(v[i] * den);
            putS32(b, i * 8, num);
            putU32(b, i * 8 + 4, den);
        }
        return b;
    }

    private static void putU32(byte[] b, int o, int v) {
        b[o] = (byte) (v & 0xFF);
        b[o + 1] = (byte) ((v >> 8) & 0xFF);
        b[o + 2] = (byte) ((v >> 16) & 0xFF);
        b[o + 3] = (byte) ((v >> 24) & 0xFF);
    }

    private static void putS32(byte[] b, int o, int v) { putU32(b, o, v); }

    private static byte[] u16(int v) { return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)}; }
    private static byte[] u32(int v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }

    private static int align2(int v) { return (v + 1) & ~1; }

    // ---- IFD 序列化 ----

    private static byte[] serialize(Ifd ifd, int extraStart, Map<Integer, Integer> patch,
                                    List<Byte> extraOut) {
        List<Byte> body = new ArrayList<>();
        for (byte x : u16(ifd.entries.size())) body.add(x);

        for (Map.Entry<Integer, Entry> kv : ifd.entries.entrySet()) {
            int tag = kv.getKey();
            Entry e = kv.getValue();
            byte[] vf;
            if (patch != null && patch.containsKey(tag)) {
                vf = u32(patch.get(tag));
            } else if (e.data.length > 4) {
                int off = extraStart + extraOut.size();
                vf = u32(off);
                for (byte x : e.data) extraOut.add(x);
                if ((e.data.length & 1) == 1) extraOut.add((byte) 0);
            } else {
                vf = new byte[4];
                System.arraycopy(e.data, 0, vf, 0, e.data.length);
            }
            for (byte x : u16(tag)) body.add(x);
            for (byte x : u16(e.type)) body.add(x);
            for (byte x : u32(e.count)) body.add(x);
            for (byte x : vf) body.add(x);
        }
        for (byte x : u32(0)) body.add(x);

        byte[] out = new byte[body.size()];
        for (int i = 0; i < body.size(); i++) out[i] = body.get(i);
        return out;
    }

    private static byte[] extraArray(List<Byte> extra) {
        byte[] b = new byte[extra.size()];
        for (int i = 0; i < b.length; i++) b[i] = extra.get(i);
        return b;
    }

    private static void writeAll(File out, byte[]... parts) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            for (byte[] p : parts) fos.write(p);
        }
    }

    // ---- 色彩工具 ----

    /** cam→XYZ 3x3 行优先 → XYZ→camera（DNG ColorMatrix1），展平 9 个 double。 */
    private static double[] colorMatrix1(float[] cam2xyz) {
        if (cam2xyz == null || cam2xyz.length < 9) {
            return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) m[i][j] = cam2xyz[i * 3 + j];
        if (!isFinite(m) || Math.abs(det(m)) < 1e-9) {
            return new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        }
        double[][] inv = inverse(m);
        double[] out = new double[9];
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 3; j++) out[i * 3 + j] = inv[j][i];
        return out;
    }

    private static boolean isFinite(double[][] m) {
        for (double[] row : m) for (double v : row) if (!Double.isFinite(v)) return false;
        return true;
    }

    private static double det(double[][] m) {
        return m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
                - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
                + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    }

    private static double[][] inverse(double[][] m) {
        double d = det(m);
        double[][] a = new double[3][3];
        a[0][0] = (m[1][1] * m[2][2] - m[1][2] * m[2][1]) / d;
        a[0][1] = (m[0][2] * m[2][1] - m[0][1] * m[2][2]) / d;
        a[0][2] = (m[0][1] * m[1][2] - m[0][2] * m[1][1]) / d;
        a[1][0] = (m[1][2] * m[2][0] - m[1][0] * m[2][2]) / d;
        a[1][1] = (m[0][0] * m[2][2] - m[0][2] * m[2][0]) / d;
        a[1][2] = (m[0][2] * m[1][0] - m[0][0] * m[1][2]) / d;
        a[2][0] = (m[1][0] * m[2][1] - m[1][1] * m[2][0]) / d;
        a[2][1] = (m[0][1] * m[2][0] - m[0][0] * m[2][1]) / d;
        a[2][2] = (m[0][0] * m[1][1] - m[0][1] * m[1][0]) / d;
        return a;
    }

    // ---- CFA DNG ----

    public static void writeCfaDng(File out, short[] mosaic, int w, int h,
                                   int[][] cfa, double[] black4, double white,
                                   float[] cam2xyz, float[] neutral, ArwMeta meta,
                                   String originalName) throws Exception {
        int hh = h - h % 2, ww = w - w % 2;
        double[] bl = black4 != null && black4.length == 4 ? black4 : new double[]{0, 0, 0, 0};

        byte[] payload = new byte[hh * ww * 2];
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < ww; x++) {
                short v = mosaic[y * w + x];
                int o = (y * ww + x) * 2;
                payload[o] = (byte) (v & 0xFF);
                payload[o + 1] = (byte) ((v >> 8) & 0xFF);
            }
        }

        byte[] thumb = cfaThumbnail(mosaic, ww, hh, bl[0], white);
        int th = thumb.length / (3 * 160);
        int tw = 160;

        Ifd ifd0 = new Ifd();
        ifd0.addLong(254, 1);
        ifd0.addLong(256, tw);
        ifd0.addLong(257, th);
        ifd0.addShort(258, 8, 8, 8);
        ifd0.addShort(259, 1);
        ifd0.addShort(262, 2);
        ifd0.addShort(277, 3);
        ifd0.addLong(278, th);
        ifd0.addLong(279, thumb.length);
        ifd0.addShort(284, 1);
        ifd0.addShort(274, meta.orientation);
        ifd0.addRational(282, new double[]{300, 1}, 1);
        ifd0.addRational(283, new double[]{300, 1}, 1);
        ifd0.addShort(296, 2);
        String make = meta.make.isEmpty() ? "SONY" : meta.make;
        String model = meta.model.isEmpty() ? "SONY" : meta.model;
        ifd0.addAscii(271, make);
        ifd0.addAscii(272, model);
        ifd0.addAscii(305, "ARW Universal 7.1");
        ifd0.addAscii(306, meta.datetime.isEmpty() ? "    :  :     :  :  " : meta.datetime);
        ifd0.add(50706, BYTE, 4, new byte[]{1, 4, 0, 0});
        ifd0.add(50707, BYTE, 4, new byte[]{1, 1, 0, 0});
        ifd0.addAscii(50708, make + " " + model);
        ifd0.addShort(50741, 1);
        ifd0.addSRational(50721, colorMatrix1(cam2xyz), 1_000_000);
        ifd0.addRational(50728, neutralDoubles(neutral), 1_000_000);
        ifd0.addShort(50778, 21);
        ifd0.addLong(50717, (int) white);
        if (originalName != null) ifd0.addAscii(50827, originalName);

        Ifd sub = new Ifd();
        sub.addLong(254, 0);
        sub.addLong(256, ww);
        sub.addLong(257, hh);
        sub.addShort(258, 16);
        sub.addShort(259, 1);
        sub.addShort(262, 32803);
        sub.addShort(277, 1);
        sub.addLong(278, hh);
        sub.addLong(279, payload.length);
        sub.addShort(284, 1);
        sub.addShort(274, meta.orientation);
        sub.addShort(33421, 2, 2);
        byte[] cfaBytes = new byte[]{(byte) cfa[0][0], (byte) cfa[0][1],
                (byte) cfa[1][0], (byte) cfa[1][1]};
        sub.add(33422, BYTE, 4, cfaBytes);
        sub.add(50710, BYTE, 3, new byte[]{0, 1, 2});
        sub.addShort(50711, 1);
        sub.addShort(50713, 2, 2);
        sub.addRational(50714, blackDoubles(bl), 10_000);
        sub.addLong(50717, (int) white);
        sub.addRational(50718, new double[]{1, 1, 1, 1}, 1);
        sub.addLong(50719, 0, 0);
        sub.addLong(50720, ww, hh);
        sub.addLong(50829, 0, 0, hh, ww);

        Ifd exif = buildExif(meta);
        writeLayout(out, ifd0, sub, exif, thumb, payload);
    }

    // ---- LinearRaw DNG ----

    public static void writeLinearDng(File out, float[][] rgb, int h, int w,
                                       float[] cam2xyz, float[] neutral,
                                       ArwMeta meta, String model, String make)
            throws Exception {
        byte[] payload = new byte[h * w * 3 * 2];
        for (int i = 0; i < h * w; i++) {
            for (int c = 0; c < 3; c++) {
                float v = Math.min(Math.max(rgb[c][i], 0f), 1f);
                int u16 = Math.round(v * 65535f);
                int o = (i * 3 + c) * 2;
                payload[o] = (byte) (u16 & 0xFF);
                payload[o + 1] = (byte) ((u16 >> 8) & 0xFF);
            }
        }

        byte[] thumb = linearThumbnail(rgb, h, w, neutral);
        int th = thumb.length / (3 * 256);
        int tw = 256;

        Ifd ifd0 = new Ifd();
        ifd0.addLong(254, 1);
        ifd0.addLong(256, tw);
        ifd0.addLong(257, th);
        ifd0.addShort(258, 8, 8, 8);
        ifd0.addShort(259, 1);
        ifd0.addShort(262, 2);
        ifd0.addAscii(271, make);
        ifd0.addAscii(272, model);
        ifd0.addShort(277, 3);
        ifd0.addLong(278, th);
        ifd0.addLong(279, thumb.length);
        ifd0.addShort(284, 1);
        ifd0.addAscii(305, "ARW Universal 7.1");
        ifd0.addSRational(50721, colorMatrix1(cam2xyz), 1_000_000);
        ifd0.addRational(50728, neutralDoubles(neutral), 1_000_000);
        ifd0.addShort(50778, 21);

        Ifd sub = new Ifd();
        sub.addLong(254, 0);
        sub.addLong(256, w);
        sub.addLong(257, h);
        sub.addShort(258, 16, 16, 16);
        sub.addShort(259, 1);
        sub.addShort(262, 34892);
        sub.addShort(277, 3);
        sub.addLong(278, h);
        sub.addLong(279, payload.length);
        sub.addShort(284, 1);
        sub.addShort(339, 1, 1, 1);
        sub.addLong(50714, 0, 0, 0);
        sub.addLong(50717, 65535);
        sub.addRational(50718, new double[]{1, 1, 1, 1}, 1);

        Ifd exif = buildExif(meta);
        writeLayout(out, ifd0, sub, exif, thumb, payload);
    }

    // ---- ExifIFD ----

    private static Ifd buildExif(ArwMeta meta) {
        Ifd ex = new Ifd();
        if (meta == null) return ex;
        if (meta.exposureTime > 0) ex.addRational(0x829A, new double[]{meta.exposureTime}, 1_000_000);
        if (meta.fnumber > 0) ex.addRational(0x829D, new double[]{meta.fnumber}, 1_000_000);
        if (meta.iso != null) ex.addShort(0x8827, meta.iso);
        if (!meta.datetime.isEmpty()) {
            ex.addAscii(0x9003, meta.datetime);
            ex.addAscii(0x9004, meta.datetime);
        }
        if (meta.focal > 0) ex.addRational(0x920A, new double[]{meta.focal}, 1_000_000);
        if (!meta.bodySerial.isEmpty()) ex.addAscii(0xA431, meta.bodySerial);
        if (meta.lensSpec != null && meta.lensSpec.length == 4)
            ex.addRational(0xA432, meta.lensSpec, 1_000_000);
        if (!meta.lensMake.isEmpty()) ex.addAscii(0xA433, meta.lensMake);
        if (!meta.lensModel.isEmpty()) ex.addAscii(0xA434, meta.lensModel);
        return ex;
    }

    // ---- 布局落盘 ----

    private static void writeLayout(File out, Ifd ifd0, Ifd sub, Ifd exif,
                                    byte[] thumb, byte[] payload) throws Exception {
        int ifd0Off = 8;
        int s0 = ifd0.size(), e0 = ifd0.extraSize();
        int thumbOff = ifd0Off + s0 + e0;
        int subOff = align2(thumbOff + thumb.length);
        int s1 = sub.size(), e1 = sub.extraSize();
        int exifOff = align2(subOff + s1 + e1);
        boolean hasExif = exif != null && !exif.entries.isEmpty();
        int pixOff;
        int s2 = 0, e2 = 0;
        if (hasExif) {
            s2 = exif.size(); e2 = exif.extraSize();
            pixOff = align2(exifOff + s2 + e2);
        } else {
            pixOff = align2(exifOff);
        }

        Map<Integer, Integer> patch0 = new HashMap<>();
        patch0.put(273, thumbOff);
        patch0.put(0x014A, subOff);
        if (hasExif) patch0.put(0x8769, exifOff);
        Map<Integer, Integer> patchSub = new HashMap<>();
        patchSub.put(273, pixOff);

        List<Byte> x0 = new ArrayList<>();
        byte[] b0 = serialize(ifd0, ifd0Off + s0, patch0, x0);
        List<Byte> x1 = new ArrayList<>();
        byte[] b1 = serialize(sub, subOff + s1, patchSub, x1);
        byte[] b2 = new byte[0], x2 = new byte[0];
        if (hasExif) {
            List<Byte> x2l = new ArrayList<>();
            b2 = serialize(exif, exifOff + s2, null, x2l);
            x2 = extraArray(x2l);
        }

        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(new byte[]{'I', 'I'});
            fos.write(u16(42));
            fos.write(u32(ifd0Off));
            fos.write(b0);
            fos.write(extraArray(x0));
            fos.write(thumb);
            padTo(fos, subOff);
            fos.write(b1);
            fos.write(extraArray(x1));
            if (hasExif) {
                padTo(fos, exifOff);
                fos.write(b2);
                fos.write(x2);
            }
            padTo(fos, pixOff);
            fos.write(payload);
        }
    }

    private static void padTo(FileOutputStream fos, long target) throws Exception {
        long cur = fos.getChannel().position();
        while (cur < target) { fos.write(0); cur++; }
    }

    // ---- 预览图 ----

    private static byte[] cfaThumbnail(short[] mosaic, int w, int h,
                                       double black, double white) {
        int tw = 160;
        int th = Math.max(1, Math.round(160f * h / w));
        byte[] out = new byte[tw * th * 3];
        double denom = Math.max(white - black, 1.0);
        int sy = Math.max(1, h / th), sx = Math.max(1, w / tw);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int srcY = Math.min(y * sy, h - 1);
                int srcX = Math.min(x * sx, w - 1);
                double v = (mosaic[srcY * w + srcX] - black) / denom;
                v = Math.min(Math.max(v, 0), 1);
                int g = (int) Math.round(Math.pow(v, 1 / 2.2) * 255);
                g = Math.min(255, Math.max(0, g));
                int o = (y * tw + x) * 3;
                out[o] = (byte) g; out[o + 1] = (byte) g; out[o + 2] = (byte) g;
            }
        }
        return out;
    }

    private static byte[] linearThumbnail(float[][] rgb, int h, int w, float[] neutral) {
        int tw = 256;
        int th = Math.max(1, Math.round(256f * h / w));
        byte[] out = new byte[tw * th * 3];
        int sy = Math.max(1, h / th), sx = Math.max(1, w / tw);
        for (int y = 0; y < th; y++) {
            for (int x = 0; x < tw; x++) {
                int srcY = Math.min(y * sy, h - 1);
                int srcX = Math.min(x * sx, w - 1);
                int i = srcY * w + srcX;
                for (int c = 0; c < 3; c++) {
                    float gain = neutral != null && neutral[1] > 0 ? neutral[1] / neutral[c] : 1f;
                    float v = rgb[c][i] * gain;
                    v = Math.min(Math.max(v, 0), 1);
                    int g = (int) Math.round(Math.pow(v, 1 / 2.2) * 255);
                    out[(y * tw + x) * 3 + c] = (byte) Math.min(255, Math.max(0, g));
                }
            }
        }
        return out;
    }

    private static double[] neutralDoubles(float[] neutral) {
        double[] d = new double[3];
        for (int i = 0; i < 3; i++) d[i] = neutral != null && i < neutral.length ? neutral[i] : 1;
        return d;
    }

    private static double[] blackDoubles(double[] bl) {
        double[] d = new double[4];
        for (int i = 0; i < 4; i++) d[i] = i < bl.length ? bl[i] : 0;
        return d;
    }

    // ---- 写回抽样校验 ----

    /** 对照 Python convert._verify_written：抽 3 行与源马赛克比对。 */
    public static void verifyCfa(File dng, short[] src, int w, int h) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(dng, "r")) {
            byte[] head = new byte[8];
            raf.readFully(head);
            boolean le = head[0] == 'I';
            long ifd0 = u32at(raf, 4, le);
            Map<Integer, int[]> ifd = readIfdInt(raf, ifd0, le);
            int[] subE = ifd.get(0x014A);
            if (subE == null) return;
            long subOff = readLong(raf, subE[2], le);
            Map<Integer, int[]> sub = readIfdInt(raf, subOff, le);
            int[] stripE = sub.get(0x0111);
            int[] photoE = sub.get(0x0106);
            if (stripE == null || photoE == null) return;
            int photo = photoE[2] & 0xFFFF;
            if (photo != 32803) return;
            long strip = readLong(raf, stripE[2], le);
            int rowBytes = w * 2;
            for (int y : new int[]{h / 4, h / 2, 3 * h / 4}) {
                raf.seek(strip + (long) y * rowBytes);
                byte[] got = new byte[rowBytes];
                raf.readFully(got);
                for (int x = 0; x < w; x++) {
                    int v = (got[x * 2] & 0xFF) | ((got[x * 2 + 1] & 0xFF) << 8);
                    if (v != (src[y * w + x] & 0xFFFF)) {
                        throw new IllegalStateException("DNG 写入校验失败：第 " + y + " 行不一致");
                    }
                }
            }
        }
    }

    private static Map<Integer, int[]> readIfdInt(RandomAccessFile raf, long off, boolean le)
            throws Exception {
        Map<Integer, int[]> out = new HashMap<>();
        if (off <= 0) return out;
        raf.seek(off);
        int n = readShort(raf, 0, le);
        for (int i = 0; i < n; i++) {
            byte[] e = new byte[8];
            raf.readFully(e);
            int tag = readShort(e, 0, le);
            int type = readShort(e, 2, le);
            int count = (int) readLong(e, 4, le);
            byte[] raw = new byte[4];
            raf.readFully(raw);
            out.put(tag, new int[]{type, count, (int) readLong(raw, 0, le)});
        }
        return out;
    }

    private static int readShort(RandomAccessFile raf, int off, boolean le) throws Exception {
        raf.seek(off);
        byte[] b = new byte[2];
        raf.readFully(b);
        return readShort(b, 0, le);
    }

    private static int readShort(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                  : ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static long readLong(RandomAccessFile raf, int off, boolean le) throws Exception {
        raf.seek(off);
        byte[] b = new byte[4];
        raf.readFully(b);
        return readLong(b, 0, le);
    }

    private static long readLong(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24)
               : ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16)
                | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    private static long u32at(RandomAccessFile raf, int off, boolean le) throws Exception {
        raf.seek(off);
        byte[] b = new byte[4];
        raf.readFully(b);
        return readLong(b, 0, le);
    }
}
