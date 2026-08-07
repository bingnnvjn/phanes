//! 工单 10 探针：Termux 内能否创建 wgpu Vulkan device（offscreen，无 Surface）。
//! 只探 adapter/device/queue + 一次 offscreen clear pass，不建 Surface。

fn main() {
    let mut descriptor = wgpu::InstanceDescriptor::new_without_display_handle();
    descriptor.backends = wgpu::Backends::VULKAN;
    let instance = wgpu::Instance::new(descriptor);

    let adapters = pollster::block_on(instance.enumerate_adapters(wgpu::Backends::VULKAN));
    println!("adapter_count={}", adapters.len());
    for adapter in &adapters {
        let info = adapter.get_info();
        println!(
            "adapter: backend={:?} name={} device_type={:?}",
            info.backend, info.name, info.device_type
        );
    }
    let adapter = match adapters.first() {
        Some(adapter) => adapter.clone(),
        None => {
            println!("RESULT: FAIL no-vulkan-adapter");
            std::process::exit(1);
        }
    };
    let info = adapter.get_info();
    println!(
        "backend={:?} name={} device_type={:?}",
        info.backend, info.name, info.device_type
    );

    let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor {
        label: Some("fable-wgpu-probe"),
        required_limits: adapter.limits(),
        ..Default::default()
    }))
    .unwrap_or_else(|err| {
        println!("RESULT: FAIL request-device {err}");
        std::process::exit(1);
    });
    println!("device-request=ok");

    let texture = device.create_texture(&wgpu::TextureDescriptor {
        label: Some("fable-probe-clear-target"),
        size: wgpu::Extent3d {
            width: 64,
            height: 64,
            depth_or_array_layers: 1,
        },
        mip_level_count: 1,
        sample_count: 1,
        dimension: wgpu::TextureDimension::D2,
        format: wgpu::TextureFormat::Rgba8Unorm,
        usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
        view_formats: &[],
    });
    let view = texture.create_view(&wgpu::TextureViewDescriptor::default());
    let mut encoder = device.create_command_encoder(&wgpu::CommandEncoderDescriptor {
        label: Some("fable-probe-encoder"),
    });
    {
        let color_attachments = [Some(wgpu::RenderPassColorAttachment {
            view: &view,
            depth_slice: None,
            resolve_target: None,
            ops: wgpu::Operations {
                load: wgpu::LoadOp::Clear(wgpu::Color::RED),
                store: wgpu::StoreOp::Store,
            },
        })];
        let _pass = encoder.begin_render_pass(&wgpu::RenderPassDescriptor {
            label: Some("fable-probe-pass"),
            color_attachments: &color_attachments,
            depth_stencil_attachment: None,
            timestamp_writes: None,
            occlusion_query_set: None,
            multiview_mask: None,
        });
    }
    queue.submit(Some(encoder.finish()));
    println!("RESULT: OK vulkan-device-ready");
}
