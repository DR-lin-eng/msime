#![allow(unsafe_code)]
use crate::tests::host_options_with_main_dictionary;
fn call_android_data(
    function: unsafe extern "C" fn(*const u8, usize) -> *mut std::ffi::c_char,
    request: serde_json::Value,
) -> serde_json::Value {
    let bytes = serde_json::to_vec(&request).unwrap();
    let pointer = unsafe { function(bytes.as_ptr(), bytes.len()) };
    assert!(!pointer.is_null());
    let value =
        unsafe { serde_json::from_slice(std::ffi::CStr::from_ptr(pointer).to_bytes()).unwrap() };
    unsafe { crate::msime_client_string_free(pointer) };
    value
}
use crate::msime_client_dictionary;
use msime_client_services::dictionary::{
    collections::DictionaryCollectionsStore, personal::PersonalDictionaryStore,
};
use serde_json::json;

fn scel(word: &str) -> Vec<u8> {
    let mut bytes = vec![0; 0x2628];
    bytes[..12].copy_from_slice(b"\x40\x15\x00\x00DCS\x01\x01\x00\x00\x00");
    bytes[0x120..0x124].copy_from_slice(&1u32.to_le_bytes());
    bytes[0x124..0x128].copy_from_slice(&1u32.to_le_bytes());
    bytes[0x1540..0x1544].copy_from_slice(&2u32.to_le_bytes());
    let mut offset = 0x1544;
    for (id, value) in ["ni", "hao"].into_iter().enumerate() {
        let entry: Vec<u8> = (id as u16)
            .to_le_bytes()
            .into_iter()
            .chain((value.len() as u16 * 2).to_le_bytes())
            .chain(value.encode_utf16().flat_map(u16::to_le_bytes))
            .collect();
        bytes[offset..offset + entry.len()].copy_from_slice(&entry);
        offset += entry.len();
    }
    bytes.extend(1u16.to_le_bytes());
    bytes.extend(4u16.to_le_bytes());
    bytes.extend(0u16.to_le_bytes());
    bytes.extend(1u16.to_le_bytes());
    bytes.extend((word.encode_utf16().count() as u16 * 2).to_le_bytes());
    bytes.extend(word.encode_utf16().flat_map(u16::to_le_bytes));
    bytes.extend(0u16.to_le_bytes());
    bytes
}
#[test]
fn sogou_public_api_persists_selection_and_rejects_unknown_fields() {
    let root = tempfile::tempdir().unwrap();
    let options = host_options_with_main_dictionary(root.path());
    let call = |action| {
        call_android_data(
            msime_client_dictionary,
            json!({"options":options,"action":action}),
        )
    };
    let status = call(json!({"operation":"sogou","command":"status"}));
    assert_eq!(status["ok"], true, "{status}");
    assert_eq!(status["value"]["installed"], json!([]));
    let saved = call(
        json!({"operation":"sogou","command":"configure","groups":[97],"dictionaries":[],"automatic":false}),
    );
    assert_eq!(saved["ok"], true, "{saved}");
    assert_eq!(
        call(json!({"operation":"sogou","command":"status"}))["value"]["settings"]["groups"],
        json!([97])
    );
    assert_eq!(
        call(json!({"operation":"sogou","command":"status","url":"https://example.test"}))["ok"],
        false
    );
}
#[test]
fn sogou_downloaded_collection_reaches_engine_and_update_removes_stale_words() {
    let root = tempfile::tempdir().unwrap();
    let options = host_options_with_main_dictionary(root.path());
    let collections = DictionaryCollectionsStore::new(
        root.path(),
        PersonalDictionaryStore::new(root.path().join("PersonalDictionary")),
    );
    collections.install_sogou(101, &scel("测试")).unwrap();
    let pending = call_android_data(
        msime_client_dictionary,
        json!({"options":options,"action":{"operation":"sogou","command":"pending"}}),
    );
    assert_eq!(pending["value"]["pending_count"], 1);
    let sync = || {
        call_android_data(
            msime_client_dictionary,
            json!({"options":options,"action":{"operation":"sogou","command":"synchronize"}}),
        )
    };
    let result = sync();
    assert_eq!(result["ok"], true, "{result}");
    assert_eq!(result["value"]["failed_count"], 0, "{result}");
    let connection =
        rusqlite::Connection::open(root.path().join("dictionaries/msime-pinyin.db")).unwrap();
    assert_eq!(
        connection
            .query_row("SELECT value FROM tbl_2_n", [], |r| r.get::<_, String>(0))
            .unwrap(),
        "测试"
    );
    collections.install_sogou(101, &scel("测示")).unwrap();
    assert_eq!(sync()["ok"], true);
    assert_eq!(
        connection
            .query_row("SELECT value FROM tbl_2_n", [], |r| r.get::<_, String>(0))
            .unwrap(),
        "测示"
    );
    let id = collections.load().unwrap().collections[0].id.to_string();
    collections.set_enabled(&id, false).unwrap();
    assert_eq!(sync()["ok"], true);
    assert_eq!(
        connection
            .query_row("SELECT count(*) FROM tbl_2_n", [], |r| r.get::<_, u32>(0))
            .unwrap(),
        0
    );
}
#[test]
fn sogou_synchronization_uses_existing_quiesce_policy() {
    assert!(crate::settings_dictionary::action_requires_quiesce(
        &json!({"operation":"sogou","command":"synchronize"})
    ));
    for command in ["status", "configure", "install", "check", "catalog"] {
        assert!(!crate::settings_dictionary::action_requires_quiesce(
            &json!({"operation":"sogou","command":command})
        ));
    }
}
