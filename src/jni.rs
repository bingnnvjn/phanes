//! 工单 10：Rust 渲染器 + PTY 的 JNI 桥（com.gph.fable.app.RenderCore）。

use crate::render_android::{log_error, Palette, Renderer, Rgb, DEFAULT_ANSI_16};
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JIntArray, JObject, JString};
use jni::sys::{jboolean, jint, jintArray, jlong, jobject, jstring, JNI_FALSE, JNI_TRUE};
use jni::Env;
use jni::EnvUnowned;
use std::ffi::{c_char, c_void, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jobject) -> *mut c_void;
    fn ANativeWindow_release(window: *mut c_void) -> i32;
    fn fable_pty_spawn(shell: *const c_char, cols: jint, rows: jint) -> jlong;
    fn fable_pty_read(handle: jlong, buf: *mut u8, len: usize) -> jint;
    fn fable_pty_write(handle: jlong, data: *const u8, len: usize) -> jint;
    fn fable_pty_resize(handle: jlong, cols: jint, rows: jint);
    fn fable_pty_close(handle: jlong);
}

struct HandleRegistry<T> {
    #[cfg_attr(
        not(test),
        expect(
            dead_code,
            reason = "工单 50：JNI 创建路径由 Java 导出符号调用，Rust 静态分析看不到"
        )
    )]
    next_handle: AtomicI64,
    entries: Mutex<std::collections::HashMap<jlong, Arc<T>>>,
}

impl<T> HandleRegistry<T> {
    fn new() -> Self {
        Self {
            next_handle: AtomicI64::new(1),
            entries: Mutex::new(std::collections::HashMap::new()),
        }
    }

    #[cfg_attr(
        not(test),
        expect(
            dead_code,
            reason = "工单 50：JNI 创建路径由 Java 导出符号调用，Rust 静态分析看不到"
        )
    )]
    fn insert(&self, value: T) -> jlong {
        loop {
            let handle = self.next_handle.fetch_add(1, Ordering::Relaxed);
            if handle <= 0 {
                self.next_handle.store(1, Ordering::Relaxed);
                continue;
            }
            let mut entries = self
                .entries
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner());
            if entries.contains_key(&handle) {
                continue;
            }
            entries.insert(handle, Arc::new(value));
            return handle;
        }
    }

    fn get(&self, handle: jlong) -> Option<Arc<T>> {
        if handle <= 0 {
            return None;
        }
        self.entries
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .get(&handle)
            .cloned()
    }

    /// 移除注册表所有权；已经取得的 Arc 会自然完成在途调用后再析构。
    fn remove(&self, handle: jlong) -> Option<Arc<T>> {
        if handle <= 0 {
            return None;
        }
        self.entries
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .remove(&handle)
    }
}

fn renderer_registry() -> &'static HandleRegistry<Renderer> {
    static REGISTRY: OnceLock<HandleRegistry<Renderer>> = OnceLock::new();
    REGISTRY.get_or_init(HandleRegistry::new)
}

fn renderer_for_handle(handle: jlong) -> Option<Arc<Renderer>> {
    renderer_registry().get(handle)
}

fn with_renderer<F, R>(handle: jlong, f: F) -> R
where
    F: FnOnce(&Renderer) -> R,
    R: Default,
{
    catch_unwind(AssertUnwindSafe(|| {
        let Some(renderer) = renderer_for_handle(handle) else {
            return R::default();
        };
        f(&renderer)
    }))
    .unwrap_or_default()
}

fn with_jni_env<R: Default>(
    mut env: EnvUnowned,
    f: impl FnOnce(&mut Env) -> jni::errors::Result<R>,
) -> R {
    catch_unwind(AssertUnwindSafe(|| {
        env.with_env(f).resolve::<LogErrorAndDefault>()
    }))
    .unwrap_or_default()
}

