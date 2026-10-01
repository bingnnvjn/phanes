//! 程序化自绘符号（工单 12 ③：sprite face，对照官方 z2d 思路）。
//!
//! 边框线/几何块/盲文等不依赖字体覆盖，按 codepoint 程序化生成灰度位图，
//! 由调用方写进灰度字形图集。官方 Ghostty 用 src/font/sprite/Face.zig +
//! z2d canvas 做同样的事（box/block/braille + cursor/underline sprites）。

#![expect(
    clippy::manual_range_patterns,
    reason = "工单 50：Unicode box drawing 分支按连续码点分组，保持既有位掩码语义"
)]

#[derive(Debug, Clone)]
pub struct SpriteBitmap {
    pub width: u32,
    pub height: u32,
    /// 灰度 alpha 位图（0..=255），行优先。
    pub alpha: Vec<u8>,
}

/// 是否为程序化自绘符号。
pub fn is_sprite(ch: char) -> bool {
    let c = ch as u32;
    matches!(
        c,
        0x2190..=0x2199 // 箭头
        | 0x2500..=0x257F // 边框线
        | 0x2580..=0x259F // 块元素
        | 0x25A0..=0x25CF // 常用几何形
        | 0x2800..=0x28FF // 盲文
    )
}

/// 生成符号位图。size = 方形图集格边长（如 32）。
pub fn sprite_bitmap(ch: char, size: u32) -> Option<SpriteBitmap> {
    let size = size.max(16);
    let c = ch as u32;
    let mut alpha = vec![0u8; (size * size) as usize];

    if (0x2800..=0x28FF).contains(&c) {
        draw_braille(&mut alpha, size, c as u8);
        return Some(SpriteBitmap {
            width: size,
            height: size,
            alpha,
        });
    }
    if (0x2500..=0x257F).contains(&c) {
        draw_box(&mut alpha, size, c as u16);
        return Some(SpriteBitmap {
            width: size,
            height: size,
            alpha,
        });
    }
    if (0x2580..=0x259F).contains(&c) {
        draw_block(&mut alpha, size, c as u16);
        return Some(SpriteBitmap {
            width: size,
            height: size,
            alpha,
        });
    }
    if (0x25A0..=0x25CF).contains(&c) {
        draw_geometric(&mut alpha, size, c as u16)?;
        return Some(SpriteBitmap {
            width: size,
            height: size,
            alpha,
        });
    }
    if (0x2190..=0x2199).contains(&c) {
        draw_arrow(&mut alpha, size, c as u16);
        return Some(SpriteBitmap {
            width: size,
            height: size,
            alpha,
        });
    }
    None
}

fn set_px(alpha: &mut [u8], size: u32, x: i32, y: i32, value: u8) {
    if x < 0 || y < 0 || x >= size as i32 || y >= size as i32 {
        return;
    }
    alpha[(y as u32 * size + x as u32) as usize] =
        value.max(alpha[(y as u32 * size + x as u32) as usize]);
}

fn fill_rect(alpha: &mut [u8], size: u32, x0: i32, y0: i32, w: i32, h: i32) {
    for y in y0..y0 + h {
        for x in x0..x0 + w {
            set_px(alpha, size, x, y, 255);
        }
    }
}

fn fill_circle(alpha: &mut [u8], size: u32, cx: i32, cy: i32, r: i32) {
    for y in cy - r..=cy + r {
        for x in cx - r..=cx + r {
            let dx = x - cx;
            let dy = y - cy;
            if dx * dx + dy * dy <= r * r {
                set_px(alpha, size, x, y, 255);
            }
        }
    }
}

fn draw_line(alpha: &mut [u8], size: u32, x0: i32, y0: i32, x1: i32, y1: i32, thickness: i32) {
    let mut x = x0;
    let mut y = y0;
    let dx = (x1 - x0).abs();
    let dy = -(y1 - y0).abs();
    let sx = if x0 < x1 { 1 } else { -1 };
    let sy = if y0 < y1 { 1 } else { -1 };
    let mut err = dx + dy;
    loop {
        fill_circle(alpha, size, x, y, thickness);
        if x == x1 && y == y1 {
            break;
        }
        let e2 = 2 * err;
        if e2 >= dy {
            err += dy;
            x += sx;
        }
        if e2 <= dx {
            err += dx;
            y += sy;
        }
    }
}

