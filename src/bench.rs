//! `bench` mode: stress the render pipeline with a full-screen pattern that
//! changes on every frame (bypasses content-signature dedup), no throttling.

use std::io::{self, Write};
use std::time::{Duration, Instant};

use crate::{signals, term};

/// Build one full-screen bench frame. `sequence` changes every cell, so every
/// frame differs from the previous one and defeats content dedup.
pub fn bench_frame(width: usize, height: usize, sequence: u64) -> Vec<u8> {
    let mut out = Vec::with_capacity(height * (width + 8));
    for row in 1..=height {
        out.extend_from_slice(format!("\x1b[{row};1H").as_bytes());
        let row_u64 = row as u64;
        for col in 0..width {
            let value = (sequence.wrapping_mul(31).wrapping_add(row_u64.wrapping_mul(17)).wrapping_add(col as u64)) & 0x7f;
            let ch = match value {
                0x20..=0x7e => value as u8,
                _ => b' ',
            };
            out.push(ch);
        }
        out.extend_from_slice(b"\r\n");
    }
    out
}

/// One-line summary for the stats block.
pub fn format_stats(frames: u64, bytes: u64, elapsed: Duration) -> String {
    let seconds = elapsed.as_secs_f64();
    let fps = if seconds > 0.0 { frames as f64 / seconds } else { 0.0 };
    let bytes_per_sec = if seconds > 0.0 { bytes as f64 / seconds } else { 0.0 };
    format!(
        "frames: {frames}\nbytes: {bytes}\nelapsed: {seconds:.3}s\nemit rate: {fps:.1} fps / {:.2} MiB/s",
        bytes_per_sec / (1024.0 * 1024.0)
    )
}

/// Run the bench loop until Ctrl-C. Returns exit code.
pub fn run() -> i32 {
    signals::install_signal_handlers();

    let size = term::terminal_size().unwrap_or(term::Size { width: 80, height: 24 });
    let stdout = io::stdout();
    let mut out = stdout.lock();

    let start = Instant::now();
    let mut sequence: u64 = 0;
    let mut total_bytes: u64 = 0;

    loop {
        if signals::stop_requested() {
            break;
        }
        let frame = bench_frame(size.width, size.height, sequence);
        if out.write_all(&frame).is_err() {
            break;
        }
        let _ = out.flush();
        total_bytes += frame.len() as u64;
        sequence += 1;
    }

    let elapsed = start.elapsed();
    eprintln!("{}", format_stats(sequence, total_bytes, elapsed));
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bench_frames_change_every_sequence() {
        for sequence in 0..8 {
            assert_ne!(bench_frame(20, 5, sequence), bench_frame(20, 5, sequence + 1));
        }
    }

    #[test]
    fn bench_frame_fills_screen() {
        let frame = bench_frame(20, 5, 0);
        let expected = 5 * (b"\x1b[1;1H".len() + 20 + 2);
        assert_eq!(frame.len(), expected);
        assert!(frame.windows(2).any(|w| w == b"\r\n"));
    }

    #[test]
    fn stats_include_all_fields() {
        let stats = format_stats(100, 1_000_000, Duration::from_secs_f64(2.0));
        assert!(stats.contains("frames: 100"));
        assert!(stats.contains("bytes: 1000000"));
        assert!(stats.contains("elapsed: 2.000s"));
        assert!(stats.contains("emit rate: 50.0 fps"));
    }
}