fn jbytes_to_vec(env: &Env, data: JByteArray, len: jint) -> Vec<u8> {
    let arr_len = data.len(env).unwrap_or(0);
    let take = if len < 0 {
        arr_len
    } else {
        (len as usize).min(arr_len)
    };
    if take == 0 {
        return Vec::new();
    }
    let mut raw = vec![0i8; take];
    if data.get_region(env, 0, &mut raw).is_err() {
        return Vec::new();
    }
    raw.into_iter().map(|b| b as u8).collect()
}

fn argb_to_rgb(argb: jint) -> Rgb {
    let value = argb as u32;
    Rgb {
        r: ((value >> 16) & 0xff) as u8,
        g: ((value >> 8) & 0xff) as u8,
        b: (value & 0xff) as u8,
    }
}

#[cfg(test)]
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererCreate(
    _env: EnvUnowned,
    _class: JClass,
    cols: jint,
    rows: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        match Renderer::new(cols.max(1) as u16, rows.max(1) as u16) {
            Some(renderer) => renderer_registry().insert(renderer),
            None => 0,
        }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererDestroy(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    // remove 幂等；已在其他 JNI 调用中取得 Arc 的 Renderer 会在调用结束后析构。
    catch_unwind(AssertUnwindSafe(|| {
        drop(renderer_registry().remove(handle))
    }))
    .ok();
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererWrite(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) {
    let bytes = with_jni_env(env, |env| Ok(jbytes_to_vec(env, data, len)));
    with_renderer(handle, |renderer| renderer.write(&bytes));
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    with_renderer(handle, |renderer| {
        renderer.resize(cols.max(1) as u16, rows.max(1) as u16)
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererScroll(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    delta: jint,
) {
    with_renderer(handle, |renderer| renderer.scroll(delta as isize));
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetSelection(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    row: jint,
    start_col: jint,
    end_col: jint,
) {
    with_renderer(handle, |renderer| {
        renderer.set_selection(
            row.max(0) as u32,
            start_col.max(0) as u32,
            end_col.max(0) as u32,
        )
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSelectionText(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let text = with_renderer(handle, |renderer| renderer.selection_text());
    with_jni_env(env, |env| {
        let string = env.new_string(text)?;
        Ok(string.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetFontSize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    size_px: f32,
) {
    with_renderer(handle, |renderer| renderer.set_font_size(size_px));
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetFontPaths(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    apple_path: JString,
    noto_path: JString,
) {
    with_jni_env(env, |env| {
        let apple_value = apple_path.mutf8_chars(env)?;
        let apple = apple_value.to_str().into_owned();
        let noto_value = noto_path.mutf8_chars(env)?;
        let noto = noto_value.to_str().into_owned();
        with_renderer(handle, |renderer| {
            renderer.set_font_paths(&apple, &noto);
        });
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetCellSize(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    out: JIntArray,
) {
    let (width, height) = with_renderer(handle, |renderer| renderer.cell_size());
    with_jni_env(env, |env| {
        let arr_len = out.len(env).unwrap_or(0);
        if arr_len >= 2 {
            let values = [width as jint, height as jint];
            let _ = out.set_region(env, 0, &values);
        }
        Ok(())
    });
}

/// 工单 26：同步查询核心光标视口位置。返回 1=有光标（out[0]=列 out[1]=行，
/// 均以核心 2027 列模型为准，供 CPR 应答），0=无光标（out 置 -1）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetCursor(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    out: JIntArray,
) -> jint {
    let cursor = with_renderer(handle, |renderer| renderer.cursor_position());
    with_jni_env(env, |env| {
        let arr_len = out.len(env).unwrap_or(0);
        if arr_len >= 2 {
            let values = match cursor {
                Some((x, y)) => [x as jint, y as jint],
                None => [-1, -1],
            };
            let _ = out.set_region(env, 0, &values);
        }
        Ok(if cursor.is_some() { 1 } else { 0 })
    })
}

/// 工单 29：当前核心标题（未设置为空串）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetTitle(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let title = with_renderer(handle, |renderer| renderer.title());
    with_jni_env(env, |env| {
        let string = env.new_string(title)?;
        Ok(string.into_raw())
    })
}

/// 工单 29：读取并清除"标题已变更"标记。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererConsumeTitleChanged(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.consume_title_changed()) as jboolean
}

/// 工单 29：读取并清除 bell 标记。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererConsumeBell(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.consume_bell()) as jboolean
}

/// 工单 29：alternate screen（DECSET 1047/1049）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeAltScreen(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_alt_screen()) as jboolean
}

/// 工单 29：任一 mouse tracking 模式激活。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeMouseTracking(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_mouse_tracking()) as jboolean
}

/// 工单 29：光标可见（DECSET 25）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeCursorVisible(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_cursor_visible()) as jboolean
}

/// 工单 29：光标闪烁（DECSET 12）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeCursorBlink(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_cursor_blink()) as jboolean
}

/// 工单 30：光标键 application mode（DECCKM，DECSET ?1）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeCursorKeysApplication(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_cursor_keys_application()) as jboolean
}

/// 工单 30：小键盘 application mode（DECKPAM，DECSET ?66）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeKeypadApplication(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_keypad_application()) as jboolean
}

/// 工单 30：bracketed paste（DECSET 2004）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeBracketedPaste(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_bracketed_paste()) as jboolean
}

/// 工单 30：推送光标闪烁相位（true = 可见相位）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetCursorBlinkState(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    visible: jboolean,
) {
    with_renderer(handle, |renderer| renderer.set_cursor_blink_state(visible));
}

/// 工单 31：当前 mouse 是否 SGR 格式（DECSET 1006）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeMouseSgr(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_mouse_sgr()) as jboolean
}

/// 工单 31：当前 mouse 是否 button-event（1002）或 any-event（1003）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetModeMouseButtonEvent(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    with_renderer(handle, |renderer| renderer.mode_mouse_button_event()) as jboolean
}

/// 工单 31：当前可向上回看的历史行数。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetScrollbackRows(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jint {
    with_renderer(handle, |renderer| renderer.scrollback_rows() as jint)
}

/// 工单 31：外部行列区间文本（0 = 活动屏顶，负 = 历史）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetText(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    row: jint,
    start_col: jint,
    end_col: jint,
) -> jstring {
    let text = with_renderer(handle, |renderer| {
        renderer.text(row, start_col.max(0) as u32, end_col.max(0) as u32)
    });
    with_jni_env(env, |env| {
        let string = env.new_string(text)?;
        Ok(string.into_raw())
    })
}

