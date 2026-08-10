//! 工单 25 JNI 边界（com.gph.fable.app.SessionHandle）：
//! SessionHandle 创建/读写/resize/close + 事件回调 + 诊断日志订阅。
//!
//! - 事件回调：Kotlin 传 `SessionEventCallback` 对象，本模块为每个会话起一个
//!   分发线程（JVM attach），从会话事件流收事件并回调 Kotlin；
//! - 日志订阅：`sessionSetLogCallback` 注册全局回调，日志分发线程转发
//!   `LogEntry`（Kotlin 第一版只接诊断/日志，ADR-0008 决策 7）。
//!
//! 事件回调与 `sessionRead` 是同一输出流的两种消费方式：回调消费方收
//! `output_chunk` 事件；`sessionRead` 消费只读侧缓冲副本。二者互不干扰
//! （字节不因一方消费而消失），Kotlin 侧第一版只接诊断/日志。

use crate::event::{Event, EventKind, SessionId};
use crate::log::LogEntry;
use crate::manager::SessionManager;
use crate::session::SessionConfig;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::EnvUnowned;
use jni::sys::{jint, jlong, jstring};
use jni::signature::RuntimeMethodSignature;
use jni::strings::JNIString;
use jni::{Env, JValue, JavaVM};
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, LazyLock, Mutex};
use std::time::Duration;

static MANAGER: LazyLock<SessionManager> = LazyLock::new(SessionManager::new);
static LAST_ERROR: Mutex<String> = Mutex::new(String::new());
static EVENT_CALLBACKS: LazyLock<Mutex<HashMap<SessionId, CallbackSlot>>> =
    LazyLock::new(|| Mutex::new(HashMap::new()));
static LOG_CALLBACK: LazyLock<Mutex<Option<CallbackSlot>>> = LazyLock::new(|| Mutex::new(None));
static LOG_DISPATCHER_STARTED: AtomicBool = AtomicBool::new(false);

/// 回调槽：持有 JavaVM（attach 用）与回调对象全局引用。
#[derive(Clone)]
struct CallbackSlot {
    vm: Arc<JavaVM>,
    callback: Arc<jni::objects::Global<JObject<'static>>>,
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

fn env_vec(env: &mut Env, arr: &JObjectArray<JString>) -> Vec<(String, String)> {
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
        let Ok(kv) = env.get_string(&s) else {
            continue;
        };
        let kv: String = kv.into();
        if let Some(eq) = kv.find('=') {
            let (k, v) = kv.split_at(eq);
            out.push((k.to_string(), v[1..].to_string()));
        }
    }
    out
}

fn string_array(env: &mut Env, arr: &JObjectArray<JString>) -> jni::errors::Result<Vec<String>> {
    let mut out = Vec::new();
    let len = arr.len(env)?;
    for i in 0..len {
        let s = arr.get_element(env, i)?;
        out.push(env.get_string(&s).map(|s| s.into())?);
    }
    Ok(out)
}

fn build_extra<'env>(
    env: &mut Env<'env>,
    extra: &BTreeMap<String, String>,
) -> jni::errors::Result<Option<JObjectArray<'env, JObject<'env>>>> {
    if extra.is_empty() {
        return Ok(None);
    }
    let class = env.find_class(&JNIString::from("java/lang/String"))?;
    let arr = env.new_object_array(
        extra.len() as jni::sys::jsize,
        &class,
        &JObject::null(),
    )?;
    for (i, (k, v)) in extra.iter().enumerate() {
        let s = env.new_string(format!("{k}={v}"))?;
        arr.set_element(env, i, &s)?;
    }
    Ok(Some(arr))
}

fn dispatch_event(
    env: &mut Env,
    callback: &jni::objects::Global<JObject<'static>>,
    ev: &Event,
) -> jni::errors::Result<()> {
    let null_obj = JObject::null();
    let kind_str = env.new_string(ev.kind.as_str())?;
    let data_arr = match &ev.meta.bytes {
        Some(bytes) => Some(env.byte_array_from_slice(bytes)?),
        None => None,
    };
    let message_str = match &ev.meta.message {
        Some(m) => Some(env.new_string(m)?),
        None => None,
    };
    let extra_arr = build_extra(env, &ev.meta.extra)?;
    let data = data_arr
        .as_ref()
        .map_or(JValue::Object(&null_obj), |a| JValue::Object(a.as_ref()));
    let message = message_str
        .as_ref()
        .map_or(JValue::Object(&null_obj), |a| JValue::Object(a.as_ref()));
    let extra = extra_arr
        .as_ref()
        .map_or(JValue::Object(&null_obj), |a| JValue::Object(a.as_ref()));
    let args = [
        JValue::Long(ev.session_id as jlong),
        JValue::Object(kind_str.as_ref()),
        JValue::Long(ev.timestamp_ms as jlong),
        data,
        JValue::Int(ev.meta.exit_code.unwrap_or(-1)),
        message,
        extra,
    ];
    let name = JNIString::from("onEvent");
    let sig = RuntimeMethodSignature::from_str(
        "(JLjava/lang/String;J[BILjava/lang/String;[Ljava/lang/String;)V",
    )?;
    env.call_method(
        callback.as_obj(),
        name.as_ref(),
        jni::signature::MethodSignature::from(&sig),
        &args,
    )?;
    Ok(())
}

