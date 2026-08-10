fn main() {
    let (apple, noto) = fable_render::emoji::default_font_paths();
    let mut fonts = fable_render::emoji::EmojiFonts::load(
        Some(&std::path::PathBuf::from(&apple)),
        Some(&std::path::PathBuf::from(&noto)),
    );
    for s in ["🇦", "♀", "🔋", "🧑\u{200d}🎓"] {
        match fonts.rasterize_cluster(s, 24) {
            Some(b) => println!(
                "{}: {}x{} bbox={:?} origin=({},{})",
                s, b.width, b.height, b.bbox, b.canvas_origin_x, b.canvas_origin_y
            ),
            None => println!("{}: None", s),
        }
    }
}
