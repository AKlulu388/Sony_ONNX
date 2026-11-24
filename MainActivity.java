package com.sony.sdm;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.sony.sdm.bridge.NativeBridge;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final int REQ_RAW = 1;
    private static final int REQ_MODEL = 2;

    private WebView web;
    private NativeBridge bridge;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestPermissions();

        File modelFile = resolveModel();
        File outputDir = new File(getFilesDir(), "output");
        if (!outputDir.exists()) outputDir.mkdirs();

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        web.setWebViewClient(new WebViewClient());

        bridge = new NativeBridge(this, web, modelFile, outputDir);
        bridge.setModelPicker(() -> runOnUiThread(this::startModelPicker));
        web.addJavascriptInterface(bridge, "Android");

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                startActivityForResult(Intent.createChooser(i, "选择 RAW 文件"), REQ_RAW);
                return true;
            }
        });

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    /** 已导入的模型（私有目录）优先，其次 assets 内置模型。 */
    private File resolveModel() {
        File dir = new File(getFilesDir(), "models");
        if (dir.exists()) {
            String[] local = dir.list();
            if (local != null) {
                for (String n : local) {
                    if (n.toLowerCase().endsWith(".onnx")) return new File(dir, n);
                }
            }
        }
        return copyModelFromAssets();
    }

    /** 供桥接调用：打开系统文件选择器挑 .onnx 模型。 */
    public void startModelPicker() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(Intent.createChooser(i, "选择 ONNX 模型"), REQ_MODEL);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{android.Manifest.permission.READ_MEDIA_IMAGES}, 3);
        } else if (Build.VERSION.SDK_INT >= 29) {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, 3);
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.Manifest.permission.READ_EXTERNAL_STORAGE}, 2);
        }
    }

    /** 把 assets/models 下的第一个 .onnx 拷到私有目录，返回模型文件。 */
    private File copyModelFromAssets() {
        try {
            String[] names = getAssets().list("models");
            if (names == null) return null;
            String onnx = null;
            for (String n : names) {
                if (n.toLowerCase().endsWith(".onnx")) { onnx = n; break; }
            }
            if (onnx == null) return null;
            File dir = new File(getFilesDir(), "models");
            if (!dir.exists()) dir.mkdirs();
            File dst = new File(dir, onnx);
            try (InputStream is = getAssets().open("models/" + onnx);
                 FileOutputStream fos = new FileOutputStream(dst)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            }
            return dst;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_MODEL) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                bridge.installModel(data.getData());
            }
            return;
        }
        if (req == REQ_RAW && filePathCallback != null) {
            Uri[] r = (res == RESULT_OK && data != null && data.getData() != null)
                    ? new Uri[]{data.getData()} : null;
            if (r != null && r.length > 0) bridge.setPendingUri(r[0]);
            filePathCallback.onReceiveValue(r);
            filePathCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
