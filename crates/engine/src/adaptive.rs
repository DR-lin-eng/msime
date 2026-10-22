//! 本机个人神经排序：固定非线性特征网络和在线训练的个人排序头。
//!
//! 训练只重放现有的词对日志，不另存用户文本或上传梯度。长上下文在内存中做
//! 有界散列，模型为空、样本不足或后端不可用时仍由原有词频和 n 元模型排序。

mod device;
pub mod night;

pub const CONTEXT_CHARACTERS: usize = 512;
pub const MAX_CANDIDATES: usize = 16;
const SIGNATURE: usize = 32;
const INPUTS: usize = 128;
const HIDDEN: usize = 64;
const RESERVOIR: usize = 8;
const MIN_OBSERVATIONS: u64 = 32;

/// 最近的本地预测上下文；按字符截断，不拆 UTF-8，也不扩大云服务输入。
pub fn remember_context(context: &mut String, text: &str) {
    let tail = crate::text::last_characters(text, CONTEXT_CHARACTERS);
    context.push_str(tail);
    let keep = crate::text::last_characters(context, CONTEXT_CHARACTERS);
    let cut = context.len() - keep.len();
    context.drain(..cut);
}

const fn mix(mut value: u32) -> u32 {
    value ^= value >> 16;
    value = value.wrapping_mul(0x7feb_352d);
    value ^= value >> 15;
    value = value.wrapping_mul(0x846c_a68b);
    value ^ (value >> 16)
}

const fn weights() -> [f32; INPUTS * HIDDEN] {
    let mut result = [0.0; INPUTS * HIDDEN];
    let mut index = 0;
    while index < result.len() {
        result[index] = ((mix(index as u32 + 1) % 2001) as f32 - 1000.0) / 2048.0;
        index += 1;
    }
    result
}

static WEIGHTS: [f32; INPUTS * HIDDEN] = weights();

fn signature(text: &str) -> [f32; SIGNATURE] {
    let mut values = [0.0; SIGNATURE];
    let mut previous = 0_u32;
    for character in crate::text::last_characters(text, CONTEXT_CHARACTERS).chars() {
        let current = character as u32;
        for hash in [mix(current), mix(previous.wrapping_mul(31) ^ current)] {
            values[hash as usize % SIGNATURE] = if hash & 0x100 != 0 { 1.0 } else { -1.0 };
        }
        previous = current;
    }
    let norm = values.iter().map(|x| x * x).sum::<f32>().sqrt().max(1.0);
    for value in &mut values {
        *value /= norm;
    }
    values
}

fn features(context: &[f32; SIGNATURE], word: &[f32; SIGNATURE]) -> [f32; INPUTS] {
    let mut input = [0.0; INPUTS];
    input[..SIGNATURE].copy_from_slice(context);
    input[SIGNATURE..SIGNATURE * 2].copy_from_slice(word);
    for (left, &a) in context.iter().enumerate() {
        for (right, &b) in word.iter().enumerate() {
            let hash = mix((left * SIGNATURE + right) as u32 + 0x1024);
            let index = SIGNATURE * 2 + hash as usize % (INPUTS - SIGNATURE * 2);
            input[index] += a * b * if hash & 0x100 != 0 { 1.0 } else { -1.0 };
        }
    }
    input
}

fn cpu_embedding(input: &[f32; INPUTS]) -> [f32; HIDDEN] {
    let mut result = [0.0_f32; HIDDEN];
    for (row, &value) in input.iter().enumerate() {
        if value != 0.0 {
            for (column, out) in result.iter_mut().enumerate() {
                *out += value * WEIGHTS[row * HIDDEN + column];
            }
        }
    }
    for value in &mut result {
        *value = value.tanh();
    }
    result
}

#[derive(Clone, Copy, Debug)]
struct RememberedWord {
    id: u64,
    signature: [f32; SIGNATURE],
}

/// 单个个人日志拥有的有界神经模型；头部权重由本机选词在线更新。
#[derive(Clone, Debug)]
pub struct PersonalRanker {
    head: [f32; HIDDEN],
    words: [Option<RememberedWord>; RESERVOIR],
    next_word: usize,
    observations: u64,
}

impl Default for PersonalRanker {
    fn default() -> Self {
        Self {
            head: [0.0; HIDDEN],
            words: [None; RESERVOIR],
            next_word: 0,
            observations: 0,
        }
    }
}

