# 本机自适应预测

拼音和双拼的同长度词典候选在开启「学习」与「个人上下文」后，使用既有词对记录训练轻量神经排序头。九键对消费同一段数字的词典候选复用同一模型。样本不足时沿用现有词频和 n 元排序；固定首选、非拼音方案、补全和部分输入不跨界比较。每次选择只增加本地统计，不保证每次预测都更准。

本地预测保留当前焦点最近 512 个 Unicode 字符，失焦和清空上下文时丢弃。个人数据清理、删除词条后重建排序头；重启从既有个人日志中的最多 512 条高频词对恢复排序头，其余个人计数完整保留，后续选词继续在线训练。云候选仍只接收原有 1024 字节窗口。已有句子 Transformer 的位置容量不变，按模型实际容量取稳定尾部窗口。

默认编入设备推理适配；没有兼容 ONNX Runtime 或没有设备提供者时，自动运行同权重纯 Rust CPU。仅 CPU 的嵌入构建可用 `cargo build -p msime-engine --no-default-features`。Wasm 不依赖 ONNX Runtime。

宿主可通过进程环境指定：

| 变量 | 值与作用 |
| --- | --- |
| `MSIME_PREDICTION_DEVICE` | `auto`（默认）：先尝试 NPU，再 GPU，最后 CPU；`npu` 或 `gpu` 限定尝试类型，失败仍回 CPU；`cpu` 强制 CPU |
| `MSIME_ONNX_RUNTIME` | 宿主所提供的兼容动态库绝对路径；未设置时按系统库名查找，不下载 |

Apple 使用 CoreML 的 Neural Engine 或 GPU 计算单元，Android 使用禁用 CPU 设备的 NNAPI（实际为 NPU 或 GPU），Intel 设备可使用 OpenVINO NPU/GPU，Windows 可用 DirectML，NVIDIA 设备可用 CUDA。库需自带对应提供者，单有硬件不等于当前运行时支持它。`msime_engine::adaptive::prediction_backend()` 返回建立成功的执行提供者；执行错误会丢弃设备会话并回退 CPU。

固定特征网络仅 32 KiB 权重，每次最多比较 16 条。它不向云端传送原始输入，不下载或保存额外模型文件。设备初始化在后台执行，初始化期间及设备忙时使用 CPU；数据搬运仍有成本，实际效率需要在目标设备测量，不能由 Docker CPU 数据推断。

Docker 回归：`scripts/test-adaptive-prediction-docker.sh`。包括在线学习、超过 64 字的上下文、重开、固定首选、学习关闭、清理数据、ONNX 与 CPU 数值对照和缺少设备时的回退。
