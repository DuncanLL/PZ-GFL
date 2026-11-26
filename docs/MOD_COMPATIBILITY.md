# 第三方模组兼容总账（MOD Compatibility Ledger）

本文件是《僵毁少女前线》（`GFL2Exilium`）全仓第三方模组适配的**唯一总索引**。
既方便对外向玩家准确说明**已支持哪些模组与兼容边界**，也确保未来任意时间、任意新对话都能在 10 秒内查清**每段兼容代码为谁而加、解决什么问题、挂在何处、如何修改或无残留删除**。

---

## 一、对外兼容速查表（方便直接回复玩家 / 写工坊说明）

| 分类 | 模组名称 | Mod ID / 工坊 ID | 支持状态与范围 | 备注 / 边界 |
| :--- | :--- | :--- | :--- | :--- |
| **3D 视角与光影** | **Project Viewpoint 0.1.7**（含 Native / Somber / FarRelief / DLSS） | `Viewpoint` | **完整支持** | 内置 `Native-GFL2` / `Somber-GFL2` 原材质光影、整备室原材质预览、远景描边收敛防 DLSS 粗黑边、FarRelief 地形起伏隔离、无六面探针自动回退 |
| **3D 瞄准扩展** | **Shoot to Thrill**（原名 I SEE THEM） | `ShootToThrill` / `ShootToThrillMultiplayer`（`3813032735`） | **轻量化兼容（已保不闪）** | 隔离画中画倍镜（PIP）副通道与 `WorldDepthPass` 双重深度通道，防止 GF2 原材质下角色一闪一闪或反复重建 FBO；对方自带第一人称手臂/瞄准补丁归对方维护 |
| **枪械模组** | **Escape From Zomboid Firearms（EFZ）** | `EscapeFromZomboidFirearms` + `SWMG` | **深度兼容** | 第一人称 ADS 持枪交接、重放防重影、专武弹药家族/弹药盒/专属弹匣、`RealAuto` 真实射速、整备室弹药补给 |
| **枪械模组** | **Modern Firearms System（MFS）** | `ModernFirearmsSystem` | **已兼容** | 锁定专武专属弹匣防被 `ChangeMagzine` 换坏、放行 `GFL2WeaponSkin` 联机涂装同步、整备室弹药适配（专武不支持 MFS 开镜） |
| **枪械模组** | **Guns of Marz（GoM / 旧版 MarzGuns）** | `GunsOfMarz` / `MarzGuns` + `SWMG` | **已兼容** | 全部 6 种口径弹药/弹药盒、远程专武与专属弹匣、整备室弹药补给（无需开新档） |
| **枪械模组** | **Xanny Arms Gun Pack** | `XannyArmsGunPack` | **已兼容** | 未开 EFZ/MFS/GoM 时自动同步全 6 口径弹药与弹药盒（含背包存量物品迁移与整备室补给） |
| **角色身形 / 皮肤** | **VS3 可变身形 3（Variable Skin Boob Bounce）** | `VariableSkinBoobBounce`（`3640496116`） | **已兼容** | 原版等距视角下清除 `uVSDEnabled` / `uVSDHeader`，彻底解决 PMX 皮肤发脏斑驳；覆盖 S/M/L/P/Z/SFW 全子模组，不影响僵尸与其他角色乳摇 |
| **角色身形** | **Tomb's Player Body** 等身形替换模组 | 原版等距视角通用兼容 | **已兼容** | 修复原版斜 45° 渲染替换、手持武器/附件挂点与缩放矩阵同步 |
| **动作与家具交互** | **Lifestyle / True Actions / Easy Sleep** 及各类第三方 FBX 动作模组 | 通用底层姿态兼容 | **已兼容** | 修复躺床钻床底与坐姿悬空；修复坐姿锁骨耸肩；自动将第三方 FBX 动作的手臂轴向扭转分流到 PMX `腕捩`/`手捩` 防“拧麻花” |

