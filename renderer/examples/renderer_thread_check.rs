//! 工单 12 ④ 渲染线程生命周期检查：mailbox 非阻塞、线程启动/退出正常。

use fable_render::Renderer;
use std::time::Duration;

fn main() {
    let renderer = Renderer::new(80, 24).expect("renderer");
    renderer.write(b"hello world\r\n");
    renderer.resize(40, 10);
    renderer.scroll(-1);
    renderer.set_selection(0, 0, 5);
    assert!(renderer.render(800, 480));
    assert!(renderer.test_pattern(800, 480));

    let mut alive = false;
    for _ in 0..200 {
        if renderer.info().contains("thread_alive=true") {
            alive = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    assert!(alive, "render thread did not start");

    // 快速输出：多帧 write+render 不阻塞调用线程。
    let start = std::time::Instant::now();
    for _ in 0..200 {
        renderer.write(b"seq\r\n");
        assert!(renderer.render(800, 480));
    }
    let elapsed = start.elapsed();
    println!(
        "200x write+render enqueue took {}ms (non-blocking mailbox)",
        elapsed.as_millis()
    );
    drop(renderer);
    println!("renderer thread joined cleanly");
}
