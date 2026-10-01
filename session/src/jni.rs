//! 工单 24：portable-pty JNI 桥（com.gph.fable.app.SessionProbe）。
//!
//! 会话句柄 = 全局注册表里的 u64 id。reader 线程阻塞读时**不持有锁**：
//! spawn 时 dup 一份 master fd 专供读，ptyClose 先 close 该 fd（解除阻塞读），
//! 再取锁 kill child 并 drop 会话——复刻工单 08 C shim 的关闭语义，
//! 避免“close 等 reader、reader 等 close”的死锁。

use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jint, jlong, jstring};
use jni::EnvUnowned;
use portable_pty::{native_pty_system, Child, CommandBuilder, MasterPty, PtySize};
use std::collections::HashMap;
use std::io::Write;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::LazyLock;
use std::sync::{Arc, Mutex};

static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static SESSIONS: LazyLock<Mutex<HashMap<u64, Arc<Mutex<Session>>>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());

struct Session {
    master: Box<dyn MasterPty>,
    child: Option<Box<dyn Child + Send + Sync>>,
    read_fd: i32,
    read_guard: Arc<Mutex<()>>,
    writer: Box<dyn Write + Send>,
}

fn set_error(msg: String) {
    if let Ok(mut e) = LAST_ERROR.lock() {
        *e = msg;
    }
}

fn clear_error() {
    if let Ok(mut e) = LAST_ERROR.lock() {
        e.clear();
    }
}

fn get_session(handle: jlong) -> Option<Arc<Mutex<Session>>> {
    if handle <= 0 {
        return None;
    }
    SESSIONS.lock().ok()?.get(&(handle as u64)).cloned()
}