> **已知容易互撞的组合（提醒玩家）**：不要同时叠挂多个重度改写 Viewpoint 相机与渲染管线的模组（例如同时开 `ShootToThrill` + `ProjectViewpointADS` + `OldPoint`），三者会互相覆盖 Viewpoint 的剔除与渲染逻辑。

---

## 二、新增与维护规约（强制执行）

1. **一模组一独立文件（隔离原则）**
   - Java 兼容逻辑统一放入独立的 `*Compatibility.java`（如 `Gfl2ShootToThrillCompatibility.java`、`EfzAimCompatibility.java`），**严禁**把反射或第三方类名直接散落写进核心渲染/动画/物理类。
   - Lua 兼容逻辑优先放入独立的 `*Compat.lua`；若必须接入共用配置表（如弹药/弹匣分发），须集中在专用映射表并用清晰注释标明模组 ID。
2. **主流程语义化单行调用**
   - 主流程代码（`ModelSession`、`Gfl2NativeRuntime`、`Gfl2NativeSourceRenderer` 等）里只允许出现一眼能看出归属模组的调用，例如 `Gfl2ShootToThrillCompatibility.isScopePipPass()`、`EfzAimCompatibility.keepsWeapon(...)`。
3. **兼容文件顶部统一“档案头注释”**
   - 每个独立兼容文件顶部必须注明 5 项：**目标模组名、Workshop ID / Mod ID、冲突现象与根因、主流程挂载点、移除方式**。
4. **零硬依赖、零未装开销、故障隔离**
   - Java 侧统一使用 `Class.forName(..., false, loader)` 静态一次性查找并缓存 `static final` 字段/方法；目标模组未安装时直接返回 `false` / `null`（被 JIT 常量折叠为零开销），且对方升级改名时静默降级，绝不拖崩本体。
5. **增删改同步更新本总账**
   - 任何新增、调整或删除第三方模组适配的改动，必须在同一轮内同步更新本文件（含上方对外速查表与下方技术档案）。

---

## 三、开发维护技术总账（逐模组源码定位与做法）

### 1. Shoot to Thrill（3D Aiming & Combat Expansion，原名 I SEE THEM）
- **Mod ID / Workshop ID**：`ShootToThrill` / `ShootToThrillMultiplayer` · Workshop `3813032735` · 包名 `iseethem`
- **冲突现象与根因**：
  1. `iseethem.WorldDepthPass`（`Patch_WorldDepth`）把同帧 `ModelPass.gbuffer` 拆成两次：第 1 次在 `@OnEnter` 临时清零本地玩家 `draws.flags`（仅画世界深度给鼠标拾取），导致在 `gbuffer` 返回前执行的 `Gfl2NativeSourceRenderer.prepass` 把“缺本地玩家”的残缺名单锁进整帧 `frozenActors`；第 2 次在 `afterPick()` 以同一 `frame.index` 重放本地玩家 `gbuffer` 时又撞上 `Duplicate source GBuffer callback` 异常，导致本地角色整帧丢失/一闪一闪。
  2. `iseethem.OpticPip` 在画中画（PIP）倍镜期间置 `OpticPip.drawing = true`，以倍镜小分辨率重跑 `WorldRenderer`，导致 `Gfl2NativeSourceRenderer` 每帧反复销毁/重建主屏 FBO 纹理并覆写时序历史 `histories` / `previousValues`。
- **代码位置与挂载点**：
  - 独立兼容类：`render-support/java/viewpoint/render/Gfl2ShootToThrillCompatibility.java`
  - `render-support/java/viewpoint/render/Gfl2NativeRegistry.java`（`owns` 遇 `isScopePipPass()` 返回 `false`，倍镜内远景角色回退 VP 默认 PMX 绘制）
  - `render-support/java/viewpoint/render/Gfl2NativeRuntime.java`（`beforeLighting` / `shadows` / `draw` 遇 `isScopePipPass()` 直接跳过）
  - `render-support/java/viewpoint/render/Gfl2NativeSourceRenderer.java`（`actors` / `prepass` 识别 `isWorldDepthSplitPass()` 与 `isWorldDepthReplay()`：拆分期间不锁死残缺 `frozenActors`，允许 `replay` 二次 `prepass` 补写本地玩家深度）
