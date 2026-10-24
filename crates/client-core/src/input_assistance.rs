//! 充电夜间学习条件和验证码识别；宿主提供电源、时间和用户操作。

/// 夜间为本地 00:00–06:00，断电、忙碌、禁学或无痕时不训练。
pub fn night_learning_allowed(
    charging: bool,
    idle: bool,
    hour: u8,
    learning: bool,
    private: bool,
) -> bool {
    charging && idle && hour < 6 && learning && !private
}

/// 只识别带明确验证码语义的单个 4–8 位数字串，不把号码和多个代码猜成验证码。
pub fn verification_code(text: &str) -> Option<&str> {
    if text.len() > 4096 || text.chars().any(|ch| ch == '\0') {
        return None;
    }
    let lowercase = text.to_ascii_lowercase();
    if ![
        "验证码",
        "校验码",
        "动态码",
        "verification code",
        "security code",
        "one-time",
        "one time",
        " otp",
    ]
    .iter()
    .any(|cue| lowercase.contains(cue))
        && !lowercase.starts_with("otp")
    {
        return None;
    }
    let mut found = None;
    let bytes = text.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if !bytes[index].is_ascii_digit() {
            index += 1;
            continue;
        }
        let start = index;
        while index < bytes.len() && bytes[index].is_ascii_digit() {
            index += 1;
        }
        if !(4..=8).contains(&(index - start)) {
            continue;
        }
        if start > 0 && bytes[start - 1].is_ascii_alphanumeric()
            || index < bytes.len() && bytes[index].is_ascii_alphabetic()
        {
            continue;
        }
        let value = &text[start..index];
        if found.is_some_and(|previous| previous != value) {
            return None;
        }
        found = Some(value);
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn night_requires_all_device_and_privacy_conditions() {
        assert!(night_learning_allowed(true, true, 2, true, false));
        for (charging, idle, hour, learning, private) in [
            (false, true, 2, true, false),
            (true, false, 2, true, false),
            (true, true, 6, true, false),
            (true, true, 2, false, false),
            (true, true, 2, true, true),
            (true, true, 255, true, false),
        ] {
            assert!(!night_learning_allowed(
                charging, idle, hour, learning, private
            ));
        }
    }
    #[test]
    fn codes_need_unambiguous_context() {
        assert_eq!(
            verification_code("合成短信：验证码 482615，请勿转发"),
            Some("482615")
        );
        assert_eq!(
            verification_code("Your verification code is 6194."),
            Some("6194")
        );
        for text in [
            "订单 482615",
            "验证码 123456789",
            "验证码 1234 和 5678",
            "验证码 AB482615CD",
            "验证码\0 482615",
        ] {
            assert!(verification_code(text).is_none(), "{text}");
        }
    }
}
