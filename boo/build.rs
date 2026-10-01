//! Embeds the Ghostty website animation frames as raw-DEFLATE compressed data.
//!
//! Format mirrors the upstream tool `ghostty/src/build/framegen/main.c`:
//! frame files sorted by name, joined with `\x01`, compressed with raw DEFLATE
//! (no zlib wrapper). Runtime decompression lives in `src/frames.rs`.

use std::env;
use std::fs;
use std::path::PathBuf;

fn main() {
    let manifest = env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR");
    let frames_dir = PathBuf::from(&manifest).join("data").join("frames");

    let mut names: Vec<_> = fs::read_dir(&frames_dir)
        .expect("frames dir")
        .map(|entry| entry.expect("frames dir entry").file_name())
        .filter(|name| name.to_string_lossy().ends_with(".txt"))
        .collect();
    names.sort();

    let mut joined: Vec<u8> = Vec::new();
    for (index, name) in names.iter().enumerate() {
        let frame = fs::read(frames_dir.join(name)).expect("read frame");
        joined.extend_from_slice(&frame);
        if index + 1 < names.len() {
            joined.push(0x01);
        }
    }

    // Z_DEFAULT_COMPRESSION in zlib terms == level 6, same as upstream framegen.
    let compressed = miniz_oxide::deflate::compress_to_vec(&joined, 6);

    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    fs::write(out_dir.join("framedata.compressed"), &compressed).expect("write compressed");

    println!("cargo:rerun-if-changed=data/frames");
}
