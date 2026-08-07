//! 工单 14 离屏自检：渲染器集成 API 三项（选中文本 / 字号 / 配色板）。
//!
//! 1) mailbox 层：`Renderer` 句柄直接验证 selection_text / cell_size /
//!    set_font_size / set_palette / reset_palette。
//! 2) GPU 像素层：真实顶点管线（RowVertexStore + 双图集 + 同款 WGSL）离屏渲染，
//!    断言字号变化时行高与字形像素变大；push 配色板后背景/选择色/前景像素变化；
//!    未 push 时维持现状（灰底 + 核心解析 + SGR 颜色）。

use fable_render::ffi::*;
use fable_render::render_android::{
    GlyphAtlas, OverlayRange, Palette, RowVertexStore, Rgb, Snapshot, apply_palette,
};
use fable_render::Renderer;

const COLS: u16 = 40;
const ROWS: u16 = 10;
const VERTEX_STRIDE: u64 = 36;

const SURFACE_SHADER: &str = r#"
struct VertexInput {
    @location(0) position: vec2<f32>,
    @location(1) tex_coord: vec2<f32>,
    @location(2) color: vec4<f32>,
    @location(3) mode: f32,
};

struct VertexOutput {
    @builtin(position) position: vec4<f32>,
    @location(0) tex_coord: vec2<f32>,
    @location(1) color: vec4<f32>,
    @location(2) mode: f32,
};

@group(0) @binding(0) var glyph_atlas: texture_2d_array<f32>;
@group(0) @binding(1) var glyph_sampler: sampler;

@vertex
fn vs_main(input: VertexInput) -> VertexOutput {
    var output: VertexOutput;
    output.position = vec4<f32>(input.position, 0.0, 1.0);
    output.tex_coord = input.tex_coord;
    output.color = input.color;
    output.mode = input.mode;
    return output;
}

@fragment
fn fs_main(input: VertexOutput) -> @location(0) vec4<f32> {
    if (input.mode < 0.5) {
        return input.color;
    }
    var layer: u32;
    if (input.mode < 1.5) {
        layer = 0u;
    } else {
        layer = 1u;
    }
    let sample = textureSample(glyph_atlas, glyph_sampler, input.tex_coord, layer);
    if (input.mode >= 1.5) {
        let linear = pow(sample.rgb, vec3<f32>(2.2));
        return vec4<f32>(linear, sample.a);
    }
    return vec4<f32>(input.color.rgb, input.color.a * sample.a);
}
"#;

fn check(result: GhosttyResult, what: &str) {
    assert_eq!(result, GHOSTTY_SUCCESS, "{what}: {result}");
}

unsafe fn collect(state: GhosttyRenderState) -> Snapshot {
    let mut colors = GhosttyRenderStateColors {
        size: std::mem::size_of::<GhosttyRenderStateColors>(),
        background: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        foreground: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        cursor: GhosttyColorRgb { r: 0, g: 0, b: 0 },
        cursor_has_value: false,
        palette: [GhosttyColorRgb { r: 0, g: 0, b: 0 }; 256],
    };
    let _ = ghostty_render_state_colors_get(state, &mut colors);
    fable_render::render_android::collect_snapshot(state, &colors)
}

fn print_case(ok: bool, label: &str, all_ok: &mut bool) {
    println!("  {} : {}", if ok { "PASS" } else { "FAIL" }, label);
    if !ok {
        *all_ok = false;
    }
}

