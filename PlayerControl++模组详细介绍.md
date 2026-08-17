# PlayerControl++ 开发文档

> 面向作者与 AI Agent 的唯一权威文档。用户面向的介绍见 [MODRINTH_CN.md](MODRINTH_CN.md) / [MODRINTH_EN.md](MODRINTH_EN.md)。

## 一、当前状态

| 项 | 值 |
|---|---|
| 版本 | 1.6 |
| 代码规模 | 64 个 Java 文件 / 约 10 500 行，**一份源码构建 7 个 MC 版本** |
| 语言文件 | `en_us` / `zh_cn` 各 219 键 |
| 运行环境 | 纯客户端（`"environment": "client"`），1 个 Mixin |
| 前置 | Fabric API + malilib（必需）；Litematica / Baritone / ChestTracker / QuickShulker / ModMenu（可选，全反射） |
| 构建 | `./gradlew buildAndGather` → 7 个 jar 全部通过 |
| 已知缺口 | 无自动化测试；`GatherContext` 是共享可变状态 |

### 支持的版本

| 子项目 | Minecraft | Java | Parchment | malilib | ModMenu |
|---|---|---|---|---|---|
| `:1.21.1`  | 1.21 – 1.21.1 | 21 | 2024.11.17 | 0.21.10 | 11.0.4 |
| `:1.21.4`  | 1.21.4 | 21 | 2025.03.23 | 0.23.5 | 13.0.3 |
| `:1.21.6`  | 1.21.6 – 1.21.7 | 21 | 无 | 0.25.7 | 15.0.2 |
| `:1.21.8`  | 1.21.8 | 21 | 无 | 0.25.7 | 15.0.2 |
| `:1.21.11` | 1.21.11 | 21 | 2025.12.20 | 0.27.12 | 17.0.0 |
| `:26.1.2`  | 26.1 – 26.1.2 | 25 | 无（未混淆） | 0.28.9 | 18.0.0-beta.1 |
| `:26.2`    | 26.2 | 25 | 无（未混淆） | 0.29.3 | 20.0.1 |

