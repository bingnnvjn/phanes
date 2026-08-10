//! 工单 10/22 本地自检（方案 1）：用与真机渲染器相同的管线（顶点布局 +
//! 真实 SURFACE_SHADER + 灰度/彩色双纹理绑定组）把内容画到离屏纹理，
//! 读回像素验证。
//!
//! 覆盖点（工单 22 修复回归）：
//! 1. solid mode（原工单 10 检查：纯色可画）。
//! 2. 彩色 glyph mode（mode>=1.5）：shader 从独立 4096 彩色纹理采样 ——
//!    验证 D2 视图与 texture_2d 声明匹配（曾误用 texture_2d_array 声明 +
//!    D2 视图导致 create_render_pipeline 失败、真机黑屏）且设备支持 4096。
//! PASS = 管线/bind group/双纹理采样本身能用；问题在 Android Surface 呈现层；
//! FAIL = 管线/shader/顶点数据/纹理尺寸本身有问题。

use fable_render::render_android::{SURFACE_SHADER, Vertex};

const VERTEX_STRIDE: u64 = 36;

fn write_vertex(out: &mut Vec<u8>, v: &Vertex) {
    for f in v.position {
        out.extend_from_slice(&f.to_le_bytes());
    }
    for f in v.tex_coord {
        out.extend_from_slice(&f.to_le_bytes());
    }
    for f in v.color {
        out.extend_from_slice(&f.to_le_bytes());
    }
    out.extend_from_slice(&v.mode.to_le_bytes());
}

