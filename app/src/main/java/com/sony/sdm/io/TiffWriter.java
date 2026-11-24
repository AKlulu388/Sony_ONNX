package com.sony.sdm.io;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** 16-bit 未压缩 RGB TIFF 写入，对应 writers.write_tiff16。 */
public final class TiffWriter {

    private TiffWriter() {}

    public static void write16(File out, float[][] rgb, int h, int w) throws Exception {
        int samples = 3;
        int pixels = w * h;

        // 图像数据：逐行交错 RGB，uint16 LE
        ByteBuffer image = ByteBuffer.allocateDirect(pixels * samples * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                for (int c = 0; c < 3; c++) {
                    int v = Math.round(Math.min(Math.max(rgb[c][i], 0f), 1f) * 65535f);
                    image.putShort((short) v);
                }
            }
        }

        // 组装 IFD（标签按编号排序）
        ByteArrayOutputStream ifd = new ByteArrayOutputStream();
        ByteArrayOutputStream extra = new ByteArrayOutputStream();

        int entryCount = 10;
        // 预留：IFD 之后紧跟 extra 区，先算布局
        // Header 8B + IFD(2 + 12*n + 4) + extra + image
        int ifdOffset = 8;
        int ifdSize = 2 + 12 * entryCount + 4;
        int extraStart = ifdOffset + ifdSize;

        int bpsExtra = extraStart;
        extra.write(new byte[]{16, 0, 16, 0, 16, 0}); // BitsPerSample 3×uint16

        int imageOffset = extraStart + extra.size();

        writeEntry(ifd, 256, 4, 1, w);                       // ImageWidth
        writeEntry(ifd, 257, 4, 1, h);                       // ImageLength
        writeEntry(ifd, 258, 3, 3, bpsExtra);                // BitsPerSample
        writeEntry(ifd, 259, 3, 1, 1);                       // Compression = 1
        writeEntry(ifd, 262, 3, 1, 2);                       // Photometric = RGB
        writeEntry(ifd, 273, 4, 1, imageOffset);              // StripOffsets
        writeEntry(ifd, 277, 3, 1, samples);                 // SamplesPerPixel
        writeEntry(ifd, 278, 4, 1, h);                       // RowsPerStrip
        writeEntry(ifd, 279, 4, 1, pixels * samples * 2);  // StripByteCounts
        writeEntry(ifd, 284, 3, 1, 1);                       // PlanarConfiguration

        try (FileOutputStream fos = new FileOutputStream(out)) {
            // Header
            fos.write(new byte[]{'I', 'I'});
            fos.write(le16((short) 42));
            fos.write(le32(ifdOffset));

            // IFD
            fos.write(le16((short) entryCount));
            fos.write(ifd.toByteArray());
            fos.write(le32(0)); // next IFD

            // Extra
            fos.write(extra.toByteArray());

            // Image
            byte[] img = new byte[image.capacity()];
            image.rewind();
            image.get(img);
            fos.write(img);
        }
    }

    private static void writeEntry(ByteArrayOutputStream out, int tag, int type,
                                   int count, int valueOrOffset) throws Exception {
        out.write(le16((short) tag));
        out.write(le16((short) type));
        out.write(le32(count));
        out.write(le32(valueOrOffset));
    }

    private static byte[] le16(short v) {
        return new byte[]{(byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF)};
    }

    private static byte[] le32(int v) {
        return new byte[]{
                (byte) (v & 0xFF), (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF), (byte) ((v >> 24) & 0xFF)};
    }
}
