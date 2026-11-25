# 版本记录

当前项目为「僵毁少女前线」(`GFL2Exilium`)。维护目标是兼容更新、四国语言本地化及复现明确的缺陷。角色、衣装、材质与动作按定版维护。

游戏动作、人物状态和光影接线详见 [维护手册](docs/maintenance/README.md)。

## 0.8.2 · 樱花、琼玖与海伦娜接入、1:1 原游身高体型还原、实时面部表情与 13 套站立待机小动作（2026-11-25）

- 新增 3 名角色、5 套衣装、2 把专武与 346 条专属日语语音（全模组总计 **33 人 / 88 外观 / 32 专武 / 5,536 条日语语音与四国字幕**）：
  - 新角色 **海伦娜**（`GFL2Helena`）：原游特别可操控单位，默认外观免费直接解锁（单人/联机均无需抽取、无专武/皮肤/语音负担，简介留白），完整接入 GF2 DanRP 原材质光影（`shading-danrp.json` + `uv1-danrp.f32` + 27 组原生 GLSL 着色器）、原游 1:1 娇小身高、实时面部表情以及原游官方 2D 封面／海报／双头像。
  - 新角色 **樱花**（`GFL2Sakura`）：原皮「非常规配送」、衣装「蝶影绮谭」（含配饰与脱鞋丝袜部件开关）、专武「绯红契约」（`GFL2.CrimsonCovenant`）及 8 级契约涂装「涂装·心速共鸣」（塔科夫 UZI PRO 室内消音枪声 + 原厂机械音）、173 条专属日语语音与 CN / EN / JP / KO 四语字幕（双外观均已绑定）、原游官方 2D 封面／海报／双头像／招募卡。
  - 新角色 **琼玖**（`GFL2Qiongjiu`）：原皮「云辉定仪」（补齐胸部软体物理 `chest.bin`）、衣装「义薄云天」（含脱鞋部件开关）、专武「金石奏」（`GFL2.GoldenMelody`）及 8 级契约涂装「涂装·神形兼备之美」（塔科夫 RPK-16 室外消音枪声 + AK-74 机械音）、173 条专属日语语音与 CN / EN / JP / KO 四语字幕（双外观均已绑定）、原游官方 2D 封面／海报／双头像／招募卡。
  - 清理两包未引用 `normalmap/` 冗余资源共瘦身 78.8 MB，并刷新 [GFL2MultiplayerCatalog.lua](game/mod/42/media/lua/shared/GFL2MultiplayerCatalog.lua) 联机目录。
- **全员 1:1 原游真实身高与三维体型还原**（零运行时性能开销）：
  - 在 [PmxModel.java](game/java/gfl2/motion/PmxModel.java) 与 [ModelSession.java](game/java/gfl2/runtime/ModelSession.java) 统一全员缩放标尺 `CANONICAL_UNIT_SCALE = 20.5f`，不再按原版女角色骨架强行抹平高矮，完整还原少前2原游约 24.5% 真实身高与肩宽/头身比差异（娇小档如夏安/米什缇/樱花/索米、中等档如可露凯/卡萝/莱娅、偏高档如阿斯缇亚/春田/琼玖、高挑御姐档如罗蕾莱/黛烟/纳美西丝），联机双端一致且武器／背包保持原版 1:1 尺寸。
- **角色动态鲜活度全面升级（待机小动作 + 实时面部表情）**：
  - **13 套烘焙站立待机小动作**（[IdleAmbientMotion.java](game/java/gfl2/runtime/IdleAmbientMotion.java) + `idle_clips.bin`）：空手站立静止一段时间后自然触发 13 套量化骨骼微动作（含玩手机 `#26`、俏皮舒展 `#30` 等），搭配自然三节手指放松级联（[IdleHandPose.java](game/java/gfl2/runtime/IdleHandPose.java)）、呼吸起伏与持枪轻幅环顾；常规站立基座保持静止不乱晃。
  - **实时面部动态表情系统**（[LiveFacialMotion.java](game/java/gfl2/runtime/LiveFacialMotion.java)）：自动接入全员 PMX 变形目标，支持自然随机眨眼、语音播放与聊天发话同步口型（含 [VoiceEngine.lua](game/mod/42/media/lua/client/GFL2Voice/VoiceEngine.lua) 实时发声状态联动），以及举枪瞄准微闭单眼/专注皱眉与近战攻击表情，零额外配置自动适配新老角色。
