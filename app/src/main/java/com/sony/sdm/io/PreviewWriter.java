package com.sony.sdm.io;

import android.graphics.Bitmap;

import com.sony.sdm.engine.ImageMath;

import java.io.File;
import java.io.FileOutputStream;

/** 预览 JPEG 写入，对应 cli.py 中的 preview 输出。 */
public final class PreviewWriter {

    private PreviewWriter() {}

    public static void write(File out, float[][] rgb, int h, int w,
                             float[] wb, float exposure) throws Exception {
        int[] pixels = ImageMath.toDisplay(rgb, h, w, wb, exposure);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, fos);
        }
        bitmap.recycle();
    }
}