fn dispatch_log(
    env: &mut Env,
    callback: &jni::objects::Global<JObject<'static>>,
    entry: &LogEntry,
) -> jni::errors::Result<()> {
    let tag = env.new_string("fable-session")?;
    let msg = env.new_string(&entry.message)?;
    let args = [
        JValue::Long(entry.session_id.unwrap_or(0) as jlong),
        JValue::Int(entry.level.as_i32()),
        JValue::Object(tag.as_ref()),
        JValue::Object(msg.as_ref()),
        JValue::Long(entry.timestamp_ms as jlong),
    ];
    let name = JNIString::from("onLog");
    let sig =
        RuntimeMethodSignature::from_str("(JILjava/lang/String;Ljava/lang/String;J)V")?;
    env.call_method(
        callback.as_obj(),
        name.as_ref(),
        jni::signature::MethodSignature::from(&sig),
        &args,
    )?;
    Ok(())
}

/// 每会话一个事件分发线程：从会话事件流收事件，转发 Kotlin 回调。
/// 回调槽被清空或收到 session_closed 后退出。
fn spawn_event_dispatcher(session_id: SessionId, slot: CallbackSlot) {
    let Some(session) = MANAGER.get(session_id) else {
        let _ = EVENT_CALLBACKS
            .lock()
            .map(|mut m| m.remove(&session_id));
        return;
    };
    let rx = session.subscribe();
    let vm = slot.vm.clone();
    let spawn = std::thread::Builder::new()
        .name(format!("fable-session-events-{session_id}"))
        .spawn(move || {
            let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
                loop {
                    let slot = EVENT_CALLBACKS
                        .lock()
                        .map(|m| m.get(&session_id).cloned())
                        .unwrap_or(None);
                    let Some(slot) = slot else { break };
                    match rx.recv_timeout(Duration::from_millis(250)) {
                        Ok(ev) => {
                            env.with_local_frame(8, |env| {
                                dispatch_event(env, slot.callback.as_ref(), &ev)
                            })?;
                            if ev.kind == EventKind::SessionClosed {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
                Ok(())
            });
            let _ = EVENT_CALLBACKS
                .lock()
                .map(|mut m| m.remove(&session_id));
        });
    if spawn.is_err() {
        let _ = EVENT_CALLBACKS
            .lock()
            .map(|mut m| m.remove(&session_id));
    }
}

/// 全局日志分发线程（只起一个）：转发 LogEntry 到当前注册的日志回调。
fn spawn_log_dispatcher(vm: Arc<JavaVM>) {
    if LOG_DISPATCHER_STARTED.swap(true, Ordering::SeqCst) {
        return;
    }
    let rx = crate::log::subscribe();
    let _ = std::thread::Builder::new()
        .name("fable-session-log".to_string())
        .spawn(move || {
            let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
                loop {
                    match rx.recv_timeout(Duration::from_millis(250)) {
                        Ok(entry) => {
                            let slot = LOG_CALLBACK
                                .lock()
                                .map(|g| g.clone())
                                .unwrap_or(None);
                            if let Some(slot) = slot {
                                env.with_local_frame(4, |env| {
                                    dispatch_log(env, slot.callback.as_ref(), &entry)
                                })?;
                            }
                        }
                        Err(_) => continue,
                    }
                }
            });
        })
        .ok();
}

fn register_event_callback(session_id: SessionId, slot: CallbackSlot) {
    let already = EVENT_CALLBACKS
        .lock()
        .map(|mut m| m.insert(session_id, slot.clone()).is_some())
        .unwrap_or(true);
    if !already {
        spawn_event_dispatcher(session_id, slot);
    }
}

