//! HTML span markup (as used by the Ghostty website frames) to ANSI SGR output.
//!
//! The official `boo.zig` parser treats `outline_style` (ANSI color index 4,
//! blue) as the style inside `<span ...>...</span>` and the default style
//! outside. The state machine below mirrors `Boo.updateFrame` exactly.

/// ANSI SGR for the official outline style (`fg index 4`).
pub const OUTLINE_SGR: &[u8] = b"\x1b[34m";
pub const RESET_SGR: &[u8] = b"\x1b[0m";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum State {
    Normal,
    Span,
    InTag,
    InClosingTag,
}

/// Walk the cells of one line. The tag state machine mirrors the upstream
/// `Boo.updateFrame`; every codepoint outside markup is one cell.
///
/// `on_cell` receives the cell's UTF-8 bytes and whether it uses the outline
/// (blue) style. Frames contain multi-byte UTF-8 (e.g. U+00B7), so continuation
/// bytes are consumed as part of the codepoint.
fn walk_line(line: &[u8], mut on_cell: impl FnMut(&[u8], bool)) {
    let mut state = State::Normal;
    let mut outline = false;
    let mut bytes = line.iter().copied();
    let mut codepoint = [0u8; 4];

    while let Some(byte) = bytes.next() {
        let cell = match state {
            State::Normal if byte == b'<' => {
                state = State::InTag;
                outline = true;
                None
            }
            State::Span if byte == b'<' => {
                state = State::InTag;
                outline = false;
                None
            }
            State::InTag => {
                if byte == b'/' {
                    state = State::InClosingTag;
                } else if byte == b'>' {
                    state = State::Span;
                }
                None
            }
            State::InClosingTag => {
                if byte == b'>' {
                    state = State::Normal;
                }
                None
            }
            _ => {
                let len = utf8_len(byte);
                codepoint[0] = byte;
                for slot in codepoint[1..len].iter_mut() {
                    *slot = bytes.next().unwrap_or(0xFF);
                }
                Some(&codepoint[..len])
            }
        };
        if let Some(cell) = cell {
            on_cell(cell, outline);
        }
    }
}

fn utf8_len(leading: u8) -> usize {
    match leading {
        0x00..=0x7F => 1,
        0xC0..=0xDF => 2,
        0xE0..=0xEF => 3,
        _ => 4,
    }
}

/// Render one frame line (up to 100 cells) to ANSI bytes.
///
/// Returns content with SGR transitions only; the caller appends a reset at the
/// end of the line so every line starts from the default style.
pub fn render_line(line: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(line.len() + 16);
    let mut blue = false;
    walk_line(line, |cell, outline| {
        if outline != blue {
            out.extend_from_slice(if outline { OUTLINE_SGR } else { RESET_SGR });
            blue = outline;
        }
        out.extend_from_slice(cell);
    });
    if blue {
        out.extend_from_slice(RESET_SGR);
    }
    out
}

/// Number of terminal cells a frame produces (tags excluded), matching the
/// upstream parser (each codepoint is one cell of width 1).
pub fn cell_count(frame: &[u8]) -> usize {
    let mut cells = 0;
    for line in frame.split(|byte| *byte == b'\n') {
        walk_line(line, |_, _| cells += 1);
    }
    cells
}

/// Render a full frame into ANSI lines (41 lines of 100 cells), without cursor
/// positioning. Each line keeps its own reset so positioning stays safe.
pub fn render_frame(frame: &[u8]) -> Vec<Vec<u8>> {
    frame
        .split(|byte| *byte == b'\n')
        .filter(|line| !line.is_empty())
        .map(render_line)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plain_line_has_no_sgr() {
        assert_eq!(render_line(b"abc def"), b"abc def");
    }

    #[test]
    fn span_text_rendered_blue() {
        let out = render_line(b"<span class=\"b\">AB</span>");
        assert_eq!(out, b"\x1b[34mAB\x1b[0m");
    }

    #[test]
    fn mixed_line_resets_after_span() {
        let out = render_line(b"a<span class=\"b\">b</span>c");
        assert_eq!(out, b"a\x1b[34mb\x1b[0mc");
    }

    #[test]
    fn render_frame_keeps_41_lines() {
        use crate::frames::{FRAME_HEIGHT, FRAME_WIDTH};

        let frame = decompress_test_frame();
        let lines = render_frame(&frame);
        assert_eq!(lines.len(), FRAME_HEIGHT);
        for line in lines {
            assert_eq!(cell_count_of_line(&line), FRAME_WIDTH);
        }
    }

    fn decompress_test_frame() -> Vec<u8> {
        crate::frames::decompress_frames().unwrap().remove(0)
    }

    /// Count cells of an already-rendered line by stripping SGR sequences.
    fn cell_count_of_line(line: &[u8]) -> usize {
        let mut count = 0;
        let mut index = 0;
        while index < line.len() {
            if line[index] == 0x1b {
                index += 2;
                while index < line.len() && line[index] != b'm' {
                    index += 1;
                }
                index += 1;
            } else {
                if line[index] & 0xC0 == 0x80 {
                    index += 1;
                    continue;
                }
                count += 1;
                index += 1;
            }
        }
        count
    }
}
