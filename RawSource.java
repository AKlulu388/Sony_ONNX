package com.sony.sdm.engine;

/** LibRaw 解出的原始数据源。 */
public class RawSource {
    public final short[] raw;
    public final int width;
    public final int height;
    public final double black;
    public final double white;
    public final float[] wb;
    public final int[][] pattern;
    public final String colorDesc;
    public String model = "SONY";
    public String make = "SONY";
    public float[] cam2xyz;
    public float[] neutral;

    /** 四相位 R / G1 / G2 / B，每个长度 (h/2)*(w/2)。 */
    public short[][] phases;

    public RawSource(short[] raw, int width, int height, double black, double white,
                     float[] wb, int[][] pattern, String colorDesc) {
        this.raw = raw;
        this.width = width;
        this.height = height;
        this.black = black;
        this.white = white;
        this.wb = wb;
        this.pattern = pattern;
        this.colorDesc = colorDesc;
    }
}
