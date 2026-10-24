use msime_client_core::{clipboard::ClipboardHistoryStore, preferences::Preferences};
use rusqlite::Connection;
use serde_json::{json, Value};

fn main() {
    let temp = tempfile::tempdir().unwrap();
    let root = temp.path();
    for folder in ["resources", "user", "cache", "dictionaries"] {
        std::fs::create_dir(root.join(folder)).unwrap();
    }
    let journal = root.join("user").join(msime_engine::assets::USER_JOURNAL);
    let connection = Connection::open(&journal).unwrap();
    connection.execute_batch("CREATE TABLE personal_bigram(previous TEXT,word TEXT,count INTEGER CHECK(count>0),PRIMARY KEY(previous,word));
        CREATE TABLE personal_trigram(earlier TEXT,previous TEXT,word TEXT,count INTEGER CHECK(count>0),PRIMARY KEY(earlier,previous,word));
        INSERT INTO personal_bigram VALUES('甲','丁',64),('乙','丙',64);").unwrap();
    let options = json!({"api_version":1,"resources":root.join("resources"),"user_data":root.join("user"),"cache":root.join("cache"),"dictionaries":root.join("dictionaries"),"preferences":Preferences::default()});
    let request = |action: Value| {
        msime_host_api::dictionary_request_json(
            &serde_json::to_vec(&json!({"options":options,"action":action})).unwrap(),
        )
    };
    let status = |result: Result<Value, String>| {
        result
            .ok()
            .and_then(|v| v.get("status").and_then(Value::as_str).map(str::to_owned))
            .unwrap_or_else(|| "unsupported".into())
    };
    let unplugged = status(request(
        json!({"operation":"night_learning","charging":false,"idle":true,"local_hour":2}),
    ));
    let daytime = status(request(
        json!({"operation":"night_learning","charging":true,"idle":true,"local_hour":12}),
    ));
    let night = status(request(
        json!({"operation":"night_learning","charging":true,"idle":true,"local_hour":2}),
    ));
    let mut history = ClipboardHistoryStore::open(root.join("clipboard.json"));
    let normal = history.push_mobile("合成剪贴板记录".into()).unwrap();
    let otp_recorded = history
        .push_mobile("合成短信：验证码 482615，请勿转发".into())
        .unwrap();
    let code = request(
        json!({"operation":"verification_code","text":"合成短信：验证码 482615，请勿转发"}),
    );
    let detected = code.ok().is_some_and(|value| value["code"] == "482615");
    println!("unplugged={unplugged}; daytime={daytime}; charging_night={night}; clipboard_recorded={normal}; otp_recorded={otp_recorded}; otp_detected={detected}");
}
