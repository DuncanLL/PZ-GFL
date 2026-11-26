# 维护手册

从 `main` 接手。2026-11-26 核对的线上公开版本是 **0.8.2**，框架条目 **3842318650**，共 **32 个角色包**、**87 套整备室造型**（核心注册表登记 88 项）与 **32 把专武**；`main` 另含 `0.8.2` 之后已本地构建验证的后续修复（见 [版本记录](../../CHANGELOG.md)）。框架与角色包各有自己的版本号。旧框架条目 3838709437 仅存草稿，内部 ID 同为 `GFL2Exilium`，排查时以游戏日志中的实际加载路径识别。[0.8.2 发布回执](../../provenance/releases/0.8.2-workshop-public.json) · [现行工坊条目索引](../../provenance/workshop-items.json)

1. 核实版本时，先用发布回执的 `framework_manifest` 对 Steam 内容清单，用 `framework_jar_sha256` 对订阅副本。Git 保存源码、资源指纹和回执。模型、原材质、贴图、语音及构建工具链按[构建手册](workflow-build-checks.md)从外部读取。
2. 复核完整订阅目录可运行 `py -3 tools/check_subscribed_release.py "<Steam 库>/steamapps/workshop/content/108600"`。它只读核对框架和角色包，要求本机已下载整套合集。修改源码后验证候选的入口见[维护与验证](verification.md)。
3. 全部 32 个已发布角色均已收录到整备室工坊按钮映射 `roomWorkshopItems` 及[工坊条目索引](../../provenance/workshop-items.json)；后续新增或调整角色入口时同步核对两处。
4. 历史排查与当前边界：光影目录未生成排查见[分发排查](rendering.md#两个光影目录都没有生成)；`main` 已修复整备室预览 `FarRelief`/`D70` 冲突及原版视角 VS3 `VSDynamics` 皮肤法线错乱。Viewpoint 3D 视角下偶发手持武器隐形、NVIDIA／Intel 全矩阵实机、真实双客户端联机表现和早期原生切人崩溃仍各需独立证据。

| 要查的问题 | 入口 |
|---|---|
| 从裸 PMX 制作一套衣装并复核结果 | [导入与标定](pmx-import-workflow.md) · [原材质与三角形映射](source-material-workflow.md) · [双后端构建和验收](workflow-build-checks.md) |
| 走跑蹲、翻窗跨栏、空手、持枪与瞄准、第一人称、车内、暂停和 VMD | [游戏中的动作、持物和物理](gameplay.md#各种游戏状态) |
| 换人失败、角色重载、姿态异常、刚体和胸部软体 | [模型会话](gameplay.md#从游戏角色到模型会话) · [异常恢复](gameplay.md#两个需要保留的异常恢复) |
| 角色包、整备室、招募和专武、保存、联机握手、语音状态 | [人物数据、整备室、联机与语音](character-systems.md) |
| 光影安装、材质接入、Native、Somber、Eclipse、透明和阴影、切包退役 | [Viewpoint、Native、Somber 与 Eclipse 光影](rendering.md) |
| 适配版本、选验证用例、工坊打包、回退及旧证据边界 | [维护与验证](verification.md) |

以前的修正和失败证据留在[版本记录](../../CHANGELOG.md)、[发布凭据](../../provenance/releases)和各章链接中。当前行为以源码、实际目标版本和现用输入为准。源码和资源文件之间的角色 ID、包键、PMX 指纹、Shader 记录格式及存档字段属于跨版本契约。