- **性能开销**：未装时严格为 0；已装时每帧仅 2~3 次静态反射字段读取（纳秒级），且开 PIP 倍镜时避免每帧重建主屏 FBO。
- **移除方式**：删除 `Gfl2ShootToThrillCompatibility.java`，移除上述 3 处挂载点中的调用并重编 `GFL2RenderSupport.jar`。

---

### 2. Project Viewpoint 0.1.7（含 Native / Somber / FarRelief / DLSS）
- **Mod ID**：`Viewpoint`（官方 0.1.7）
- **关键兼容做法与代码位置**：
  - **Somber 漫画描线遮罩**：`render-support/java/gfl2/render/SomberPostHooks.java` + `render-support/java/viewpoint/render/Gfl2SomberCoverage.java`（向 Somber 后处理注入 GF2 原材质可见覆盖图，避免场景漫画边线叠到角色丝袜上）。
  - **FarRelief（地形起伏）与 D70 纹理槽隔离**：`render-support/java/viewpoint/render/Gfl2StudioPreviewRenderer.java`（整备室预览绘制前清零 `uReliefGrid = (0,0,0,0)` 防角色被地形抬升甩出视锥；`BLIT_HDR_UNIT = 119` / `BLIT_LUT_UNIT = 120` 避开 Viewpoint D70 低位槽位告警）。
  - **DLSS / 超分远景描边收敛**：`render-support/java/viewpoint/render/Gfl2NativeSourceRenderer.java`（`outlineDistanceScale` 按画面垂直占比收敛，抵消原游 1.2px 硬底限；描边为 0 时直接跳过描边 Pass）。
  - **无六面反射探针回退**：`render-support/java/viewpoint/render/Gfl2NativeEnvironment.java`（遇到不支持六面探针的场景着色器自动回退屏幕与天空取样）。
  - **物品页 3D 预览 & 持物同步**：`game/java/gfl2/runtime/PlayerPreviewPatch.java`、`game/java/gfl2/runtime/RenderPatch.java`、`render-support/combined/java/gfl2/render/UnifiedStartup.java`。

---

### 3. Escape From Zomboid Firearms（EFZ / 逃离僵毁枪械）
- **Mod ID / 依赖**：`EscapeFromZomboidFirearms`（及基础库 `SWMG`）· Java 包名 `pz.escapefromzomboidfirearms.zb`
- **冲突现象与根因**：
  1. EFZ 第一人称开镜会接管武器摆放并以 `FpViewLatch.acceptAllFilter` 重放原版模型，若 GFL2 继续改写手持挂载点或不过滤重放列表，会导致第一人称武器错位或人物重影。
  2. EFZ 使用 `SWMG` 自定义弹药家族、弹匣配置表与 `RealAuto` 真实射速机制，GFL2 专武需接入其弹药盒/弹匣/射速系统。
- **代码位置与挂载点**：
  - Java 独立兼容类：`game/java/gfl2/runtime/EfzAimCompatibility.java`
  - `game/java/gfl2/runtime/ModelSession.java`（`preserveAimWeapon` 调用 `EfzAimCompatibility.keepsWeapon(...)`）
  - `render-support/java/viewpoint/render/Gfl2StockModelReplay.java` 与 `render-support/combined/java/viewpoint/render/Gfl2StockModelReplay.java`（遇到 `EfzAimCompatibility.isReplay(original)` 时排除已被 GF2 原材质接管的绘制项）
  - Lua 射速：`game/mod/42/media/lua/shared/GFL2AutomaticCadence.lua`（`SWMG` 激活时注册各专武 `RateOfFire` RPM 与 `RealAuto` `RecoilDelay = 0`）
  - Lua 弹匣/弹药/商店：`game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua`（`usesEFZ`）、`game/mod/42/media/lua/shared/GFL2SignatureWeapon.lua`（`efzAmmoBox`）、`game/mod/42/media/lua/shared/GFL2RoomEconomy.lua`（`usesEFZ` 弹药盒与价格）、各角色分包 `game/packs/*/42/media/lua/shared/*EFZ.lua`
