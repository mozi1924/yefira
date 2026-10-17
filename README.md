# Yefira (MC Sync Blender)

[![Fabric](https://img.shields.io/badge/Fabric-1.20.1%20%7C%201.20.4%20%7C%201.21.1%20%7C%201.21.4%20%7C%2026.2-blue.svg)](https://fabricmc.net/)
[![Forge](https://img.shields.io/badge/Forge-1.20.1-orange.svg)](https://files.minecraftforge.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-1.20.4%20%7C%201.21.1%20%7C%201.21.4%20%7C%2026.2-orange.svg)](https://neoforged.net/)
[![Build CI](https://github.com/mozi1924/mc-sync-blender/actions/workflows/build.yml/badge.svg)](https://github.com/mozi1924/mc-sync-blender/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-GPL_3.0-blue.svg)](LICENSE)
[![Blender](https://img.shields.io/badge/Companion-MoziToolKit-green.svg)](https://github.com/mozi1924/MoziToolKit)

**Yefira** 是专为 **Minecraft** 与 **Blender ([MoziToolKit](https://github.com/mozi1924/MoziToolKit))** 打造的高性能、低延迟实时双向同步模组。

通过内置轻量化 WebSocket 通信架构与高频方块状态编码器，Yefira 能够将游戏内指定 3D 选区的方块数据、增量变化实时推送至 Blender 视口中构建三维点云与模型；同时支持类似 Blender 视口的 Ghost 模式自由漫游与射线选区操作。

> **权威协议规范**：服务端与 DCC 客户端之间的二进制帧、`state_hash`、基线/增量状态机、
> 资源硬限与错误恢复契约，以
> **[RFC v3.1: MTK 2.0 Live Sync 协议与架构设计规范](../MoziToolKit/docs/live_sync/live_sync_protocol.md)** 为准。

---

## 🏛️ 项目多版本架构 (Multi-Version Architecture)

项目采用 **主分支统一维护 (Unified Main Branch)** 架构，将核心业务逻辑与版本特化兼容层（Compatibility Layer）严格解耦，并通过 5 大独立子工程实现真正的跨版本 API 破坏隔离：

```text
mc-sync-blender/
├── common-core/                        # 跨版本共享核心层 (Pure Core Logic)
│   ├── network/                        # WebSocketServerManager 通信管理与帧调度
│   ├── selection/                      # SelectionBox 数学几何、坐标换算与状态流
│   ├── config/                         # YefiraConfig 统一配置管理
│   ├── encoder/                        # 方块状态调色板压缩与 DTO 序列化
│   └── compat/                         # 统一版本抽象契约 (ILevelCompat / VersionCompat)
│
├── versions/
│   ├── 1.20.1/                         # Era 1: Minecraft 1.20 - 1.20.1 (Java 17, Fabric & Forge)
│   │   ├── common/                     # LevelCompat120, GuiCompat, RenderCompat
│   │   ├── fabric/                     # Fabric 加载器绑定
│   │   └── forge/                      # MinecraftForge 加载器绑定
│   │
│   ├── 1.20.4/                         # Era 2: Minecraft 1.20.2 - 1.20.4 (Java 17, Fabric & NeoForge)
│   │   ├── common/                     # LevelCompat1204, Checkbox.builder, turnPlayer(double)
│   │   ├── fabric/                     # Fabric 加载器绑定
│   │   └── neoforge/                   # NeoForge 加载器绑定
│   │
│   ├── 1.21.1/                         # Era 3: Minecraft 1.20.5 - 1.21.1 (Java 21, Fabric & NeoForge)
│   │   ├── common/                     # LevelCompat121, DeltaTracker, 新 VertexConsumer
│   │   ├── fabric/                     # Fabric 加载器绑定
│   │   └── neoforge/                   # NeoForge 加载器绑定
│   │
│   ├── 1.21.4/                         # Era 4: Minecraft 1.21.2 - 1.21.4 (Java 21, Fabric & NeoForge)
│   │   ├── common/                     # LevelCompat1214, ShapeRenderer, ClientInput
│   │   ├── fabric/                     # Fabric 加载器绑定
│   │   └── neoforge/                   # NeoForge 加载器绑定
│   │
│   └── 26.2/                           # Era 5: Minecraft 26.2 (Java 25, Fabric & NeoForge)
│       ├── common/                     # LevelCompat26, 原生 Gizmos 接入, Identifier
│       ├── fabric/                     # Fabric 加载器绑定
│       └── neoforge/                   # NeoForge 加载器绑定
│
├── build.gradle                        # 根目录聚合构建脚本 (提供全局快捷命令与发布聚合)
├── settings.gradle                     # 根工程设置
└── .github/workflows/build.yml         # GitHub Actions 5 矩阵并行 CI
```

---

## 📦 支持的版本与模组加载器 (Supported Versions & Loaders)

| Minecraft 兼容纪元 (Era) | 对应 Java 运行环境 | 支持的模组加载器 (Mod Loaders) | 特性与渲染管线 |
| :--- | :--- | :--- | :--- |
| **1.20 - 1.20.1** (Era 1) | Java 17 | **Fabric** & **Forge** (47.3.0) | 自定义 3D 箭头与抗遮挡视口渲染兼容层 (旧 VertexConsumer) |
| **1.20.2 - 1.20.4** (Era 2) | Java 17 | **Fabric** & **NeoForge** (20.4.251) | 适配 `Checkbox.builder` 与 `turnPlayer(double)`，保留经典顶点管线 |
| **1.20.5 - 1.21.1** (Era 3) | Java 21 | **Fabric** & **NeoForge** (21.1.80) | 独立缓冲区与多批次渲染兼容层 (新 VertexConsumer, DeltaTracker) |
| **1.21.2 - 1.21.4** (Era 4) | Java 21 | **Fabric** & **NeoForge** (21.4.157) | 适配独立 `ShapeRenderer` 辅助管线与全新 `ClientInput` 体系 |
| **26.2** (Era 5) | Java 25 | **Fabric** & **NeoForge** (26.2.0.75) | 原生 `Gizmos` 视口手柄、`Identifier` 命名系统与最新输入架构 |

> [!IMPORTANT]
> **Minecraft 子版本破坏性重构与隔离策略说明**：
> Minecraft 官方在各个子版本中引入了诸多不可向下兼容的重大底层重构，因此模组必须按纪元严格隔离编译产物：
> 1. **1.20.2 断层**：Mojang 彻底废弃了旧版 `Checkbox` 构造器改用 Builder 模式，并修改了 `MouseHandler.turnPlayer(double)` 签名与网络生命周期，因此 `1.20.1` 产物绝对不可跨至 `1.20.2+`。
> 2. **1.20.5 断层**：Minecraft 强升 **Java 21**，引入 **Data Components** 彻底替换 NBT，重写了 `VertexConsumer`（移除 `endVertex()`）并引入 `DeltaTracker`。
> 3. **1.21.2 断层**：重写了 `Camera.update(DeltaTracker)`（移除旧 `setup` 方法），并用 `ClientInput` 重构了原 `KeyboardInput` 输入字段。
> 4. **26.2 断层**：Minecraft 强升 **Java 25**（字节码 69），将 `ResourceLocation` 更名为 `net.minecraft.resources.Identifier`，并引入原生 `Gizmos` 渲染系统。严禁将 Java 25 产物声明在 Java 21 环境（如 1.21.x）运行。

---

## 🚀 启动与调试方式 (Development Run Commands)

在仓库根目录下，即可通过 `./gradlew` 直接启动不同纪元版本、不同模组加载器的客户端或独立服务端进行本地调试开发。

### 1. 启动客户端 (Client)

#### Minecraft 1.20.1 (需 Java 17)
```bash
# 启动 1.20.1 Fabric 客户端
./gradlew run120FabricClient

# 启动 1.20.1 Forge 客户端
./gradlew run120ForgeClient
```

#### Minecraft 1.20.4 (需 Java 17)
```bash
# 启动 1.20.4 Fabric 客户端
./gradlew run1204FabricClient

# 启动 1.20.4 NeoForge 客户端
./gradlew run1204NeoForgeClient
```

#### Minecraft 1.21.1 (需 Java 21)
```bash
# 启动 1.21.1 Fabric 客户端
./gradlew run121FabricClient

# 启动 1.21.1 NeoForge 客户端
./gradlew run121NeoForgeClient
```

#### Minecraft 1.21.4 (需 Java 21)
```bash
# 启动 1.21.4 Fabric 客户端
./gradlew run1214FabricClient

# 启动 1.21.4 NeoForge 客户端
./gradlew run1214NeoForgeClient
```

#### Minecraft 26.2 (需 Java 25)
```bash
# 启动 26.2 Fabric 客户端
./gradlew run26FabricClient

# 启动 26.2 NeoForge 客户端
./gradlew run26NeoForgeClient
```

---

### 2. 启动服务端 (Dedicated Server)

#### Minecraft 1.20.1
```bash
./gradlew run120FabricServer
./gradlew run120ForgeServer
```

#### Minecraft 1.20.4
```bash
./gradlew run1204FabricServer
./gradlew run1204NeoForgeServer
```

#### Minecraft 1.21.1
```bash
./gradlew run121FabricServer
./gradlew run121NeoForgeServer
```

#### Minecraft 1.21.4
```bash
./gradlew run1214FabricServer
./gradlew run1214NeoForgeServer
```

#### Minecraft 26.2
```bash
./gradlew run26FabricServer
./gradlew run26NeoForgeServer
```

> [!TIP]
> **多 JDK 环境变量配置**：
> 根目录脚本支持通过环境变量自动绑定对应的 Java 路径，例如：
> - `JAVA_17_HOME=/path/to/jdk-17`
> - `JAVA_21_HOME=/path/to/jdk-21`
> - `JAVA_25_HOME=/path/to/jdk-25`
> 若未设置上述变量，脚本将优先探测系统默认安装的 JVM 路径或使用当前终端的 `JAVA_HOME`。

---

## 🔨 编译与打包 (Building Artifacts)

### 一键全量打包 (Build All Versions & Loaders)
在根目录下执行：
```bash
./gradlew buildAll
```
该任务将按序编译全部 5 大纪元的所有加载器版本，并将最终可执行模组 Jar 自动归集到 `build/dist/` 目录（共 10 款）：
- `build/dist/yefira-fabric-mc1.20-1.20.1-1.0.0.jar`
- `build/dist/yefira-forge-mc1.20-1.20.1-1.0.0.jar`
- `build/dist/yefira-fabric-mc1.20.2-1.20.4-1.0.0.jar`
- `build/dist/yefira-neoforge-mc1.20.2-1.20.4-1.0.0.jar`
- `build/dist/yefira-fabric-mc1.20.5-1.21.1-1.0.0.jar`
- `build/dist/yefira-neoforge-mc1.20.5-1.21.1-1.0.0.jar`
- `build/dist/yefira-fabric-mc1.21.2-1.21.4-1.0.0.jar`
- `build/dist/yefira-neoforge-mc1.21.2-1.21.4-1.0.0.jar`
- `build/dist/yefira-fabric-mc26.2-1.0.0.jar`
- `build/dist/yefira-neoforge-mc26.2-1.0.0.jar`

### 单独编译特定版本
如果仅需要编译某个特定大版本，可执行对应的单版本构建任务：
```bash
# 仅编译 1.20.1 (Fabric & Forge)
./gradlew build120

# 仅编译 1.20.4 (Fabric & NeoForge)
./gradlew build1204

# 仅编译 1.21.1 (Fabric & NeoForge)
./gradlew build121

# 仅编译 1.21.4 (Fabric & NeoForge)
./gradlew build1214

# 仅编译 26.2 (Fabric & NeoForge)
./gradlew build26
```

---

## 🤖 持续集成 (GitHub Actions CI)

项目配置了完整的多版本并行矩阵构建工作流 (`.github/workflows/build.yml`)：

1. **并行构建矩阵 (Parallel Matrix)**：
   - 每一个 PR 或 Push 提交都会同时启动 5 个独立 Runner：
     - **Job 1 (1.20.1)**: Ubuntu 24.04 + Temurin JDK 17
     - **Job 2 (1.20.4)**: Ubuntu 24.04 + Temurin JDK 17
     - **Job 3 (1.21.1)**: Ubuntu 24.04 + Temurin JDK 21
     - **Job 4 (1.21.4)**: Ubuntu 24.04 + Temurin JDK 21
     - **Job 5 (26.2)**: Ubuntu 24.04 + Microsoft JDK 25
2. **构建工件自动归档 (Artifact Staging)**：
   - 每个 Runner 自动筛选出该版本的最终可执行模组 Jar（自动排除 sources/javadoc）；
   - 上传带版本与 Git Ref 命名的 Artifacts 包，便于直接下载测试和发布 Release。

---

## 🎮 控制与快捷键 (Controls & Shortcuts)

| 快捷键 (默认) | 功能说明 | 适用场景 |
| :--- | :--- | :--- |
| <kbd>G</kbd> | 开启 / 关闭 **幽灵摄像机模式 (Ghost Mode)** | 全局 |
| <kbd>O</kbd> | 打开 **Yefira 设置与控制面板 GUI** | 幽灵模式下 (非幽灵模式可通过 `/yefira gui`) |
| <kbd>鼠标中键拖拽</kbd> | 围绕轴心旋转视角 (Orbit) | 幽灵模式下 |
| <kbd>Shift + 鼠标中键</kbd> | 视口平移 (Pan) | 幽灵模式下 |
| <kbd>滚轮</kbd> / <kbd>Ctrl + 中键</kbd> | 视口平滑缩放 (Zoom) | 幽灵模式下 |
| <kbd>鼠标左键拖拽</kbd> | 框选新区域 / 拖动坐标轴微调选区 | 幽灵模式下 |
| <kbd>F</kbd> / <kbd>小键盘 .</kbd> | 视口居中聚焦至当前选区 (Focus Selection) | 幽灵模式下 |
| <kbd>X</kbd> / <kbd>Delete</kbd> | 清空当前已选区域 | 幽灵模式下 |
| <kbd>C</kbd> | 在光标/枢轴处快速生成 16×16×16 预设区域 | 幽灵模式下 |
| <kbd>右键</kbd> / <kbd>ESC</kbd> | 取消当前正在拖拽的选区框选 | 幽灵模式下 |

---

## ⌨️ 游戏内指令 (Commands)

所有选区与管理命令开箱即用，**无需额外开启作弊**（单人游戏中默认全面开放）：

```text
/yefira pos1 [<x> <y> <z>]                  # 设置选区起点（不填坐标则为当前所在位置）
/yefira pos2 [<x> <y> <z>]                  # 设置选区终点
/yefira box <x1> <y1> <z1> <x2> <y2> <z2>   # 一键指定两点立体选区
/yefira box preset [<size>]                 # 以玩家为中心快速生成预设大小立方体选区（默认 16）
/yefira clear                               # 清空当前活动选区
/yefira status                              # 查看当前选区最小/最大坐标、尺寸与总方块数
/yefira refresh                             # 手动重新向 Blender 客户端广播全量快照
/yefira gui                                 # 快速呼出 Yefira 设置与控制面板 GUI
/yefira server <start|stop|restart|status>  # 控制/查看 WebSocket 服务运行状态
/yefira config                              # 查看当前配置（Host、Port、AutoStart）
/yefira config host [<ip>]                  # 查看或修改 WebSocket 监听 IP
/yefira config port [<port>]                # 查看或修改 WebSocket 端口 (1024-65535)
/yefira config autostart [<true|false>]     # 查看或切换进服自动启动
```

---

## ⚙️ 配置文件 (Configuration)

模组配置文件位于 `.minecraft/config/yefira.json`：

```json
{
  "host": "0.0.0.0",
  "port": 8765,
  "autoStartOnWorldLoad": false
}
```

* `host`: WebSocket 监听绑定的 IP 地址（默认 `0.0.0.0`，允许外部 DCC 工具连接）。
* `port`: WebSocket 端口（默认 `8765`，需与 Blender 插件设置一致）。
* `autoStartOnWorldLoad`: 进入单人世界或服务器启动时是否自动启动 WebSocket 服务。

---

## 📄 开源许可 (License)

本项目采用 [GNU General Public License v3.0 (GPL-3.0)](LICENSE) 许可开源。
