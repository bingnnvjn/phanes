# Phanes（原名 Fable / 寓言）

私人 Android 终端 App：打开就是终端。应用层是 Kotlin 壳，底层是 Rust（渲染器 +
会话层），终端模拟核心用 libghostty-vt。仓库当前是**私有单仓库**，公开是另一次
不可逆决策，尚未发生（ADR-0011、ADR-0012）。

词汇与分层见 [CONTEXT.md](CONTEXT.md)；架构决策见 [docs/adr/](docs/adr/)；工具链现状与
路线图见 [项目总览与交接.md](项目总览与交接.md)。

## 仓库名与应用身份不一致，这是刻意的

项目对外名称是 **Phanes**，仓库叫 `phanes`。应用身份冻结在 `com.gph.fable`：
`applicationId`、由它派生的 `$PREFIX`（`/data/data/com.gph.fable/files/usr`）、JNI 符号
前缀、签名身份都不随改名变化（ADR-0013）。所以包名仍是 `com.gph.fable`，App 显示名
是 Phanes。看到这个不一致不要当成漏改。

代码标识符（`Fable*` 类名、`libfable-*.so` 文件名、`termux.properties` 等）的改名
另行排期，逐层清单、每层要同改的关联对象、哪些绑死不能单独改，都记在
[docs/rename-inventory.md](docs/rename-inventory.md)。

## 五个角色目录

目录用角色名，不含产品名（ADR-0011）。

| 目录 | 角色 | 内容 |
| --- | --- | --- |
| `android/` | 应用 | Kotlin 壳、XML/经典 View 界面、JNI 桥；四个 Gradle 模块 `app`、`termux-shared`、`core`、`terminal-view` |
| `renderer/` | 渲染器 | Rust crate：渲染状态 → 快照 → 字形图集 → wgpu/Vulkan 上屏；FreeType 彩色字形与 emoji sbix 解析 |
| `session/` | 会话层 | Rust crate：PTY、进程、环境、生命周期，六事件流（portable-pty） |
| `boo/` | 动画程序 | Rust crate：官网动画播放 + 渲染压力 bench，单二进制 |
| `libghostty/` | 终端核心资产 | libghostty-vt 的 `.a`、头文件、Nerd Font；属构建输入，仓库只跟踪它的 README |

## 从零配置一台新设备

下面这套流程 2026-10-02 在本机（aarch64 + Termux）实测走通；x86_64 电脑的差异单独标注。
仓库本身目前只有本机工作树，私有远端在工单 65 建立；克隆用 `git clone <远端或本地路径>`。

### 1. 工具链

| 组件 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 25（本机实测） | 构建用 AGP 9.3 + Gradle 9.7.0，要求 JDK 17 以上 |
| Android SDK | `platforms;android-36`、`build-tools;36.0.0`、`ndk;22.1.7171670` | NDK 版本取自 `android/app/build.gradle` 的 `ndkVersion` |
| Rust | 1.97.1 | `rust-toolchain.toml` 固定；交叉编译目标 `aarch64-linux-android` |
| Gradle | 9.7.0 | 用 `android/gradlew`（wrapper），不要用系统 gradle |

Termux 宿主本身就是 aarch64，`cargo build --target aarch64-linux-android` 直接可用；
其他宿主用 cargo-ndk 或 NDK 自带 clang。

### 2. 设备配置（每台机器自己写，全部不入库）

```bash
# SDK 路径：下面两行都是示例，按自己的安装位置改
echo "sdk.dir=$HOME/Android/Sdk" > android/local.properties
```

```properties
# 只有 aarch64 宿主需要。SDK 自带的 aapt2 是 x86_64，在 aarch64 上跑不起来。
# 写进用户级 Gradle 属性，不要写进仓库内的 android/gradle.properties。
# android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
```

x86_64 电脑不需要上面这条覆盖，SDK 自带的 aapt2 直接可用。release 签名输入同样放
环境变量或 `~/.gradle/gradle.properties`，见 `android/docs/signing.md`。

### 3. 构建输入

构建输入（bootstrap 归档、预编译 `.a`、字体、预编译 `.so`、工具链）不入库
（ADR-0012），来源、sha256 与落地路径见 [docs/build-inputs.md](docs/build-inputs.md)。

- bootstrap 归档：Gradle 的 `downloadBootstraps` 任务自动下载并校验，不用手工
- Apple 彩色 emoji 字体：`scripts/fetch-emoji-fonts.sh` 下载原始 ttc、剥离到 160px、校验 sha256
- libghostty-vt 的 `.a` 与头文件、Nerd Font：按来源表手工下载解包到 `libghostty/lib/` 下
- Rust JNI 库：`session/` 用 `scripts/build-session-lib.sh` 生成 `libfable-session.so`；
  `renderer/` 用 `cargo build --locked --release --target aarch64-linux-android`，把
  `target/aarch64-linux-android/release/libfable_render.so` 复制成
  `android/app/src/main/jniLibs/arm64-v8a/libfable-render.so`（JNI 名带连字符）

`.so` 与 assets 都在忽略规则里，不会误提交。

### 4. 构建与校验

```bash
cd android
export JAVA_HOME=/path/to/jdk-25        # 示例路径，用本机的 JDK
./gradlew :app:assembleDebug
```

一条命令跑完构建加校验：`./build-and-verify.sh debug`（构建 → badging → `.so` 清单 →
签名校验）。

产物预期（2026-10-02 实测）：包名 `com.gph.fable`，`lib/arm64-v8a/` 下有
`libfable-render.so`、`libfable-session.so`、`liblocal-socket.so`、
`libtermux-bootstrap.so`；debug 包由 AGP 的 debug keystore 签名。

## 什么在仓库里，什么要现取

| 类别 | 在哪 |
| --- | --- |
| 产品与设计 | 源码、`CONTEXT.md`、`docs/adr/`、`docs/agents/` |
| 可复现的结论 | 工单 Comments、`docs/release/`、`.scratch/fable-v1/baseline/` 报告 |
| 构建输入 | 不入库，`docs/build-inputs.md` 给来源与 sha256 |
| 操作记录 | 不入库：真机运行数据、诊断现场、一次性恢复副本、签名材料台账（ADR-0012） |

## 许可与第三方来源

- `android/`：GPLv3-only（termux-app fork），例外清单见 `android/LICENSE.md`
- `renderer/`、`session/`、`boo/`：MIT（各目录 `LICENSE`）
- 第三方来源与许可证台账：[docs/release/third-party-sources.md](docs/release/third-party-sources.md)
- 核心资产清单：[libghostty/README.md](libghostty/README.md)
- Apple Color Emoji 许可上不可再分发，不入库：来源见
  [docs/fonts/apple-emoji-provenance.md](docs/fonts/apple-emoji-provenance.md)

## 文档地图

| 文件 | 用途 |
| --- | --- |
| `CONTEXT.md` | 领域词汇（SSOT） |
| `docs/adr/` | 架构决策 |
| `docs/agents/` | 工程流程、规则、事实源映射 |
| `docs/build-inputs.md` | 构建输入来源表 |
| `docs/rename-inventory.md` | 改名清单（各层现状、关联对象、冻结点） |
| `项目总览与交接.md` | 工具链状态、路线图、常用命令 |
| `.scratch/fable-v1/issues/` | 工单（本地 markdown tracker，规则见 `docs/agents/issue-tracker.md`） |