// ---------- 盲文 ----------

fn draw_braille(alpha: &mut [u8], size: u32, pattern: u8) {
    // Unicode 盲文位顺序：dot1..dot6 对应 bit0..5，dot7/8 对应 bit6/7。
    let dots = [
        (0, 0, 0x01), // dot1 左上
        (0, 1, 0x02), // dot2 左中上
        (0, 2, 0x04), // dot3 左中下
        (1, 0, 0x08), // dot4 右上
        (1, 1, 0x10), // dot5 右中上
        (1, 2, 0x20), // dot6 右中下
        (0, 3, 0x40), // dot7 左下
        (1, 3, 0x80), // dot8 右下
    ];
    let margin_x = (size as f32 * 0.25) as i32;
    let margin_y = (size as f32 * 0.12) as i32;
    let gap_x = (size as f32 * 0.28) as i32;
    let gap_y = (size as f32 * 0.20) as i32;
    let radius = (size as f32 * 0.07).max(1.0) as i32;
    for (col, row, bit) in dots {
        if pattern & bit != 0 {
            let cx = margin_x + col * gap_x;
            let cy = margin_y + row * gap_y;
            fill_circle(alpha, size, cx, cy, radius);
        }
    }
}

// ---------- 边框线 ----------

fn draw_box(alpha: &mut [u8], size: u32, code: u16) {
    let size_i = size as i32;
    let t = ((size as f32 * 0.08).round() as i32).max(2); // 线宽
    let x0 = t / 2;
    let x1 = size_i - 1 - t / 2;
    let y0 = t / 2;
    let y1 = size_i - 1 - t / 2;
    let cx = size_i / 2;
    let cy = size_i / 2;

    // 方向位：N=1 S=2 E=4 W=8
    let dirs: u8 = match code {
        0x2500 | 0x2501 | 0x2504 | 0x2508 => 0x04 | 0x08, // ─ ━ ┄ ┈
        0x2502 | 0x2503 | 0x2506 | 0x250A => 0x01 | 0x02, // │ ┃ ┆ ┊
        0x250C..=0x250F => 0x02 | 0x04,                   // ┌
        0x2510..=0x2513 => 0x02 | 0x08,                   // ┐
        0x2514..=0x2517 => 0x01 | 0x04,                   // └
        0x2518..=0x251B => 0x01 | 0x08,                   // ┘
        0x251C..=0x2523 => 0x01 | 0x02 | 0x04,            // ├
        0x2524..=0x252B => 0x01 | 0x02 | 0x08,            // ┤
        0x252C..=0x2533 => 0x02 | 0x04 | 0x08,            // ┬
        0x2534..=0x253B => 0x01 | 0x04 | 0x08,            // ┴
        0x253C..=0x254B => 0x01 | 0x02 | 0x04 | 0x08,     // ┼
        0x254C | 0x254D | 0x254E | 0x254F => 0x01 | 0x02 | 0x04 | 0x08,
        0x2574 => 0x08,          // ╴
        0x2575 => 0x01,          // ╵
        0x2576 => 0x04,          // ╶
        0x2577 => 0x02,          // ╷
        0x2578..=0x257F => 0x00, // 重线单边：当普通单边处理
        _ => 0x00,
    };

    if dirs != 0 {
        if dirs & 0x04 != 0 {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t); // E
        }
        if dirs & 0x08 != 0 {
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t); // W
        }
        if dirs & 0x02 != 0 {
            fill_rect(alpha, size, cx - t / 2, cy, t, y1 - cy + 1); // S
        }
        if dirs & 0x01 != 0 {
            fill_rect(alpha, size, cx - t / 2, y0, t, cy - y0 + 1); // N
        }
        return;
    }

    match code {
        // 双线水平/垂直：两条平行线
        0x2550 => {
            fill_rect(alpha, size, x0, cy - t - t / 2, x1 - x0 + 1, t);
            fill_rect(alpha, size, x0, cy + t / 2, x1 - x0 + 1, t);
        }
        0x2551 => {
            fill_rect(alpha, size, cx - t - t / 2, y0, t, y1 - y0 + 1);
            fill_rect(alpha, size, cx + t / 2, y0, t, y1 - y0 + 1);
        }
        // 双线角/三通/十字：方向位 + double 标志近似（外沿双线）
        0x2552 | 0x2553 | 0x2554 => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, cx + t / 2, cy, t, y1 - cy + 1);
            fill_rect(alpha, size, cx, cy + t + t / 2, x1 - cx + 1, t);
        }
        0x2555 | 0x2556 | 0x2557 => {
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx + t / 2, cy, t, y1 - cy + 1);
            fill_rect(alpha, size, x0, cy + t + t / 2, cx - x0 + 1, t);
        }
        0x2558 | 0x2559 | 0x255A => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, cx + t / 2, y0, t, cy - y0 + 1);
            fill_rect(alpha, size, cx, y0 - t / 2, x1 - cx + 1, t);
        }
        0x255B | 0x255C | 0x255D => {
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx + t / 2, y0, t, cy - y0 + 1);
            fill_rect(alpha, size, x0, y0 - t / 2, cx - x0 + 1, t);
        }
        0x255E | 0x255F | 0x2560 => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, cx + t / 2, y0, t, y1 - y0 + 1);
            fill_rect(alpha, size, cx, cy + t + t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, cx - t - t / 2, y0, t, cy - y0 + 1);
        }
        0x2561 | 0x2562 | 0x2563 => {
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx + t / 2, y0, t, y1 - y0 + 1);
            fill_rect(alpha, size, x0, cy + t + t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx - t - t / 2, y0, t, cy - y0 + 1);
        }
        0x2564 | 0x2565 | 0x2566 => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx + t / 2, cy, t, y1 - cy + 1);
            fill_rect(alpha, size, cx, cy + t + t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, cy + t + t / 2, cx - x0 + 1, t);
        }
        0x2567 | 0x2568 | 0x2569 => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx + t / 2, y0, t, cy - y0 + 1);
            fill_rect(alpha, size, cx, y0 - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, y0 - t / 2, cx - x0 + 1, t);
        }
        0x256A | 0x256B | 0x256C => {
            fill_rect(alpha, size, cx, cy - t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, cy - t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx, cy + t + t / 2, x1 - cx + 1, t);
            fill_rect(alpha, size, x0, cy + t + t / 2, cx - x0 + 1, t);
            fill_rect(alpha, size, cx - t - t / 2, y0, t, y1 - y0 + 1);
            fill_rect(alpha, size, cx + t / 2, y0, t, y1 - y0 + 1);
        }
        // 圆角：普通角 + 角点加粗（近似圆角）
        0x256D..=0x2570 => {
            let (dx, dy, hdir, vdir) = match code {
                0x256D => (1, 1, 0x04, 0x02),  // ╭
                0x256E => (-1, 1, 0x08, 0x02), // ╮
                0x256F => (1, -1, 0x04, 0x01), // ╯
                _ => (-1, -1, 0x08, 0x01),     // ╰
            };
            let hx0 = if hdir & 0x04 != 0 { cx } else { x0 };
            let hx1 = if hdir & 0x04 != 0 { x1 } else { cx };
            let vy0 = if vdir & 0x02 != 0 { cy } else { y0 };
            let vy1 = if vdir & 0x02 != 0 { y1 } else { cy };
            fill_rect(alpha, size, hx0, cy - t / 2, hx1 - hx0 + 1, t);
            fill_rect(alpha, size, cx - t / 2, vy0, t, vy1 - vy0 + 1);
            fill_circle(alpha, size, cx + dx * (t / 2), cy + dy * (t / 2), t / 2 + 1);
        }
        0x2571 => draw_line(alpha, size, x0, y1, x1, y0, t / 2), // ╱
        0x2572 => draw_line(alpha, size, x0, y0, x1, y1, t / 2), // ╲
        0x2573 => {
            draw_line(alpha, size, x0, y1, x1, y0, t / 2);
            draw_line(alpha, size, x0, y0, x1, y1, t / 2);
        }
        _ => {}
    }
}

