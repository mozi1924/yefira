# GEMINI.md - Yefira (Live Sync Mod) 协作与开发规范

本文档是供所有 AI Coding Agent 以及核心开发者在 `yefira` 工作区阅读的开发规范与架构准则。

---

## 1. 项目定位与工作区边界

`Yefira` 是专为 **Minecraft** 与 **Blender ([MoziToolKit](../MoziToolKit))** 打造的高性能、低延迟实时双向同步模组（Fabric / Forge / NeoForge），作为三端协同体系中的服务端事实源。

### 多工作区协同拓扑 (Three-Pillar Architecture)
| 工作区路径 | 角色 | 职责边界 |
| :--- | :--- | :--- |
| **`../yefira`** | **实时协同服务端模组 (当前工作区)** | 负责 Minecraft 服务端权威 3D 体素扫描 (`CanonicalVoxelScanner`)、XXHash64 校验码生成、增量分片推送、选区管理与 WebSocketServer 通信（支持 1.20.1 ~ 26.2 多版本）。 |
| **`../libmozitoolkit`** | **Rust 核心引擎 (`libmtk`)** | 消费 Yefira 推送的 Protocol v3 二进制帧，负责握手裁决、状态机维护、增量网格化 (`DeltaMesher`) 与双通道图集重映射。 |
| **`../MoziToolKit`** | **DCC 插件前端 (Blender Addon)** | 提供 3D 视图交互面板、点云伴生网格装配、选区射线交互与操作符事件响应。 |

---

## 2. 核心规约与必须遵守的铁律 (Golden Rules)

### 规则 1：服务端权威原则 (Server-Authoritative Sync)
- 世界体素状态、选区边界（SelectionBox）有效性、区块加载状态与变化检测**以 Minecraft 服务端为唯一事实源**。
- 采用 `CanonicalVoxelScanner` 与 `XXHash64` 进行 3D 空间扫描与哈希指纹计算，确保客户端与服务端的体素状态具备自愈校验能力。

### 规则 2：协议对齐原则 (RFC v3.1 Compliance)
- 所有 WebSocket 帧调度、握手流程（`HELLO` / `MANIFEST`）、二进制体素增量、选区同步必须严格对齐 **[RFC v3.1: MTK 2.0 Live Sync 协议与架构设计规范](../MoziToolKit/docs/live_sync/live_sync_protocol.md)**。
- 严禁在未同步更新 RFC 规范及各端实现的情况下擅自变动数据包格式与操作码。

### 规则 3：跨版本解耦与架构分代隔离 (Multi-Version Separation)
- **纯核心层共享**：`common-core` 必须保持无具体版本依赖，所有通用通信协议（`network/`）、选区几何数学（`selection/`）、配置项（`config/`）与编码器（`encoder/`）均在此实现。
- **分代兼容隔离**：所有针对具体 Minecraft 版本的 API 适配（如 `LevelChunkMixin`、渲染接口、按键输入）必须严格收敛在对应 `versions/` 子工程中：
  - `versions/1.20.1` (Java 17, Fabric & Forge)
  - `versions/1.20.4` (Java 17, Fabric & NeoForge)
  - `versions/1.21.1` (Java 21, Fabric & NeoForge)
  - `versions/1.21.4` (Java 21, Fabric & NeoForge)
  - `versions/26.2` (Java 25, Fabric & NeoForge)
- 严禁在 `common-core` 中引入任何特定版本的混淆名（Mojang / Yarn / Intermediary）或硬依赖。

### 规则 4：增量分片与协同并发安全 (Chunked Deltas & Thread Safety)
- 针对大规模选区更新，必须采用分片批处理（Batch Chunking），杜绝单帧网络包体积膨胀导致 Netty 缓冲区溢出。
- 体素扫描与 WebSocket 帧发送必须遵循协作式取消（Cooperative Cancellation）与有界线程池设计，严禁阻塞 Minecraft 主服务线程（Server Tick Thread）。

### 规则 5：安全与鉴权规约 (Security & Local Priority)
- WebSocket 通信支持 Auth Token 认证机制。
- **Loopback 本地优先**：默认配置下，对于来自本机的 Loopback 连接（127.0.0.1 / localhost），允许根据配置安全免密直连，降低本地创作流程摩擦；外部网络连接必须强制核验 Token。
- 选区存储必须保证线程原子性（Atomic Selection Storage），具备防越界与选区软上限警告机制。

### 规则 6：构建与测试门禁 (Build & Test Gates)
- 提交前必须保证跨版本工程能顺利编译且单元测试全绿：
  ```bash
  ./gradlew test
  ```
- 涉及网络包格式调整时，必须同步在 `common-core/src/test/java/` 中增加对应的编解码与集成测试。

---

## 3. 项目架构概览

```
yefira/
├── common-core/                        # 跨版本共享纯逻辑核心
│   ├── network/                        # WebSocketServerManager 通信管理与帧调度
│   ├── selection/                      # SelectionBox 选区数学几何与原子状态
│   ├── config/                         # YefiraConfig 统一配置与 Token 鉴权
│   ├── encoder/                        # 权威调色板压缩与 DTO 序列化
│   └── compat/                         # 跨版本通用抽象契约
├── versions/                           # 针对具体 MC 版本的加载器绑定
│   ├── 1.20.1/                         # Fabric & Forge
│   ├── 1.20.4/                         # Fabric & NeoForge
│   ├── 1.21.1/                         # Fabric & NeoForge
│   ├── 1.21.4/                         # Fabric & NeoForge
│   └── 26.2/                           # Fabric & NeoForge
└── build.gradle                        # 根工程聚合构建
```

---

## 4. 相关文档与协议索引

- **协议权威规范**：[`../MoziToolKit/docs/live_sync/live_sync_protocol.md`](../MoziToolKit/docs/live_sync/live_sync_protocol.md)
- **Rust 客户端实现**：[`../libmozitoolkit/crates/mtk-sync/`](../libmozitoolkit/crates/mtk-sync/)
- **Blender 操作符**：[`../MoziToolKit/operators/sync/`](../MoziToolKit/operators/sync/)
