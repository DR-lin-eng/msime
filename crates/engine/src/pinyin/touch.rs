//! 离散触点的键宽坐标、概率代价和逐字母旁路；只在本次组字内存中保留。

/// 中心区域以命中键宽为单位；高斯尺度再按实际横、纵键距调整。
pub const CENTER_RADIUS: f64 = 0.22;
const SIGMA: f64 = 0.35;
const MAX_NEIGHBOR_DISTANCE: f64 = 1.65;
const COST_SCALE: f64 = 6.0;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TouchPoint {
    x: f32,
    y: f32,
    pitch_x: f32,
    pitch_y: f32,
    centers: [Option<(f32, f32)>; 26],
}

impl TouchPoint {
    /// `centers` 按 a..z 排列 x/y；不可见键用两个 NaN 表示，所有值均相对命中键中心。
    pub fn new(key: u8, x: f32, y: f32, centers: &[f32]) -> Option<Self> {
        let key = key.to_ascii_lowercase();
        if !key.is_ascii_lowercase()
            || !x.is_finite()
            || !y.is_finite()
            || x.abs() > 2.0
            || y.abs() > 2.0
            || centers.len() != 52
        {
            return None;
        }
        let mut result = Self {
            x,
            y,
            pitch_x: 1.0,
            pitch_y: 1.0,
            centers: [None; 26],
        };
        for (index, pair) in centers.chunks_exact(2).enumerate() {
            if pair[0].is_nan() && pair[1].is_nan() {
                continue;
            }
            if !pair[0].is_finite() || !pair[1].is_finite() {
                return None;
            }
            result.centers[index] = Some((pair[0], pair[1]));
        }
        if result.centers[usize::from(key - b'a')] != Some((0.0, 0.0)) {
            return None;
        }
        // 行内最多一像素的舍入偏差不能被误认成一整行；分离式布局的远端键也不参与尺度估计。
        result.pitch_x = result
            .centers
            .iter()
            .flatten()
            .filter(|(cx, cy)| cy.abs() < 0.25 && cx.abs() > 0.25)
            .map(|(cx, _)| cx.abs())
            .reduce(f32::min)
            .unwrap_or(1.0)
            .clamp(0.5, 4.0);
        result.pitch_y = result
            .centers
            .iter()
            .flatten()
            .filter(|(_, cy)| cy.abs() > 0.25)
            .map(|(_, cy)| cy.abs())
            .reduce(f32::min)
            .unwrap_or(1.0)
            .clamp(0.5, 4.0);
        for center in &mut result.centers {
            if center.is_some_and(|(cx, cy)| {
                f64::from(cx / result.pitch_x).hypot(f64::from(cy / result.pitch_y))
                    > MAX_NEIGHBOR_DISTANCE
            }) {
                *center = None;
            }
        }
        Some(result)
    }

    pub fn centered(&self) -> bool {
        f64::from(self.x).hypot(f64::from(self.y)) <= CENTER_RADIUS
    }

    /// 自己和几何邻键的归一化高斯概率；用 log-sum-exp 避免极小似然下溢。
    pub fn probability(&self, intended: u8) -> Option<f64> {
        let intended = intended.to_ascii_lowercase();
        if !intended.is_ascii_lowercase() {
            return None;
        }
        let log_likelihood = |(x, y): (f32, f32)| {
            let dx = (f64::from(self.x) - f64::from(x)) / f64::from(self.pitch_x);
            let dy = (f64::from(self.y) - f64::from(y)) / f64::from(self.pitch_y);
            -(dx * dx + dy * dy) / (2.0 * SIGMA * SIGMA)
        };
        let target = log_likelihood(self.centers[usize::from(intended - b'a')]?);
        let peak = self
            .centers
            .iter()
            .flatten()
            .copied()
            .map(log_likelihood)
            .fold(f64::NEG_INFINITY, f64::max);
        let sum: f64 = self
            .centers
            .iter()
            .flatten()
            .copied()
            .map(|center| (log_likelihood(center) - peak).exp())
            .sum();
        Some((target - peak).exp() / sum)
    }

    pub fn correction_cost(&self, intended: u8) -> Option<f64> {
        if self.centered() {
            return None;
        }
        let probability = self.probability(intended)?;
        // 极低概率的方向不占展开预算。
        (probability >= 0.02).then(|| (-probability.ln() * COST_SCALE).max(1.0))
    }
}

/// 只有整个片段都带明确中心触点时，才能在访问音节变体表之前跳过它。
pub fn centered_segment(points: &[Option<TouchPoint>], start: usize, length: usize) -> bool {
    length != 0
        && points.get(start..start + length).is_some_and(|slice| {
            slice
                .iter()
                .all(|point| point.as_ref().is_some_and(TouchPoint::centered))
        })
}

