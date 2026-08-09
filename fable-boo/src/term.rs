//! Terminal helpers: size query and frame centering math.

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Size {
    pub width: usize,
    pub height: usize,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Offset {
    pub x: usize,
    pub y: usize,
}

/// Query the terminal size via TIOCGWINSZ, falling back to COLUMNS/LINES.
pub fn terminal_size() -> Option<Size> {
    let mut winsize = unsafe { std::mem::zeroed::<libc::winsize>() };
    let ok = unsafe { libc::ioctl(libc::STDOUT_FILENO, libc::TIOCGWINSZ, &mut winsize) } == 0;
    if ok && winsize.ws_col > 0 && winsize.ws_row > 0 {
        return Some(Size {
            width: winsize.ws_col as usize,
            height: winsize.ws_row as usize,
        });
    }
    match (std::env::var("COLUMNS"), std::env::var("LINES")) {
        (Ok(cols), Ok(rows)) => parse_size(&cols, &rows),
        _ => None,
    }
}

pub fn parse_size(cols: &str, rows: &str) -> Option<Size> {
    let width = cols.parse::<usize>().ok()?;
    let height = rows.parse::<usize>().ok()?;
    if width == 0 || height == 0 {
        return None;
    }
    Some(Size { width, height })
}

/// Center a `frame_width x frame_height` surface inside `size`.
pub fn centered_offset(size: Size, frame_width: usize, frame_height: usize) -> Offset {
    Offset {
        x: size.width.saturating_sub(frame_width) / 2,
        y: size.height.saturating_sub(frame_height) / 2,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_size_accepts_valid() {
        assert_eq!(parse_size("100", "41"), Some(Size { width: 100, height: 41 }));
    }

    #[test]
    fn parse_size_rejects_garbage_and_zero() {
        assert_eq!(parse_size("abc", "41"), None);
        assert_eq!(parse_size("0", "41"), None);
        assert_eq!(parse_size("100", ""), None);
    }

    #[test]
    fn centering_is_symmetric() {
        assert_eq!(
            centered_offset(Size { width: 120, height: 41 }, 100, 41),
            Offset { x: 10, y: 0 }
        );
        assert_eq!(
            centered_offset(Size { width: 100, height: 80 }, 100, 41),
            Offset { x: 0, y: 19 }
        );
    }
}
