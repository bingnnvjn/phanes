/*
 * 工单 10：Rust 渲染器 .so 内嵌的最小 PTY（借工单 08 ghostty_spike_jni.c 的探针实现）。
 * 会话层阶段一仍由 Termux Java 负责；本 shim 只供渲染 harness 自建 PTY 验证。
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>
#include <android/log.h>
#include <android/native_window.h>

typedef struct {
  int master;
  pid_t pid;
} FablePty;

int64_t fable_pty_spawn(const char *shell_path, int cols, int rows) {
  if (!shell_path || !*shell_path) return 0;

  int master = posix_openpt(O_RDWR | O_NOCTTY);
  if (master < 0) return 0;
  if (grantpt(master) != 0 || unlockpt(master) != 0) {
    close(master);
    return 0;
  }
  char *slave_name = ptsname(master);
  if (!slave_name) {
    close(master);
    return 0;
  }

  pid_t pid = fork();
  if (pid < 0) {
    close(master);
    return 0;
  }

  if (pid == 0) {
    setsid();
    int slave = open(slave_name, O_RDWR);
    if (slave < 0) _exit(127);
    ioctl(slave, TIOCSCTTY, 0);
    dup2(slave, 0);
    dup2(slave, 1);
    dup2(slave, 2);
    if (slave > 2) close(slave);

    setenv("TERM", "xterm-256color", 1);
    setenv("HOME", "/data/data/com.gph.fable/files/home", 1);
    setenv("PREFIX", "/data/data/com.gph.fable/files/usr", 1);
    setenv("TMPDIR", "/data/data/com.gph.fable/files/usr/tmp", 1);
    setenv("SHELL", shell_path, 1);
    setenv("LANG", "en_US.UTF-8", 1);
    setenv(
        "PATH",
        "/data/data/com.gph.fable/files/usr/bin:/data/data/com.gph.fable/files/usr/bin/applets:/system/bin:/system/xbin",
        1);
    mkdir("/data/data/com.gph.fable/files/home", 0755);
    if (chdir("/data/data/com.gph.fable/files/home") != 0) {
      if (chdir("/data/data/com.gph.fable/files") != 0) {
        chdir("/");
      }
    }
    char cwd_buf[512];
    if (getcwd(cwd_buf, sizeof cwd_buf)) {
      setenv("PWD", cwd_buf, 1);
    }
    execl(shell_path, shell_path, "--login", (char *)NULL);
    _exit(126);
  }

  struct winsize ws;
  memset(&ws, 0, sizeof ws);
  ws.ws_row = (unsigned short)rows;
  ws.ws_col = (unsigned short)cols;
  ioctl(master, TIOCSWINSZ, &ws);

  FablePty *pty = calloc(1, sizeof(FablePty));
  if (!pty) {
    close(master);
    kill(pid, SIGHUP);
    return 0;
  }
  pty->master = master;
  pty->pid = pid;
  return (int64_t)(uintptr_t)pty;
}

int fable_pty_read(int64_t handle, uint8_t *buf, size_t len) {
  FablePty *pty = (FablePty *)(uintptr_t)handle;
  if (!pty || !buf || len == 0) return -1;
  ssize_t n = read(pty->master, buf, len);
  if (n < 0) return -1;
  return (int)n;
}

/* 离屏/端到端检查用：当前是否有可读数据（非阻塞，0 超时）。 */
int fable_pty_read_ready(int64_t handle) {
  FablePty *pty = (FablePty *)(uintptr_t)handle;
  if (!pty) return 0;
  struct pollfd pfd = {.fd = pty->master, .events = POLLIN};
  int r = poll(&pfd, 1, 0);
  return r > 0 ? 1 : 0;
}

int fable_pty_write(int64_t handle, const uint8_t *data, size_t len) {
  FablePty *pty = (FablePty *)(uintptr_t)handle;
  if (!pty || !data || len == 0) return -1;
  ssize_t n = write(pty->master, data, len);
  if (n < 0) return -1;
  return (int)n;
}

void fable_pty_resize(int64_t handle, int cols, int rows) {
  FablePty *pty = (FablePty *)(uintptr_t)handle;
  if (!pty) return;
  struct winsize ws;
  memset(&ws, 0, sizeof ws);
  ws.ws_row = (unsigned short)rows;
  ws.ws_col = (unsigned short)cols;
  ioctl(pty->master, TIOCSWINSZ, &ws);
}

void fable_pty_close(int64_t handle) {
  FablePty *pty = (FablePty *)(uintptr_t)handle;
  if (!pty) return;
  if (pty->master >= 0) close(pty->master);
  if (pty->pid > 0) {
    kill(pty->pid, SIGHUP);
    int status;
    waitpid(pty->pid, &status, WNOHANG);
  }
  free(pty);
}

int fable_android_log(int prio, const char *tag, const char *msg) {
  return __android_log_print(prio, tag, "%s", msg);
}

int fable_anw_get_width(void *window) {
  if (!window) return 0;
  return ANativeWindow_getWidth((ANativeWindow *)window);
}

int fable_anw_get_height(void *window) {
  if (!window) return 0;
  return ANativeWindow_getHeight((ANativeWindow *)window);
}
