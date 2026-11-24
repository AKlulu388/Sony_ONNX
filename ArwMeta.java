package com.sony.sdm.io;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 最小 ARW/TIFF EXIF 解析器，对照 Python exif.py：
 * 只取 DNG 回写需要的厂商/机型/方向/时间/镜头/曝光/光圈/ISO/焦距。
 * 不复制 MakerNote 整段，避免私签标签把渲染器搞坏。
 */
public class ArwMeta {

    public String make = "";
    public String model = "";
    public String software = "";
    public String datetime = "";
    public int orientation = 1;
    public String lensModel = "";
    public String lensMake = "";
    public String bodySerial = "";
    public double exposureTime = 0;
    public double fnumber = 0;
    public double focal = 0;
    public Integer iso = null;
    public double[] lensSpec = null;

    private static final int[] TYPE_SIZE = {0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8};

    private static class Entry {
        int type;
        int count;
        byte[] raw = new byte[4];
        int dataOffset;
    }

    public static ArwMeta read(String path) {
        ArwMeta meta = new ArwMeta();
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            byte[] head = new byte[8];
            raf.readFully(head);
            boolean le;
            if (head[0] == 'I' && head[1] == 'I') le = true;
            else if (head[0] == 'M' && head[1] == 'M') le = false;
            else return meta; // CR3 等容器，无 TIFF IFD 可读

            long ifd0 = readU32(head, 4, le);
            Map<Integer, Entry> ifd = readIfd(raf, ifd0, le);

            meta.make = ascii(ifd, raf, 0x010F, le);
            meta.model = ascii(ifd, raf, 0x0110, le);
            meta.software = ascii(ifd, raf, 0x0131, le);
            Integer orient = shortFirst(ifd, raf, 0x0112, le);
            if (orient != null) meta.orientation = orient;

            Entry exifPtr = ifd.get(0x8769);
            if (exifPtr != null) {
                long exifOff = readU32(exifPtr.raw, 0, le);
                Map<Integer, Entry> exif = readIfd(raf, exifOff, le);
                meta.datetime = ascii(exif, raf, 0x9003, le);
                meta.lensModel = ascii(exif, raf, 0xA434, le);
                meta.lensMake = ascii(exif, raf, 0xA433, le);
                meta.bodySerial = ascii(exif, raf, 0xA431, le);
                meta.exposureTime = rationalFirst(exif, raf, 0x829A, le);
                meta.fnumber = rationalFirst(exif, raf, 0x829D, le);
                meta.focal = rationalFirst(exif, raf, 0x920A, le);
                Integer isoVal = isoValue(exif, raf, 0x8827, le);
                if (isoVal != null) meta.iso = isoVal;
                meta.lensSpec = rational4(exif, raf, 0xA432, le);
            }
        } catch (Exception ignored) {
            // 元数据读不到不阻断转换
        }
        return meta;
    }

    private static Map<Integer, Entry> readIfd(RandomAccessFile raf, long off, boolean le)
            throws Exception {
        Map<Integer, Entry> out = new HashMap<>();
        if (off <= 0) return out;
        raf.seek(off);
        int n = readU16(raf, le);
        for (int i = 0; i < n; i++) {
            byte[] e = new byte[8];
            raf.readFully(e);
            Entry entry = new Entry();
            int tag = readU16(e, 0, le);
            entry.type = readU16(e, 2, le);
            entry.count = (int) readU32(e, 4, le);
            raf.readFully(entry.raw);
            int size = TYPE_SIZE[entry.type] * entry.count;
            entry.dataOffset = size <= 4 ? -1 : (int) readU32(entry.raw, 0, le);
            out.put(tag, entry);
        }
        return out;
    }

    private static byte[] data(Entry e, RandomAccessFile raf) throws Exception {
        int size = TYPE_SIZE[e.type] * e.count;
        if (e.dataOffset < 0) {
            byte[] r = new byte[size];
            System.arraycopy(e.raw, 0, r, 0, size);
            return r;
        }
        byte[] r = new byte[size];
        raf.seek(e.dataOffset);
        raf.readFully(r);
        return r;
    }

    private static String ascii(Map<Integer, Entry> ifd, RandomAccessFile raf, int tag,
                                boolean le) {
        Entry e = ifd.get(tag);
        if (e == null) return "";
        try {
            byte[] d = data(e, raf);
            int len = 0;
            while (len < d.length && d[len] != 0) len++;
            return new String(d, 0, len, StandardCharsets.UTF_8).trim();
        } catch (Exception ex) {
            return "";
        }
    }

    private static Integer shortFirst(Map<Integer, Entry> ifd, RandomAccessFile raf,
                                      int tag, boolean le) {
        Entry e = ifd.get(tag);
        if (e == null) return null;
        try {
            byte[] d = data(e, raf);
            return readU16(d, 0, le);
        } catch (Exception ex) {
            return null;
        }
    }

    private static Integer isoValue(Map<Integer, Entry> ifd, RandomAccessFile raf,
                                    int tag, boolean le) {
        Entry e = ifd.get(tag);
        if (e == null) return null;
        try {
            byte[] d = data(e, raf);
            if (e.type == 3) return (int) readU16(d, 0, le);
            if (e.type == 4) return (int) readU32(d, 0, le);
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static double rationalFirst(Map<Integer, Entry> ifd, RandomAccessFile raf,
                                        int tag, boolean le) {
        double[] r = rationalN(ifd, raf, tag, le, 1);
        return r.length > 0 ? r[0] : 0;
    }

    private static double[] rational4(Map<Integer, Entry> ifd, RandomAccessFile raf,
                                      int tag, boolean le) {
        return rationalN(ifd, raf, tag, le, 4);
    }

    private static double[] rationalN(Map<Integer, Entry> ifd, RandomAccessFile raf,
                                       int tag, boolean le, int want) {
        Entry e = ifd.get(tag);
        if (e == null) return new double[0];
        try {
            byte[] d = data(e, raf);
            int n = Math.min(e.count, want);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) {
                int num = (int) readS32(d, i * 8, le);
                int den = (int) readS32(d, i * 8 + 4, le);
                out[i] = den != 0 ? (double) num / den : 0;
            }
            return out;
        } catch (Exception ex) {
            return new double[0];
        }
    }

    private static int readU16(RandomAccessFile raf, boolean le) throws Exception {
        byte[] b = new byte[2];
        raf.readFully(b);
        return readU16(b, 0, le);
    }

    private static int readU16(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                  : ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    private static long readU32(byte[] b, int o, boolean le) {
        return le ? (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8)
                | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24)
               : ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16)
                | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL);
    }

    private static long readS32(byte[] b, int o, boolean le) {
        return readU32(b, o, le);
    }
}
