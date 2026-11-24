# 模型放置目录

把解密好的 ONNX 模型（每个约 92MB）放到本目录，例如：

```
assets/models/9981FFE2.onnx
```

首启时 `MainActivity` 会把 `models/` 下第一个 `.onnx` 拷到 App 私有目录并加载。
仓库内不附带模型文件（版权与体积原因）。模型缺失时，推理模式会提示缺少模型；
`convert`（ARW → DNG）模式不依赖模型。
