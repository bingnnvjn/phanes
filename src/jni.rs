//! 工单 10：Rust 渲染器 + PTY 的 JNI 桥（com.gph.fable.app.RenderCore）。

use crate::render_android::{DEFAULT_ANSI_16, Palette, Renderer, Rgb, log_error};
use jni::Env;
use jni::EnvUnowned;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JIntArray, JObject, JString};
use jni::sys::{jboolean, jint, jlong, jobject, jstring, JNI_FALSE, JNI_TRUE};
use std::ffi::{CString, c_char, c_void};
use std::panic::{AssertUnwindSafe, catch_unwind};

extern "C" {
    fn ANativeWindow_fromSurface(env: *mut jni::sys::JNIEnv, surface: jobject) -> *mut c_void;
    fn fable_pty_spawn(shell: *const c_char, cols: jint, rows: jint) -> jlong;
    fn fable_pty_read(handle: jlong, buf: *mut u8, len: usize) -> jint;
    fn fable_pty_write(handle: jlong, data: *const u8, len: usize) -> jint;
    fn fable_pty_resize(handle: jlong, cols: jint, rows: jint);
    fn fable_pty_close(handle: jlong);
}

unsafe fn renderer_ptr(handle: jlong) -> *mut Renderer {
    if handle == 0 {
        std::ptr::null_mut()
    } else {
        handle as *mut Renderer
    }
}

fn with_renderer<F, R>(handle: jlong, f: F) -> R
where
    F: FnOnce(&mut Renderer) -> R,
    R: Default,
{
    catch_unwind(AssertUnwindSafe(|| {
        let ptr = unsafe { renderer_ptr(handle) };
        if ptr.is_null() {
            return R::default();
        }
        f(unsafe { &mut *ptr })
    }))
    .unwrap_or_default()
}

fn with_jni_env<R: Default>(
    mut env: EnvUnowned,
    f: impl FnOnce(&mut Env) -> jni::errors::Result<R>,
) -> R {
    env.with_env(f).resolve::<LogErrorAndDefault>()
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

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererCreate(
    _env: EnvUnowned,
    _class: JClass,
    cols: jint,
    rows: jint,
) -> jlong {
    catch_unwind(AssertUnwindSafe(|| {
        match Renderer::new(cols.max(1) as u16, rows.max(1) as u16) {
            Some(renderer) => Box::into_raw(Box::new(renderer)) as jlong,
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
    let ptr = unsafe { renderer_ptr(handle) };
    if !ptr.is_null() {
        catch_unwind(AssertUnwindSafe(|| unsafe {
            drop(Box::from_raw(ptr));
        }))
        .ok();
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererWrite(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) {
    let bytes = with_jni_env(env, |env| {
        Ok(jbytes_to_vec(env, data, len))
    });
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
        renderer.set_selection(row.max(0) as u32, start_col.max(0) as u32, end_col.max(0) as u32)
    });
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererSelectionText(
    mut env: EnvUnowned,
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
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererGetCellSize(
    mut env: EnvUnowned,
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
        let len = ansi.len(env).unwrap_or(0).min(16) as usize;
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
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    surface: JObject,
    width_px: jint,
    height_px: jint,
) {
    with_jni_env(env, |env| {
        let env_ptr = env.get_raw();
        let surface_raw = surface.into_raw();
        let window = unsafe { ANativeWindow_fromSurface(env_ptr, surface_raw) };
        if window.is_null() {
            log_error("ANativeWindow_fromSurface returned null");
            return Ok(());
        }
        with_renderer(handle, |renderer| {
            if let Err(error) = renderer.attach(
                window,
                width_px.max(1) as u32,
                height_px.max(1) as u32,
            ) {
                log_error(&format!("attach failed: {error}"));
            }
        });
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
    with_renderer(
        handle,
        |renderer| {
            if renderer.render(width_px.max(1) as u32, height_px.max(1) as u32) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        },
    )
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererTestPattern(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    width_px: jint,
    height_px: jint,
) -> jboolean {
    with_renderer(
        handle,
        |renderer| {
            if renderer.test_pattern(width_px.max(1) as u32, height_px.max(1) as u32) {
                JNI_TRUE
            } else {
                JNI_FALSE
            }
        },
    )
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_rendererInfo(
    mut env: EnvUnowned,
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
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptySpawn(
    mut env: EnvUnowned,
    _class: JClass,
    shell: JString,
    cols: jint,
    rows: jint,
) -> jlong {
    with_jni_env(env, |env| {
        let value = shell.mutf8_chars(env)?;
        let shell_text = value.to_str().into_owned();
        let c_shell = CString::new(shell_text).map_err(|_| jni::errors::Error::NullPtr("shell"))?;
        Ok(unsafe { fable_pty_spawn(c_shell.as_ptr(), cols, rows) })
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyRead(
    mut env: EnvUnowned,
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
        let n = unsafe { fable_pty_read(handle, raw.as_mut_ptr() as *mut u8, arr_len) };
        if n > 0 {
            let _ = buf.set_region(env, 0, &raw[..n as usize]);
        }
        Ok(n)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyWrite(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) -> jint {
    let bytes = with_jni_env(env, |env| Ok(jbytes_to_vec(env, data, len)));
    if bytes.is_empty() {
        return 0;
    }
    unsafe { fable_pty_write(handle, bytes.as_ptr(), bytes.len()) }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    unsafe { fable_pty_resize(handle, cols, rows) };
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_RenderCore_ptyClose(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    unsafe { fable_pty_close(handle) };
}
