# SELENE 发布流程

每个 SELENE 发布版本都会构建到新的不可变本地发布目录，随后作为 GitHub Release 附件上传；发布文件不提交到 Git。请先统一 Android 的 `versionName`/`versionCode` 和 Windows 的程序集版本，再为两个平台选择同一个发布版本号。

## 前置条件

- Windows 10 22H2 或更高版本。
- .NET SDK 9.0。
- JDK 17。
- 项目内 `.android-build` 下的 Android SDK 和 Gradle 发行版。
- `app/src/main/jniLibs/SYNCTHING_PROVENANCE.json` 中记录的两份 ARM 原生核心及
  [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md)。

脚本使用项目内 Android 工具链，不依赖机器范围的 Android Studio。本地 Gradle 缓存已就绪时不会下载依赖；确需下载时，先在 shell 中设置本地代理再执行脚本。

## 构建与验证

在仓库根目录执行：

```powershell
$env:JAVA_HOME = '<jdk-home>'
.\tools\prepare-release.ps1 -Version 0.5.2
```

请显式传入 `-Version`。脚本拒绝重复使用对应的 `releases\v<version>` 目录，避免意外覆盖此前的本地发布暂存目录。它会依次执行：

1. 按 provenance manifest 校验 Syncthing 原生文件大小与 SHA-256。
2. Android debug lint 与 APK 构建。
3. Windows Release 构建。
4. Windows 不可变快照契约测试。
5. Windows x64 单文件自包含发布。
6. Android 清单、ZIP 对齐和签名校验。
7. Windows ZIP 内容校验。
8. SHA-256 校验和生成。

产物目录如下：

```text
releases/
  v0.5.2/
    published/windows-x64/
    artifacts/
      SELENE-0.5.2-windows-x64.zip
      SELENE-0.5.2-android-debug.apk
      SHA256SUMS.txt
```

`published/` 和 `artifacts/` 有意被 Git 忽略。Android 产物是 debug 签名，因为仓库中不保存私有发布签名密钥；文件名会明确标记这一点。

## GitHub Release 清单

1. 确认源代码已提交并推送。
2. 从该提交创建 `v<version>` 标签。
3. 以该标签创建 GitHub Release。
4. 上传两个二进制产物和 `SHA256SUMS.txt`。
5. 在发布说明中重申隐私边界：SELENE 只采集文档说明的本地非文本信号，不采集聊天内容、键盘、剪贴板、截图、通知正文、短信、通话、支付记录或其他应用数据库。
6. 发布前下载一个附件，并用随附校验和文件验证其 SHA-256。
7. Android 包含运动记录时，说明用户必须选择精确位置并设置为“始终允许”，系统才会采集已确认行程。通知权限只负责显示前台服务状态，不授予位置权限。
8. 说明 Android 只支持两个 ARM ABI，内置核心来源于固定 Syncthing-Fork/Syncthing
   提交，并附上 `THIRD_PARTY_NOTICES.md` 中的 MPL-2.0 源码获取信息。

发布说明还应写明支持的 `selene-context-events/v1` schema，使用户知道 THEIA 只导入 SELENE 的严格不可变快照信封。