// ---------- 块元素 ----------

fn draw_block(alpha: &mut [u8], size: u32, code: u16) {
    let s = size as i32;
    let half = s / 2;
    let eighth = (s as f32 / 8.0).round() as i32;
    match code {
        0x2580 => fill_rect(alpha, size, 0, 0, s, half), // 上半
        0x2584 => fill_rect(alpha, size, 0, half, s, s - half), // 下半
        0x2581..=0x2587 => fill_rect(
            alpha,
            size,
            0,
            s - eighth * (code as i32 - 0x2580),
            s,
            eighth * (code as i32 - 0x2580),
        ), // 下 1/8..7/8
        0x2588 => fill_rect(alpha, size, 0, 0, s, s),    // 全块
        0x2589..=0x258B => fill_rect(alpha, size, 0, 0, eighth * (code as i32 - 0x2588 + 1), s), // 左 1/8..3/8
        0x258C => fill_rect(alpha, size, 0, 0, half, s), // 左半
        0x2590 => fill_rect(alpha, size, half, 0, s - half, s), // 右半
        0x2591 => {
            // 浅阴影：棋盘 25%
            for y in 0..s {
                for x in 0..s {
                    if (x / 2 + y / 2) % 2 == 0 {
                        set_px(alpha, size, x, y, 255);
                    }
                }
            }
        }
        0x2592 => {
            for y in 0..s {
                for x in 0..s {
                    if (x + y) % 2 == 0 {
                        set_px(alpha, size, x, y, 255);
                    }
                }
            }
        }
        0x2593 => {
            for y in 0..s {
                for x in 0..s {
                    if x % 2 == 0 || y % 2 == 0 {
                        set_px(alpha, size, x, y, 255);
                    }
                }
            }
        }
        0x2596 => fill_rect(alpha, size, 0, half, half, s - half), // ▖ 左下
        0x2597 => fill_rect(alpha, size, half, half, s - half, s - half), // ▗ 右下
        0x2598 => fill_rect(alpha, size, 0, 0, half, half),        // ▘ 左上
        0x2599 => {
            fill_rect(alpha, size, 0, 0, half, half);
            fill_rect(alpha, size, 0, half, s, s - half);
        }
        0x259A => {
            fill_rect(alpha, size, 0, 0, half, half);
            fill_rect(alpha, size, half, half, s - half, s - half);
        }
        0x259B => {
            fill_rect(alpha, size, 0, 0, half, s);
            fill_rect(alpha, size, half, 0, s - half, half);
        }
        0x259C => {
            fill_rect(alpha, size, 0, 0, s, half);
            fill_rect(alpha, size, half, half, s - half, s - half);
        }
        0x259D => fill_rect(alpha, size, half, 0, s - half, half), // ▝ 右上
        0x259E => {
            fill_rect(alpha, size, half, 0, s - half, half);
            fill_rect(alpha, size, 0, half, half, s - half);
        }
        0x259F => {
            fill_rect(alpha, size, 0, half, s, s - half);
            fill_rect(alpha, size, half, 0, s - half, half);
        }
        _ => {}
    }
}

