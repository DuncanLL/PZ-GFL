# PZ-GFL — Project Zomboid Build 42 模块化角色渲染与物理技术框架

> **原创开发者主页（爱发电 / Afdian）**：[https://ifdian.net/a/CRASHCRASHCRASH](https://ifdian.net/a/CRASHCRASHCRASH)  
> **仓库定位**：《僵尸毁灭工程 (Project Zomboid) Build 42》开源 / 免费模块化角色渲染、骨骼动作、软体物理与自定义 UI 技术框架（**纯源码仓库，不含任何第三方受版权保护的美术、模型或音频资产**）。

---

## 项目简介

本项目是面向 **Project Zomboid Build 42（42.22.2）** 编写的个人原创技术框架源码仓库，基于 Java 运行时注入（ZombieBuddy）、原生 C++/Rust/OpenCL 骨骼与物理核心（Ozz Animation + Bullet3）以及 OpenGL / GLSL 渲染管线适配，为游戏提供模块化外部角色挂载、实时软体物理、自定义着色器集成、多人同步状态机与交互式界面能力。

本仓库完整保留自 `2026-02` 起的工程迭代提交记录，所有第三方二进制资产（模型、贴图、立绘、音频等）均严格排除在仓库与构建源码之外。

---

## 核心技术特性

### 1. 原生骨骼评估与软体物理引擎（`native/`）
- **独立原生引擎（C++ / Rust / OpenCL）**：集成 `ozz-animation` 骨骼层级求值与 `Bullet3` 刚体 / 软体碰撞求解，支持高频物理步进与多层碰撞掩码隔离。
- **GPU 顶点蒙皮与形变**：结合 OpenGL / Compute Shader 管线处理蒙皮矩阵与局部软体形变，降低主线程 CPU 负载。
- **零泄漏生命周期管理**：跨存档切换、回主菜单重载与退出阶段自动释放原生句柄与缓冲对象。

### 2. Java 运行时与双视角渲染管线集成（`game/java/`、`render-support/`）
- **原版等距视角 & 3D 视角双通道渲染**：统一角色会话与姿态快照采样，兼容第三方身形/动画类模组（隔离第三方着色器 Uniform 污染与骨骼轴向扭转修正）。
- **自定义着色器与光影后端适配**：支持 `Native`、`Somber` 等多渲染后端接入，提供严格 OpenGL 状态保护（`Gfl2GlScope`）与纹理槽位隔离。
- **实时多角度 3D 预览与画中画交互（Studio Preview / Portrait HUD）**：内置离屏 FBO 3D 实时预览渲染器（LRU 显存缓存与闲置自动回收）及多角度小窗观察能力。
- **实时面部表情与待机微动作系统**：支持骨骼待机微动作混合、自然手指级联姿态、随机眨眼、发声同步口型与瞄准/动作状态表情驱动。
- **交互式握持与骨骼校准工具**：游戏内实时调节手持物体坐标、旋转与多节指骨弯曲参数，并支持本地跨档持久化。

### 3. 模块化 Lua UI、联机同步与生态兼容架构（`game/mod/42/media/lua/`）
- **模块化内容包挂载协议**：核心框架与外部扩展包完全解耦，运行时按启用清单动态发现、校验与加载扩展模块。
- **多人联机服务端权威同步与实时落盘持久化**：提供客户端-服务端状态握手、远端外观独立校验、纯 Lua JSON 双文件热备落盘防回档机制。
- **多语言文本与 3D 空间语音路由框架**：支持 CN / EN / JP / KO 四语本地化架构、头顶实时字幕排版与联机远端 3D 空间事件路由。
- **自定义交互界面与轮盘菜单**：包含可折叠模块化主控面板、分栏客户端实时设置页、游戏原生沙盒选项配置及 `Q` 键二级轮盘菜单接入。
- **第三方武器/配件生态兼容层**：提供面向主流第三方武器模组的自动化弹匣、口径、射速与外观数据无侵入兼容适配。

### 4. 自动化构建与无头回归校验工具链（`tools/`）
- **可复现构建脚本**：固定归档时间戳与 SHA-256 指纹校验，自动编译 Java 运行时 JAR、原生引擎与光影支持包。
- **Kahlua / JVM 无头测试套件**：覆盖联机服务端状态机、客户端握手与回退、本地化完整度、多模组兼容断言与内存/句柄泄漏检测。

---

## 仓库目录结构

| 目录 / 文件 | 说明 |
| :--- | :--- |
| [`native/`](native) | C++ / Rust / OpenCL 原生骨骼求值与 Bullet3 物理引擎源码 |
| [`game/java/`](game/java) | Java 运行时补丁、动作重定向、面部表情、实时预览与握持校准源码 |
| [`render-support/`](render-support) | OpenGL / GLSL / Compute Shader 渲染支持与离屏预览渲染器源码 |
| [`game/mod/42/media/lua/`](game/mod/42/media/lua) | 游戏内模块化 UI、联机协议、语音路由与第三方模组兼容 Lua 脚本 |
| [`tools/`](tools) | Python 自动化构建、打包校验与无头回归测试套件 |
| [`docs/`](docs/README.md) | 架构设计、渲染管线、联机协议与维护技术文档 |
| [`config/paths.example.json`](config/paths.example.json) | 本地构建工具链与外部资源路径配置模板 |
| [`licenses/`](licenses) | 第三方开源依赖（Ozz Animation、Bullet3、Rust Crates 等）许可声明 |

---

## 本地构建说明

1. 复制 [`config/paths.example.json`](config/paths.example.json) 为 `.local/paths.json`，按本机环境填写 Project Zomboid 安装目录、ZombieBuddy、编译器与本地测试目录。
2. 运行 `tools/build.py` 编译 Java 运行时组件；运行 `tools/check_multiplayer.py`、`tools/check_runtime.py` 等脚本执行无头回归校验。
3. 所有模型、贴图、音频、构建产物与本地日志均通过 `.gitignore` 严格隔离在本机，不进入代码仓库。

---

## 合规与版权声明

- **个人业余非商业开源开发**：本项目由个人业余时间独立编写与维护，相关模组在 Steam 创意工坊等平台均 **100% 免费发布**，不设任何付费解锁、收费模组或定制售卖行为。
- **零第三方版权资产**：本代码仓库 **仅包含本人原创的程序源代码、着色器脚本、UI 逻辑、配置模板与技术文档**，不包含、也不分发任何第三方受版权保护的 3D 模型、美术立绘、贴图或音频文件。
- **爱发电赞助说明**：开发者爱发电主页（[https://ifdian.net/a/CRASHCRASHCRASH](https://ifdian.net/a/CRASHCRASHCRASH)）仅用于对个人业余编程、引擎适配与长期维护时间的**纯自愿无偿支持**，不与任何第三方 IP 资产、模型或付费内容挂钩。
- **第三方开源组件许可**：本框架引用的开源库（如 `ozz-animation`、`bullet3` 等）均遵循各自原始开源协议，详见 [`licenses/`](licenses) 目录。
