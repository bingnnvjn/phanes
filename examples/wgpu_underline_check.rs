//! 工单 13 真机"下划线完全没有"专项：走真实数据路径（核心 SGR 4 → 快照 →
//! RowVertexStore → 双图集 → wgpu 离屏 → 读回像素），断言文字行底部存在
//! 白色下划线像素带。全绿 = 渲染链正确，问题在设备呈现层。

use fable_render::ffi::*;
use fable_render::render_android::{GlyphAtlas, RowVertexStore, Snapshot};

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

const VERTEX_STRIDE: u64 = 36;
const W: u32 = 800;
const H: u32 = 480;
const COLS: u16 = 80;
const ROWS: u16 = 24;

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
    fable_render::render_android::collect_snapshot(state, &colors, None)
}

fn main() {
    unsafe {
        let opts = GhosttyTerminalOptions {
            cols: COLS,
            rows: ROWS,
            max_scrollback: 10000,
        };
        let mut terminal: GhosttyTerminal = std::ptr::null_mut();
        assert_eq!(
            ghostty_terminal_new(std::ptr::null(), &mut terminal, opts),
            GHOSTTY_SUCCESS
        );
        let mut state: GhosttyRenderState = std::ptr::null_mut();
        assert_eq!(
            ghostty_render_state_new(std::ptr::null(), &mut state),
            GHOSTTY_SUCCESS
        );
        // 与探针 [u-line] 按钮相同的命令：SGR 4 underline。
        let cmd = b"printf '\x1b[4munderline\x1b[0m\n'";
        ghostty_terminal_vt_write(terminal, cmd.as_ptr(), cmd.len());
        assert_eq!(ghostty_render_state_update(state, terminal), GHOSTTY_SUCCESS);
        let snapshot = collect(state);
        let mut any_underline = false;
        for row in &snapshot.lines {
            for cell in row {
                if cell.underline {
                    any_underline = true;
                }
            }
        }
        println!("snapshot: any_underline={any_underline}");

        let mut atlas = GlyphAtlas::new().expect("atlas");
        let mut store = RowVertexStore::new(COLS, ROWS);
        let mut ranges = Vec::new();
        for row in 0..snapshot.rows as usize {
            ranges.push(store.rebuild_row(row, &snapshot, &mut atlas, W, H));
        }
        ranges.push(store.rebuild_overlays(&snapshot, &[], W, H));
        let draw_ranges = store.draw_ranges();
        let solid_verts = store
            .payload()
            .chunks_exact(VERTEX_STRIDE as usize)
            .filter(|v| v[32..36].iter().enumerate().any(|(i, b)| {
                // mode 是最后一个 f32（offset 32）
                let mode = f32::from_le_bytes([v[32], v[33], v[34], v[35]]);
                i == 0 && mode.abs() < f32::EPSILON
            }))
            .count();
        println!("draw_ranges={draw_ranges:?} solid_vertices={solid_verts}");

        let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
        descriptor.backends = wgpu::Backends::VULKAN;
        let instance = wgpu::Instance::new(descriptor);
        let adapter = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN))
            .first()
            .cloned()
            .expect("no adapter");
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
            label: Some("ul-check-device"),
            required_limits: adapter.limits(),
            ..Default::default()
        }))
        .expect("device");

        let target = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("ul-check-target"),
            size: wgpu::Extent3d { width: W, height: H, depth_or_array_layers: 1 },
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
            label: Some("ul-check-atlas"),
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
            label: Some("ul-check-shader"),
            source: wgpu::ShaderSource::Wgsl(SURFACE_SHADER.into()),
        });
        let bind_group_layout =
            device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
                label: Some("ul-check-bgl"),
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
            label: Some("ul-check-pl"),
            bind_group_layouts: &[Some(&bind_group_layout)],
            immediate_size: 0,
        });
        let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("ul-check-pipeline"),
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
            label: Some("ul-check-sampler"),
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
            label: Some("ul-check-bg"),
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
            label: Some("ul-check-vb"),
            size: required.max(1),
            usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::VERTEX,
            mapped_at_creation: false,
        });
        for (offset, len) in &ranges {
            let start = *offset as usize;
            queue.write_buffer(&vertex_buffer, *offset, &store.payload()[start..start + *len]);
        }

        let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
            label: Some("ul-check-encoder"),
        });
        {
            let color_attachments = [Some(wgpu::RenderPassColorAttachment {
                view: &target_view,
                depth_slice: None,
                resolve_target: None,
                ops: wgpu::Operations {
                    load: wgpu::LoadOp::Clear(wgpu::Color { r: 0.0, g: 0.0, b: 0.0, a: 1.0 }),
                    store: wgpu::StoreOp::Store,
                },
            })];
            let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: Some("ul-check-pass"),
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

        let row_pitch = (W * 4 + 255) & !255;
        let readback = device.create_buffer(&wgpu::BufferDescriptor {
            label: Some("ul-check-readback"),
            size: (row_pitch as u64) * H as u64,
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
                    rows_per_image: Some(H),
                },
            },
            wgpu::Extent3d { width: W, height: H, depth_or_array_layers: 1 },
        );
        queue.submit(Some(encoder.finish()));
        readback.slice(..).map_async(wgpu::MapMode::Read, |_| {});
        device
            .poll(wgpu::PollType::wait_indefinitely())
            .expect("poll");
        let data = readback.slice(..).get_mapped_range().expect("map");

        // 断言：第一行（row 0，y 0..20）底部 3px（y 17..20）存在白色像素带。
        let row_h = H / ROWS as u32;
        let mut text_white = 0u32;
        let mut underline_white = 0u32;
        for col in 0..W {
            for dy in 0..row_h {
                let y = dy;
                let i = (y as usize * row_pitch as usize + col as usize * 4) as usize;
                let px = &data[i..i + 4];
                if px[0] > 200 && px[1] > 200 && px[2] > 200 {
                    if dy >= row_h - 3 {
                        underline_white += 1;
                    } else {
                        text_white += 1;
                    }
                }
            }
        }
        println!("row0: text_white={text_white} underline_white={underline_white}");
        let ok = any_underline && solid_verts > 0 && underline_white > 100;
        println!(
            "RESULT: {}",
            if ok {
                "PASS underline rendered offscreen"
            } else {
                "FAIL underline missing offscreen"
            }
        );
        ghostty_render_state_free(state);
        ghostty_terminal_free(terminal);
        std::process::exit(if ok { 0 } else { 1 });
    }
}