// ---------- 几何形 ----------

fn draw_geometric(alpha: &mut [u8], size: u32, code: u16) -> Option<()> {
    let s = size as i32;
    let m = (s as f32 * 0.14) as i32;
    let t = ((s as f32 * 0.07) as i32).max(2);
    match code {
        0x25A0 => fill_rect(alpha, size, m, m, s - 2 * m, s - 2 * m), // ■
        0x25A1 => {
            // □ 描边
            fill_rect(alpha, size, m, m, s - 2 * m, t);
            fill_rect(alpha, size, m, s - m - t, s - 2 * m, t);
            fill_rect(alpha, size, m, m, t, s - 2 * m);
            fill_rect(alpha, size, s - m - t, m, t, s - 2 * m);
        }
        0x25AA => fill_rect(alpha, size, s / 4, s / 4, s / 2, s / 2), // ▪
        0x25AC => fill_rect(alpha, size, m, s / 2 - t / 2, s - 2 * m, t), // ▬
        0x25CF => fill_circle(alpha, size, s / 2, s / 2, s / 2 - m),  // ●
        0x25CB => {
            // ○ 描边环
            let r = s / 2 - m;
            for y in 0..s {
                for x in 0..s {
                    let dx = x - s / 2;
                    let dy = y - s / 2;
                    let d2 = dx * dx + dy * dy;
                    let r_out = r * r;
                    let r_in = (r - t) * (r - t);
                    if d2 <= r_out && d2 >= r_in {
                        set_px(alpha, size, x, y, 255);
                    }
                }
            }
        }
        0x25A2..=0x25A9 | 0x25B2..=0x25B7 | 0x25BC..=0x25C1 | 0x25C6..=0x25C7 => {
            // 三角形/菱形族：统一按"外接正方形内填充"近似（实心）
            fill_rect(alpha, size, m, m, s - 2 * m, s - 2 * m);
        }
        0x25CA..=0x25CF => fill_circle(alpha, size, s / 2, s / 2, s / 2 - m),
        _ => return None,
    }
    Some(())
}

