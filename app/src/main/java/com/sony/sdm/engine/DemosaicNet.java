package com.sony.sdm.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * ONNX 超分网络封装，对应 pipeline.SuperDemosaic。
 * 瓦片固定 256×256 输入 → 1024×1024 输出。
 */
public class DemosaicNet implements AutoCloseable {

    public static final int TILE_IN = 256;
    public static final int SCALE = 4;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final boolean precise;

    public DemosaicNet(File modelFile, boolean precise) throws OrtException {
        this.precise = precise;
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (precise) {
            opts.setInterOpNumThreads(1);
            opts.setIntraOpNumThreads(1);
        }
        session = env.createSession(modelFile.getAbsolutePath(), opts);
        inputName = session.getInputNames().iterator().next();
    }

    /** tile: [8][256][256] float32 → [3][1024][1024] float32。 */
    public float[][][] runTile(float[][][] tile) throws OrtException {
        float[][][][] input = new float[1][8][TILE_IN][TILE_IN];
        for (int c = 0; c < 8; c++) System.arraycopy(tile[c], 0, input[0][c], 0, TILE_IN * TILE_IN);

        Map<String, OnnxTensor> inputs = new HashMap<>();
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input)) {
            inputs.put(inputName, tensor);
            try (OrtSession.Result result = session.run(inputs)) {
                OnnxTensor out = (OnnxTensor) result.get(0);
                float[][][][] arr = (float[][][][]) out.getValue();
                float[][][] dst = new float[3][TILE_IN * SCALE][TILE_IN * SCALE];
                for (int c = 0; c < 3; c++) {
                    for (int y = 0; y < TILE_IN * SCALE; y++) {
                        System.arraycopy(arr[0][c][y], 0, dst[c][y], 0, TILE_IN * SCALE);
                    }
                }
                return dst;
            }
        }
    }

    /** 输出有限且未越界，对应 _tile_ok。 */
    public static boolean tileOk(float[][][] o) {
        for (float[][] ch : o) {
            for (float[] row : ch) {
                for (float v : row) {
                    if (!Float.isFinite(v) || v >= 8.0f || v <= -1.0f) return false;
                }
            }
        }
        return true;
    }

    public boolean isPrecise() { return precise; }

    @Override
    public void close() {
        if (session != null) session.close();
    }
}