- **全量皮肤定价按原游商店校准**：
  - 直接按《少女前线2：追放》`StoreGoodData` 信用币官方原价校准 [catalog.json](game/mod/42/media/ui/GFL2ReadyRoom/catalog.json) 全部 47 套直购衣装定价（原游直购档位 `980 / 1080 / 1280 / 1580 / 2580`，活动/班组类衣装维持 `600`，既有 45 套中校准 37 套、新增樱花/琼玖衣装均为 `1280`）。
- **核心动作、兼容与稳定性修复**：
  - 修复第三方动画模组手臂异常轴向扭转（拧麻花）问题，自动将上臂/前臂扭转分配到 PMX `腕捩`/`手捩` 辅助骨（[WorldPoseRetargeter.java](game/java/gfl2/motion/WorldPoseRetargeter.java)）。
  - 修复 AMD 显卡在整备室原材质 3D 预览（`Gfl2StudioPreviewRenderer`）下的画面闪烁与状态泄漏问题（[Gfl2GlScope.java](render-support/java/viewpoint/render/Gfl2GlScope.java)、[Gfl2StudioPreviewRenderer.java](render-support/java/viewpoint/render/Gfl2StudioPreviewRenderer.java)）。
  - 修复原版斜 45° 渲染管线与 Tomb's Player Body / VS 可变身形（Variable Skin）等角色身形模组的兼容性问题（[VanillaWorld.java](game/java/gfl2/runtime/VanillaWorld.java)、[VanillaWorldPatch.java](game/java/gfl2/runtime/VanillaWorldPatch.java)）。
  - 修复 **米什缇**（G11）新衣装「迷盹睡鼠」（`pmx-2a37b36707a4f3f2`）遗漏语音绑定导致不发声的问题（[ZZ_GFL2PmxMechtyVoice.lua](game/packs/GFL2Mechty/42/media/lua/client/ZZ_GFL2PmxMechtyVoice.lua)）。
  - 修复整备室「握持校准」预览采样未叠加实况站立姿态的问题（[GripRoomPreview.java](game/java/gfl2/runtime/GripRoomPreview.java)），并修复维克托专武「报丧轻语」临时枪声声明问题（[GFL2BansheesWhisperSounds.txt](game/packs/GFL2Vector/42/media/scripts/GFL2BansheesWhisperSounds.txt)）。
  - 全量 32 把专武与 8 级契约涂装的 3D 武器贴图（64 张，含原 4K/2K 贴图）统一无损等比降采样至上限 `1024px`（`1024×1024` / `1024×512`），专武显存峰值下降 **79.9%**（`1,096 MB → 220 MB`）、贴图体积减少 **52.1%**。

## 0.8.1 · 卡萝与米什缇新装接入、第一人称遮挡修复、设置页／沙盒／Q 轮盘／VMD 镜头与兼容升级（2026-11-24）

- **⚠ 已知问题（暂未解决）**：部分玩家在 Viewpoint 3D 视角下可能偶发手持武器隐形（切回原版斜 45° 视角正常可见）；该问题目前尚未定位根因、暂未修复（关闭 ProjectViewpointADS 等模组后仍有玩家遇到，尚不确定是 Viewpoint 本体、第三方模组交互还是其它触发条件所致），后续持续排查。
- 新增 1 名角色、3 套外观、1 把专武与 173 条专属日语语音（全模组总计 30 人 / 83 外观 / 30 专武 / 5,190 条日语语音与四国字幕）：
  - 新角色 **卡萝**（`GFL2Kuro`）：原皮「奔涌霓虹」、衣装「酒会观测者」、专武「阈限风暴」（`GFL2.ThresholdStorm`）及契约涂装「涂装·奔涌霓虹」、173 条专属日语配音与 CN / EN / JP / KO 四语字幕（双外观均已绑定）、官方抽卡横幅与四国语言本地化。
  - **米什缇**（`GFL2Mechty`）新增衣装「迷盹睡鼠」。
