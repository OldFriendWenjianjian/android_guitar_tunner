# 🎸 Guitar Tuner

**吉他调音器** — 一款基于 Kotlin + Jetpack Compose 的 Android 原生调音应用

---

## Overview / 概述

Guitar Tuner 是一款轻量级 Android 调音器应用，采用 **YIN 音高检测算法** 实时分析麦克风输入，帮助乐手快速精准地完成调音。应用支持吉他、尤克里里和贝斯三种乐器，提供固定调弦与色度调音两种工作模式，配合直观的仪表盘视觉反馈，让调音过程简洁高效。

---

## Features / 功能特性

### 🎯 实时音高检测
- 基于 **YIN 算法** 的高精度音高识别（采样率 44100Hz）
- 实时显示：当前频率 (Hz)、目标/最近音名、cent 偏差值
- 信号稳定性处理：指数移动平均 (EMA) 平滑、噪声过滤、弱信号保护

### 🎵 多乐器支持

| 乐器 | 标准调弦 | 音域范围 |
|------|---------|---------|
| **吉他 (Guitar)** | E2 A2 D3 G3 B3 E4 | 6 弦标准调弦 |
| **尤克里里 (Ukulele)** | G4 C4 E4 A4 | 高 G 标准调弦 |
| **贝斯 (Bass)** | E1 A1 D2 G2 | 4 弦标准调弦 |

### 🔀 双调音模式
- **固定调弦模式 (Standard)**：按选定乐器的标准弦位逐弦调音，自动匹配最近的目标弦
- **色度调音模式 (Chromatic)**：检测当前音高并匹配最近的半音音名（C1–B7 全音域覆盖）

### 📊 可视化反馈
- **半圆弧仪表盘**：指针实时指示 cent 偏差方向与幅度（±50 cents）
- **弹簧动画**：指针带有自然过冲的弹簧物理动画，手感流畅
- **颜色编码**：
  - 🟢 **绿色** — 音准正确（偏差 ≤ 5 cents）
  - 🔴 **红色** — 音高偏高，需松弦
  - 🔵 **蓝色** — 音高偏低，需紧弦
- **状态文字提示**：准确/偏高/偏低/等待信号 四种状态实时反馈

---

## Tech Stack / 技术栈

| 项目 | 版本/说明 |
|------|---------|
| 语言 | Kotlin 2.1.0 |
| UI 框架 | Jetpack Compose (BOM 2024.12.01) |
| 架构 | MVVM (ViewModel + StateFlow) |
| 构建工具 | Gradle 8.11.1 (Kotlin DSL) |
| compileSdk | 35 (Android 15) |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| JDK | 17 |

---

## Project Structure / 项目结构

```
app/src/main/java/com/example/guitartuner/
├── app/
│   └── MainActivity.kt              # 入口 Activity，权限处理与生命周期管理
├── audio/
│   └── AudioInput.kt                # 麦克风 PCM 音频采集（AudioRecord API）
├── pitch/
│   └── YinPitchDetector.kt          # YIN 音高检测算法（论文完整 6 步实现）
├── music/
│   ├── Instruments.kt               # 乐器/音符/调弦预设数据模型
│   └── NoteMapper.kt                # 频率 → 音名映射（色度 & 固定调弦）
├── viewmodel/
│   └── TunerViewModel.kt            # 状态管理，信号链整合，EMA 平滑
└── ui/
    ├── theme/
    │   ├── Color.kt                  # 颜色常量定义
    │   ├── Type.kt                   # 字体排版定义
    │   └── Theme.kt                  # Material3 暗色主题
    ├── screens/
    │   └── TunerScreen.kt           # 调音主界面（音名/仪表盘/频率/选择器）
    └── components/
        └── TuningMeter.kt           # 半圆弧仪表盘组件（Canvas 自绘制）
```

---

## Architecture / 架构说明

```
┌─────────────┐    FloatArray     ┌──────────────────┐   PitchResult   ┌─────────────┐
│  AudioInput  │ ── SharedFlow ──▸│ YinPitchDetector  │ ──────────────▸ │  NoteMapper  │
│  (麦克风采集) │                   │  (YIN 音高检测)    │                 │ (音符映射)    │
└─────────────┘                   └──────────────────┘                 └──────┬──────┘
                                                                              │
                                                                       NoteMapResult
                                                                              │
                                                                              ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                           TunerViewModel (状态管理)                                   │
│  EMA 平滑 → 稳定帧计数 → 置信度过滤 → 音符映射 → cent 偏差 → UI 状态输出              │
└──────────────────────────────────────────────────┬───────────────────────────────────┘
                                                   │ StateFlow<TunerUiState>
                                                   ▼
┌──────────────────────────────────────────────────────────────────────────────────────┐
│                              TunerScreen (Jetpack Compose)                            │
│  乐器/模式选择 │ 音名显示 │ TuningMeter 仪表盘 │ 频率显示 │ 状态提示                   │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Getting Started / 构建与运行

### 环境要求

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **JDK 17**
- Android 真机（推荐）或支持麦克风的模拟器

### 构建步骤

1. 克隆项目到本地：
   ```bash
   git clone <repository-url>
   cd guitar-tuner
   ```

2. 使用 Android Studio 打开项目根目录

3. 等待 Gradle Sync 完成依赖下载

4. 连接 Android 设备（或启动支持麦克风的模拟器）

5. 点击 **Run ▶** 构建并安装到设备

### 命令行构建（可选）

```bash
# Windows
.\gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

---

## Permissions / 权限说明

| 权限 | 用途 |
|------|------|
| `RECORD_AUDIO` | 访问麦克风进行实时音频采集，用于音高检测 |

应用启动时会动态请求麦克风录音权限。此权限是调音功能的必要条件，拒绝授权将无法使用调音功能。

---

## Algorithm / 算法说明

### YIN 音高检测

本应用采用 **YIN 算法**（de Cheveigné & Kawahara, 2002）进行基频检测，完整实现论文中的 6 个步骤：

1. **差分函数 (Difference Function)**：计算信号与其延迟版本的差异
2. **累积均值归一化差分函数 (CMNDF)**：消除差分函数的绝对能量依赖
3. **绝对阈值搜索**：在 CMNDF 中寻找第一个低于阈值的周期候选
4. **局部谷底追踪**：从候选点继续搜索更精确的谷底位置
5. **抛物线插值**：在离散点间进行亚采样精度的周期估计
6. **频率有效性验证**：确保结果在 20–5000 Hz 有效范围内

### 信号稳定性处理

- **EMA 平滑**（α=0.3）：减少帧间频率抖动
- **大跳变检测**（±6% 阈值）：换弦时自动重置平滑状态
- **连续帧验证**：至少连续 2 帧有效才更新显示；连续 5 帧无效才清除
- **置信度过滤**（≥0.5）：低置信度结果视为不可靠并丢弃

---

## License / 许可证

本项目仅供学习与个人使用。