/// mailbox 层 API 自检：不接 GPU，只验证命令保序与返回值。
fn mailbox_checks() -> bool {
    let mut all_ok = true;
    let renderer = Renderer::new(COLS, ROWS).expect("renderer");

    // 选中文本：跨行 + CJK/emoji 宽字符边界。
    renderer.write("第一行\r\nline-two\r\n🚀 emoji 中文\r\n".as_bytes());
    renderer.set_selection(0, 0, 4);
    renderer.set_selection(1, 0, 8);
    renderer.set_selection(2, 0, 11);
    let text = renderer.selection_text();
    println!("selection_text={text:?}");
    print_case(text == "第一\nline-two\n🚀 emoji 中", "selection: 跨行文本正确", &mut all_ok);

    renderer.set_selection(1, 0, 0);
    renderer.set_selection(2, 0, 0);
    renderer.set_selection(0, 2, 3);
    let wide_tail = renderer.selection_text();
    println!("wide_tail={wide_tail:?}");
    print_case(wide_tail == "一", "selection: CJK 右半边选中仍取整字", &mut all_ok);

    renderer.set_selection(0, 0, 0);
    renderer.set_selection(2, 1, 2);
    let emoji_tail = renderer.selection_text();
    println!("emoji_tail={emoji_tail:?}");
    print_case(emoji_tail == "🚀", "selection: emoji 右半边选中仍取整字", &mut all_ok);

    renderer.set_selection(2, 0, 0);
    renderer.set_selection(10, 0, 5);
    print_case(renderer.selection_text().is_empty(), "selection: 越界行返回空", &mut all_ok);

    // 字号：cell size 随字号变化；默认值 = 24 行为不变。
    let default_cell = renderer.cell_size();
    renderer.set_font_size(16.0);
    let cell_16 = renderer.cell_size();
    renderer.set_font_size(24.0);
    let cell_24 = renderer.cell_size();
    println!("cell_size default={default_cell:?} size16={cell_16:?} size24={cell_24:?}");
    print_case(
        cell_24.0 > cell_16.0 && cell_24.1 > cell_16.1,
        "font: 字号 24 的 cell 尺寸大于 16",
        &mut all_ok,
    );
    print_case(default_cell == cell_24, "font: 默认字号行为保持（=24）", &mut all_ok);

    // 配色板 push/reset 不破坏 mailbox。
    renderer.set_palette(Palette {
        fg: Rgb { r: 255, g: 255, b: 255 },
        bg: Rgb { r: 20, g: 80, b: 20 },
        selection: Rgb { r: 220, g: 40, b: 220 },
        cursor: Rgb { r: 0, g: 255, b: 255 },
    });
    renderer.set_selection(9, 0, 0);
    renderer.set_selection(0, 0, 4);
    print_case(
        renderer.selection_text() == "第一",
        "palette: push 后选中文本仍正常",
        &mut all_ok,
    );
    renderer.reset_palette();
    renderer.set_selection(9, 0, 0);
    renderer.set_selection(0, 0, 4);
    print_case(
        renderer.selection_text() == "第一",
        "palette: reset 后选中文本仍正常",
        &mut all_ok,
    );

    all_ok
}