- 顶栏 2 号齿轮 `[⚙]` 与分栏人物设置页：
  - 替换 `room-settings.png` 为 128×128 8× 超采样八齿镂空齿轮并移至整备室顶栏右上角（信用币 `+` 与 `^` 之间），未启用角色也可随时点开或收起。
  - [Tabs.lua](game/mod/42/media/lua/client/GFL2Room/Tabs.lua) 设置页分为三组（单机显示前两组，联机 `isClient()` 自动显示第三组）：**语音设置**（语音音量 0–100、头顶字幕开关）、**物理模拟**（自身胸部物理 0–100%、衣发物理开关）、**联机显示（其他玩家）**（远端角色显示开关、远端胸部物理 0–100% 默认关、远端衣发物理默认关、远端日语语音与字幕开关、沙盒提示与一键恢复默认）；全部修改实时生效并持久化到本地 `GFL2Exilium/client-settings.json` 跨档保留。
- 5 项 PZ 原生沙盒设置（[sandbox-options.txt](game/mod/42/media/sandbox-options.txt) 及 CN / EN / JP / KO 四语 `Sandbox.json`）：新增 `AllowHoldGrant`（允许长按加号补充资源，默认开）、`FreeUnlockAll`（全角色与衣装免费解锁，默认关）、`CreditsPerCash`（钞票兑换信用币汇率 10–5000，默认 300）、`AllowSignatureWeapons`（允许整备室获取专武，默认开）、`AllowAmmoAndMagazines`（允许买弹药与领弹匣，默认开），已打通服务端与客户端双端校验。
- 按住 `Q` 轮盘菜单与 VMD 面板升级：
  - 新增 [GFL2RadialMenu.lua](game/mod/42/media/lua/client/GFL2RadialMenu.lua)，在原版 `Q` 表情轮盘末尾零冲突追加「僵毁少女前线」入口及 6 项二级轮盘（打开整备室、重置物理、衣发物理开关、VMD 面板、握持校准、人物设置），配 6 张 64×64 8× 超采样金属战术图标与四语文案。
  - VMD 面板取消长按隐藏后门改为常规常驻入口，新增 VMD 镜头轨道读取功能（`VmdCamera` / `VmdCameraController` / `VmdCameraPatch`），并修复 Viewpoint 0.1.7 跨加载器调用 `VmdCameraController` 的权限报错；握持校准新增大拇指三节指骨弯曲校准（`親指0/1/2`）。
- 原材质全身预览、光影描边与整备室 UI 标准化：
  - 整备室及物品栏 3D 预览接入少前2原材质渲染管线（`Gfl2StudioPreviewRenderer`，严格 LRU-2 缓存、闲置自动回收显存、设置页可控开关）。
  - `Native-GFL2` / `Somber-GFL2` 远景描边按画面垂直占比自动收敛（`outlineDistanceScale = clamp(ndcPerMeter / 0.72, 0.16, 1.0)`）抵消 1.2px 硬底限，描边为 0 时彻底跳过描边 Pass，并调整自定义光影参数范围与中文选项说明。
  - 统一全部 30 名角色展开三列方底头像（`grid-*.png`）、左侧纵列圆形头像与模组列表透明头像，修复整备室角色长简介文本自动换行与排版对齐。
- 武器与运行环境兼容增强：
  - [GFL2SignatureMagazines.lua](game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua) 补齐 Guns of Marz（新版 `GunsOfMarz` 与旧版 `MarzGuns`）全 6 口径及 28 把远程专武/25 个专属弹匣兼容（无需开新档），[GFL2AutomaticCadence.lua](game/mod/42/media/lua/shared/GFL2AutomaticCadence.lua) 补录卡萝专武「阈限风暴」全自动射速，并补充 [gom-compat-fixture.lua](tools/tests/gom-compat-fixture.lua) 回归断言。
  - 新增 CN / EN / JP / KO 四语 Java 运行环境实时自检弹窗（[GFL2CharacterRuntime.lua](game/mod/42/media/lua/client/GFL2CharacterRuntime.lua)）：当 `GFL2Exilium.jar` 未加载时进档自动弹窗提醒一次，右键地面菜单与 `Q` 轮盘入口均可随时点开查看 ZombieBuddy 模组/运行时检测状态与授权重启步骤。
