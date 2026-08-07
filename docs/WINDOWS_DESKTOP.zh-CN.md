# SELENE Windows 桌面版

SELENE Windows 是一个原生 WPF 托盘程序，在当前 Windows 用户会话中运行。
它不是系统服务：这样不需要管理员权限，安装更简单，也让用户可以随时
暂停或退出采集。

## 环境要求

- Windows 10 22H2 或 Windows 11，x64。
- 发布版为单文件自包含程序，不需要另装 .NET 运行时。
- 开发构建需要 .NET SDK 9.0。
- 不请求管理员权限。
- Android 远程同步使用官方 Syncthing；生成配对码时若缺少，会通过 winget 自动安装。

## 第一次使用

1. 将 SELENE-0.5.2-windows-x64.zip 解压到普通用户目录。
2. 启动 SELENE.Windows.exe。
3. 选择一个导出父目录。它可以和 Android SELENE 使用同一个父目录，
   两个平台会分别创建自己的不可变快照。
4. 保持自动采集开启，选择 5、15、30 或 60 分钟的周期。
5. 只有使用发布版时才建议打开“登录 Windows 后启动”。调试运行不会写入
   开机启动项。

关闭窗口只会让 SELENE 隐藏到系统托盘。托盘菜单可以重新显示窗口、立即
采集或退出进程。只有 SELENE 正在运行时才会采集；登录后自动启动是保持
它可用的正式方式。

## Android 一次配对

“Android 一次配对同步”由 Windows SELENE 自己管理，不需要进入 Syncthing Web UI：

1. 选择/确认 Receive Only 收件箱，建议开启登录后启动。
2. 点击生成二维码。SELENE 准备 Syncthing、随机证书、一次性令牌和 5 分钟监听器。
3. 在同一可信局域网中用 Android SELENE 扫描，或复制文本配对码。

配对准备、临时证书和二维码编码均在后台执行，不阻塞窗口。SELENE 会缓存当前进程中
已验证的 Syncthing 路径、收件箱和设备 ID。`THEIA_SELENE_INBOX` 仅在值实际变化时
写入；首次写入采用直接注册表更新和限时后台系统通知，避免某个无响应窗口把二维码
生成拖慢十几秒。
4. 手机回传设备 ID 后，Windows 自动批准设备并共享 `selene-inbox-v1`。

SELENE 会设置当前用户的 `THEIA_SELENE_INBOX`；首次完成后重启 THEIA。后续跨网络
同步不需要再次扫码。完整协议、安全边界和排错见 [P2P_SYNC.zh-CN.md](P2P_SYNC.zh-CN.md)。

## 采集内容与选择

每个周期都会新建一个 SELENE-v1-UTC 时间戳文件夹，其中包含一个
context-events.json。默认记录：

- SELENE 运行期间观察到的前台进程名及开始、结束时间；
- 该周期前台使用总秒数和不同应用数量；
- GetLastInputInfo 提供的当前空闲时长；
- Windows 能报告时的电池百分比、充电和交流电状态；
- 网络是否可用及粗粒度传输类型：wifi、ethernet、vpn、other 或 none。

“采集范围与隐私边界”提供逐项开关。默认只写上述低敏元数据；用户明确开启后，
还可写入前台窗口标题、可执行文件完整路径和当前前台浏览器的 URL。每个快照均
包含 `collection-profile` 事件，明确标注本次写入时开启了哪些字段。

事件时间戳使用 Windows 系统时区和 ISO 8601 偏移，例如
`2026-08-06T22:54:39.123+08:00`；快照目录名仍使用 UTC 以保证稳定排序。JSON
采用紧凑 UTF-8 编码，SELENE 会在每次采集完成后显示写入字节数。

进程名是 chrome、devenv 这类可执行文件名。窗口标题、路径和 URL 默认关闭，
开启后会以 `sensitive` 隐私级别写入本地快照；URL 只通过当前浏览器地址栏进行
尽力读取，不能保证获取每个标签页或每次导航，且可能含查询参数。SELENE 不读取
网页正文、表单、命令行参数、键盘、剪贴板、通知、聊天数据库、短信、通话、截图
或支付记录。短于 5 秒的会话不会单独输出，但如果已经在采样窗口内观察到，仍会
计入周期汇总。

Windows 版暂不读取日历数据库、精确位置、通知正文或应用内部使用历史。
这些数据源应由拥有明确权限的专用适配器接入，不通过不可靠的抓取模拟。

Android 的后台持续运动记录使用独立的权限和前台服务边界，详见
[ANDROID_MOVEMENT.zh-CN.md](ANDROID_MOVEMENT.zh-CN.md)。

## 数据与隐私

设置保存于：

~~~text
%LOCALAPPDATA%\SELENE\desktop-settings.json
~~~

诊断日志只记录事件名、时间和错误消息：

~~~text
%LOCALAPPDATA%\SELENE\logs\selene-YYYYMMDD.log
~~~

原始时间线只写入用户选定的导出目录。SELENE 不扫描该目录，不合并旧事件，
不重写已有 JSON，也不删除旧快照。写入中断时会留下不完整快照供检查，
下一次成功采集会创建新的文件夹。

尚未写入快照的前台会话会保存在当前用户的 `%LOCALAPPDATA%\SELENE` 待导出
文件中；成功写入并落盘后才会确认删除。突然退出、断电或 Windows 强制结束进程
仍可能损失正在进行的一个采样间隔，无法保证零丢失。

快照使用统一协议：

~~~json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "windows" },
  "producer": {
    "name": "SELENE",
    "version": "0.5.2",
    "layout": "immutable-snapshot-v1"
  },
  "events": []
}
~~~

THEIA 接收的是粗粒度模型投影。其他 SELENE 平台本地可能存在的精确坐标、
地址类字段不会发送给模型。

## 排查

没有新文件夹时，先确认选定的父目录仍存在，并确认 SELENE 图标在系统托盘。
可移动磁盘容易断开，建议使用稳定的本地目录。写入失败会记录到当天日志，
程序不会因此退出，可以直接再次点击“立即采集”。

如果要移动或删除旧的发布目录，先在 SELENE 中关闭开机启动。启动项写在
当前用户的 Run 注册表键中，不会影响其他 Windows 用户。

## 开发与构建

~~~powershell
dotnet build desktop\SELENE.Windows\SELENE.Windows.csproj -c Release
dotnet run --project desktop\SELENE.Windows.ContractTests\SELENE.Windows.ContractTests.csproj -c Release
dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:PublishTrimmed=false -o releases\SELENE-0.5.2-windows-x64
~~~

契约测试会使用同一个时间戳写入两次快照，确认目录不同，并确认第一次
写入的 JSON 在第二次写入后逐字节不变。