- **移除方式**：Java 侧删 `EfzAimCompatibility.java` 与 `Gfl2StockModelReplay.java` 调用；Lua 侧按上述 4 个文件及角色包 `*EFZ.lua` 清理。

---

### 4. Modern Firearms System（MFS / 现代枪械系统）
- **Mod ID**：`ModernFirearmsSystem`
- **冲突现象与根因**：
  1. MFS 的换弹匣函数 `ChangeMagzine` 会把 GFL2 专武误换成通用弹匣或改写 `modData.MagzineTypeNow`，且联机白名单 `AWCWF_AllowModDataTable` 不含 `GFL2WeaponSkin` 时会阻断契约涂装同步。
  2. 整备室弹药补给与 `SWMG` 弹匣体系需识别 MFS 状态。
- **代码位置与挂载点**：
  - Lua 独立兼容文件：`game/mod/42/media/lua/client/ZZ_GFL2ModernFirearmsCompat.lua`（守护 `ChangeMagzine`、修复专属弹匣、向 `AWCWF_AllowModDataTable` 注册 `GFL2WeaponSkin`）
  - Lua 共用配置：`game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua`（`usesMFS`）、`game/mod/42/media/lua/shared/GFL2RoomEconomy.lua`（`usesMFS`）
  - 回归测试：`tools/tests/mfs-compat-fixture.lua`
- **移除方式**：删除 `ZZ_GFL2ModernFirearmsCompat.lua`、`tools/tests/mfs-compat-fixture.lua`，并移除 `GFL2SignatureMagazines.lua` / `GFL2RoomEconomy.lua` 中的 `usesMFS` 分支。

---

### 5. Guns of Marz（GoM / 旧版 MarzGuns）
- **Mod ID**：`GunsOfMarz` / `MarzGuns`（依赖 `SWMG`）
- **冲突现象与根因**：将 GFL2 专武 6 口径与弹匣接入 GoM 的 `SWMG_AmmoTypes` / `MarzGuns_AmmoTypes` 弹药体系及整备室弹药盒兑换。
- **代码位置与挂载点**：
  - `game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua`（`usesGOM` / `configureGunsOfMarz`）
  - `game/mod/42/media/lua/shared/GFL2RoomEconomy.lua`（`MarzGuns.*` 弹药盒与价格映射）
  - 回归测试：`tools/tests/gom-compat-fixture.lua`
- **移除方式**：移除上述两文件中的 `usesGOM` / `MarzGuns` 映射段与 `tools/tests/gom-compat-fixture.lua`。

---

### 6. Xanny Arms Gun Pack
- **Mod ID**：`XannyArmsGunPack`
- **冲突现象与根因**：在未启用 EFZ / MFS / GoM 时，将 GFL2 专武的 6 口径弹药类型（含 `.308` / `.44` 捐献模板解析）及弹药盒同步映射到 Xanny Arms 体系，并自动迁移背包存量专武与弹匣。
- **代码位置与挂载点**：
  - `game/mod/42/media/lua/shared/GFL2SignatureMagazines.lua`（`usesXanny` / `configureXannyArms` / `syncXannyItem` / `syncXannyContainer`）
  - `game/mod/42/media/lua/shared/GFL2RoomEconomy.lua`（`XannyArms.*` 弹药盒与价格映射）
- **移除方式**：移除上述两文件中的 `usesXanny` / `XannyArms` 段即可。

