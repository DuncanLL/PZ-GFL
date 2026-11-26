# 僵毁少女前线

已在 Steam 创意工坊正式发布。当前线上框架 **0.8.2**，内部 MOD ID 为 `GFL2Exilium`；共 **32 个角色包**、**87 套整备室造型**（核心注册表登记 88 项）、**32 把专武**及 **5,536 条日语配音与四国字幕**，按需订阅。正式框架分发 Native 和 Somber，Eclipse 保留在维护构建中。当前核对的环境为 Project Zomboid 42.22.2、官方 Project Viewpoint 0.1.7 和 ZombieBuddy 2.3.4。

> 注：`main` 分支包含 `0.8.2` 之后已提交并完成本地构建验证的后续修复与优化（整备室原材质预览 `FarRelief`/`D70` 隔离、暗石墨+少前2橘黄 UI 与统一 VMD 面板、原版视角 VS3 `VSDynamics` 兼容修复等），详见 [版本记录](CHANGELOG.md)，尚未执行下一次 Steam 创意工坊上传。

- [框架工坊项目](https://steamcommunity.com/sharedfiles/filedetails/?id=3842318650)
- [32 个角色包与框架的订阅合集](https://steamcommunity.com/sharedfiles/filedetails/?id=3841234557)
- [版本记录](CHANGELOG.md)
- [0.8.2 线上清单、框架 JAR 指纹与角色更新记录](provenance/releases/0.8.2-workshop-public.json)
- [维护手册](docs/maintenance/README.md) · [源码导航](docs/README.md) · [光影发布检查](docs/render-release-standard.md)
- [从 PMX 标定、原材质接入到 Native / Eclipse 合包的实作流程](docs/maintenance/pmx-import-workflow.md)

## 本机使用

从 [路径模板](config/paths.example.json) 创建 `.local/paths.json`，填写实际游戏、ZombieBuddy、Viewpoint、编译工具和外置人物资源位置。模型、贴图、语音、生成包与原始日志留在本机。版本核对以工作区提交、实际 `mod.info` 和产物指纹为准。

人物核心由 `tools/build.py` 构建。使用 `tools/package_workshop.py` 准备人物输入，用 `tools/build_combined_render_support.py` 合并渲染后端输入，最终由 `tools/package_gfl2_unified.py` 生成框架工坊候选；日常一键构建、全量校验与本地目录同步可直接运行 `tools/sync_framework.py`。发布目录采用 `Workshop/GFL2Exilium`，保留现有工坊项目 ID。工具的参数和输入检查以源码为准。

## 维护范围

角色与功能按定版维护，日常工作集中在游戏和光影适配、第三方模组兼容、四国语言本地化及复现明确的缺陷。版本记录分别注明组件检查、现装版本、Steam 发布和实机验证。
