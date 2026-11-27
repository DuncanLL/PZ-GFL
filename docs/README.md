# 源码导航

[维护手册](maintenance/README.md) 记录游戏状态、公共动作修正、UI 交互与多人同步以及多光影后端的运行链路。工程迭代摘要见 [技术迭代记录](../CHANGELOG.md)，项目总览与合规声明见 [项目首页](../README.md)。

| 模块 | 位置 |
| :--- | :--- |
| 骨骼动作、姿态重定向、面部表情与物理接口 | [`game/java/gfl2/motion`](../game/java/gfl2/motion) · [`game/java/gfl2/runtime`](../game/java/gfl2/runtime) |
| 原生 C++ / Rust / OpenCL 骨骼与软体物理核心 | [`native/`](../native) |
| 模块化 UI、语音路由、兼容层与多人同步脚本 | [`game/mod/42/media/lua`](../game/mod/42/media/lua) · [多人协议记录](multiplayer-framework.md) |
| OpenGL / GLSL 渲染支持与离屏 3D 预览管线 | [`render-support/`](../render-support) · [渲染发布检查标准](render-release-standard.md) |
| 自动化构建与无头回归校验工具 | [`tools/`](../tools) · [历史技术笔记](handoffs) |