---

### 7. VS3 可变身形 3（Variable Skin Boob Bounce / VSDynamics）
- **Mod ID / Workshop ID**：`VariableSkinBoobBounce`（`VSDynamics.jar`，含 S / M / L / P / Z / SFW 全子模组）· Workshop `3640496116`
- **冲突现象与根因**：原版等距视角下，`VSDynamics` 钩住了 `Shader.startCharacter(slot, bodyData)`，检测到原版底层 `FemaleBody` 后将 `uVSDEnabled` 置为 `1` 并按 `gl_VertexID` 覆写前段皮肤顶点法线，导致 GFL2 PMX 角色皮肤大块灰黑斑驳发脏。
- **代码位置与挂载点**：
  - `game/java/gfl2/runtime/VanillaWorld.java`（L33–L50 附近，在 GFL2 原版视角 `shader.startCharacter` 之后缓存查询并清零 `uVSDEnabled` 与 `uVSDHeader` uniform；仅影响当前 GFL2 角色绘制，不影响僵尸及非 GFL2 角色的 VS3 身形与乳摇物理）
- **移除方式**：移除 `VanillaWorld.java` 中 `cachedVsdEnabledLoc` / `cachedVsdHeaderLoc` 的清零逻辑即可。

---

### 8. Tomb's Player Body 等原版身形替换模组
- **适用范围**：原版斜 45° 等距视角下替换玩家 `FemaleBody` / `ModelSlot` 的身形模组（如 Tomb's Player Body）
- **冲突现象与根因**：原版身形模组会改写玩家模型的 `ModelSlotRenderData` 准备时序或武器附件矩阵，容易引发角色漏绘、模型闪回原版或手持武器/附件错位。
- **代码位置与挂载点**：
  - `game/java/gfl2/runtime/VanillaWorldPatch.java` + `game/java/gfl2/runtime/VanillaWorld.java`（统一在 `ModelSlotRenderData.{init, checkReady, performRenderCharacter, postRender}`、`ModelInstanceRenderData.RenderCharacter`、`Model.DrawChar` 完整接管与还原）
  - `game/java/gfl2/runtime/HeldAttachmentPatch.java`（`ModelInstance.getAttachmentWorldPosition` 补丁，确保手持武器挂点在原版视角与身形替换下坐标一致）
- **说明**：属于原版视角基础兼容架构，一般不移除。

---

### 9. 第三方动作与家具交互模组（Lifestyle / True Actions / Easy Sleep / 第三方 FBX 动作）
- **适用范围**：Lifestyle、True Actions、Easy Sleep 及各类 Blender 导出的第三方 FBX 动作/枪械持姿模组
- **冲突现象与根因**：
  1. 躺床/坐姿动作常包含未映射的 `Root -> Pelvis` 杠杆旋转，导致 PMX 角色钻进床底或悬空坐立。
  2. 坐姿与特殊持姿动作中锁骨轴向旋转过大导致耸肩变形。
  3. 第三方 Blender FBX 动作常直接扭转前臂骨（原版 PZ 前臂轴向扭转为 `0°`），破坏 Ozz IK 轴向导致肘部/手臂“拧麻花”。
- **代码位置与挂载点**：
  - `game/java/gfl2/motion/WorldPoseRetargeter.java`（自动识别 PMX `腕捩` / `手捩` 辅助扭转骨并分流上臂/前臂轴向扭转；`buildAxialTwistGuardedDelta` 保护锁骨防耸肩；抵消未映射 `Root -> Pelvis` 杠杆偏移）
  - `game/java/gfl2/runtime/FurnitureSeat.java` + `game/java/gfl2/runtime/Bridge.java`（按当前角色臀部/大腿根蒙皮表面校正坐姿高度，随坐下/起身平滑过渡）
- **说明**：属于公共姿态重定向基础能力，全角色共用且无单模组硬编码。