- 专武数值、音效与快捷栏图标标准化：
  - 修正卡萝专武「阈限风暴」（黑 DT / MDR 7.62）为 `.308` 口径、25 发专属弹匣、高威力 `.308` 伤害与 650 RPM 射速，并同步 EFZ `MDR762` 与 Guns of Marz `.308` 兼容。
  - 全部 28 把远程专武统一换装《逃离塔科夫》一枪一音差异化枪声与配套原厂换弹 / 退匣 / 拉栓 / 泵动 / 空仓击锤机械音（28 把 × 5 音效槽位共 280 条单声道 44.1kHz WAV，按室内/室外逐枪听感定版并分档标定响度，全自动枪 `maxInstancesPerEmitter = 24` 彻底消除连发尾音截断，与原版及 SWMG / EFZ / MFS / Guns of Marz 零冲突）。
  - 修复伊格蕾塔（沙鹰）、托洛洛、纳美西丝、卡萝、米什缇 5 个新包及本地 `mods/` 22 个旧包的专武快捷栏图标爆大问题（全部 30 个角色包统一为 `32×32` 物品图标 + `WeaponIcons/` 高清原图双轨标准），并新增 [check_weapon_icons.py](tools/check_weapon_icons.py) 自动校验门禁。
- 第一人称视线遮挡修复与渲染／封面标准化：
  - 修复第一人称下角色头发、帽子及头饰遮挡视线的问题：在模型加载阶段按头部与头发骨骼子树权重自动划分独立三角面分区，第一人称色彩通道精确隐藏头/发/帽，同时完整保留地面投影与第一人称身体／双臂显示（全 84 套模型零泄漏实测通过）。
  - 修复整备室原材质 3D 预览 `Gfl2StudioPreviewRenderer` 在退出时对 OpenGL 纹理单元 1 与 SSBO 1–3 的状态泄漏，新增 `Gfl2GlScope.Phase.STUDIO` 零泄漏状态保护。
  - 统一全部 30 个角色分包 2D 特写封面、透明半身海报与图标，主框架换装卡萝 `EXILIUM 2` 新封面，并接入 [check_pack_images.py](tools/check_pack_images.py) 自动校验门禁。

## 0.8.0 · 托洛洛／伊格蕾塔／纳美西丝接入、25 套衣装胸部物理恢复与联机／兼容大更（2026-11-23）

- 新增 3 名角色、8 套外观与 3 把专武（全模组总计 29 人 / 80 外观 / 29 专武 / 5,017 条日语语音与四国字幕）：托洛洛（`3844749849`，原皮「灰域流星」、衣装「暮夜天河」「晴空巡航」、专武「游星」及契约涂装「亘古的恒星」）、伊格蕾塔（`3844750567`，原皮「苍漠孤鹰」、衣装「玫瑰莺语」含部件开关、专武「银翼匹丝缇」及契约涂装「万里相随」）、纳美西丝（`3844750305`，原皮「星图演绎」、衣装「纳美西丝的训练服」「静谧暗河」、专武「西比尔」及契约涂装「棱镜真言」）。
- 扩充 `tools/chest_model_data.py` 胸部骨骼识别，为 13 名既有角色共 25 套衣装恢复／接入胸部软体物理并同步发布分包更新。
- 联机修复与同步增强：合并 B 站 **Aryun_** 协助提交的多人实测排查与同步思路，重构进服预加载、断线会话清理与按单套外观独立校验；新增联机队友 3D 空间日语语音与头顶四语字幕同步；握持与瞄准校准本地跨档持久化。
- 动作、兼容与画面修复：从 `WorldPoseRetargeter` / `FurnitureSeat` 底层修复原版及第三方动作模组（Lifestyle、True Actions、Easy Sleep 等）躺床钻床底与坐姿悬空问题；新增现代枪械系统（Modern Firearms System）兼容（现兼容 原版 / EFZ / GOM / MFS，专武不支持开镜）；修复原版第三人称缩放 3D 物品矩阵缓存失步、角色闪烁、穿墙悬浮枪械轮廓与 DLSS 超分粗黑边；翻新整备室 UI 并新增保底剩余抽数显示。[工坊发布与验证记录](provenance/releases/0.8.0-workshop-public.json)

## 0.7.33 · 人物设置页面、语音音量/胸部物理调节与整备室展示光影升级（2026-11-21）