// ---------- SessionHandle JNI 导出 ----------

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionCreate(
    mut env: EnvUnowned,
    _class: JClass,
    shell: JString,
    args_arr: JObjectArray<JString>,
    env_arr: JObjectArray<JString>,
    cwd: JString,
    cols: jint,
    rows: jint,
    callback: JObject,
) -> jlong {
    let (shell_str, args, envs, cwd_str, slot) = env
        .with_env(|env| {
            let shell_str: String = env.get_string(&shell).map(|s| s.into())?;
            let args = string_array(env, &args_arr)?;
            let envs = env_vec(env, &env_arr);
            let cwd_str: String = env.get_string(&cwd).map(|s| s.into())?;
            let slot = if callback.as_raw().is_null() {
                None
            } else {
                let vm = Arc::new(env.get_java_vm()?);
                let cb = env.new_global_ref(&callback)?;
                Some(CallbackSlot {
                    vm,
                    callback: Arc::new(cb),
                })
            };
            Ok::<_, jni::errors::Error>((shell_str, args, envs, cwd_str, slot))
        })
        .resolve::<LogErrorAndDefault>();

    if shell_str.is_empty() {
        set_error("sessionCreate: shell 为空".into());
        return 0;
    }

    clear_error();
    let cfg = SessionConfig {
        shell: shell_str,
        args,
        env: envs,
        cwd: if cwd_str.is_empty() {
            None
        } else {
            Some(cwd_str)
        },
        cols: cols.max(1) as u16,
        rows: rows.max(1) as u16,
        ..SessionConfig::default()
    };
    let id = match MANAGER.create(cfg) {
        Ok(id) => id,
        Err(e) => {
            set_error(format!("sessionCreate 失败: {e:#}"));
            return 0;
        }
    };
    if let Some(slot) = slot {
        register_event_callback(id, slot);
    }
    id as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionWrite(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    data: JByteArray,
    len: jint,
) -> jint {
    let bytes: Vec<u8> = env
        .with_env(|env| {
            let arr_len = data.len(env).unwrap_or(0) as usize;
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
    let Some(session) = MANAGER.get(handle as u64) else {
        set_error("sessionWrite: 句柄不存在".into());
        return -1;
    };
    match session.write(&bytes) {
        Ok(()) => bytes.len() as jint,
        Err(e) => {
            set_error(format!("sessionWrite 失败: {e:#}"));
            -1
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionRead(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    buf: JByteArray,
) -> jint {
    let Some(session) = MANAGER.get(handle as u64) else {
        set_error("sessionRead: 句柄不存在".into());
        return -1;
    };
    let mut local = [0u8; 8192];
    let n = match session.read_output(&mut local) {
        Ok(n) => n,
        Err(e) => {
            set_error(format!("sessionRead 失败: {e:#}"));
            return -1;
        }
    };
    env.with_env(|env| {
        let cap = buf.len(env).unwrap_or(0) as usize;
        let take = n.min(cap);
        let i8data: Vec<i8> = local[..take].iter().map(|b| *b as i8).collect();
        buf.set_region(env, 0, &i8data).map(|_| take as jint)
    })
    .resolve::<LogErrorAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionResize(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    cols: jint,
    rows: jint,
) {
    let Some(session) = MANAGER.get(handle as u64) else {
        set_error("sessionResize: 句柄不存在".into());
        return;
    };
    if let Err(e) = session.resize(cols.max(1) as u16, rows.max(1) as u16) {
        set_error(format!("sessionResize 失败: {e:#}"));
    }
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionClose(
    _env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    if handle <= 0 {
        return;
    }
    let _ = MANAGER.close(handle as u64);
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionSetEventCallback(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    let result = env
        .with_env(|env| {
            if handle <= 0 {
                return Ok::<_, jni::errors::Error>(());
            }
            if callback.as_raw().is_null() {
                let _ = EVENT_CALLBACKS
                    .lock()
                    .map(|mut m| m.remove(&(handle as u64)));
                return Ok(());
            }
            let vm = Arc::new(env.get_java_vm()?);
            let cb = env.new_global_ref(&callback)?;
            register_event_callback(
                handle as u64,
                CallbackSlot {
                    vm,
                    callback: Arc::new(cb),
                },
            );
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
    let _ = result;
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionSetLogCallback(
    mut env: EnvUnowned,
    _class: JClass,
    callback: JObject,
) {
    let result = env
        .with_env(|env| {
            if callback.as_raw().is_null() {
                *LOG_CALLBACK.lock().unwrap_or_else(|p| p.into_inner()) = None;
                return Ok::<_, jni::errors::Error>(());
            }
            let vm = Arc::new(env.get_java_vm()?);
            let cb = env.new_global_ref(&callback)?;
            let slot = CallbackSlot {
                vm: vm.clone(),
                callback: Arc::new(cb),
            };
            *LOG_CALLBACK.lock().unwrap_or_else(|p| p.into_inner()) = Some(slot);
            spawn_log_dispatcher(vm);
            Ok(())
        })
        .resolve::<LogErrorAndDefault>();
    let _ = result;
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionLastError(
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
