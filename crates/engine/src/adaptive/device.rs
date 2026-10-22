//! 设备执行后端只计算固定的非线性网络，个人排序头留在本机 Rust 状态中。

use super::{HIDDEN, INPUTS, MAX_CANDIDATES};
type Inputs = [[f32; INPUTS]; MAX_CANDIDATES];
type Embeddings = [[f32; HIDDEN]; MAX_CANDIDATES];

#[cfg(all(feature = "device-inference", not(target_arch = "wasm32")))]
mod onnx {
    use super::{Embeddings, Inputs, HIDDEN, INPUTS, MAX_CANDIDATES};
    use ort::{ep, session::Session, value::Tensor};
    use std::sync::{Mutex, Once, OnceLock};

    struct Device {
        session: Session,
        name: &'static str,
    }
    static DEVICE: OnceLock<Mutex<Option<Device>>> = OnceLock::new();
    static START: Once = Once::new();

    fn slot() -> &'static Mutex<Option<Device>> {
        let slot = DEVICE.get_or_init(|| Mutex::new(None));
        START.call_once(|| {
            // 设备编译与探测不占用击键线程，初始化期间直接执行同权重 CPU。
            let _ = std::thread::Builder::new()
                .name("msime-prediction-device".into())
                .spawn(move || {
                    let device = load();
                    if let Ok(mut state) = slot.lock() {
                        *state = device;
                    }
                });
        });
        slot
    }

    fn load() -> Option<Device> {
        let preference =
            std::env::var("MSIME_PREDICTION_DEVICE").unwrap_or_else(|_| "auto".to_owned());
        if !matches!(preference.as_str(), "auto" | "npu" | "gpu") {
            return None;
        }
        let library = std::env::var_os("MSIME_ONNX_RUNTIME").unwrap_or_else(|| {
            if cfg!(target_os = "windows") {
                "onnxruntime.dll".into()
            } else if cfg!(target_vendor = "apple") {
                "libonnxruntime.dylib".into()
            } else {
                "libonnxruntime.so".into()
            }
        });
        // 加载失败返回错误，不触发下载，也不让缺少运行时阻断输入。
        ort::init_from(library)
            .ok()?
            .with_name("msime-local-prediction")
            .commit();
        let providers = [
            (
                "coreml-npu",
                false,
                ep::CoreML::default()
                    .with_compute_units(ep::coreml::ComputeUnits::CPUAndNeuralEngine)
                    .build(),
            ),
            (
                "nnapi-device",
                false,
                ep::NNAPI::default().with_disable_cpu(true).build(),
            ),
            (
                "openvino-npu",
                false,
                ep::OpenVINO::default().with_device_type("NPU").build(),
            ),
            (
                "coreml-gpu",
                true,
                ep::CoreML::default()
                    .with_compute_units(ep::coreml::ComputeUnits::CPUAndGPU)
                    .build(),
            ),
            ("cuda", true, ep::CUDA::default().build()),
            ("directml", true, ep::DirectML::default().build()),
            (
                "openvino-gpu",
                true,
                ep::OpenVINO::default().with_device_type("GPU").build(),
            ),
        ];
        for (name, gpu, provider) in providers {
            if preference == "npu" && gpu || preference == "gpu" && !gpu {
                continue;
            }
            // 禁止设备会话把不支持的节点静默交给 CPU，失败后再试下一个后端。
            let attempt = || -> ort::Result<Session> {
                Session::builder()?
                    .with_intra_threads(1)?
                    .with_parallel_execution(false)?
                    .with_memory_pattern(false)?
                    .with_config_entry("session.disable_cpu_ep_fallback", "1")?
                    .with_execution_providers([provider.error_on_failure()])?
                    .commit_from_memory(include_bytes!("model.onnx"))
            };
            if let Ok(session) = attempt() {
                return Some(Device { session, name });
            }
        }
        None
    }

    pub(super) fn embeddings(input: &Inputs) -> Option<Embeddings> {
        let mut device = slot().try_lock().ok()?;
        let selected = device.as_mut()?;
        let mut run = || -> ort::Result<Embeddings> {
            let data: Vec<f32> = input.iter().flatten().copied().collect();
            let tensor = Tensor::from_array(([MAX_CANDIDATES, INPUTS], data))?;
            let outputs = selected.session.run(ort::inputs![tensor])?;
            let (_, values) = outputs[0].try_extract_tensor::<f32>()?;
            if values.len() != MAX_CANDIDATES * HIDDEN
                || values.iter().any(|value| !value.is_finite())
            {
                return Err(ort::Error::new("设备预测输出不完整或含非有限数值"));
            }
            let mut result = [[0.0; HIDDEN]; MAX_CANDIDATES];
            for (row, values) in result.iter_mut().zip(values.chunks_exact(HIDDEN)) {
                row.copy_from_slice(values);
            }
            Ok(result)
        };
        match run() {
            Ok(result) => Some(result),
            Err(_) => {
                *device = None;
                None
            }
        }
    }

    pub(super) fn backend() -> &'static str {
        slot()
            .try_lock()
            .ok()
            .and_then(|device| device.as_ref().map(|device| device.name))
            .unwrap_or("cpu")
    }
}