fn main() {
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = wgpu::Backends::VULKAN;
    let instance = wgpu::Instance::new(descriptor);
    let adapters = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN));
    let adapter = match adapters.first() {
        Some(adapter) => adapter.clone(),
        None => {
            println!("RESULT: FAIL no-adapter");
            std::process::exit(1);
        }
    };
    let info = adapter.get_info();
    println!("adapter={} backend={:?}", info.name, info.backend);

    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("offscreen-check-device"),
        required_limits: adapter.limits(),
        ..Default::default()
    }))
    .unwrap_or_else(|error| {
        println!("RESULT: FAIL device {error}");
        std::process::exit(1);
    });

    let size = 64u32;
    let target = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("offscreen-target"),
        size: wgpu::Extent3d {
            width: size,
            height: size,
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

    // 灰度图集 2048（与渲染器一致）：像素 (0,0)=白（glyph 采样）。
    let gray_w = 2048u32;
    let gray = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("gray-atlas"),
        size: wgpu::Extent3d {
            width: gray_w,
            height: gray_w,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &gray,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        &[255u8, 255, 255, 255],
        wgpu::TexelCopyBufferLayout {
            offset: 0,
            bytes_per_row: Some(4),
            rows_per_image: Some(1),
        },
        wgpu::Extent3d {
            width: 1,
            height: 1,
            depth_or_array_layers: 1,
        },
    );

    // 彩色图集 4096（与渲染器一致）：像素 (0,0)=绿（彩色 glyph 采样）。
    // 同时验证设备 max_texture_dimension_2d >= 4096（工单 22 图集升级）。
    let color_w = 4096u32;
    let color = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("color-atlas"),
        size: wgpu::Extent3d {
            width: color_w,
            height: color_w,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::COPY_DST | wgpu::TextureUsages::TEXTURE_BINDING,
        view_formats: &[],
    });
    queue.write_texture(
        wgpu::TexelCopyTextureInfo {
            texture: &color,
            mip_level: 0,
            origin: wgpu::Origin3d::ZERO,
            aspect: wgpu::TextureAspect::All,
        },
        &[0u8, 255, 0, 255],
        wgpu::TexelCopyBufferLayout {
            offset: 0,
            bytes_per_row: Some(4),
            rows_per_image: Some(1),
        },
        wgpu::Extent3d {
            width: 1,
            height: 1,
            depth_or_array_layers: 1,
        },
    );

    let shader = device.create_shader_module(wgpu::ShaderModuleDescriptor {
        label: Some("offscreen-check-shader"),
        source: wgpu::ShaderSource::Wgsl(SURFACE_SHADER.into()),
    });
    // 与 render_android::Renderer::ensure_pipeline 同款 3-binding 布局
    // （0=灰度 D2、1=sampler、2=彩色 D2）。
    let bind_group_layout =
        device.create_bind_group_layout(&wgpu::BindGroupLayoutDescriptor {
            label: Some("offscreen-check-bgl"),
            entries: &[
                wgpu::BindGroupLayoutEntry {
                    binding: 0,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
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
                wgpu::BindGroupLayoutEntry {
                    binding: 2,
                    visibility: wgpu::ShaderStages::FRAGMENT,
                    ty: wgpu::BindingType::Texture {
                        sample_type: wgpu::TextureSampleType::Float { filterable: true },
                        view_dimension: wgpu::TextureViewDimension::D2,
                        multisampled: false,
                    },
                    count: None,
                },
            ],
        });
    let pipeline_layout = device.create_pipeline_layout(&wgpu::PipelineLayoutDescriptor {
        label: Some("offscreen-check-pl"),
        bind_group_layouts: &[Some(&bind_group_layout)],
        immediate_size: 0,
    });
    let pipeline = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
        label: Some("offscreen-check-pipeline"),
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
        label: Some("offscreen-check-sampler"),
        mag_filter: wgpu::FilterMode::Linear,
        min_filter: wgpu::FilterMode::Linear,
        ..Default::default()
    });
    let gray_view = gray.create_view(&wgpu::TextureViewDescriptor::default());
    let color_view = color.create_view(&wgpu::TextureViewDescriptor::default());
    let bind_group = device.create_bind_group(&wgpu::BindGroupDescriptor {
        label: Some("offscreen-check-bg"),
        layout: &bind_group_layout,
        entries: &[
            wgpu::BindGroupEntry {
                binding: 0,
                resource: wgpu::BindingResource::TextureView(&gray_view),
            },
            wgpu::BindGroupEntry {
                binding: 1,
                resource: wgpu::BindingResource::Sampler(&sampler),
            },
            wgpu::BindGroupEntry {
                binding: 2,
                resource: wgpu::BindingResource::TextureView(&color_view),
            },
        ],
    });

    // Pass 1：solid 红色（mode=0）。
    // Pass 2：彩色 glyph（mode=2），UV 覆盖彩色纹理 (0,0) 一个像素 -> 绿色。
    let red = [1.0f32, 0.0, 0.0, 1.0];
    let white = [1.0f32, 1.0, 1.0, 1.0];
    let solid = [
        Vertex { position: [-1.0, -1.0], tex_coord: [0.0, 1.0], color: red, mode: 0.0 },
        Vertex { position: [1.0, -1.0], tex_coord: [1.0, 1.0], color: red, mode: 0.0 },
        Vertex { position: [-1.0, 1.0], tex_coord: [0.0, 0.0], color: red, mode: 0.0 },
        Vertex { position: [1.0, -1.0], tex_coord: [1.0, 1.0], color: red, mode: 0.0 },
        Vertex { position: [1.0, 1.0], tex_coord: [1.0, 0.0], color: red, mode: 0.0 },
        Vertex { position: [-1.0, 1.0], tex_coord: [0.0, 0.0], color: red, mode: 0.0 },
    ];
    let u = 1.0f32 / color_w as f32;
    let v = 1.0f32 / color_w as f32;
    let color_glyph = [
        Vertex { position: [-1.0, -1.0], tex_coord: [0.0, v], color: white, mode: 2.0 },
        Vertex { position: [1.0, -1.0], tex_coord: [u, v], color: white, mode: 2.0 },
        Vertex { position: [-1.0, 1.0], tex_coord: [0.0, 0.0], color: white, mode: 2.0 },
        Vertex { position: [1.0, -1.0], tex_coord: [u, v], color: white, mode: 2.0 },
        Vertex { position: [1.0, 1.0], tex_coord: [u, 0.0], color: white, mode: 2.0 },
        Vertex { position: [-1.0, 1.0], tex_coord: [0.0, 0.0], color: white, mode: 2.0 },
    ];
    let mut payload = Vec::new();
    for vertex in &solid {
        write_vertex(&mut payload, vertex);
    }
    for vertex in &color_glyph {
        write_vertex(&mut payload, vertex);
    }
    let vertex_buffer = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("offscreen-check-vb"),
        size: payload.len() as u64,
        usage: wgpu::BufferUsages::COPY_DST | wgpu::BufferUsages::VERTEX,
        mapped_at_creation: false,
    });
    queue.write_buffer(&vertex_buffer, 0, &payload);

    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
        label: Some("offscreen-check-encoder"),
    });
    {
        let color_attachments = [Some(wgpu::RenderPassColorAttachment {
            view: &target_view,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations {
                load: wgpu::LoadOp::Clear(wgpu::Color::BLACK),
                store: wgpu::StoreOp::Store,
            },
        })];
        let mut pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("offscreen-check-pass"),
            color_attachments: &color_attachments,
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
            multiview_mask: None,
        });
        pass.set_pipeline(&pipeline);
        pass.set_bind_group(0, &bind_group, &[]);
        pass.set_vertex_buffer(0, vertex_buffer.slice(..));
        pass.draw(0..6, 0..1); // solid 红
        pass.draw(6..12, 0..1); // 彩色绿（叠加）
    }

    let readback = device.create_buffer(&wgpu::BufferDescriptor {
        label: Some("offscreen-check-readback"),
        size: (size * size * 4) as u64,
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
                bytes_per_row: Some(size * 4),
                rows_per_image: Some(size),
            },
        },
        wgpu::Extent3d {
            width: size,
            height: size,
            depth_or_array_layers: 1,
        },
    );
    queue.submit(Some(encoder.finish()));

    readback.slice(..).map_async(wgpu::MapMode::Read, |_| {});
    device
        .poll(wgpu::PollType::wait_indefinitely())
        .unwrap_or_else(|error| {
            println!("RESULT: FAIL poll {error}");
            std::process::exit(1);
        });
    let data = readback
        .slice(..)
        .get_mapped_range()
        .unwrap_or_else(|error| {
            println!("RESULT: FAIL map {error}");
            std::process::exit(1);
        });
    let center = (size / 2 * size + size / 2) as usize * 4;
    let (r, g, b) = (data[center], data[center + 1], data[center + 2]);
    println!("center=({r},{g},{b})");
    // 彩色 glyph 绿色叠加在 solid 红色上：期望绿色为主（G 高、R/B 低）。
    let ok = g > 200 && r < 100 && b < 100;
    println!(
        "RESULT: {}",
        if ok {
            "PASS color-glyph-sampled-from-4096-atlas"
        } else {
            "FAIL color-glyph-missing"
        }
    );
    std::process::exit(if ok { 0 } else { 1 });
}