// ---------- 箭头 ----------

fn draw_arrow(alpha: &mut [u8], size: u32, code: u16) {
    let s = size as i32;
    let t = ((s as f32 * 0.08) as i32).max(2);
    let head = (s as f32 * 0.28) as i32;
    match code {
        0x2190 => {
            // ←
            fill_rect(alpha, size, head, s / 2 - t / 2, s - 2 * head, t);
            draw_line(alpha, size, head, s / 2 - head, head, s / 2 + head, t);
            draw_line(alpha, size, head, s / 2 + head, 0, s / 2, t);
            draw_line(alpha, size, 0, s / 2, head, s / 2 - head, t);
        }
        0x2192 => {
            fill_rect(alpha, size, head, s / 2 - t / 2, s - 2 * head, t);
            draw_line(
                alpha,
                size,
                s - head,
                s / 2 - head,
                s - head,
                s / 2 + head,
                t,
            );
            draw_line(alpha, size, s - head, s / 2 + head, s, s / 2, t);
            draw_line(alpha, size, s, s / 2, s - head, s / 2 - head, t);
        }
        0x2191 => {
            fill_rect(alpha, size, s / 2 - t / 2, head, t, s - 2 * head);
            draw_line(alpha, size, s / 2 - head, head, s / 2 + head, head, t);
            draw_line(alpha, size, s / 2 + head, head, s / 2, 0, t);
            draw_line(alpha, size, s / 2, 0, s / 2 - head, head, t);
        }
        0x2193 => {
            fill_rect(alpha, size, s / 2 - t / 2, head, t, s - 2 * head);
            draw_line(
                alpha,
                size,
                s / 2 - head,
                s - head,
                s / 2 + head,
                s - head,
                t,
            );
            draw_line(alpha, size, s / 2 + head, s - head, s / 2, s, t);
            draw_line(alpha, size, s / 2, s, s / 2 - head, s - head, t);
        }
        0x2194 => {
            fill_rect(alpha, size, head, s / 2 - t / 2, s - 2 * head, t);
            draw_line(alpha, size, head, s / 2 - head, 0, s / 2, t);
            draw_line(alpha, size, 0, s / 2, head, s / 2 + head, t);
            draw_line(alpha, size, s - head, s / 2 - head, s, s / 2, t);
            draw_line(alpha, size, s, s / 2, s - head, s / 2 + head, t);
        }
        0x2195 => {
            fill_rect(alpha, size, s / 2 - t / 2, head, t, s - 2 * head);
            draw_line(alpha, size, s / 2 - head, head, s / 2, 0, t);
            draw_line(alpha, size, s / 2, 0, s / 2 + head, head, t);
            draw_line(alpha, size, s / 2 - head, s - head, s / 2, s, t);
            draw_line(alpha, size, s / 2, s, s / 2 + head, s - head, t);
        }
        0x2196 | 0x2197 | 0x2198 | 0x2199 => {
            let (x0, y0, x1, y1) = match code {
                0x2196 => (s, 0, 0, s), // ↖
                0x2197 => (0, 0, s, s), // ↗
                0x2198 => (0, s, s, 0), // ↘
                _ => (s, s, 0, 0),      // ↙
            };
            draw_line(alpha, size, x0, y0, x1, y1, t / 2);
        }
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sprite_covers_core_symbols() {
        for ch in [
            '─', '│', '┌', '┐', '└', '┘', '├', '┤', '┬', '┴', '┼', '═', '║', '╔', '╗', '╚', '╝',
            '╠', '╣', '╦', '╩', '╬', '╭', '╮', '╯', '╰', '╱', '╲', '╳', '■', '□', '●', '○', '▲',
            '▼', '▶', '◀', '◆', '⠿', '⡇', '←', '→', '↑', '↓',
        ] {
            assert!(is_sprite(ch), "missing sprite: {ch:?}");
            let bmp = sprite_bitmap(ch, 32).expect("sprite bitmap");
            assert!(bmp.alpha.iter().any(|&a| a > 0), "empty bitmap: {ch:?}");
        }
    }
}