/// 离屏渲染一帧（真实 RowVertexStore + 双图集 + 同款 WGSL），返回 RGBA 像素。
fn render_offscreen(
    snapshot: &Snapshot,
    atlas: &mut GlyphAtlas,
    overlays: &[OverlayRange],
    width: u32,
    height: u32,
    clear: [f32; 4],
) -> Vec<u8> {
    let mut store = RowVertexStore::new(snapshot.cols, snapshot.rows);
    let mut ranges = Vec::new();
    for row in 0..snapshot.rows as usize {
        ranges.push(store.rebuild_row(row, snapshot, atlas, width, height));
    }
    ranges.push(store.rebuild_overlays(snapshot, overlays, width, height));
    let draw_ranges = store.draw_ranges();

    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = wgpu::Backends::VULKAN;
    let instance = wgpu::Instance::new(descriptor);
    let adapter = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN))
        .first()
        .cloned()
        .expect("no adapter");
    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("api-check-device"),
        required_limits: adapter.limits(),
        ..Default::default()
    }))
    .expect("device");

    let target = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("api-check-target"),
        size: wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT | wgpu::TextureUsages::COPY_SRC,
        view_formats: &[],
    });
    let target_view = target.create_view(&wgpu::TextureViewDescriptor::default());

    let (atlas_w, atlas_h) = atlas.extent();
    let atlas_tex = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("api-check-atlas"),
        size: wgpu::Extent3d {
            width: atlas_w,
            height: atlas_h,
            depth_or_array_layers: 2,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    let mut layers = Vec::with_capacity((atlas_w * atlas_h * 8) as usize);
    layers.extend_from_slice(atlas.pixels());
    layers.extend_from_slice(atlas.color_pixels());
    queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &atlas_tex,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        &layers,
        wgpu::TexelCopyBufferLayout {
            offset: 0,
            bytes_per_row: Some(atlas_w * 4),
            rows_per_image: Some(atlas_h),
        },
        wgpu::Extent3d {
            width: atlas_w,
            height: atlas_h,
            depth_or_array_layers: 2,
        },
    );

    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("api-check-shader"),
        source: wgpu::ShaderSource::Wgsl(SURFACE_SHADER.into()),
    });
    let bind_group_layout = device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
        label: Some("api-check-bgl"),
        entries: &[
            wgpu::BindGroupLayoutEntry {
                binding: 0,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Texture {
                    sample_type: wgpu::TextureSampleType::Float { filterable: true },
                    view_dimension: wgpu::TextureViewDimension::D2Array,
                    multisampled: false,
                },
                count: None,
            },
            wgpu::BindGroupLayoutEntry {
                binding: 1,
                visibility: wgpu::ShaderStages::FRAGMENT,
                ty: wgpu::BindingType::Sampler(wgpu::SamplerBindingType::Filtering),
                count: None,
            },
        ],
    });
    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("api-check-pl"),
        bind_group_layouts: &[Some(&bind_group_layout)],
        immediate_size: 0,
    });
    let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("api-check-pipeline"),
        layout: Some(&pipeline_layout),
        vertex: wgpu::VertexState {
            module: &shader,
            entry_point: Some("vs_main"),
            compilation_options: wgpu::PipelineCompilationOptions::default(),
            buffers: &[Some(wgpu::VertexBufferLayout {
                array_stride: VERTEX_STRIDE,
                step_mode: wgpu::VertexStepMode::Vertex,
                attributes: &[
                    wgpu::VertexAttribute {
                        format: wgpu::VertexFormat::Float32x2,
                        offset: 0,
                        shader_location: 0,
                    },
                    wgpu::VertexAttribute {
                        format: wgpu::VertexFormat::Float32x2,
                        offset: 8,
                        shader_location: 1,
                    },
                    wgpu::VertexAttribute {
                        format: wgpu::VertexFormat::Float32x4,
                        offset: 16,
                        shader_location: 2,
                    },
                    wgpu::VertexAttribute {
                        format: wgpu::VertexFormat::Float32,
                        offset: 32,
                        shader_location: 3,
                    },
                ],
            })],
        },
        fragment: Some(wgpu::FragmentState {
            module: &shader,
            entry_point: Some("fs_main"),
            compilation_options: wgpu::PipelineCompilationOptions::default(),
            targets: &[Some(wgpu::ColorTargetState {
                format: wgpu::TextureFormat::Rgba8Unorm,
                blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                write_mask: wgpu::ColorWrites::ALL,
            })],
        }),
        primitive: wgpu::PrimitiveState {
            topology: wgpu::PrimitiveTopology::TriangleList,
            ..Default::default()
        },
        depth_stencil: None,
        multisample: wgpu::MultisampleState::default(),
        multiview_mask: None,
        cache: None,
    });
    let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
        label: Some("api-check-sampler"),
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        ..Default::default()
    });
    let atlas_view = atlas_tex.create_view(&wgpu::TextureViewDescriptor {
        dimension: Some(wgpu::TextureViewDimension::D2Array),
        array_layer_count: Some(2),
        ..Default::default()
    });
    let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("api-check-bg"),
        layout: &bind_group_layout,
        entries: &[
            wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::TextureView(&atlas_view),
            },
            wgpu::BindGroupEntry {
                binding: 1,
                resource: wgpu::BindingResource::Sampler(&sampler),
            },
        ],
    });

    let required = ranges
        .iter()
        .map(|(offset, len)| offset + *len as u64)
        .max()
        .unwrap_or(0);
    let vertex_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("api-check-vb"),
        size: required.max(1),
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::VERTEX,
        mapped_at_creation: false,
    });
    for (offset, len) in &ranges {
        let start = *offset as usize;
        queue.write_buffer(&vertex_buffer, *offset, &store.payload()[start..start + *len]);
    }

    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
        label: Some("api-check-encoder"),
    });
    {
        let color_attachments = [Some(wgpu::RenderPassColorAttachment {
            view: &target_view,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations {
                load: wgpu::LoadOp::Clear(wgpu::Color {
                    r: clear[0] as f64,
                    g: clear[1] as f64,
                    b: clear[2] as f64,
                    a: clear[3] as f64,
                }),
                store: wgpu::StoreOp::Store,
            },
        })];
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("api-check-pass"),
            color_attachments: &color_attachments,
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
            multiview_mask: None,
        });
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind_group, &[]);
        pass.set_vertex_buffer(0, vertex_buffer.slice(..));
        for (start, count) in &draw_ranges {
            pass.draw(*start..start + count, 0..1);
        }
    }

    let row_pitch = (width * 4 + 255) & !255;
    let readback = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("api-check-readback"),
        size: (row_pitch as u64) * height as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::MAP_READ,
        mapped_at_creation: false,
    });
    encoder.copy_texture_to_buffer(
        wgpu::TexelCopyTextureInfo {
            texture: &target,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        wgpu::TexelCopyBufferInfo {
            buffer: &readback,
            layout: wgpu::TexelCopyBufferLayout {
                offset: 0,
                bytes_per_row: Some(row_pitch),
                rows_per_image: Some(height),
            },
        },
        wgpu::Extent3d {
            width,
            height,
            depth_or_array_layers: 1,
        },
    );
    queue.submit(Some(encoder.finish()));
    readback.slice(..).map_async(wgpu::MapMode::Read, |_| {});
    device
        .poll(wgpu::PollType::wait_indefinitely())
        .expect("poll");
    let data = readback.slice(..).get_mapped_range().expect("map");
    let mut pixels = Vec::with_capacity((width as usize) * (height as usize) * 4);
    for row in 0..height as usize {
        let base = row * row_pitch as usize;
        pixels.extend_from_slice(&data[base..base + width as usize * 4]);
    }
    pixels
}

