/* ===== 界面逻辑 ===== */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);

  const state = {
    mode: 'hires',
    files: [],
    options: {
      format: 'dng',
      cas: 0.30,
      nrStrength: 1.0,
      detail: 3,
      noise: 'std',
      precise: false,
    },
    running: false,
  };

  /* 各模式下需要显示的参数行 */
  const MODE_ROWS = {
    hires: ['format', 'cas', 'detail', 'noise', 'precise'],
    nr: ['format', 'nr-strength', 'detail', 'noise', 'precise'],
    convert: ['format'],
  };

  /* ---------- 模式切换 ---------- */
  function bindModeSegment() {
    document.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.seg-btn').forEach((b) => b.classList.remove('is-active'));
        btn.classList.add('is-active');
        state.mode = btn.dataset.mode;
        applyMode();
      });
    });
  }

  function applyMode() {
    const rows = MODE_ROWS[state.mode] || [];
    document.querySelectorAll('.row').forEach((row) => {
      row.hidden = !rows.includes(row.dataset.param);
    });
  }

  /* ---------- 小分段控件（输出格式 / 噪声级别） ---------- */
  function bindSmSegments() {
    document.querySelectorAll('.segmented-sm').forEach((group) => {
      group.querySelectorAll('.sm-btn').forEach((btn) => {
        btn.addEventListener('click', () => {
          group.querySelectorAll('.sm-btn').forEach((b) => b.classList.remove('is-active'));
          btn.classList.add('is-active');
          const key = group.closest('.row').dataset.param;
          if (key === 'format') state.options.format = btn.dataset.value;
          if (key === 'noise') state.options.noise = btn.dataset.value;
        });
      });
    });
  }

  /* ---------- 滑杆 ---------- */
  function bindSliders() {
    const cas = $('casSlider'), nr = $('nrSlider');
    cas.addEventListener('input', () => {
      state.options.cas = parseFloat(cas.value);
      $('casValue').textContent = state.options.cas.toFixed(2);
    });
    nr.addEventListener('input', () => {
      state.options.nrStrength = parseFloat(nr.value);
      $('nrValue').textContent = state.options.nrStrength.toFixed(2);
    });
  }

  /* ---------- 细节档位步进器 ---------- */
  function bindStepper() {
    const min = 1, max = 5;
    $('detailMinus').addEventListener('click', () => {
      state.options.detail = Math.max(min, state.options.detail - 1);
      $('detailValue').textContent = state.options.detail;
    });
    $('detailPlus').addEventListener('click', () => {
      state.options.detail = Math.min(max, state.options.detail + 1);
      $('detailValue').textContent = state.options.detail;
    });
  }

  /* ---------- 开关 ---------- */
  function bindSwitch() {
    $('preciseSwitch').addEventListener('change', (e) => {
      state.options.precise = e.target.checked;
    });
  }

  /* ---------- 文件列表 ---------- */
  function renderFiles() {
    const list = $('fileList');
    list.innerHTML = '';
    state.files.forEach((f, idx) => {
      const li = document.createElement('li');
      li.className = 'file-item';
      const meta = document.createElement('div');
      meta.className = 'file-meta';
      const name = document.createElement('div');
      name.className = 'file-name';
      name.textContent = f.name;
      const sub = document.createElement('div');
      sub.className = 'file-sub';
      sub.textContent = Engine.fmtSize(f.size);
      meta.appendChild(name);
      meta.appendChild(sub);

      const btn = document.createElement('button');
      btn.className = 'remove-btn';
      btn.textContent = '移除';
      btn.addEventListener('click', () => {
        state.files.splice(idx, 1);
        renderFiles();
      });

      li.appendChild(meta);
      li.appendChild(btn);
      list.appendChild(li);
    });
  }

  function bindFilePicker() {
    const card = $('dropCard'), input = $('fileInput');
    card.addEventListener('click', () => input.click());
    input.addEventListener('change', () => {
      Engine.pickFiles(input, (files) => {
        files.forEach((f) => {
          if (!state.files.some((x) => x.name === f.name && x.size === f.size)) {
            state.files.push(f);
          }
        });
        renderFiles();
        input.value = '';
      });
    });
  }

  /* ---------- 模型导入 ---------- */
  function refreshModel(info) {
    const label = $('modelStatus');
    if (!label) return;
    if (info && info.present) {
      label.textContent = info.name + ' · ' + Engine.fmtSize(info.size);
    } else {
      label.textContent = '未加载（点右侧导入 .onnx）';
    }
  }

  function bindModel() {
    const btn = $('modelBtn');
    if (btn) {
      btn.addEventListener('click', () => {
        if (!Engine.importModel()) log('当前环境不支持导入模型', 'err');
      });
    }
    Engine.onModelInfo(refreshModel);
    Engine.getModelInfo();
  }

  /* ---------- 日志 / 进度 ---------- */
  function log(text, tag) {
    const box = $('logBox');
    const line = document.createElement('div');
    if (tag) line.className = tag;
    line.textContent = text;
    box.appendChild(line);
    box.scrollTop = box.scrollHeight;
  }

  function setProgress(done, total) {
    const pct = total > 0 ? Math.round((done / total) * 100) : 0;
    $('progressFill').style.width = pct + '%';
    $('progressPct').textContent = pct + '%';
  }

  function setStatus(title, meta) {
    $('progressTitle').textContent = title;
    if (meta !== undefined) $('progressMeta').textContent = meta;
  }

  /* ---------- 结果 ---------- */
  function renderResult(items) {
    const group = $('resultGroup');
    const list = $('resultList');
    list.innerHTML = '';
    items.forEach((item) => {
      const li = document.createElement('li');
      li.className = 'result-item';
      const meta = document.createElement('div');
      meta.className = 'result-meta';
      const name = document.createElement('div');
      name.className = 'result-name';
      name.textContent = item.name;
      const sub = document.createElement('div');
      sub.className = 'result-sub';
      sub.textContent = item.sub || (item.base64 ? '' : '已存到下载目录');
      meta.appendChild(name);
      meta.appendChild(sub);

      if (item.base64) {
        const btn = document.createElement('button');
        btn.className = 'save-btn';
        btn.textContent = '保存';
        btn.addEventListener('click', () => {
          Engine.saveFile(item.name, item.base64, item.mime);
        });
        li.appendChild(meta);
        li.appendChild(btn);
      } else {
        li.appendChild(meta);
      }
      list.appendChild(li);
    });
    group.hidden = items.length === 0;
  }

  /* ---------- 开始处理 ---------- */
  function bindStart() {
    const btn = $('startBtn');
    btn.addEventListener('click', async () => {
      if (state.running) return;
      if (!state.files.length) {
        log('请先选择 RAW 文件', 'err');
        return;
      }

      state.running = true;
      btn.disabled = true;
      $('progressGroup').hidden = false;
      $('logBox').innerHTML = '';
      setProgress(0, 1);
      setStatus('准备中…', state.mode === 'convert' ? 'ARW → DNG' : '');

      const started = Date.now();
      try {
        await Engine.runJob({
          files: state.files,
          mode: state.mode,
          options: Object.assign({}, state.options),
          onLog: log,
          onProgress: (done, total) => {
            setProgress(done, total);
            setStatus('处理中…', '已用 ' + ((Date.now() - started) / 1000).toFixed(1) + 's');
          },
          onResult: (result) => {
            renderResult(result.files || []);
            setStatus('完成', '共 ' + (result.files || []).length + ' 个输出');
          },
        });
      } catch (e) {
        log(e.message || String(e), 'err');
        setStatus('未开始', '');
      } finally {
        state.running = false;
        btn.disabled = false;
      }
    });
  }

  /* ---------- 初始化 ---------- */
  document.addEventListener('DOMContentLoaded', () => {
    bindModeSegment();
    bindSmSegments();
    bindSliders();
    bindStepper();
    bindSwitch();
    bindFilePicker();
    bindStart();
    bindModel();
    applyMode();
  });
})();