`1.21.6` 与 `1.21.8` 的预处理产物**逐字节相同**（见 [§五](#五跨版本差异)），
malilib / ModMenu 也是同一个 jar 覆盖 1.21.6–1.21.8，两个子项目纯粹是为了各自声明
`game_versions` 与 `minecraft_dependency`。

## 二、三条不可违反的约定

1. **不要直接调 `KeyMapping.setDown()`。** 所有模拟按键经 `input/SimulatedInput`：
   功能只声明 `hold(key, owner)` / `release(key, owner)`，每 tick 末尾 `apply()` 统一落地，
   按 owner 引用计数。功能中止时 `releaseAll(owner)` 一行保证不留卡键。
2. **新增功能实现 `ClientFeature` 并在 `InitHandler.registerFeatures()` 注册**，
   不要往 `ClientEventHandler` 里加 tick 调用。注册顺序 = tick 顺序 = 世界切换通知顺序。
3. **只编辑 `src/main/`。** 主工程是 `26.2`，其余版本由预处理器生成到
   `versions/<mc>/build/preprocessed/`。

当前 tick 顺序（`InitHandler.registerFeatures()`）：

```
AutoForwardFeature → RouteFlowRuntime → RecordingManager → AutoMaterialGatherer
→ AutoCacheNearbyContainersFeature → SchematicSelectionContainerCacheFeature
→ AutoWaterFillFeature → AutoRestockFeature
```

路径与录制排在前面，因为它们产生的移动输入要由 `ClientEventHandler` 在所有功能 tick 完之后读取。

## 三、功能清单

### 3.1 基础控制

| 功能 | 关键实现点 |
|---|---|
| **自动前进** | 只置一个布尔量，键由 `ClientEventHandler` 声明 |
| **快速转向** | `RotateAction` 同时设 `yRot` 与 `yRotO`；只设前者会变成 50 ms 的平滑扫过 |

### 3.2 路径流系统 `route/`

- 数据：`Route`（≥2 个 `RouteNode`）+ 每路径独立热键；`getNodes()` 只读，
  增删只能走 `insertNode` / `removeNode`，节点数不会被减到 1 而让执行器越界。
- 执行：模拟按住前进键 + yaw 修正。分级转速（>45°=25°/tick，>15°=18°/tick，其余 15°/tick），
  死区 ±2°，到达导航点瞬间 Snap。只控制 XZ 平面。
- 循环：`1` 单程 / `>1` 往返 N 次 / `0` 无限。
- 卡住检测：3 秒未移动 → 跳一次；再 5 秒未移动 → 终止。
- 选项：Sprint、LayerCtrl（每趟切 Litematica 渲染层）、Layer Inc。
- 持久化：`config/playercontrolpp_routes.json`，原子写入。
- 热键管理：`KeybindProvider` 每次从 `RouteManager` 现场枚举，删路径后只需
  `IKeybindManager.updateUsedKeys()` 重建映射——只有这一条注册路径，不会两套不同步。

### 3.3 录制与回放 `record/`

- 录制内容：移动分量、疾跑、跳跃、潜行、Yaw/Pitch、左右键。
- **RLE 压缩**：连续相同输入合成一个 `RecordedSegment` + `duration`；按住 W 十秒 = 1 段。
- 存储：`index.json`（只有元数据，GUI 只读它，打开速度与录制数量无关）
  + 每条录制一个 gzip 压缩的 NBT `.pcr`。
- 线程模型：序列化在客户端线程（只把不可变 `CompoundTag` 交给后台），
  单条守护 IO 线程，`.pcr` 写成功后才回主线程更新索引。
- 损坏保护：索引解析失败另存为 `index.json.corrupt-N` 且**不标记为已加载**，
  下一次保存不会用空列表覆盖；启动时剔除 `.pcr` 已丢失的条目。
- 回放：`IDLE → LOADING → MOVING_TO_START → PLAYING → COMPLETED`，
  异步解压不卡帧，播完释放引用。
- 位置偏差：每 20 tick 一个 `PositionKeyframe`；偏差 >4 格提示一次，
  设置项 **回放位置修正**（默认关闭）开启后偏差 >2 格拉回。默认关闭是有意的——
  改写客户端坐标与服务端权威位置矛盾，可能被判飞行。

### 3.4 自动缓存附近容器 `AutoCacheNearbyContainersFeature`

**v1.6 提速。** 状态机 6 → 4 个状态，与 3.5 同一套 `advance()` 结构。

```
SCANNING → OPENING_CONTAINER → [COOLDOWN] → SCANNING
无目标 → AUTO_STOP_COUNTDOWN（3 秒，期间每 5 tick 重扫，发现新容器直接用该目标开箱）
```

- 扫描：以交互距离为半径的立方体（约 1331 次迭代），白名单预解析为 `Set<Block>`、
  复用一个 `MutableBlockPos`、单趟求最近目标。
- 白名单默认含 11 种原版容器 + 全部 17 种潜影盒（由 `DyeColor` 生成）。
- **落库仍然靠 ChestTracker 自己的「关界面」钩子**，但那个钩子是**同步**的：
  `ScreenEvents.remove` 在 `setScreen(null)` 里就调用 `provider.onScreenClose()` 读走
  `screen.getMenu()` 的内容，随后 `InteractionTracker.clear()`。所以「关箱（落库）→ 点下一个箱
  （建立新交互记录）」这个顺序在同一 tick 内完全成立——删掉的 `WAITING_AFTER_OPEN` /
  `CLOSING_GUI` 共 3 tick 空转并不影响落库正确性。
- 固定的 `RECORD_WAIT_TICKS = 1` 换成「等到内容包真的到了」（`menuHasContent()`，最多宽限 2 tick）：
  内容同 tick 到达时不多等，内容迟到时也不会被 ChestTracker 记成空容器。
- 关箱判据从 `mc.screen instanceof AbstractContainerScreen` 换成
  `containerMenu != inventoryMenu`——更早、也不受替换 Screen 的模组影响。

### 3.5 缓存投影选区容器 `SchematicSelectionContainerCacheFeature`

**v1.6 重写。** 行为与 3.4 一致——只缓存玩家**此刻伸手可及**的容器，走到哪缓存到哪，
区别仅在候选集合被限制在 Litematica 当前区域选区内。

```
IDLE / SEEKING / OPENING / COOLDOWN      —— 只有 4 个状态
```

四个关键设计：

| 做法 | 原因 |
|---|---|
| 待缓存容器按 **16³ 区段分桶**（`Map<Long, List<BlockPos>>`），每 tick 只查玩家周围的桶 | 选区里上千个容器也不影响取目标的开销 |
| 扫描按区段推进，**区段按距玩家远近排序**，玩家移动 >16 格重排剩余队列 | 扫到的容器立刻可缓存，不必等整个选区扫完；先扫玩家脚下 |
| `advance()` 返回「本步是否消耗了服务端往返」，**同 tick 连续推进** | 关箱→找下一个→发右键落在同一 tick，比旧版每容器省 3 tick |
| 等到**内容包真的到了**再落库（`menuHasContent()`，最多宽限 2 tick） | 既不多等一 tick，也不会把没同步的容器记成空的 |
| 区块未加载时**整段跳过**并延后重试（每 40 tick 一轮） | 旧版逐方块 `isLoaded()`，一个区段白算 4096 次 |

- 目标可达性判据是**眼睛到方块外框**的距离（不是到方块中心），与服务端接受右键的范围一致。
- 自己用 ChestTracker 的 `MemoryBuilder` 落库，不依赖它的关界面钩子。
- 找不到可达容器时停在 SEEKING 等玩家移动，**不自动终止**；
  队列清空 + 扫描完成才报完成。旧版「连续 5 个超范围就终止」已删除。
- 双箱只登记一半（`claimed` 集合）。

详细延迟分析见 [容器缓存性能分析.md](容器缓存性能分析.md)。

### 3.6 自动填水 `AutoWaterFillFeature`

```
SCANNING → FINDING_BUCKET → [SHULKERING] → ROTATING → PLACING_WATER → COOLDOWN → SCANNING
```

- 水桶三级回退：快捷栏 → 主背包（三次容器点击换下来）→ 潜影盒 + QuickShulker（只取一个）。
- 选中槽位后必须发 `ServerboundSetCarriedItemPacket`，否则服务端仍认为手持旧物品，
  `useItemOn` 静默失败。
- `recentlyAttempted` 冷却集合（20 tick）：刚填过的方块在服务端状态回传前不会被再次扫成候选。
- 点击前重新校验世界方块状态与投影期望；`useItemOn` + `useItem` 两个调用都要发。
- 潜行时暂停、死亡/世界切换立即停止。

### 3.7 自动投影材料备货 `feature/automaterial/`（需 Baritone + Litematica + ChestTracker）

11 状态任务状态机（`TaskStateMachine`），模块分工：
`MaterialAnalyzer`（读清单、按缺口排序）、`ContainerSearcher`（问 ChestTracker）、
`BaritonePathingController`（寻路 / 判到达 / 判卡住）、`ContainerOpener`（瞄准、重试、六邻回溯）、
`ItemTransferExecutor`（取物）、`ShulkerBoxStorage`（背包满时存盒）。

- **取物数量策略** `ItemTransferStrategy`（零 MC 依赖，最适合先补 JUnit）：
  ≤64 取 1 组；65–1728 取 `ceil(需求/64)` 组；>1728 取 `floor(需求/1728)` 个整盒 + 余量按组。
  盒数**向下**取整——向上取整时需求 1729 会取走 2 整盒 = 3456 个。
- **隔空开箱**：构造 `BlockHitResult` 直接发 `useItemOn`，绕过客户端射线检测，
  相邻容器抢不走点击；`useItemOn` 抛异常时的右键回退由 `SimulatedInput` 记账。
- **交互距离**读 `Attributes.BLOCK_INTERACTION_RANGE` 属性，不硬编码。
- **存盒**两模式：模拟放置/开/挖，或 QuickShulker 就地开盒。满盒经 GUI 实测确认后记录槽位，
  跨周期保持；放置位判定用 `isFaceSturdy(level, pos, UP)`。
- 刚取过整盒时跳过自动存盒，避免把刚拿的盒子存回去。

### 3.8 自动续料建造 `AutoRestockFeature`（需 Baritone + Litematica）

```
MONITORING ──建造完成────────────────────────→ 停止
     └─建造暂停→ ANALYZING
                   ├─其实不缺料     → 挪材料上快捷栏 → resume
                   ├─物品栏有含料盒 → SHULKER_OPEN → SHULKER_TAKE ─┐
                   └─要跑标记容器   → PATHING → OPENING →          │
                                      TRANSFERRING ───────────────┤
                                                                   ▼
                                                               FINISHING
```

**两条路为什么不同**：走去标记容器要驱动 `CustomGoalProcess`，而 `cancelEverything()`
会给所有进程发 `onLostControl()`，`BuilderProcess` 收到后丢掉整个蓝图 → 必须重启建造。
从背包开盒取料不需要移动 → 只 `resume()`，层数进度保留。

**缺料检测**：`countMissing` 是创建清单时算的固定总量，`countAvailable` 把潜影盒内容也算进去
（Baritone 只能放散装）。所以两者都不直接用，改为自己数背包 36 格的**散装**数量，
与 `min(countMissing, N组)` 比较。

**材料挪上快捷栏**：Baritone 的 `allowInventory` 默认关闭，只认快捷栏 9 格。
检测到「不缺料但仍暂停」时用数字键交换（SWAP）把材料挪到**空的**快捷栏格——
只用空位，不挤掉玩家的镐子。

**QuickShulker 联动的两个前置检查**：① 必须在玩家自己的 `InventoryMenu` 界面下发包
（快捷栏 0–8 → 菜单 36–44，主背包 9–35 → 9–35）；② 只有 `count == 1` 的盒子才能开
（潜影盒注册时没设 `ignoreSingleStackCheck`）。

**防无限循环三层**：`containerTripDone`（每轮最多跑一趟标记容器）、
`MAX_SHULKERS_PER_CYCLE = 3`（每趟最多搬 3 个含料盒）、
`MAX_NO_GAIN_CYCLES = 3`（连续 3 轮零收获则停止）。

**v1.6 新增：标记容器的距离限制与启用开关**

| 项 | 说明 |
|---|---|
| 存储格式 | `ConfigStringList` 每行 `维度 x y z [off]`；4 段 = 启用（旧数据原样可读），第 5 段 `off` = 禁用 |
| 距离限制 | `restockContainerMaxDistance`，0 = 不限；超距容器直接不寻路 |
| 切换方式 | **潜行 + 标记容器热键** = 切换启用/禁用；也可在 malilib 列表编辑器里手打 `off` |
| 筛选出口 | `MarkedContainerManager.pickTargets(level, origin)` → `Targets(positions, disabled, tooFar)`，同时给出被过滤的原因数量 |
| 提示区分 | 「一个都没标记」与「标记了但全被禁用/超距」是两条不同的消息 |

`dimensionIdOf()` 现在用 `level.dimension().identifier().toString()`，
不再解析 `ResourceKey.toString()` 的 `"ResourceKey[a / b]"` 形状——那是调试输出，没有兼容承诺。
两种取法对原版维度结果相同，旧配置无需迁移。

## 四、架构

```
src/main/java/com/alonediamond/playercontrolpp/
├── Playercontrolpp.java            MOD_ID + LOGGER + 通用入口
├── client/PlayercontrolppClient     ClientModInitializer
├── compat/                          ★ 跨版本兼容层（9 类，见 §五）
├── config/  Configs / InitHandler / StorageMode
├── event/   ClientEventHandler      malilib 事件桥接 + 移动按键声明
├── feature/
│   ├── ClientFeature / FeatureRegistry
│   ├── AutoForwardFeature / QuickTurnFeature
│   ├── AutoCacheNearbyContainersFeature / SchematicSelectionContainerCacheFeature
│   ├── AutoWaterFillFeature / ItemTransferStrategy / AutoMaterialGatherer
│   └── automaterial/               备货 + 续料子模块（11 类）
├── action/RotateAction
├── route/   Route / RouteNode / RouteManager / RouteExecutor / RouteFlowRuntime
├── record/  RecordedSegment / PositionKeyframe / RecordingFile
│            InputRecorder / InputPlayer / RecordingManager
├── integration/  ModIntegration + Litematica / Baritone / ChestTracker / QuickShulker
├── input/   SimulatedInput（★ 唯一写入点）/ KeybindProvider / KeybindCallbacks
├── gui/     PlayerControlppConfigGui / RouteListGui / RecordingListGui / ModMenuIntegration
├── mixin/client/MixinLocalPlayer   全模组唯一 Mixin
└── util/    AtomicFiles / ItemUtil / PlayerUtil / MessageUtil
```

工具层职责：

| 类 | 职责 |
|---|---|
| `SimulatedInput` | 模拟按键唯一写入点，按 owner 引用计数 |
| `AtomicFiles` | 原子写入（临时文件 + `ATOMIC_MOVE`）、损坏文件隔离 |
| `ItemUtil` | 潜影盒判定（判方块类型而非注册名）、物品比较（引用相等）、读盒内物品 |
| `PlayerUtil` | 交互距离（读属性）、`HOTBAR_SIZE` 常量 |
| `MessageUtil` | ActionBar 消息，支持格式参数 |

`MixinLocalPlayer` 只做一件事：潜行与疾跑是实体**状态**不是按键状态，
所以回放和路径疾跑要在 `LocalPlayer.tick()` 之后把状态补回去。

### 配置界面标签页

| 标签页 | 内容 |
|---|---|
| Hotkeys | 全部 10 个热键 + 可缓存容器白名单 + 标记容器列表 |
| Route Hotkeys | 动态路径热键 |
| Settings | 转向角度、缓存容器延迟、填水半径、填水延迟、回放位置修正 |
| Routes / Recording | 独立编辑界面 |
| Baritone联动功能 | 备货热键 + 自动存盒 + 存储模式 + 忽略列表 + 标记容器热键 + 一键建造+续料 + 收集潜影盒 + 补给组数 + **容器距离上限** + 标记容器列表（仅三模组齐备时显示，顶部有红色作弊警示） |

## 五、跨版本差异

### 5.1 兼容层 `compat/`

预处理器能自动处理 Mojang 映射的**重命名**，但**签名变化**必须手工桥接：

| 类 | 桥接的差异 | 分界 |
|---|---|---|
| `ScreenCompat` | `mc.screen` / `setScreen()` → `mc.gui.screen()` / `mc.gui.setScreen()` | 26.2 |
| `DrawCtx` | `GuiGraphics` → `GuiGraphicsExtractor`；`drawString`/`drawCenteredString`/`render` 改名 | 26.1 |
| `SlotActionCompat` | `handleInventoryMouseClick(…ClickType…)` → `handleContainerInput(…ContainerInput…)` | 26.1 |
| `ContainerContentsCompat` | `nonEmptyItems()` 元素 `ItemStack` → `ItemStackTemplate` | 26.1 |
| `PlayerCompat` | `displayClientMessage(text, true)` → `sendOverlayMessage(text)` | 26.1 |
| `NbtCompat` | `CompoundTag` 全部 getter 改返回 `Optional` | 1.21.5 |
| `InventoryCompat` | `Inventory.selected` 字段 → `getSelectedSlot()` / `setSelectedSlot()` | 1.21.5 |
| `InputCompat` | `Input.jumping` / `shiftKeyDown` → `input.keyPresses`（`PlayerInput` record） | 1.21.2 |
| `MaLiLibCompat` | `JsonUtils` 移包 + `getConfigDirectory()` 返回类型 | malilib 0.27（MC 1.21.11） |

另有三处写在业务代码里（要拆分方法签名，藏不进工具类）：
`RouteListGui` / `RecordingListGui` / `PlayerControlppConfigGui` 的 render 与输入事件签名
（1.21.11 起 `MouseButtonEvent` / `CharacterEvent` / `KeyEvent`），
以及 `RouteManager.RouteHotkey` 的 malilib dirty 追踪方法。

未覆盖也不需要覆盖：`Inventory.SELECTION_SIZE` 从 1.21.4 起才有 → 用自有常量 `PlayerUtil.HOTBAR_SIZE`；
`Inventory.INVENTORY_SIZE` 所有版本都有，直接用。

### 5.2 实测的版本差异分布

对比 `src/main`（26.2）与各版本预处理产物（忽略空行）得到的**完整**差异集：

| 目标版本 | 有差异的文件 |
|---|---|
| 26.1.2 | `ScreenCompat` |
| 1.21.11 | + `ContainerContentsCompat` `DrawCtx` `PlayerCompat` `SlotActionCompat` `PlayerControlppConfigGui` `RecordingListGui` `RouteListGui` |
| **1.21.8** | + `MaLiLibCompat` `ContainerSearcher` `ChestTrackerIntegration` `Playercontrolpp` `InputRecorder` `Route` `RouteExecutor` `RouteManager`（后 5 个只是 `Identifier`↔`ResourceLocation`、`identifier()`↔`location()` 的自动改名） |
| **1.21.6** | 与 1.21.8 **逐字节相同** |
| 1.21.4 | + `InventoryCompat` `NbtCompat`（1.21.5 的 Optional / getSelectedSlot 分界） |
| 1.21.1 | + `InputCompat`（1.21.2 的 PlayerInput 分界） |

结论：**1.21.6 / 1.21.8 不需要任何新的兼容代码**，现有 `MC >= 12105`（真）与
`MC >= 12111`（假）两道门槛已经把它们放在正确的一侧。

### 5.3 唯一的语义差异

| 差异 | 说明 |
|---|---|
| 1.21.1 的疾跑录制 | 该版本 `Input` 没有疾跑**按键**状态，回退为读实体的疾跑**状态**（`player.isSprinting()`）。跨版本共用录制文件时这一项语义不同 |

### 5.4 预处理器语法

```java
//#if MC >= 260000
this.delegate.text(font, text, x, y, color, shadow);              // 生效分支
//#else
//$$ this.delegate.drawString(font, text, x, y, color, shadow);   // 非生效分支
//#endif
```

版本号是整数：`1.21.4` → `12104`，`1.21.8` → `12108`，`26.2` → `260200`。
`//$$` 行同样会被映射重命名器处理，两个分支都能跟着版本更新走。

## 六、新增一个 Minecraft 版本

1. `settings.json` 加版本号；
2. `build.gradle` 的 `preprocess {}` 里 `createNode('<mc>', <整数>, '')` 并 `link` 进链
   （当前链：1.21.1 → 1.21.4 → 1.21.6 → 1.21.8 → 1.21.11 → 26.1.2 → 26.2）；
3. 建 `versions/<mc>/gradle.properties`（照抄邻近版本，改 `minecraft_version` /
   `minecraft_dependency` / `game_versions` / `malilib_jar` / `modmenu_jar`）；
4. 把该版本的 malilib / ModMenu jar 放进 `libs/`；
5. `./gradlew :<mc>:compileJava`，按报错逐个在 `compat/` 里补桥接；
6. `diff -rq versions/<新>/build/preprocessed/main versions/<邻近>/build/preprocessed/main`
   核对差异是否只落在预期的兼容类上。

> **下载 jar 的来源**：malilib / ModMenu 1.21.4 以后的版本不在 masa 的 maven 上，
> 走 Modrinth API：`https://api.modrinth.com/v2/project/{malilib,modmenu}/version?loaders=["fabric"]&game_versions=["<mc>"]`。
> Parchment 没有 1.21.6 / 1.21.7 / 1.21.8 的发布，`parchment_version` 留空即可。

## 七、设计原则

1. **模拟真人输入**：所有移动通过模拟按键，不改位置或 velocity。
   唯一的位置写入（回放偏移修正）是默认关闭的可选项。
2. **单一写入点**：模拟按键只经 `SimulatedInput`，任何直接 `setDown` 都是 bug。
3. **客户端纯执行**：不修改服务端状态，不发异常数据包。
4. **崩溃不丢数据**：自有持久化文件全部原子写入；解析失败的文件隔离而非覆盖。
5. **低耦合**：功能生命周期由注册表驱动，新增功能不改事件层。
6. **单一源码多版本**：版本差异集中在 `compat/` 与 `//#if`。
7. **联动独立**：全部反射，无编译时依赖，缺失时静默降级。
8. **国际化**：所有用户可见文本 English + 简体中文。
9. **malilib 风格 GUI**：与 Litematica / Tweakeroo 保持一致的交互体验。

## 八、待办与改进方向

按性价比排序。

| 优先级 | 项 | 说明 |
|---|---|---|
| 高 | **补 JUnit** | `ItemTransferStrategy`、`Route.getTotalSegments`、`RecordedSegment` NBT 往返、`MarkedContainerManager.parseEntry`（含新的 `off` 后缀）都是零 MC 依赖的纯逻辑。多版本工程加 test 源集要同时处理 7 个子项目的预处理配置 |
| 中 | **抽出 `ContainerCacheOps`** | 「求最近面 + 构造 `BlockHitResult` + 发 `useItemOn`」这段在 6 处重复；「求最近面」本身有 4 份实现（`AutoCacheNearbyContainersFeature` / `SchematicSelectionContainerCacheFeature` 内联 / `ContainerOpener.nearestFace` / `ShulkerBoxStorage.getNearestFace`），其中两份符号相反但等价 |
| 中 | **拆 `GatherContext`** | 25 个 public 字段被 7 个模块任意读写。切成 material / search / pathing / container 四个小状态对象，方法签名就能一眼看出它能碰什么 |
| 低 | **标记容器专用界面** | 现在靠 `ConfigStringList` 手打 `off`。仿 `RouteListGui` 做一个带勾选框、距离显示、重命名的列表界面会好得多 |
| 低 | **自适应开箱超时** | 用观测到的「点击→界面出现」tick 数 × 3 代替固定的 `OPEN_WAIT_TICKS = 10`，失败容器不再各卡半秒 |
| 低 | **SoA 录制存储** | 录制时长到数十万 tick 时，把 `RecordedSegment` 拆成并行数组可做到零对象分配 |
| 低 | 动作流系统 | 预留 `RotateAction` / `JumpAction` / `WaitAction` / `SneakAction` |

## 九、工具脚本

| 脚本 | 用途 |
|---|---|
| `tools/MakeIcon.java` | 生成 `assets/playercontrolpp/icon.png`（改配色/构图后重跑） |
| `tools/checkzh.py` | 扫出仍含英文注释的文件（`--show <片段>` 打印具体行） |
| `tools/comments.py` | `dump` 只打印英文注释块；`apply <文件> <json>` 按块号整块替换 |
| `tools/applytr.py` | 把一个多分组 json 的译文批量套到对应源文件（分组名→文件映射写在脚本里） |

```bash
javac -d /tmp/icon tools/MakeIcon.java && java -cp /tmp/icon MakeIcon src/main/resources/assets/playercontrolpp/icon.png
python tools/checkzh.py
```

---

> 仓库：https://github.com/Alonediamond/playercontrolpp ·
> 开发落点：`src/main/`（主工程 26.2）· 许可证 MIT