fn env_vec(env: &mut jni::Env, arr: &JObjectArray<JString>) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let len = match arr.len(env) {
        Ok(n) => n,
        Err(_) => return out,
    };
    for i in 0..len {
        let s = match arr.get_element(env, i) {
            Ok(s) => s,
            Err(_) => continue,
        };
        let Ok(kv) = s.try_to_string(env) else {
            continue;
        };
        if let Some(eq) = kv.find('=') {
            let (k, v) = kv.split_at(eq);
            out.push((k.to_string(), v[1..].to_string()));
        }
    }
    out
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_ptySpawn(
    mut env: EnvUnowned,
    _class: JClass,
    shell: JString,
    env_arr: JObjectArray<JString>,
    cwd: JString,
    cols: jint,
    rows: jint,
) -> jlong {
    let shell_str: String = env
        .with_env(|env| shell.try_to_string(env))
        .resolve::<LogErrorAndDefault>();
    let cwd_str: String = env
        .with_env(|env| cwd.try_to_string(env))
        .resolve::<LogErrorAndDefault>();
    let envs: Vec<(String, String)> = env
        .with_env(|env| Ok::<Vec<(String, String)>, jni::errors::Error>(env_vec(env, &env_arr)))
        .resolve::<LogErrorAndDefault>();

    if shell_str.is_empty() {
        set_error("ptySpawn: shell 为空".into());
        return 0;
    }

    clear_error();
    let result = (|| -> anyhow::Result<u64> {
        let mut cmd = CommandBuilder::new(&shell_str);
        cmd.arg("--login");
        cmd.set_controlling_tty(true);
        for (k, v) in &envs {
            cmd.env(k, v);
        }
        if !cwd_str.is_empty() {
            cmd.cwd(&cwd_str);
        }

        let system = native_pty_system();
        let size = PtySize {
            rows: rows.max(1) as u16,
            cols: cols.max(1) as u16,
            pixel_width: 0,
            pixel_height: 0,
        };
        let pair = system.openpty(size)?;
        let mut child = pair.slave.spawn_command(cmd)?;
        let master_fd = pair
            .master
            .as_raw_fd()
            .ok_or_else(|| anyhow::anyhow!("master pty 无 raw fd"))?;
        // dup 一份专供阻塞读；close 它即可解除 reader 阻塞（见模块注释）。
        // SAFETY: `master_fd` is a live PTY descriptor owned by `pair.master`;
        // `dup` creates an independent descriptor that this session owns.
        let read_fd = unsafe { libc::dup(master_fd) };
        if read_fd < 0 {
            let _ = child.kill();
            anyhow::bail!("dup(master_fd) 失败: {}", std::io::Error::last_os_error());
        }
        let writer = match pair.master.take_writer() {
            Ok(w) => w,
            Err(e) => {
                let _ = child.kill();
                // SAFETY: `read_fd` was returned by `dup` above and has not
                // been transferred elsewhere after `take_writer` failed.
                unsafe {
                    libc::close(read_fd);
                }
                anyhow::bail!("take_writer 失败: {e:#}");
            }
        };

        let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
        if let Ok(mut map) = SESSIONS.lock() {
            let session = Session {
                master: pair.master,
                child: Some(child),
                read_fd,
                read_guard: Arc::new(Mutex::new(())),
                writer,
            };
            map.insert(id, Arc::new(Mutex::new(session)));
        } else {
            let _ = child.kill();
            // SAFETY: `read_fd` was returned by `dup` above and is owned by
            // this failed session construction path.
            unsafe {
                libc::close(read_fd);
            }
            anyhow::bail!("SESSIONS 锁中毒");
        }
        Ok(id)
    })();

    match result {
        Ok(id) => id as jlong,
        Err(e) => {
            set_error(format!("ptySpawn 失败: {e:#}"));
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_ptyRead(
    mut _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    buf: JByteArray,
) -> jint {
    let Some(session) = get_session(handle) else {
        return -1;
    };
    let (read_guard, read_fd) = match session.lock() {
        Ok(s) => (s.read_guard.clone(), s.read_fd),
        Err(_) => return -1,
    };
    let _read_guard = match read_guard.lock() {
        Ok(g) => g,
        Err(_) => return -1,
    };
    if read_fd < 0 {
        return 0; // 已关闭
    }
    let mut data = [0u8; 4096];
    // SAFETY: `read_fd` is a valid descriptor while the session is open;
    // `data` is an initialized writable buffer of exactly `data.len()` bytes.
    let n = unsafe { libc::read(read_fd, data.as_mut_ptr() as *mut libc::c_void, data.len()) };
    if n < 0 {
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EIO) {
            return 0; // 会话结束（与 portable-pty PtyFd 语义一致）
        }
        set_error(format!("ptyRead 失败: {err}"));
        return -1;
    }
    if n == 0 {
        return 0;
    }
    let n = n as usize;
    _env.with_env(|env| {
        let cap = buf.len(env).unwrap_or(0);
        let take = n.min(cap);
        let i8data: Vec<i8> = data[..take].iter().map(|b| *b as i8).collect();
        buf.set_region(env, 0, &i8data).map(|_| take as jint)
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_ptyWrite(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) -> jint {
    let bytes: Vec<u8> = env
        .with_env(|env| {
            let arr_len = data.len(env).unwrap_or(0);
            let take = if len < 0 {
                arr_len
            } else {
                (len as usize).min(arr_len)
            };
            let mut raw = vec![0i8; take];
            if data.get_region(env, 0, &mut raw).is_err() {
                return Ok::<Vec<u8>, jni::errors::Error>(Vec::new());
            }
            Ok(raw.into_iter().map(|b| b as u8).collect::<Vec<u8>>())
        })
        .resolve::<LogErrorAndDefault>();
    let Some(session) = get_session(handle) else {
        return -1;
    };
    let mut session = match session.lock() {
        Ok(s) => s,
        Err(_) => return -1,
    };
    match session.writer.write_all(&bytes) {
        Ok(()) => bytes.len() as jint,
        Err(e) => {
            set_error(format!("ptyWrite 失败: {e}"));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_ptyResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    let Some(session) = get_session(handle) else {
        return;
    };
    let session = match session.lock() {
        Ok(s) => s,
        Err(_) => return,
    };
    let size = PtySize {
        rows: rows.max(1) as u16,
        cols: cols.max(1) as u16,
        pixel_width: 0,
        pixel_height: 0,
    };
    if let Err(e) = session.master.resize(size) {
        set_error(format!("ptyResize 失败: {e:#}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_ptyClose(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    let removed = match SESSIONS.lock() {
        Ok(mut map) => map.remove(&(handle as u64)),
        Err(_) => None,
    };
    let Some(session) = removed else {
        return;
    };
    // 1) kill child first; an in-flight PTY read then returns EIO/EOF.
    let (child, read_guard) = match session.lock() {
        Ok(mut s) => (s.child.take(), s.read_guard.clone()),
        Err(poisoned) => {
            let mut s = poisoned.into_inner();
            (s.child.take(), s.read_guard.clone())
        }
    };
    if let Some(mut child) = child {
        let _ = child.kill();
    }
    let _read_guard = read_guard.lock().unwrap_or_else(|p| p.into_inner());
    let mut session = match session.lock() {
        Ok(s) => s,
        Err(poisoned) => poisoned.into_inner(),
    };
    if session.read_fd >= 0 {
        // SAFETY: the read guard excludes `ptyRead`; this descriptor is owned
        // by the removed session and is closed exactly once.
        unsafe {
            libc::close(session.read_fd);
        }
        session.read_fd = -1;
    }
    drop(session);
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_buildInfo(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    let info = format!(
        "portable-pty {} / rustc {} / {}",
        env!("CARGO_PKG_VERSION"),
        env!("RUSTC_VERSION"),
        std::env::consts::ARCH
    );
    env.with_env(|env| env.new_string(&info).map(|s| s.into_raw()))
        .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionProbe_lastError(
    mut env: EnvUnowned,
    _class: JClass,
) -> jstring {
    let msg = LAST_ERROR
        .lock()
        .map(|mut e| {
            let s = e.clone();
            e.clear();
            s
        })
        .unwrap_or_default();
    env.with_env(|env| env.new_string(&msg).map(|s| s.into_raw()))
        .resolve::<LogErrorAndDefault>()
}