impl PersonalRanker {
    /// 从既有持久化词对训练；计数不逐次无界展开，单条日志最多训练 16 步。
    pub fn observe(&mut self, context: &str, word: &str, count: u32) {
        if word.is_empty() || count == 0 {
            return;
        }
        let word_signature = signature(word);
        let id = word.bytes().fold(0xcbf29ce484222325_u64, |hash, byte| {
            (hash ^ u64::from(byte)).wrapping_mul(0x100000001b3)
        });
        if !context.is_empty() && context != crate::lattice::ngram::SENTENCE_START {
            let context = signature(context);
            let chosen = cpu_embedding(&features(&context, &word_signature));
            let mut negatives = [[0.0; HIDDEN]; 3];
            let mut negative_count = 0;
            for negative in self
                .words
                .iter()
                .flatten()
                .filter(|entry| entry.id != id)
                .take(3)
            {
                negatives[negative_count] = cpu_embedding(&features(&context, &negative.signature));
                negative_count += 1;
            }
            for _ in 0..count.min(16) {
                for rejected in &negatives[..negative_count] {
                    let margin: f32 = self
                        .head
                        .iter()
                        .zip(chosen.iter().zip(rejected))
                        .map(|(w, (a, b))| w * (a - b))
                        .sum();
                    let gradient = 0.08 / (1.0 + margin.clamp(-12.0, 12.0).exp());
                    for (weight, (a, b)) in self.head.iter_mut().zip(chosen.iter().zip(rejected)) {
                        *weight = (*weight * 0.9995 + gradient * (a - b)).clamp(-2.0, 2.0);
                    }
                }
            }
            self.observations = self.observations.saturating_add(u64::from(count));
        }
        if !self.words.iter().flatten().any(|entry| entry.id == id) {
            self.words[self.next_word] = Some(RememberedWord {
                id,
                signature: word_signature,
            });
            self.next_word = (self.next_word + 1) % RESERVOIR;
        }
    }

    /// 夜间缓存只保存数值头，不包含原始上下文或候选文字。
    pub(crate) fn snapshot(&self) -> Vec<f32> {
        self.head.to_vec()
    }

    pub(crate) fn restore(&mut self, head: &[f32]) -> bool {
        if head.len() != HIDDEN
            || head
                .iter()
                .any(|value| !value.is_finite() || value.abs() > 2.0)
        {
            return false;
        }
        self.head.copy_from_slice(head);
        true
    }

    pub fn observations(&self) -> u64 {
        self.observations
    }

    /// 样本不足不介入；置信度渐增且限制个人神经评分对词频的影响。
    pub fn confidence(&self) -> f32 {
        if self.observations < MIN_OBSERVATIONS {
            return 0.0;
        }
        0.5 * self.observations as f32 / (self.observations as f32 + 32.0)
    }

    /// 一次最多比较 16 条；使用完整的 512 字本地窗口，硬件失败则同输入回退 CPU。
    pub fn scores(&self, context: &str, candidates: &[&str]) -> Option<[f32; MAX_CANDIDATES]> {
        if self.confidence() == 0.0 || context.is_empty() || candidates.len() > MAX_CANDIDATES {
            return None;
        }
        let context = signature(context);
        let mut inputs = [[0.0; INPUTS]; MAX_CANDIDATES];
        for (input, word) in inputs.iter_mut().zip(candidates) {
            *input = features(&context, &signature(word));
        }
        let embeddings = device::embeddings(&inputs).unwrap_or_else(|| {
            let mut rows = [[0.0; HIDDEN]; MAX_CANDIDATES];
            for (out, input) in rows.iter_mut().zip(&inputs).take(candidates.len()) {
                *out = cpu_embedding(input);
            }
            rows
        });
        let mut result = [0.0; MAX_CANDIDATES];
        for (score, embedding) in result.iter_mut().zip(embeddings).take(candidates.len()) {
            *score = self
                .head
                .iter()
                .zip(embedding)
                .map(|(weight, value)| weight * value)
                .sum();
        }
        result
            .iter()
            .all(|score| score.is_finite())
            .then_some(result)
    }
}

/// 实际建立成功的执行后端；设备运行失败后会回到 `cpu`。
pub fn prediction_backend() -> &'static str {
    device::backend()
}

#[cfg(test)]
mod tests;