- 新增整备室「人物设置」二级页面：将原一级菜单物理开关迁入统一管理，支持角色语音音量调节（`0–100`，默认 `50`、`0` 静音）、胸部物理效果调节（`0–100`，`0` 彻底关闭且零性能占用）以及独立的衣服与头发物理开关，并补齐四国语言翻译。
- 升级整备室人物与专武展示融合效果（[ReadyRoomPreview.java](game/java/gfl2/runtime/ReadyRoomPreview.java)、[GFL2RoomWindow.lua](game/mod/42/media/lua/client/GFL2RoomWindow.lua)）：修正预览逆光并加入冷暖环境光、金属地板倒影、跟随转向的双脚接触投影、28° 长焦微透视，以及专武机库顶灯聚光晕与悬浮投影。[工坊发布与验证记录](provenance/releases/0.7.33-workshop-public.json)

## 0.7.32 · 米什缇接入、专武平衡与第三人称持枪修复（2026-11-20）

- 新增角色米什缇（G11，工坊 `3842869667`）、专武「白日梦」及 173 条专属日语语音，并同步加入角色合集与简介头像墙。
- 放大专武抽取界面展示比例，统一 26 把专武预览尺度与闪电「金石奏」官方朝左 2D 卡图/图标，补齐芙洛伦、米什缇高清抽卡图标。
- 平衡调整全部 26 把专武的伤害、射程、多目标穿透、消音音量与射速判定。
- 修复 Viewpoint 0.1.7 第三人称持枪随镜头乱转及光照异常，并修复坐姿等第三方动作下锁骨异常扭转耸肩。
- 优化整备室交互：已解锁角色置顶、恢复按钮更醒目、握持校准新增站立/瞄准切换、VMD 新增文件夹浏览与收藏置顶。
- 修复整备室窗口折叠（点击右上角 `-` 号）或被隐藏后，右键地面点击「僵毁少女前线」无法重新展开界面的问题。
- 紧急修复破坏者（`GFL2Destroyer` `0.7.4`，工坊 `3838762173`）专武「盖亚终端」（`GaiaTerminus`）`Projectilecount = 10` 超过 B42.22 引擎 `BallisticsController` 弹道散射数组上限（9 颗 / 36 floats）导致开枪必现 `ArrayIndexOutOfBoundsException` 卡死/闪退的恶性 Bug，调整为 9 颗弹丸并等比补齐单颗伤害。
- 补齐秋桦（`3838935753`）和米什缇（`3842869667`）未订阅角色的一键跳转工坊链接。[工坊发布与验证记录](provenance/releases/0.7.32-workshop-public.json)

## 2026-11-20 · 正式版源码与资源核对

- 按公开框架及 25 个角色包回读资源清单，补登秋桦和索普共 346 份语音，更新已发布的语音与角色描述文件指纹。芙铃的呼喊、潜行呼喊和击杀语音配置与现行角色包一致。
- 更新独立人物 JAR 的构建参考和[订阅副本校验入口](tools/check_subscribed_release.py)。[维护首页](docs/maintenance/README.md)列出正式版身份、构建资料和光影分发的待定位反馈。核对 11,524 条资源指纹、独立人物核心和 Native／Somber 构建，复用正式版已封装渲染输入后，重组出的框架 JAR 与线上逐字节相同。本次沿用工坊 0.7.31。[源码对照记录](provenance/releases/2026-11-20-source-audit.json)

## 0.7.31 · 专武快捷栏图标（2026-11-20）

- 修复罗蕾莱等 24 个角色把专武背在身上后，底部快捷栏的图标放大、偏移和越界。物品图标沿用原文件名，整备室抽取结果使用高清原图。
- 使用 B42.22.2 原版挂载和快捷栏绘制代码检查全部 25 把专武，背部和枪套内的图标均在栏位范围内。[工坊发布与验证记录](provenance/releases/0.7.31-workshop-public.json)

## 罗蕾莱 0.7.3 · 日语配音（2026-11-20）

- 原皮和「暮色絮语」共用 173 条日语配音，接入角色状态、呼喊和击倒后的台词，并带简中、日文、英文及韩文字幕。[工坊发布记录](provenance/releases/2026-11-20-loreley-173-voice-workshop-public.json)。

## 0.7.30 · 家具坐姿高度（2026-11-20）

- 坐在椅子或床沿时，框架根据当前角色臀部和大腿根部的蒙皮表面校正显示高度。坐下和起身按实际姿态渐变。游戏的家具高度和朝向仍由原版提供。[工坊发布与验证记录](provenance/releases/0.7.30-workshop-public.json)

## 0.7.29 · Viewpoint 持物同步（2026-11-20）