/// 按实际改变的字母取概率代价；缺失触点沿用原价，已知中心或不可能的方向拒绝展开。
pub fn replacement_cost(
    points: &[Option<TouchPoint>],
    start: usize,
    typed: &str,
    intended: &str,
    fallback: f64,
) -> Option<f64> {
    if centered_segment(points, start, typed.len()) {
        return None;
    }
    if typed.len() != intended.len() {
        return Some(fallback);
    }
    let mut cost = 0.0;
    let mut observed = false;
    for (index, (from, to)) in typed.bytes().zip(intended.bytes()).enumerate() {
        if from == to {
            continue;
        }
        match points.get(start + index).and_then(Option::as_ref) {
            Some(point) => {
                cost += point.correction_cost(to)?;
                observed = true;
            }
            None => cost += fallback,
        }
    }
    Some(if observed { cost } else { fallback })
}

#[cfg(test)]
pub(crate) fn qwerty_point(key: u8, x: f32, y: f32) -> TouchPoint {
    let mut centers = [f32::NAN; 52];
    let mut origin = (0.0, 0.0);
    for (row, letters) in [b"qwertyuiop".as_slice(), b"asdfghjkl", b"zxcvbnm"]
        .iter()
        .enumerate()
    {
        for (column, &letter) in letters.iter().enumerate() {
            let cx = column as f32 + [0.0, 0.25, 0.75][row];
            centers[usize::from(letter - b'a') * 2] = cx;
            centers[usize::from(letter - b'a') * 2 + 1] = row as f32;
            if letter == key {
                origin = (cx, row as f32);
            }
        }
    }
    for pair in centers.chunks_exact_mut(2) {
        pair[0] -= origin.0;
        pair[1] -= origin.1;
    }
    TouchPoint::new(key, x, y, &centers).unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gaussian_prefers_the_touched_side_and_probabilities_sum_to_one() {
        let point = qwerty_point(b'g', 0.49, 0.0);
        assert!(point.probability(b'h') > point.probability(b'f'));
        let total: f64 = (b'a'..=b'z').filter_map(|key| point.probability(key)).sum();
        assert!((total - 1.0).abs() < 1e-12);
        assert!(point.correction_cost(b'h').unwrap() < 20.0);
        assert_eq!(point.correction_cost(b'a'), None);
    }

    #[test]
    fn center_gate_and_absent_samples_have_distinct_behavior() {
        let center = qwerty_point(b'g', 0.1, 0.1);
        assert_eq!(replacement_cost(&[Some(center)], 0, "g", "h", 20.0), None);
        assert_eq!(replacement_cost(&[], 0, "g", "h", 20.0), Some(20.0));
        assert_eq!(
            replacement_cost(&[None, Some(center)], 0, "gg", "hg", 20.0),
            Some(20.0)
        );
    }

    #[test]
    fn invalid_geometry_is_rejected() {
        assert!(TouchPoint::new(b'g', f32::NAN, 0.0, &[0.0; 52]).is_none());
        assert!(TouchPoint::new(b'g', 0.0, 0.0, &[0.0; 50]).is_none());
        let mut centers = [f32::NAN; 52];
        centers[12] = 0.0;
        centers[13] = 0.0;
        assert!(TouchPoint::new(b'g', 0.0, 0.0, &centers).is_some());
        centers[0] = f32::INFINITY;
        assert!(TouchPoint::new(b'g', 0.0, 0.0, &centers).is_none());
    }

    #[test]
    fn tall_keys_keep_cross_row_neighbors_and_separate_keyboard_halves() {
        let mut centers = [f32::NAN; 52];
        for (key, x, y) in [
            (b'g', 0.0, 0.0),
            (b'f', -1.1, 0.0),
            (b't', -0.25, -2.2),
            (b'h', 6.0, 0.0),
        ] {
            centers[usize::from(key - b'a') * 2] = x;
            centers[usize::from(key - b'a') * 2 + 1] = y;
        }
        let point = TouchPoint::new(b'g', -0.12, -1.08, &centers).unwrap();
        assert!(point.correction_cost(b't').unwrap() < 20.0);
        assert_eq!(point.probability(b'h'), None);
    }

    #[test]
    fn stage_two_expands_toward_the_neighbor_and_gates_centers() {
        use crate::pinyin::autocorrect::autocorrect_cut_kbest_with_touch;
        use crate::types::autocorrect_type;
        let cost = |points: &[Option<TouchPoint>]| {
            autocorrect_cut_kbest_with_touch("hso", autocorrect_type::NEIGHBOR, 9, points)
        };
        assert!(cost(&[])
            .iter()
            .any(|cut| cut.segments[0].syllable == "hao"));
        let mut points: Vec<_> = b"hso"
            .iter()
            .map(|&key| Some(qwerty_point(key, 0.0, 0.0)))
            .collect();
        assert!(cost(&points).is_empty());
        points[1] = Some(qwerty_point(b's', -0.49, 0.0));
        let toward = cost(&points);
        assert!(toward.iter().any(|cut| cut.segments[0].syllable == "hao"));
        points[1] = Some(qwerty_point(b's', 0.49, 0.0));
        assert!(!cost(&points)
            .iter()
            .any(|cut| cut.segments[0].syllable == "hao"));
    }
}
