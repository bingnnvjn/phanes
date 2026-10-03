# 安全策略

Phanes 是个人项目，**不承诺响应时限**，也没有漏洞赏金。

## 私下报告漏洞

请用 GitHub 的私密漏洞报告入口，不要用公开 issue 提交漏洞细节：

<https://github.com/bingnnvjn/phanes/security/advisories/new>

报告里给出：受影响的提交、平台与目标、最小复现、影响评估。**不要**附带 token、
私钥、keystore、设备标识或机密日志正文。

## 敏感面

以下改动按安全敏感处理：

- `renderer/`：Rust、JNI、FreeType、wgpu、原生窗口与字体资产；
- `session/`：Rust、JNI、PTY、进程启动与跨线程关闭；
- 依赖供应链（公告、许可证、来源）；
- 构建输入与第三方资产的来源/哈希一致性。

## 约定

- 签名材料永不进入仓库或构建输入，应用身份冻结见 ADR-0013；
- 泄露响应最小流程见 [`docs/release/public-release-gate.md`](docs/release/public-release-gate.md)；
- 责任人：仓库所有者。