fn pixel_at(pixels: &[u8], width: u32, x: u32, y: u32) -> (u8, u8, u8) {
    let i = ((y as usize) * width as usize + x as usize) * 4;
    (pixels[i], pixels[i + 1], pixels[i + 2])
}

fn text_bbox_height(pixels: &[u8], width: u32, cell_h: u32) -> u32 {
    // 无配色板帧：清屏灰底 (31,31,31)，文字白；取第一行区域非底像素包围盒。
    let mut min_y = u32::MAX;
    let mut max_y = 0u32;
    for y in 0..cell_h {
        for x in 0..width {
            let i = ((y as usize) * width as usize + x as usize) * 4;
            let diff = (pixels[i] as i32 - 31).abs().max(
                (pixels[i + 1] as i32 - 31).abs().max((pixels[i + 2] as i32 - 31).abs()),
            );
            if diff > 20 {
                min_y = min_y.min(y);
                max_y = max_y.max(y);
            }
        }
    }
    if max_y >= min_y {
        max_y - min_y + 1
    } else {
        0
    }
}

fn red_pixel_count(pixels: &[u8], width: u32, y0: u32, y1: u32) -> u32 {
    let mut count = 0;
    for y in y0..y1 {
        for x in 0..width {
            let i = ((y as usize) * width as usize + x as usize) * 4;
            let r = pixels[i] as u32;
            let g = pixels[i + 1] as u32;
            let b = pixels[i + 2] as u32;
            if r > 150 && r > g * 3 / 2 && r > b * 3 / 2 {
                count += 1;
            }
        }
    }
    count
}

