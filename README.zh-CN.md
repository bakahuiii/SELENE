# SELENE

[English](README.md) | [简体中文](README.zh-CN.md)

开发、扩展 Android/Windows 采集器、修改运动阈值或事件协议前，请先阅读
[开发者指南](docs/DEVELOPER_GUIDE.zh-CN.md)；英文版为
[Developer Guide](docs/DEVELOPER_GUIDE.md)。

SELENE 是 THEIA 的独立时间线采集端，包含 Android 和 Windows 两个平台。
它只在本地采集经过授权的非文本背景，并写成不可变快照供 THEIA 直接导入。
SELENE 不读取聊天数据库，也不上传或解释聊天内容。

## 平台

- Android：采集屏幕使用、前台应用时段、日历、设备状态、网络状态和可选
  的后台持续移动轨迹、速度与最近位置兜底。
- Windows：采集 SELENE 运行期间的前台进程名及使用时段、空闲时长、电源
  与网络状态。

两个平台都使用 selene-context-events/v1，每次采集都会创建新的：

~~~text
SELENE-v1-20260806T185439123Z/context-events.json
~~~

旧快照永远不会被打开、合并、改写或删除。THEIA 请选择这些快照的父目录。

## Android 持续运动记录

Android `0.3.0` 在开启自动采集和后台位置后，会使用前台定位服务记录已经确认的
持续移动。它会输出轨迹点、各点的大致速度、距离和一次行程汇总；室内走几步、
单个噪声点、陈旧位置、低精度定位和不合理跳点不会作为移动导出。

完整的开通步骤、权限、过滤规则、字段、隐私边界、耗电与重启限制见
[Android 持续运动记录](docs/ANDROID_MOVEMENT.zh-CN.md)。英文版见
[ANDROID_MOVEMENT.md](docs/ANDROID_MOVEMENT.md)。导出协议中英文对照见
[EXPORT_LAYOUT.md](docs/EXPORT_LAYOUT.md) 和
[EXPORT_LAYOUT.zh-CN.md](docs/EXPORT_LAYOUT.zh-CN.md)。

## Windows 快速开始

1. 解压 SELENE-0.3.0-windows-x64.zip。
2. 运行 SELENE.Windows.exe。
3. 选择导出父目录。
4. 开启自动采集并选择周期。
5. 关闭窗口后程序会停留在系统托盘；需要完全退出时使用托盘菜单。

Windows 版无需管理员权限，发布版也不需要另装 .NET。详细说明见
Windows 桌面版文档：docs/WINDOWS_DESKTOP.zh-CN.md。

## Android 构建

Android SDK Platform 35、JDK 17 和 Gradle 工具链位于
H:\work\SELENE\.android-build。完整发布步骤见
[RELEASE_PROCESS.zh-CN.md](docs/RELEASE_PROCESS.zh-CN.md)，英文版见
[RELEASE_PROCESS.md](docs/RELEASE_PROCESS.md)。
