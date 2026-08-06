# SELENE Windows 桌面版

SELENE Windows 是一个原生 WPF 托盘程序，在当前 Windows 用户会话中运行。
它不是系统服务：这样不需要管理员权限，安装更简单，也让用户可以随时
暂停或退出采集。

## 环境要求

- Windows 10 22H2 或 Windows 11，x64。
- 发布版为单文件自包含程序，不需要另装 .NET 运行时。
- 开发构建需要 .NET SDK 9.0。
- 不请求管理员权限。

## 第一次使用

1. 将 SELENE-0.3.0-windows-x64.zip 解压到普通用户目录。
2. 启动 SELENE.Windows.exe。
3. 选择一个导出父目录。它可以和 Android SELENE 使用同一个父目录，
   两个平台会分别创建自己的不可变快照。
4. 保持自动采集开启，选择 5、15、30 或 60 分钟的周期。
5. 只有使用发布版时才建议打开“登录 Windows 后启动”。调试运行不会写入
   开机启动项。

关闭窗口只会让 SELENE 隐藏到系统托盘。托盘菜单可以重新显示窗口、立即
采集或退出进程。只有 SELENE 正在运行时才会采集；登录后自动启动是保持
它可用的正式方式。

## 采集内容

每个周期都会新建一个 SELENE-v1-UTC 时间戳文件夹，其中包含一个
context-events.json。Windows 版当前记录：

- SELENE 运行期间观察到的前台进程名及开始、结束时间；
- 该周期前台使用总秒数和不同应用数量；
- GetLastInputInfo 提供的当前空闲时长；
- Windows 能报告时的电池百分比、充电和交流电状态；
- 网络是否可用及粗粒度传输类型：wifi、ethernet、vpn、other 或 none。

事件时间戳使用 Windows 系统时区和 ISO 8601 偏移，例如
`2026-08-06T22:54:39.123+08:00`；快照目录名仍使用 UTC 以保证稳定排序。JSON
采用紧凑 UTF-8 编码，SELENE 会在每次采集完成后显示写入字节数。

进程名是 chrome、devenv 这类可执行文件名。SELENE 不读取窗口标题、
文档名、网址、文本、剪贴板、命令行参数、键盘、通知、聊天数据库、截图
或支付记录。短于 5 秒的会话不会单独输出，但如果已经在采样窗口内观察到，
仍会计入周期汇总。

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

快照使用统一协议：

~~~json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "windows" },
  "producer": {
    "name": "SELENE",
    "version": "0.3.0",
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
dotnet publish desktop\SELENE.Windows\SELENE.Windows.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:PublishTrimmed=false -o releases\SELENE-0.3.0-windows-x64
~~~

契约测试会使用同一个时间戳写入两次快照，确认目录不同，并确认第一次
写入的 JSON 在第二次写入后逐字节不变。
