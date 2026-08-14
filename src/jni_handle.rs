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
use crossbeam_channel::RecvTimeoutError;
use jni::errors::LogErrorAndDefault;
use jni::objects::{JByteArray, JClass, JObject, JObjectArray, JString};
use jni::signature::RuntimeMethodSignature;
use jni::strings::JNIString;
use jni::sys::{jint, jlong, jstring};
use jni::EnvUnowned;
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

/// 单个 JNI 回调合并的最大输出字节数。Rust reader 仍以 4 KiB 读取；这里合到
/// 64 KiB 后才跨 JNI，Android 侧还会继续合并为主线程 drain 批次。
const MAX_JNI_OUTPUT_BATCH_BYTES: usize = 64 * 1024;
const OUTPUT_COALESCE_WINDOW: Duration = Duration::from_millis(4);
const IDLE_EVENT_POLL_WINDOW: Duration = Duration::from_millis(250);

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

fn string_array(env: &mut Env, arr: &JObjectArray<JString>) -> jni::errors::Result<Vec<String>> {
    let mut out = Vec::new();
    let len = arr.len(env)?;
    for i in 0..len {
        let s = arr.get_element(env, i)?;
        out.push(s.try_to_string(env)?);
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
    let class = env.find_class(JNIString::from("java/lang/String"))?;
    let arr = env.new_object_array(extra.len() as jni::sys::jsize, &class, JObject::null())?;
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

/// 先复制 callback 槽位再调用 Java，避免 Java 回调重入
/// `sessionClose`/`sessionSetEventCallback` 时持锁死锁。关闭会撤销后续投递；
/// 已经复制出的在途调用允许完成。
fn dispatch_registered_event(
    env: &mut Env,
    session_id: SessionId,
    ev: &Event,
) -> jni::errors::Result<bool> {
    let slot = EVENT_CALLBACKS
        .lock()
        .unwrap_or_else(|p| p.into_inner())
        .get(&session_id)
        .cloned();
    let Some(slot) = slot else {
        return Ok(false);
    };
    dispatch_event(env, slot.callback.as_ref(), ev)?;
    Ok(true)
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
    let sig = RuntimeMethodSignature::from_str("(JILjava/lang/String;Ljava/lang/String;J)V")?;
    env.call_method(
        callback.as_obj(),
        name.as_ref(),
        jni::signature::MethodSignature::from(&sig),
        &args,
    )?;
    Ok(())
}

/// 把相邻 output_chunk 合成一个 JNI payload；不是输出事件或达到 64 KiB 时，返回
/// 应立即送达的上一批。保序且不丢字节。
fn merge_output_batch(pending: &mut Option<Event>, event: Event) -> Option<Event> {
    debug_assert!(event.kind == EventKind::OutputChunk);
    let Some(current) = pending.as_mut() else {
        *pending = Some(event);
        return None;
    };
    let current_bytes = current.meta.bytes.get_or_insert_with(Vec::new);
    let incoming = event.meta.bytes.as_deref().unwrap_or_default();
    if current.session_id == event.session_id
        && current_bytes.len() + incoming.len() <= MAX_JNI_OUTPUT_BATCH_BYTES
    {
        current_bytes.extend_from_slice(incoming);
        None
    } else {
        pending.replace(event)
    }
}

/// 每会话一个事件分发线程：连续 4 KiB 输出在最多 4ms/64KiB 内合批，再跨 JNI。
/// 回调槽被清空或收到 session_closed 后退出。
fn spawn_event_dispatcher(session_id: SessionId, slot: CallbackSlot) {
    let Some(session) = MANAGER.get(session_id) else {
        let _ = EVENT_CALLBACKS.lock().map(|mut m| m.remove(&session_id));
        return;
    };
    let rx = session.subscribe();
    let vm = slot.vm.clone();
    let spawn = std::thread::Builder::new()
        .name(format!("fable-session-events-{session_id}"))
        .spawn(move || {
            let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
                let mut pending_output = None;
                loop {
                    let callback_registered = EVENT_CALLBACKS
                        .lock()
                        .map(|m| m.contains_key(&session_id))
                        .unwrap_or(false);
                    if !callback_registered {
                        break;
                    }
                    let timeout = if pending_output.is_some() {
                        OUTPUT_COALESCE_WINDOW
                    } else {
                        IDLE_EVENT_POLL_WINDOW
                    };
                    match rx.recv_timeout(timeout) {
                        Ok(ev) => {
                            if ev.kind == EventKind::OutputChunk {
                                if let Some(batch) = merge_output_batch(&mut pending_output, ev) {
                                    if !env.with_local_frame(8, |env| {
                                        dispatch_registered_event(env, session_id, &batch)
                                    })? {
                                        break;
                                    }
                                }
                                continue;
                            }
                            if let Some(batch) = pending_output.take() {
                                if !env.with_local_frame(8, |env| {
                                    dispatch_registered_event(env, session_id, &batch)
                                })? {
                                    break;
                                }
                            }
                            if !env.with_local_frame(8, |env| {
                                dispatch_registered_event(env, session_id, &ev)
                            })? {
                                break;
                            }
                            if ev.kind == EventKind::SessionClosed {
                                break;
                            }
                        }
                        // 4ms 输出合并窗结束后立即回显，静默期仍继续等后续输出。
                        Err(RecvTimeoutError::Timeout) => {
                            if let Some(batch) = pending_output.take() {
                                if !env.with_local_frame(8, |env| {
                                    dispatch_registered_event(env, session_id, &batch)
                                })? {
                                    break;
                                }
                            }
                        }
                        Err(RecvTimeoutError::Disconnected) => break,
                    }
                }
                Ok(())
            });
            let _ = EVENT_CALLBACKS.lock().map(|mut m| m.remove(&session_id));
        });
    if spawn.is_err() {
        let _ = EVENT_CALLBACKS.lock().map(|mut m| m.remove(&session_id));
    }
}