fn yellow_pixel_count(pixels: &[u8], width: u32, y0: u32, y1: u32) -> u32 {
    let mut count = 0;
    for y in y0..y1 {
        for x in 0..width {
            let i = ((y as usize) * width as usize + x as usize) * 4;
            let r = pixels[i] as u32;
            let g = pixels[i + 1] as u32;
            let b = pixels[i + 2] as u32;
            if r > 200 && g > 180 && b < 100 {
                count += 1;
            }
        }
    }
    count
}

fn dark_pixel_count(pixels: &[u8], width: u32, y0: u32, y1: u32, x1: u32) -> u32 {
    let mut count = 0;
    for y in y0..y1 {
        for x in 0..x1 {
            let i = ((y as usize) * width as usize + x as usize) * 4;
            let r = pixels[i] as u32;
            let g = pixels[i + 1] as u32;
            let b = pixels[i + 2] as u32;
            if r < 20 && g < 30 && b < 20 {
                count += 1;
            }
        }
    }
    count
}

unsafe fn gpu_checks() -> bool {
    let mut all_ok = true;
    let opts = GhosttyTerminalOptions {
        cols: COLS,
        rows: ROWS,
        max_scrollback: 10000,
    };
    let mut terminal: GhosttyTerminal = std::ptr::null_mut();
    check(ghostty_terminal_new(std::ptr::null(), &mut terminal, opts), "terminal_new");
    let mut state: GhosttyRenderState = std::ptr::null_mut();
    check(ghostty_render_state_new(std::ptr::null(), &mut state), "render_state_new");
    let data = b"Hello World\r\n\x1b[31mRED\x1b[0m\r\n";
    ghostty_terminal_vt_write(terminal, data.as_ptr(), data.len());
    check(ghostty_render_state_update(state, terminal), "render_state_update");
    let snapshot = collect(state);

    let mut atlas = GlyphAtlas::new().expect("atlas");

    // 字号像素断言：行高与字形像素随字号变化。
    atlas.set_pixels_per_em(16.0);
    let (cw16, ch16) = atlas.cell_size();
    let frame16 = render_offscreen(
        &snapshot,
        &mut atlas,
        &[],
        cw16 * COLS as u32,
        ch16 * ROWS as u32,
        [0.12, 0.12, 0.12, 1.0],
    );
    atlas.set_pixels_per_em(24.0);
    let (cw24, ch24) = atlas.cell_size();
    let frame24 = render_offscreen(
        &snapshot,
        &mut atlas,
        &[],
        cw24 * COLS as u32,
        ch24 * ROWS as u32,
        [0.12, 0.12, 0.12, 1.0],
    );
    let bbox16 = text_bbox_height(&frame16, cw16 * COLS as u32, ch16);
    let bbox24 = text_bbox_height(&frame24, cw24 * COLS as u32, ch24);
    println!("font16 cell=({cw16},{ch16}) text_bbox_h={bbox16}");
    println!("font24 cell=({cw24},{ch24}) text_bbox_h={bbox24}");
    print_case(
        ch24 > ch16 && cw24 > cw16,
        "font: 行高/格宽像素随字号变大",
        &mut all_ok,
    );
    print_case(
        bbox24 > bbox16,
        "font: 字形像素尺寸随字号变大",
        &mut all_ok,
    );

    // 未 push：维持现状（灰底 + 核心解析 + SGR 红色保留）。
    let frame_default = render_offscreen(
        &snapshot,
        &mut atlas,
        &[],
        cw24 * COLS as u32,
        ch24 * ROWS as u32,
        [0.12, 0.12, 0.12, 1.0],
    );
    let (dr, dg, db) = pixel_at(&frame_default, cw24 * COLS as u32, cw24 * 30, ch24 * 5);
    println!("default bg px=({dr},{dg},{db})");
    print_case(
        dr >= 25 && dr <= 40 && dg >= 25 && dg <= 40 && db >= 25 && db <= 40,
        "palette: 未 push 维持现状灰底",
        &mut all_ok,
    );
    // SGR 红：第一行是 "Hello World"，第二行 "RED"。
    let sgr_red = red_pixel_count(&frame_default, cw24 * COLS as u32, ch24, ch24 * 2);
    println!("SGR red pixels={sgr_red}");
    print_case(sgr_red > 20, "palette: 未 push 时 SGR 16 色语义保留", &mut all_ok);

    // push 配色板：背景 / 前景 / 选择色像素变化。
    let mut snap_pal = snapshot.clone();
    let palette = Palette {
        fg: Rgb { r: 255, g: 240, b: 0 },
        bg: Rgb { r: 20, g: 80, b: 20 },
        selection: Rgb { r: 220, g: 40, b: 220 },
        cursor: Rgb { r: 0, g: 0, b: 0 },
    };
    apply_palette(&mut snap_pal, palette);
    let overlays = [OverlayRange {
        row: 0,
        start_col: 5,
        end_col: 7,
    }];
    let frame_pal = render_offscreen(
        &snap_pal,
        &mut atlas,
        &overlays,
        cw24 * COLS as u32,
        ch24 * ROWS as u32,
        [
            20.0 / 255.0,
            80.0 / 255.0,
            20.0 / 255.0,
            1.0,
        ],
    );
    let (br, bg, bb) = pixel_at(&frame_pal, cw24 * COLS as u32, cw24 * 30, ch24 * 5);
    println!("palette bg px=({br},{bg},{bb})");
    print_case(
        br >= 10 && br <= 30 && bg >= 70 && bg <= 95 && bb >= 10 && bb <= 30,
        "palette: push 后背景像素 = push bg",
        &mut all_ok,
    );
    // 前景色断言：push fg=黄，第一行 "Hello World" 应出现黄色字形像素。
    let pal_fg_yellow = yellow_pixel_count(&frame_pal, cw24 * COLS as u32, 0, ch24);
    println!("palette fg yellow pixels={pal_fg_yellow}");
    print_case(
        pal_fg_yellow > 20,
        "palette: push 后前景像素 = push fg",
        &mut all_ok,
    );
    // 光标色：push 深色光标应原样渲染（不触发坑 H 近黑转亮蓝）。
    let cursor_dark = dark_pixel_count(&frame_pal, cw24 * COLS as u32, ch24 * 2, ch24 * 3, cw24);
    println!("palette cursor dark pixels={cursor_dark}");
    print_case(
        cursor_dark > 20,
        "palette: push 深色光标原样渲染",
        &mut all_ok,
    );
    // 选择色混合：0.45*sel + 0.55*bg ≈ (110,62,110)，取第 5 列空格中心避开字形。
    let (cr, cg, cb) = pixel_at(
        &frame_pal,
        cw24 * COLS as u32,
        cw24 * 5 + cw24 / 2,
        ch24 / 2,
    );
    println!("palette selection px=({cr},{cg},{cb})");
    print_case(
        cr >= 85 && cr <= 135 && cg >= 40 && cg <= 85 && cb >= 85 && cb <= 135,
        "palette: push 后选择色像素 = push selection 混合",
        &mut all_ok,
    );
    // SGR 红在 push 配色板下仍保留（16 色语义不变）。
    let pal_sgr_red = red_pixel_count(&frame_pal, cw24 * COLS as u32, ch24, ch24 * 2);
    println!("palette SGR red pixels={pal_sgr_red}");
    print_case(pal_sgr_red > 20, "palette: push 后 SGR 16 色仍保留", &mut all_ok);

    ghostty_render_state_free(state);
    ghostty_terminal_free(terminal);
    all_ok
}

fn main() {
    force_tls_pad();
    let mut all_ok = true;
    println!("== 工单 14 mailbox API 自检 ==");
    let mailbox_ok = mailbox_checks();
    print_case(mailbox_ok, "mailbox 层全过", &mut all_ok);

    println!("\n== 工单 14 GPU 离屏像素自检 ==");
    let gpu_ok = unsafe { gpu_checks() };
    print_case(gpu_ok, "GPU 像素层全过", &mut all_ok);

    println!("\n结果: {}", if all_ok { "ALL PASS" } else { "FAILED" });
    std::process::exit(if all_ok { 0 } else { 1 });
}
