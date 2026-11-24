/* ===== 引擎桥接层 =====
 * UI 只通过 Engine 调用原生能力；原生回调注册在 window.SdmBridge。
 * 协议：
 *   网页 → 原生：Android.processFile(JSON{mode, options, files})
 *   原生 → 网页：SdmBridge.onLog / onProgress / onResult / onError
 */
(function (global) {
  'use strict';

  const native = global.Android || null;
  let pending = null;
  let modelListener = null;

  global.SdmBridge = {
    onLog: function (arg) {
      if (pending && pending.onLog) pending.onLog(arg.text, arg.tag || '');
    },
    onProgress: function (arg) {
      if (pending && pending.onProgress) pending.onProgress(arg.done, arg.total);
    },
    onResult: function (arg) {
      const cb = pending; pending = null;
      if (cb && cb.onResult) cb.onResult(arg);
    },
    onError: function (arg) {
      const cb = pending; pending = null;
      if (cb && cb.onError) cb.onError(new Error(arg.message || '处理失败'));
    },
    onModelInfo: function (arg) {
      if (modelListener) modelListener(arg);
    },
  };

  function fmtSize(bytes) {
    if (bytes == null) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  function pickFiles(inputEl, cb) {
    const list = Array.prototype.slice.call(inputEl.files || []);
    cb(list);
  }

  function saveFile(name, base64, mime) {
    if (native && typeof native.saveFile === 'function') {
      native.saveFile(name, base64);
      return true;
    }
    const a = document.createElement('a');
    a.href = 'data:' + (mime || 'application/octet-stream') + ';base64,' + base64;
    a.download = name;
    document.body.appendChild(a);
    a.click();
    a.remove();
    return true;
  }

  function filesPresent(job) {
    return job.files && job.files.length > 0;
  }

  /**
   * 提交处理任务。返回 Promise，resolve 结果 JSON，reject Error。
   */
  function runJob(job) {
    if (!filesPresent(job)) {
      return Promise.reject(new Error('请先选择 RAW 文件'));
    }
    if (!native || typeof native.processFile !== 'function') {
      job.onLog('原生桥不可用：当前环境未接入引擎', 'err');
      return Promise.reject(new Error('引擎未接入'));
    }
    return new Promise(function (resolve, reject) {
      pending = { onLog: job.onLog, onProgress: job.onProgress, onResult: resolve, onError: reject };
      const payload = JSON.stringify({
        mode: job.mode,
        options: job.options,
        files: job.files.map(function (f) { return { name: f.name, size: f.size }; }),
      });
      native.processFile(payload);
    });
  }

  global.Engine = {
    isNative: !!native,
    fmtSize: fmtSize,
    pickFiles: pickFiles,
    runJob: runJob,
    saveFile: saveFile,
    onModelInfo: function (cb) { modelListener = cb; },
    getModelInfo: function () {
      if (!native || typeof native.modelInfo !== 'function') return;
      try {
        const info = JSON.parse(native.modelInfo());
        if (modelListener) modelListener(info);
      } catch (e) { /* 忽略 */ }
    },
    importModel: function () {
      if (!native || typeof native.importModel !== 'function') return false;
      native.importModel();
      return true;
    },
  };
})(window);
