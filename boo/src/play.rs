//! `play` mode: Ghostty website animation at the official 30 fps pace.

use std::io::{self, Write};
use std::time::Instant;

use crate::ansi;
use crate::frames::{self, FRAME_HEIGHT, FRAME_PERIOD, FRAME_WIDTH};
use crate::signals;
use crate::term::{self, Offset};

pub const TOO_SMALL_MESSAGE: &str = "Screen must be at least 100w x 41h";

/// Deadline for frame `index` counting from `start` (no cumulative drift).
pub fn frame_deadline(start: Instant, index: u64) -> Instant {
    start + FRAME_PERIOD.saturating_mul(index as u32)
}

/// Run the play loop. Returns the process exit code.
pub fn run() -> i32 {
    let Some(size) = term::terminal_size() else {
        eprintln!("{TOO_SMALL_MESSAGE}");
        return 1;
    };
    if size.width < FRAME_WIDTH || size.height < FRAME_HEIGHT {
        eprintln!("{TOO_SMALL_MESSAGE}");
        return 1;
    }

    let frames = match frames::decompress_frames() {
        Ok(frames) => frames,
        Err(err) => {
            eprintln!("boo: {err}");
            return 1;
        }
    };
    let rendered: Vec<Vec<Vec<u8>>> = frames
        .iter()
        .map(|frame| ansi::render_frame(frame))
        .collect();

    signals::install_signal_handlers();

    let stdout = io::stdout();
    let mut out = stdout.lock();
    let offset = term::centered_offset(size, FRAME_WIDTH, FRAME_HEIGHT);
    let start = Instant::now();
    let mut frame_index: u64 = 0;

    if let Err(err) = play_loop(&mut out, &rendered, offset, start, &mut frame_index) {
        eprintln!("boo: {err}");
        let _ = restore(&mut out);
        return 1;
    }
    let _ = restore(&mut out);
    0
}

fn play_loop(
    out: &mut impl Write,
    rendered: &[Vec<Vec<u8>>],
    offset: Offset,
    start: Instant,
    frame_index: &mut u64,
) -> io::Result<()> {
    write_setup(out)?;
    loop {
        if signals::stop_requested() {
            break;
        }
        let buffer = build_frame_buffer(rendered, offset, *frame_index);
        out.write_all(&buffer)?;
        out.flush()?;

        *frame_index += 1;
        let next = frame_deadline(start, *frame_index);
        let now = Instant::now();
        if next > now {
            std::thread::sleep(next - now);
        }
    }
    Ok(())
}

/// Compose one whole frame (cursor positioning + styled lines + per-line reset)
/// so it can be written in a single write call.
pub fn build_frame_buffer(rendered: &[Vec<Vec<u8>>], offset: Offset, frame_index: u64) -> Vec<u8> {
    let frame = &rendered[frame_index as usize % rendered.len()];
    let mut buffer = Vec::with_capacity(frame.len() + 64);
    for (row, line) in frame.iter().enumerate() {
        buffer
            .extend_from_slice(format!("\x1b[{};{}H", offset.y + row + 1, offset.x + 1).as_bytes());
        buffer.extend_from_slice(line);
        buffer.extend_from_slice(ansi::RESET_SGR);
    }
    buffer
}

/// Clear the screen, hide the cursor, and remember where we started.
fn write_setup(out: &mut impl Write) -> io::Result<()> {
    out.write_all(b"\x1b[s\x1b[2J\x1b[H\x1b[?25l")?;
    out.flush()
}

/// Restore cursor visibility and position, reset styles.
fn restore(out: &mut impl Write) -> io::Result<()> {
    out.write_all(b"\x1b[0m\x1b[?25h\x1b[u")?;
    out.flush()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frame_deadlines_are_monotonic_and_match_round() {
        let start = Instant::now();
        let first = frame_deadline(start, 1);
        let second = frame_deadline(start, 2);
        assert!(first > start);
        assert!(second > first);
        assert_eq!(
            frame_deadline(start, 235) - start,
            frames::one_round_duration()
        );
    }

    #[test]
    fn full_frame_buffer_contains_positioning_for_all_rows() {
        let frames = frames::decompress_frames().unwrap();
        let rendered = frames
            .iter()
            .take(1)
            .map(|frame| ansi::render_frame(frame))
            .collect::<Vec<_>>();
        let buffer = build_frame_buffer(&rendered, Offset { x: 0, y: 0 }, 0);
        let text = String::from_utf8_lossy(&buffer);
        for row in 1..=FRAME_HEIGHT {
            assert!(
                text.contains(&format!("\x1b[{row};1H")),
                "missing row {row}"
            );
        }
    }

    #[test]
    fn buffer_cycles_through_frames() {
        let rendered = vec![vec![b"one".to_vec()], vec![b"two".to_vec()]];
        let first = build_frame_buffer(&rendered, Offset { x: 0, y: 0 }, 0);
        let second = build_frame_buffer(&rendered, Offset { x: 0, y: 0 }, 1);
        let wrapped = build_frame_buffer(&rendered, Offset { x: 0, y: 0 }, 2);
        assert_eq!(first, wrapped);
        assert_ne!(first, second);
    }
}
