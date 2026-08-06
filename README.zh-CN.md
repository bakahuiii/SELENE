# SELENE

SELENE 是 THEIA 的独立时间线采集端，包含 Android 和 Windows 两个平台。
它只在本地采集经过授权的非文本背景，并写成不可变快照供 THEIA 直接导入。
SELENE 不读取聊天数据库，也不上传或解释聊天内容。

## 平台

- Android：采集屏幕使用、前台应用时段、日历、设备状态、网络状态和可选
  的被动后台位置。
- Windows：采集 SELENE 运行期间的前台进程名及使用时段、空闲时长、电源
  与网络状态。

两个平台都使用 selene-context-events/v1，每次采集都会创建新的：

~~~text
SELENE-v1-20260806T185439123Z/context-events.json
~~~

旧快照永远不会被打开、合并、改写或删除。THEIA 请选择这些快照的父目录。

## Windows 快速开始

1. 解压 SELENE-0.2.0-windows-x64.zip。
2. 运行 SELENE.Windows.exe。
3. 选择导出父目录。
4. 开启自动采集并选择周期。
5. 关闭窗口后程序会停留在系统托盘；需要完全退出时使用托盘菜单。

Windows 版无需管理员权限，发布版也不需要另装 .NET。详细说明见
Windows 桌面版文档：docs/WINDOWS_DESKTOP.zh-CN.md。

## Android 构建

Android SDK Platform 35、JDK 17 和 Gradle 工具链位于
H:\work\SELENE\.android-build。完整导出协议见 docs/EXPORT_LAYOUT.md。
