# 代码审查记录 - SBPlus MainHook.java 2026-08-22

## 审计结论

### 已修复/优化 (装机 21195e5 + aeb306d)
1. **删 DIAG-BLUE 全局 setColorFilter hook**（每帧绘制过桥+打日志，开屏卡主嫌）
2. **删 onDraw hook 里 dumpProbe/dumpBlueSeen 日志**（每个 TextView 绘制打日志）
3. **修 onDraw 重绘风暴**：无条件 `setTextColor+invalidate+postInvalidate` → 颜色相同直接 return，不再每帧排队重绘（掉帧主因之一）
4. **syncPeriodic 日志只在状态变化时打**（原每 800ms 打一条）
5. **删 13 个死方法 + 6 个死字段**（诊断遗留，-254 行）

### 发现的隐患（未修，需评估）
- **`httpGetBytesProgress` 全内存缓冲**（ByteArrayOutputStream）：B站 108MB 视频全进内存，低内存设备 OOM 风险。m3u8 分片是落盘的，但单文件是内存的。建议改流式写临时文件。
- **copyFile 明文**：'516942' 处 `in.read(buf)` 假设一次读完（JSON 小文件，风险低）
- FileInputStream 大多有 close（无严重泄漏）

### 代码规模
- 原始：16469 行 / 925743 字符 / 775 log 调用
- 现在：16215 行 / 910907 字符 / 767 log 调用
- 368 私有方法, 24 static 字段

### hook 面（绘制热路径，每帧）
- TextView.onDraw ×2, ImageView.onDraw ×1, TextView setText/onDraw/setTypeface/setTextColor/getTextColors
- 这些是主题染色/字体功能必需，不能删，但已全部加轻量化处理
