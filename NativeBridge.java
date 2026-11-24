package com.sony.sdm.bridge;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.sony.sdm.engine.SdmEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 网页 ↔ 原生引擎桥接。网页通过 window.Android 调用，回调走 window.SdmBridge。 */
public class NativeBridge {

    private final Context ctx;
    private final WebView web;
    private final File modelsDir;
    private final File outputDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Uri pendingUri;
    private Runnable modelPicker;

    public NativeBridge(Context ctx, WebView web, File modelsDir, File outputDir) {
        this.ctx = ctx;
        this.web = web;
        this.modelsDir = modelsDir;
        this.outputDir = outputDir;
    }

    public void setPendingUri(Uri uri) {
        this.pendingUri = uri;
    }

    /** 由 Activity 注册：点「导入模型」时在主线程打开系统文件选择器。 */
    public void setModelPicker(Runnable picker) {
        this.modelPicker = picker;
    }

    /** 网页请求打开系统文件选择器，导入一个 .onnx 模型。 */
    @JavascriptInterface
    public void importModel() {
        if (modelPicker != null) modelPicker.run();
    }

    /** 返回当前模型状态 JSON：{present, name, size, count}。 */
    @JavascriptInterface
    public String modelInfo() {
        try {
            JSONObject o = new JSONObject();
            File[] files = listOnnx();
            boolean present = files.length > 0;
            o.put("present", present);
            o.put("count", files.length);
            if (present) {
                o.put("name", files[0].getName());
                o.put("size", files[0].length());
            } else {
                o.put("name", "");
                o.put("size", 0);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"present\":false}";
        }
    }

    private File[] listOnnx() {
        if (modelsDir == null || !modelsDir.isDirectory()) return new File[0];
        File[] all = modelsDir.listFiles();
        if (all == null) return new File[0];
        java.util.List<File> out = new java.util.ArrayList<>();
        for (File f : all) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".onnx")) out.add(f);
        }
        return out.toArray(new File[0]);
    }

    /** 把选中的模型拷进私有目录，校验 ONNX 魔数；保留目录内已有的其它模型。 */
    public void installModel(Uri uri) {
        io.submit(() -> {
            try {
                if (!modelsDir.exists()) modelsDir.mkdirs();

                String name = queryDisplayName(uri);
                if (name == null || name.isEmpty()) name = "imported.onnx";
                if (!name.toLowerCase().endsWith(".onnx")) name = name + ".onnx";
                File dst = new File(modelsDir, sanitize(name));

                try (InputStream is = ctx.getContentResolver().openInputStream(uri);
                     FileOutputStream fos = new FileOutputStream(dst)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }

                // 校验开头字节：ONNX protobuf 以 0x08 开头
                try (FileInputStream fis = new FileInputStream(dst)) {
                    int head = fis.read();
                    if (head != 0x08) {
                        dst.delete();
                        throw new Exception("不是有效的 ONNX 文件（开头字节 0x"
                                + Integer.toHexString(head < 0 ? 0 : head) + "）");
                    }
                }

                File[] all = listOnnx();
                emitModelInfo(true, dst.getName(), dst.length());
                emitLog("模型已导入：" + dst.getName() + "（目录内共 " + all.length + " 个）", "ok");
            } catch (Exception e) {
                emitError("模型导入失败：" + e.getMessage());
            }
        });
    }

    /** 网页提交任务：{mode, options:{...}, files:[{name}]} */
    @JavascriptInterface
    public void processFile(String json) {
        io.submit(() -> {
            try {
                JSONObject job = new JSONObject(json);
                SdmEngine.Options o = new SdmEngine.Options();
                o.mode = job.optString("mode", "hires");
                JSONObject opt = job.optJSONObject("options");
                if (opt != null) {
                    o.format = opt.optString("format", "dng");
                    o.cas = opt.optDouble("cas", 0.30);
                    o.nrStrength = opt.optDouble("nrStrength", 1.0);
                    o.detail = opt.optInt("detail", 3);
                    o.noise = opt.optString("noise", "std");
                    o.precise = opt.optBoolean("precise", false);
                }

                if (pendingUri == null) {
                    emitError("尚未选择文件");
                    return;
                }
                File src = copyUriToCache(pendingUri);

                SdmEngine engine = new SdmEngine(modelsDir, outputDir);
                engine.process(src.getAbsolutePath(), o,
                        (msg, tag) -> emitLog(msg, tag),
                        (done, total) -> emitProgress(done, total),
                        (files, w, h, seconds) -> {
                            JSONArray arr = new JSONArray();
                            for (SdmEngine.OutputFile f : files) {
                                File saved = copyToDownloads(new File(f.path));
                                JSONObject item = new JSONObject();
                                try {
                                    item.put("name", saved != null ? saved.getName() : f.name);
                                    item.put("mime", f.mime);
                                } catch (Exception ignored) {}
                                arr.put(item);
                            }
                            JSONObject result = new JSONObject();
                            try {
                                result.put("files", arr);
                                result.put("width", w);
                                result.put("height", h);
                                result.put("seconds", seconds);
                            } catch (Exception ignored) {}
                            emit("onResult", result.toString());
                        });
            } catch (Exception e) {
                emitError(e.getMessage());
            }
        });
    }

    /** 通用文件保存（base64 → 下载目录），网页结果按钮调用。 */
    @JavascriptInterface
    public void saveFile(String name, String base64) {
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, sanitize(name));
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            os.write(data);
            os.close();
        } catch (Exception ignored) {}
    }

    // ---- 内部 ----

    private String queryDisplayName(Uri uri) {
        String name = null;
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return name;
    }

    private File copyUriToCache(Uri uri) throws Exception {
        File dst = new File(ctx.getCacheDir(), "input_" + System.currentTimeMillis() + ".arw");
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        }
        return dst;
    }

    private File copyToDownloads(File src) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, src.getName());
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) return null;
            try (InputStream is = new FileInputStream(src);
                 OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            return src;
        } catch (Exception e) {
            return null;
        }
    }

    private void emitLog(String msg, String tag) {
        try {
            JSONObject arg = new JSONObject();
            arg.put("text", msg);
            arg.put("tag", tag == null ? "" : tag);
            emit("onLog", arg.toString());
        } catch (Exception ignored) {}
    }

    private void emitProgress(int done, int total) {
        try {
            JSONObject arg = new JSONObject();
            arg.put("done", done);
            arg.put("total", total);
            emit("onProgress", arg.toString());
        } catch (Exception ignored) {}
    }

    private void emitModelInfo(boolean present, String name, long size) {
        try {
            JSONObject arg = new JSONObject();
            arg.put("present", present);
            arg.put("name", name);
            arg.put("size", size);
            emit("onModelInfo", arg.toString());
        } catch (Exception ignored) {}
    }

    private void emitError(String message) {
        try {
            JSONObject arg = new JSONObject();
            arg.put("message", message);
            emit("onError", arg.toString());
        } catch (Exception ignored) {}
    }

    private void emit(String fn, String argJson) {
        web.post(() -> web.evaluateJavascript(
                "window.SdmBridge && window.SdmBridge." + fn + "(" + argJson + ")", null));
    }

    private static String sanitize(String name) {
        return name.replace("/", "_").replace("\\", "_").replace(":", "_")
                .replace("*", "_").replace("?", "_").replace("\"", "_")
                .replace("<", "_").replace(">", "_").replace("|", "_");
    }
}
