//! Ghostty website animation frame data.
//!
//! Source: `ghostty-org/ghostty` (MIT), commit `05221c11c9db0715666fc6e038915128fc6a563e`
//! (2026-08-09), `src/build/framegen/frames/frame_*.txt` joined with `\x01` and
//! compressed with raw DEFLATE exactly like upstream `framegen/main.c`.

use std::time::Duration;

pub const FRAME_WIDTH: usize = 100;
pub const FRAME_HEIGHT: usize = 41;
pub const FRAME_COUNT: usize = 235;
/// 30 fps: one frame every 33.333ms.
pub const FRAME_PERIOD: Duration = Duration::from_nanos(33_333_333);
/// Frames are joined with this byte in the upstream data format.
pub const SEPARATOR: u8 = 0x01;

const DECOMPRESS_LIMIT: usize = 4 * 1024 * 1024;
const COMPRESSED: &[u8] = include_bytes!(concat!(env!("OUT_DIR"), "/framedata.compressed"));

/// Size of the embedded compressed payload in bytes.
pub fn compressed_size() -> usize {
    COMPRESSED.len()
}

/// Duration of one full loop (235 frames at 30 fps).
pub fn one_round_duration() -> Duration {
    FRAME_PERIOD.saturating_mul(FRAME_COUNT as u32)
}

/// Decompress the embedded payload and split it into the 235 official frames.
pub fn decompress_frames() -> Result<Vec<Vec<u8>>, String> {
    let raw = miniz_oxide::inflate::decompress_to_vec_with_limit(COMPRESSED, DECOMPRESS_LIMIT)
        .map_err(|err| format!("inflate failed: {err:?}"))?;
    let frames: Vec<Vec<u8>> = raw
        .split(|byte| *byte == SEPARATOR)
        .map(|frame| frame.to_vec())
        .collect();
    if frames.len() != FRAME_COUNT {
        return Err(format!(
            "frame count mismatch: expected {FRAME_COUNT}, got {}",
            frames.len()
        ));
    }
    Ok(frames)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ansi;

    #[test]
    fn decompresses_to_235_frames() {
        let frames = decompress_frames().unwrap();
        assert_eq!(frames.len(), FRAME_COUNT);
    }

    #[test]
    fn embedded_payload_is_compressed() {
        let frames = decompress_frames().unwrap();
        let raw_size: usize = frames.iter().map(|frame| frame.len()).sum::<usize>() + 234;
        assert!(compressed_size() > 0);
        assert!(compressed_size() < raw_size, "payload should shrink");
    }

    #[test]
    fn every_frame_has_official_shape() {
        for (index, frame) in decompress_frames().unwrap().iter().enumerate() {
            assert!(!frame.is_empty(), "frame {} is empty", index + 1);
            assert_eq!(frame.iter().filter(|b| **b == b'\n').count(), FRAME_HEIGHT);
            assert_eq!(ansi::cell_count(frame), FRAME_WIDTH * FRAME_HEIGHT);
        }
    }

    #[test]
    fn every_consecutive_pair_differs() {
        let frames = decompress_frames().unwrap();
        for index in 0..FRAME_COUNT {
            let next = (index + 1) % FRAME_COUNT;
            assert_ne!(
                frames[index],
                frames[next],
                "frames {} and {} identical",
                index + 1,
                next + 1
            );
        }
    }

    #[test]
    fn first_frame_contains_expected_art() {
        let frames = decompress_frames().unwrap();
        assert!(frames[0]
            .windows(b"+++==*%%%%%%%%%%%%*==+++".len())
            .any(|w| w == b"+++==*%%%%%%%%%%%%*==+++"));
    }

    #[test]
    fn one_round_is_about_7_83_seconds() {
        let round = one_round_duration();
        assert_eq!(round, Duration::from_nanos(33_333_333 * 235));
        assert!(round > Duration::from_secs_f64(7.8));
        assert!(round < Duration::from_secs_f64(7.9));
    }
}