- 修复正常加载框架时偶发漏挂最终持物同步的问题。第一次准备模型时重新挂接 Viewpoint 的模型插值类，使枪及其附件按已显示的人物姿态定位。启动回归直接调用游戏实际使用的绘制重载。[工坊发布记录](provenance/releases/0.7.29-workshop-public.json)

## 角色分包 · 整备室配件图标（2026-11-20）

- 可露凯、玛绮朵、翡图萨、六分仪、幼熙、芙铃、索普、索米的 48 个已有配件选项接入 47 张同衣装原游图标。芙铃的两个 P2 下装选项使用同一件下装的原图。8 个角色分包已在 Steam 公开更新，见[工坊发布记录](provenance/releases/2026-11-20-wardrobe-icons-workshop-public.json)。

## 0.7.28 · 衣物间接光遮挡修复（2026-11-19）

- 在场景反弹光下，晚于不透明物体绘制的衣料现在使用自身表面的朝向来计算受光，修复 Native 中后方车辆等物体的轮廓映到花纹丝袜上的问题。[工坊发布回执](provenance/releases/0.7.28-workshop-public.json)

## 0.7.27 · 衣物遮挡修复（2026-11-19）

- Native 与 Somber 按当前可见衣物的覆盖程度应用人物调色，修正旧调色逻辑引入的衣带和场景轮廓。已更新公开框架，见[工坊更新记录](provenance/releases/0.7.27-workshop-public.json)。

## 0.7.26 · 反射探针兼容性（2026-11-19）

- Native 和 Somber 遇到不支持六面探针的场景着色器时，人物反光回退到屏幕和天空取样，Viewpoint 继续绘制。已更新公开框架，该次公开更新也包含 0.7.24 的加载优化和 0.7.25 的菜单修正。见[工坊更新记录](provenance/releases/0.7.26-workshop-public.json)。

## 0.7.25 · 授权后刷新光影列表（2026-11-19）

- 首次允许框架 JAR 后，已安装的 Native 和 Somber 会加入当前 Viewpoint 光影菜单，保留玩家选中的光影。

## 0.7.24 · 衣装与光影加载性能（2026-11-19）

- 衣装贴图用查表和整行复制处理像素，保留原有 RGBA 输出和材质标记。
- Native 和 Somber 在后台读取和校验人物渲染目录。目录就绪前由 Viewpoint 绘制人物，切换到同一份人物资料的光影设置可复用解析结果。

## 0.7.23 · 人物反射探针开关（2026-11-19）

- Native 和 Somber 的「少前2人物效果」新增「反射探针」开关，默认关闭。开关控制人物的六面室内捕获，屏幕反光仍跟随 Viewpoint 的反射设置。见[工坊更新记录](provenance/releases/0.7.23-workshop-public.json)。

## 0.7.22 · 原游专武背景（2026-11-19）

- 专武界面使用原游 `Img_Weapon_Bg` 及其磨砂图，贴图缺失时回退到整备室背景。
- 核对 25 个角色包的线上内容和 72 个模型登记，复测专武领取、弹匣和誓约涂装。框架已更新至未列出的工坊条目，见[发布复核记录](provenance/releases/0.7.22-workshop-unlisted.json)。
- 新框架条目、25 个角色包与订阅合集均已公开。角色包的工坊依赖切换到了新框架，内部 MOD ID 为 `GFL2Exilium`。见[正式发布记录](provenance/releases/0.7.22-workshop-public.json)。
- 18 个角色包的 75 份 PNG 贴图经无损重压，共减少 444,088,369 字节。保留原分辨率、RGBA 像素和 PNG 元数据。见[工坊更新记录](provenance/releases/0.7.22-lossless-role-textures.json)。

## 0.7.21 · 罗蕾莱角色与专武草稿（2026-11-19）

- 罗蕾莱原皮排在「暮色絮语」之前。整备室接入专武「潮涌夜曲」、配套弹匣与「涂装·片刻囚徒」。
- 放大专武展示视口，保留长枪旋转时的方形视口和整备室资源加号的 3 秒长按。
- 罗蕾莱与玛绮朵的狙击枪沿用芙铃 G41 专武的三条开火音频。框架和两份角色包都已上传到未列出的工坊条目，见[草稿更新记录](provenance/releases/0.7.21-workshop-unlisted.json)。

