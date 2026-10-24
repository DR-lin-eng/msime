//! Apple 公开粘贴入口复用的纯计算边界；不需要词库路径或输入会话。
use serde::Deserialize;
use std::ffi::c_char;

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct Request {
    text: String,
    #[serde(default)]
    allow_plain_digits: bool,
}

/// 识别验证码并返回 `{code:null|数字串}`；调用方负责用户粘贴意图、五分钟寿命与焦点。
/// # Safety
/// `request` 必须指向 `length` 个有效字节；返回字符串由 `msime_client_string_free` 释放。
#[no_mangle]
pub unsafe extern "C" fn msime_client_input_assistance(
    request: *const u8,
    length: usize,
) -> *mut c_char {
    super::response(|| {
        if request.is_null() || length > 32_768 {
            return Err("invalid input assistance buffer".to_owned());
        }
        // SAFETY: 有效范围由上述 C 调用契约保证，解码前已限制长度。
        let bytes = unsafe { std::slice::from_raw_parts(request, length) };
        let value: Request =
            serde_json::from_slice(bytes).map_err(|_| "invalid input assistance request")?;
        let plain = value.text.trim();
        let code =
            msime_client_core::input_assistance::verification_code(&value.text).or_else(|| {
                (value.allow_plain_digits
                    && (4..=8).contains(&plain.len())
                    && plain.bytes().all(|byte| byte.is_ascii_digit()))
                .then_some(plain)
            });
        Ok(serde_json::json!({"code":code}))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn detect(text: &str, explicit: bool) -> serde_json::Value {
        let input =
            serde_json::to_vec(&serde_json::json!({"text":text,"allow_plain_digits":explicit}))
                .unwrap();
        let raw = unsafe { msime_client_input_assistance(input.as_ptr(), input.len()) };
        let result = unsafe { std::ffi::CStr::from_ptr(raw) }.to_bytes().to_vec();
        unsafe { crate::msime_client_string_free(raw) };
        serde_json::from_slice(&result).unwrap()
    }
    #[test]
    fn explicit_paste_accepts_bare_code_but_automatic_capture_requires_a_cue() {
        assert!(detect("482615", false)["value"]["code"].is_null());
        assert_eq!(detect(" 482615 ", true)["value"]["code"], "482615");
        assert_eq!(
            detect("合成验证码 482615", false)["value"]["code"],
            "482615"
        );
        assert!(detect("123456789", true)["value"]["code"].is_null());
    }
}