/// 全局日志分发线程（只起一个）：进程级所有权随 libfable-session 直到进程退出。
/// 不提供 per-session stop；每条日志临时读取当前槽位，sessionSetLogCallback(null)
/// 立即释放旧 Java 全局引用，线程不会永久持有失效 callback。
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
                            let slot = LOG_CALLBACK.lock().map(|g| g.clone()).unwrap_or(None);
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
            let shell_str: String = shell.try_to_string(env)?;
            let args = string_array(env, &args_arr)?;
            let envs = env_vec(env, &env_arr);
            let cwd_str: String = cwd.try_to_string(env)?;
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
        let cap = buf.len(env).unwrap_or(0);
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
    // 先撤销全局 Java 引用并与在途 dispatch 串行化，关闭后不再投递旧 callback。
    let _ = EVENT_CALLBACKS
        .lock()
        .map(|mut m| m.remove(&(handle as u64)));
    let _ = MANAGER.close(handle as u64);
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionSetEventCallback(
    mut env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    callback: JObject,
) {
    env.with_env(|env| {
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
}

#[no_mangle]
pub extern "system" fn Java_com_gph_fable_app_SessionHandle_sessionSetLogCallback(
    mut env: EnvUnowned,
    _class: JClass,
    callback: JObject,
) {
    env.with_env(|env| {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::event::{now_ms, EventMeta};

    fn output(bytes: &[u8]) -> Event {
        Event {
            kind: EventKind::OutputChunk,
            session_id: 7,
            timestamp_ms: now_ms(),
            meta: EventMeta {
                bytes: Some(bytes.to_vec()),
                ..EventMeta::default()
            },
        }
    }

    #[test]
    fn consecutive_output_chunks_coalesce_without_losing_order() {
        let mut pending = None;
        assert!(merge_output_batch(&mut pending, output(b"alpha")).is_none());
        assert!(merge_output_batch(&mut pending, output(b"-beta")).is_none());
        assert!(merge_output_batch(&mut pending, output(b"-gamma")).is_none());

        let batch = pending.take().expect("应有合并后的输出批次");
        assert_eq!(batch.kind, EventKind::OutputChunk);
        assert_eq!(
            batch.meta.bytes.as_deref(),
            Some(b"alpha-beta-gamma".as_slice())
        );
    }

    #[test]
    fn output_batch_flushes_before_exceeding_bound() {
        let first = vec![b'a'; MAX_JNI_OUTPUT_BATCH_BYTES];
        let mut pending = None;
        assert!(merge_output_batch(&mut pending, output(&first)).is_none());

        let flushed = merge_output_batch(&mut pending, output(b"b")).expect("满批后应先送旧批");
        assert_eq!(
            flushed.meta.bytes.as_ref().map(Vec::len),
            Some(MAX_JNI_OUTPUT_BATCH_BYTES)
        );
        assert_eq!(
            pending.unwrap().meta.bytes.as_deref(),
            Some(b"b".as_slice())
        );
    }
}