/// 工单 31：单词列边界 {start, end}（无词返回 null）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetWordBoundsAt(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    column: jint,
    row: jint,
) -> jintArray {
    let bounds = with_renderer(handle, |renderer| {
        renderer.word_bounds_at(column.max(0) as u32, row)
    });
    match bounds {
        Some((start, end)) => with_jni_env(env, |env| {
            let array = env.new_int_array(2)?;
            let values = [start as jint, end as jint];
            array.set_region(env, 0, &values)?;
            Ok(array.into_raw())
        }),
        None => std::ptr::null_mut(),
    }
}

/// 工单 31：取词（软换行整行语义；无词返回空串）。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetWordAt(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    column: jint,
    row: jint,
) -> jstring {
    let word = with_renderer(handle, |renderer| {
        renderer.word_at(column.max(0) as u32, row)
    });
    with_jni_env(env, |env| {
        let string = env.new_string(word)?;
        Ok(string.into_raw())
    })
}

/// 工单 31：完整转录文本。
#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetTranscriptText(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    lines_joined: jboolean,
    trim: jboolean,
) -> jstring {
    let text = with_renderer(handle, |renderer| {
        renderer.transcript_text(lines_joined == JNI_TRUE, trim == JNI_TRUE)
    });
    with_jni_env(env, |env| {
        let string = env.new_string(text)?;
        Ok(string.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetPalette(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    fg_argb: jint,
    bg_argb: jint,
    selection_argb: jint,
    cursor_argb: jint,
) {
    with_renderer(handle, |renderer| {
        renderer.set_palette(Palette {
            fg: argb_to_rgb(fg_argb),
            bg: argb_to_rgb(bg_argb),
            selection: argb_to_rgb(selection_argb),
            cursor: argb_to_rgb(cursor_argb),
            ansi: DEFAULT_ANSI_16,
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSetPalette16(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    fg_argb: jint,
    bg_argb: jint,
    selection_argb: jint,
    cursor_argb: jint,
    ansi: JIntArray,
) {
    let ansi_colors = with_jni_env(env, |env| -> jni::errors::Result<[Rgb; 16]> {
        if ansi.is_null() {
            return Ok(DEFAULT_ANSI_16);
        }
        let len = ansi.len(env).unwrap_or(0).min(16);
        let mut raw = [0i32; 16];
        if len > 0 {
            let _ = ansi.get_region(env, 0, &mut raw[..len]);
        }
        Ok(std::array::from_fn(|i| argb_to_rgb(raw[i])))
    });
    with_renderer(handle, |renderer| {
        renderer.set_palette(Palette {
            fg: argb_to_rgb(fg_argb),
            bg: argb_to_rgb(bg_argb),
            selection: argb_to_rgb(selection_argb),
            cursor: argb_to_rgb(cursor_argb),
            ansi: ansi_colors,
        })
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererResetPalette(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    with_renderer(handle, |renderer| renderer.reset_palette());
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererAttach(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    surface: JObject,
    width_px: jint,
    height_px: jint,
) {
    with_jni_env(env, |env| {
        // 先保留注册表 Arc，再从 Java Surface 取得需手动 release 的窗口引用。
        // destroy 此后只能移除注册表项，不能让本次 attach 访问失效对象。
        let Some(renderer) = renderer_for_handle(handle) else {
            return Ok(());
        };
        let env_ptr = env.get_raw();
        let surface_raw = surface.into_raw();
        // SAFETY: `env_ptr` 属于当前 JNI 线程，`surface_raw` 来自本次 Java 调用；
        // Android API 返回一份独立 window 引用，后续由 attach 失败路径或 renderer 接管释放。
        let window = unsafe { ANativeWindow_fromSurface(env_ptr, surface_raw) };
        if window.is_null() {
            log_error("ANativeWindow_fromSurface returned null");
            return Ok(());
        }
        if let Err(error) = renderer.attach(window, width_px.max(1) as u32, height_px.max(1) as u32)
        {
            // 命令未进入 mailbox 时 RendererCore 不会接管 window 引用。
            // SAFETY: attach 返回错误表示 renderer 未接管该引用，本路径恰好释放一次。
            unsafe {
                ANativeWindow_release(window);
            }
            log_error(&format!("attach failed: {error}"));
        }
        Ok(())
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererDetach(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    with_renderer(handle, |renderer| renderer.detach());
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererRender(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    width_px: jint,
    height_px: jint,
) -> jboolean {
    with_renderer(handle, |renderer| {
        if renderer.render(width_px.max(1) as u32, height_px.max(1) as u32) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererForceRender(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    width_px: jint,
    height_px: jint,
) -> jboolean {
    with_renderer(handle, |renderer| {
        renderer.force_render(width_px.max(0) as u32, height_px.max(0) as u32)
    }) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererTestPattern(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    width_px: jint,
    height_px: jint,
) -> jboolean {
    with_renderer(handle, |renderer| {
        if renderer.test_pattern(width_px.max(1) as u32, height_px.max(1) as u32) {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererInfo(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let info = with_renderer(handle, |renderer| renderer.info());
    let value = if info.is_empty() {
        "no-renderer".to_string()
    } else {
        info
    };
    with_jni_env(env, |env| {
        let string = env.new_string(value)?;
        Ok(string.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererLastError(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let message = with_renderer(handle, |renderer| renderer.last_error());
    with_jni_env(env, |env| {
        let string = env.new_string(message)?;
        Ok(string.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptySpawn(
    env: EnvUnowned,
    _class: JClass,
    shell: JString,
    cols: jint,
    rows: jint,
) -> jlong {
    with_jni_env(env, |env| {
        let value = shell.mutf8_chars(env)?;
        let shell_text = value.to_str().into_owned();
        let c_shell = CString::new(shell_text).map_err(|_| jni::errors::Error::NullPtr("shell"))?;
        // SAFETY: `c_shell` is NUL-terminated and alive for the call; shim copies/owns
        // the spawned PTY state behind returned opaque handle.
        Ok(unsafe { fable_pty_spawn(c_shell.as_ptr(), cols, rows) })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyRead(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    buf: JByteArray,
) -> jint {
    with_jni_env(env, |env| {
        let arr_len = buf.len(env).unwrap_or(0);
        if arr_len == 0 {
            return Ok(0);
        }
        let mut raw = vec![0i8; arr_len];
        // SAFETY: `raw` has `arr_len` initialized bytes and remains mutable for call;
        // shim writes no more than supplied length and returns count.
        let n = unsafe { fable_pty_read(handle, raw.as_mut_ptr() as *mut u8, arr_len) };
        if n > 0 {
            let _ = buf.set_region(env, 0, &raw[..n as usize]);
        }
        Ok(n)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyWrite(
    env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) -> jint {
    let bytes = with_jni_env(env, |env| Ok(jbytes_to_vec(env, data, len)));
    if bytes.is_empty() {
        return 0;
    }
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: `bytes` 在本次 C 调用期间保持有效；shim 只读取给定长度。
        unsafe { fable_pty_write(handle, bytes.as_ptr(), bytes.len()) }
    }))
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: PTY shim 以 opaque handle 处理列行整数；迟到/无效 handle 由 shim 安全忽略。
        unsafe { fable_pty_resize(handle, cols, rows) };
    }))
    .ok();
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyClose(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    catch_unwind(AssertUnwindSafe(|| {
        // SAFETY: close 接受 opaque handle，重复关闭由 shim 保证幂等。
        unsafe { fable_pty_close(handle) };
    }))
    .ok();
}

#[cfg(test)]
mod tests {
    use super::HandleRegistry;
    use std::sync::Arc;
    use std::thread;

    #[test]
    fn handles_are_registry_tokens_and_destroy_is_idempotent() {
        let registry = HandleRegistry::new();
        let first = registry.insert(String::from("renderer"));
        assert!(first > 0);
        assert_eq!(
            registry.get(first).as_deref().map(String::as_str),
            Some("renderer")
        );
        assert!(registry.remove(first).is_some());
        assert!(registry.remove(first).is_none());
        assert!(registry.get(first).is_none());
        assert!(registry.get(0).is_none());
        assert!(registry.get(-1).is_none());
    }

    #[test]
    fn in_flight_lookup_keeps_value_alive_after_remove() {
        let registry = Arc::new(HandleRegistry::new());
        let handle = registry.insert(String::from("renderer"));
        let in_flight = registry.get(handle).expect("registered handle");
        assert!(registry.remove(handle).is_some());
        assert_eq!(in_flight.as_str(), "renderer");
        drop(in_flight);
        assert!(registry.get(handle).is_none());
    }

    #[test]
    fn concurrent_lookup_and_destroy_never_resurrects_handle() {
        let registry = Arc::new(HandleRegistry::new());
        let handle = registry.insert(7u32);
        let readers = (0..8)
            .map(|_| {
                let registry = Arc::clone(&registry);
                thread::spawn(move || {
                    for _ in 0..10_000 {
                        let _ = registry.get(handle);
                    }
                })
            })
            .collect::<Vec<_>>();
        assert!(registry.remove(handle).is_some());
        for reader in readers {
            reader.join().expect("reader thread");
        }
        assert!(registry.get(handle).is_none());
    }
}