## 0.7.20 · 整备室资源长按时间（2026-11-18）

- 新装许可、专访许可和信用币的加号长按均在 3 秒触发，进度条与触发时间同步。已上传至现有的未列出框架条目，见[工坊更新记录](provenance/releases/0.7.20-workshop-unlisted.json)。

## 0.7.19 · Somber 透明材质的场景描线修正（2026-11-18）

- Somber 对使用少前2原材质法线的可见表面沿用人物描线遮罩，避免背景边线叠到翡图萨等角色的丝袜上。场景着色、人物调色和透明材质的混合方式沿用原逻辑。见[本地验证记录](provenance/releases/0.7.19-somber-ink-local.json)和[工坊更新记录](provenance/releases/0.7.19-workshop-unlisted.json)。

## 0.7.18 · 运行时优化与光影分发调整（2026-11-18）

- 工坊框架自动分发 Native-GFL2 和 Somber-GFL2。Eclipse 只保留在独立维护构建，已安装的光影文件与玩家选择由 Viewpoint 管理。
- 减少人物绘制过程中反复查询 OpenGL 状态的次数，复用 PMX 姿态计算所需的临时缓存，保留原有人物、物理、材质和存档格式。受控计时、回退条件及复跑入口见[性能复审](docs/handoffs/2026-11-18-runtime-performance.md)。
- 框架及 24 个现有角色包已提交至未列出的工坊条目，框架内容清单与验证范围见[工坊记录](provenance/releases/0.7.18-workshop-unlisted.json)。

## 0.7.17 · Somber 人物光影分发（2026-11-18）

框架增加可选光影 Somber GFL 2，沿用 Native 的人物、衣装、专武和材质目录。启动时分发 Native、Somber 和 Eclipse，保留玩家的光影选择与手改文件。Somber 的漫画效果保留在场景中，角色使用原材质描边和颜色转换。构建、更新和回归步骤见[维护手册](docs/maintenance/rendering.md)，本机版本的包指纹与测试范围见[验证记录](provenance/releases/0.7.17-somber-local.json)。

## 0.7.16 · 工坊内容更新（2026-11-18）

框架和 24 个角色包均已更新到原有工坊条目。Steam 回读的 25 项内容大小与本地提交一致。最终框架包含角色材质目录加载优化，内容清单为 `1050789018337649627`，北京时间 12:40 发布。[各条目的内容清单与核验记录](provenance/releases/0.7.16-workshop.json)

- 修正 Project Viewpoint 0.1.7 物品页的少前人物预览。原生人物控件的朝向与缩放按 PMX 预览的坐标和尺度解释，物品页默认取正面并以正常比例显示。
- 整备室沿用中央可旋转、可缩放的 3D 人物预览。快速滑过衣装时优先加载最后停下的款式，衣装卡可在滚动途中接着拖动。右侧操作面板切页时短暂淡入，招募命中后显示角色头像和名称。
- 总览将角色资料和操作合入同一面板。身体物理使用整行开关，换装、启用和专武共用右侧边线。表情、握持和部件面板随内容收短，长列表保留滚动。换装及招募说明增加阅读底色，招募结果与许可消耗分开显示。
- 修复 VMD 动作列表未响应鼠标悬停的问题。动作行、角色头像、衣装和专武卡沿用同一套移入与移出渐变。时间轴悬停显示落点，拖动时加大滑块，松手应用进度。长按资源加号会按实际计时显示进度。
- 总览专武图按宽高等比完整显示，武器名和锁图标各自留位。专武详情使用独立的正方形模型视口，旋转和滚轮缩放以武器中心为基准。枪械按当前模型的枪口挂点校正预览朝向，修复伊阿索及其誓约涂装握把向上的问题。长说明和枪皮列表超高时可滚动。
- 专武详情的视口对齐窗口正中心，默认武器和枪皮各自按游戏载入的网格边界取旋转中心。总览卡按图像可见范围居中，保留原始 PNG。
- 核心、Native 和 Eclipse 在各自独立的空目录中编译发布类。删除源码或内部类后，重复构建会从 JAR 中移除对应字节码，避免重新带入退役的启动钩子。
- Native 与 Eclipse 合并角色渲染目录时，只复制待追加条目的数组和索引。使用本机 24 包对照，完整目录与身份摘要一致；在中途拒绝坏包后，后续角色包仍可加载。

