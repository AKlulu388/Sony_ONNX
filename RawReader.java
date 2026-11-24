package com.sony.sdm.jni;

/**
 * LibRaw 的 JNI 包装。原生库 libsdm-raw.so 由 cpp/ 编译产出。
 * 句柄为 LibRaw 实例指针，0 表示无效。
 */
public class RawReader {

    static {
        System.loadLibrary("sdm-raw");
    }

    /** 打开 RAW 文件并解包马赛克。返回句柄，失败返回 0。 */
    public native long open(String path);

    /** 返回 [width, height]（raw_image 尺寸）。 */
    public native int[] getDimensions(long handle);

    /** 单通道 uint16 马赛克平面，长度 w*h。 */
    public native short[] getRawPlane(long handle);

    /** 2x2 CFA 图案，按行展开为 4 个颜色索引。 */
    public native int[] getCfaPattern(long handle);

    /** LibRaw color_desc，如 "RGBG"。 */
    public native String getColorDesc(long handle);

    /** 每通道黑电平，最多 4 个。 */
    public native double[] getBlackLevels(long handle);

    /** 白电平。 */
    public native double getWhiteLevel(long handle);

    /** 相机白平衡增益 [R, G, B]。 */
    public native float[] getWhiteBalance(long handle);

    /** 日间白平衡增益 [R, G, B]（用于色彩矩阵锚定）。 */
    public native float[] getDaylightWb(long handle);

    /** 厂商名 make。 */
    public native String getMake(long handle);

    /** 机型 model。 */
    public native String getModel(long handle);

    /** 相机 RGB → XYZ(D65) 矩阵，行优先 9 个 float（cam_xyz 前 3×3）。 */
    public native float[] getCameraMatrix(long handle);

    /** 可见区域 [left, top, width, height]。 */
    public native int[] getVisibleArea(long handle);

    /** 释放 LibRaw 实例。 */
    public native void close(long handle);
}