pub(super) fn embeddings(input: &Inputs) -> Option<Embeddings> {
    #[cfg(all(feature = "device-inference", not(target_arch = "wasm32")))]
    {
        onnx::embeddings(input)
    }
    #[cfg(not(all(feature = "device-inference", not(target_arch = "wasm32"))))]
    {
        let _ = input;
        None
    }
}

pub(super) fn backend() -> &'static str {
    #[cfg(all(feature = "device-inference", not(target_arch = "wasm32")))]
    {
        onnx::backend()
    }
    #[cfg(not(all(feature = "device-inference", not(target_arch = "wasm32"))))]
    {
        "cpu"
    }
}

#[cfg(all(test, feature = "device-inference", not(target_arch = "wasm32")))]
mod tests {
    use super::*;
    use ort::{session::Session, value::Tensor};

    #[test]
    fn onnx_cpu_matches_the_fallback_network() {
        let Ok(library) = std::env::var("MSIME_TEST_ONNX_RUNTIME") else {
            eprintln!("skipped: MSIME_TEST_ONNX_RUNTIME 未设置；Docker 设备图验证时必须提供运行时");
            return;
        };
        ort::init_from(library)
            .unwrap()
            .with_name("msime-local-prediction")
            .commit();
        let mut session = Session::builder()
            .unwrap()
            .with_intra_threads(1)
            .unwrap()
            .commit_from_memory(include_bytes!("model.onnx"))
            .unwrap();
        let mut inputs = [[0.0; INPUTS]; MAX_CANDIDATES];
        for (row, input) in inputs.iter_mut().enumerate() {
            for (column, value) in input.iter_mut().enumerate() {
                *value = ((row * 17 + column) % 19) as f32 / 19.0 - 0.5;
            }
        }
        let data: Vec<f32> = inputs.iter().flatten().copied().collect();
        let output = session
            .run(ort::inputs![Tensor::from_array((
                [MAX_CANDIDATES, INPUTS],
                data
            ))
            .unwrap()])
            .unwrap();
        let (shape, values) = output[0].try_extract_tensor::<f32>().unwrap();
        assert_eq!(&shape[..], &[MAX_CANDIDATES as i64, HIDDEN as i64]);
        let mut max_error = 0.0_f32;
        for (input, row) in inputs.iter().zip(values.chunks_exact(HIDDEN)) {
            let expected = crate::adaptive::cpu_embedding(input);
            for (&a, b) in row.iter().zip(expected) {
                max_error = max_error.max((a - b).abs());
            }
        }
        println!("onnx_cpu_max_absolute_error={max_error:.8}");
        assert!(max_error < 0.00001);
    }
}