## 0.7.16 · 本机与工坊上传目录

- 限定衣装池采用[原游概率公示](https://gf2.haoplay.com/game/probability.html)的 0.02% 基础概率及四段软保底。第 100 抽必得所选衣装，含保底综合概率约 1.1776%。
- 整备室经济模块更名为 `GFL2RoomEconomy.lua`，单机和联机入口使用新文件名。
- 核心 Lua 入口、整备室资源和 24 个角色包登记使用当前命名。旧存档的余额、角色解锁、外观、表情以及站姿与瞄准握持设置可迁移到新键。
- 工坊候选复用已核验的整备室图片，角色包检查按实际 MOD ID 读取分包。
- 合入简体中文、英文、日文和韩文本地化，涵盖角色与衣装文案、字号适配、表情控件和 24 个角色的语音字幕。四种语言的菜单入口统一显示「僵毁少女前线」。
- 更新 Java 包、原生库和 PMX 资源路径，并重新组装 Native 与 Eclipse。保留原有 PMX、动作和已验收的软体输入。
- 语音脚本、字幕键和媒体目录迁到现用命名。载入存档时可以恢复旧角色的原声前缀。
- 该次本机安装已回读核心与 24 个角色包。后续工坊发布见上方 11 月 18 日记录，实机使用由玩家验证。

## 0.7.15 · 当时的本地维护基线

- VMD 入口默认隐藏。原有触发方式和会话行为见对应发布记录。
- 本地安装和工坊作者包的核心目录统一为 `GFL2Exilium`，JAR 为 `GFL2Exilium.jar`。同步构建、打包与核验的路径。作者包通过 PZ 校验，现装原生组件可加载阿斯缇亚模型。
- 该版本记录为本地安装。Steam 内容版本按下方有发布凭据的条目确认。四国语言开发在自己的工作树继续。

[0.7.15 本地版本记录](provenance/releases/0.7.15-vmd-entry-toggle.json)

## 工坊页面 · 2026-11-17

- 24 个角色使用高清原皮插画头像作为主封面，工坊标题统一为「中文名 - 英文名」。
- 框架简介用 88 × 88 的无文字头像链接连接各角色页，并提供原生订阅合集入口。
- 角色详情相册中的旧目录小头像已撤下。

[框架项目](https://steamcommunity.com/sharedfiles/filedetails/?id=3838709437) · [角色订阅合集](https://steamcommunity.com/sharedfiles/filedetails/?id=3841234557)

## 0.7.14 · 2026-11-17

- 持物附件使用游戏手部姿态和物品的附件定义。重新捕获镜头时，每帧复用该姿态的持物结果。
- 通过握持重放、暂停镜头回归、官方宿主的早加载和后加载检查，以及 RX 9070 XT 的受控出图。
- 框架和秋桦 0.7.2 的 Steam 发布均有内容清单回读。真实游玩验证在该发布记录中仍为待测。

[框架发布及 Steam 回读](provenance/releases/0.7.14-held-slot-attachment.json) · 秋桦 0.7.2

## 0.7.13 · 2026-11-17

- 适配 Steam 创意工坊正式版 Viewpoint 0.1.7。统一身体物理入口并更新持物姿态的同步。
- 完成人物联机协议、整备室经济和换装逻辑的组件测试，核对框架和本次更新的角色包发布回读。

[0.7.13 记录](provenance/releases/0.7.13-viewpoint-multiplayer.json) · 多人回环验证

## 0.7.9–0.7.12

- 从旧宿主版本迁移到 Steam 正式版 Viewpoint 0.1.7，核对点光布局、身体物理入口、握持与兼容性。
- 各阶段的原生组件、宿主与发布状态按独立记录核对。

0.7.9 · 0.7.10 · 0.7.11 · 0.7.12

## 0.7.0–0.7.8 · 历史

- 角色包独立发布，补齐整备室、专武及语音资料，迭代局部软体物理。
- 当时的常规版与 RC4 并行验收属于历史。当前支持范围见 [README](README.md)。

[0.7.0](docs/releases/0.7.0.md) · [0.7.5](docs/releases/0.7.5.md) · [0.7.6](docs/releases/0.7.6.md) · [0.7.7](docs/releases/0.7.7.md) · [0.7.8](docs/releases/0.7.8.md)
