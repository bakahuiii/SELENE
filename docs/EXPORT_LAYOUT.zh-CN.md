# SELENE 导出布局

## 契约

SELENE 导出不可变快照。一个快照是名称由创建时间确定、创建后永不修改的目录：

```text
SELENE-v1-<UTC timestamp>/context-events.json
```

`<UTC timestamp>` 使用 `yyyyMMddTHHmmssSSSZ` 格式，例如 `20260806T185439123Z`。`v1` 是快照布局版本，不是应用版本。目录时间戳固定使用 UTC，以保证命名与排序稳定；JSON 内用于展示的 ISO 8601 时间使用设备系统时区和偏移，例如 `2026-08-06T22:54:39.123+08:00`。

内容文件符合 `selene-context-events/v1`：

```json
{
  "schema": "selene-context-events/v1",
  "device": { "platform": "android" },
  "generatedAt": "2026-08-06T22:54:39.123+08:00",
  "producer": {
    "name": "SELENE",
    "version": "0.3.0",
    "layout": "immutable-snapshot-v1"
  },
  "events": []
}
```

`producer` 是必填元数据。THEIA 会在进入时间线导入流程前用它拒绝非 SELENE JSON。

## 写入与导入语义

1. 采集器创建一个新的快照目录。
2. 在目录中创建 `context-events.json`。
3. 只写入本次采集产生的事件。
4. 不读取之前的导出，不编辑或删除旧导出。

中断写入可能留下不完整快照。导入器应拒绝无法解析的 JSON，但保留目录供检查；之后成功采集会创建新的独立快照，不会修复旧目录。Android 的 Worker 和前台运动服务会在进程内串行写入，避免同时写入同一导出文件。

请选择父目录。THEIA 会递归扫描 JSON，保留相对路径作为来源，并按稳定事件 ID 去重。因此每小时快照与运动服务快照可以重叠，而导出目录不需要可变数据库。SELENE 不读取、迁移、修改或删除自己快照目录之外的文件。

JSON 使用无缩进的紧凑 UTF-8。Android 不输出与 `capturedAt` 重复的生产端 `importedAt`；THEIA 会记录实际导入时间，并在需要时从快照生成时间派生导入时间，因此不改变事件时间信息。

`device.platform` 在 Android 采集器中为 `android`，在 Windows 采集器中为 `windows`；两个平台使用同一 `selene-context-events/v1` 信封。

## Android 运动事件

当 Android 自动采集和后台位置同时开启时，SELENE 使用前台位置服务记录运动，而不再依赖每小时 WorkManager。只有相邻样本独立显示持续移动后才确认行程；短暂室内步行、单个噪声点、陈旧最后已知位置、低精度点和不合理跳点不会导出为运动。

确认行程的轨迹点为精确 `location` 事件，`values.moving` 为 `true`，包含稳定 `trackId`、速度（m/s 和 km/h）、与前一点距离、精度和提供方。行程结束时会写入一个 `movement` 汇总事件，其中包含时长、距离、平均与最高速度和样本数。确认前缓冲会随已确认行程写出，以保留真实散步起点而不把家里几步当成行程。

```json
{
  "id": "SELENE-movement-point-<trackId>-<sequence>",
  "kind": "location",
  "startAt": "2026-08-06T22:54:39.123+08:00",
  "privacy": "precise",
  "location": { "latitude": 31.230416, "longitude": 121.473701, "accuracyMeters": 12.4 },
  "locationConsent": { "exactLocation": true, "captureMode": "foreground", "grantedAt": "2026-08-01T09:00:00.000+08:00" },
  "values": { "trackId": "<trackId>", "sequence": 0, "moving": true, "speedMps": 1.21, "speedKmh": 4.4, "distanceFromPreviousMeters": 15.3, "accuracyMeters": 12.4, "provider": "gps", "sampleMode": "foreground-service" }
}
```

行程汇总的 `kind` 为 `movement`，包含 `durationSeconds`、`distanceMeters`、`averageSpeedMps`、`averageSpeedKmh`、`maxSpeedMps`、`maxSpeedKmh`、`sampleCount` 和同一个 `trackId`。每小时 Worker 还可能导出 `last-known-fallback` 地点上下文；位置超过 30 分钟会被丢弃，且该事件明确不是运动记录。

关于权限、阈值、耗电和排查，见 [ANDROID_MOVEMENT.zh-CN.md](ANDROID_MOVEMENT.zh-CN.md)。
