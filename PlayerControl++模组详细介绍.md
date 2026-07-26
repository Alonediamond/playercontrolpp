# PlayerControl++ 模组详细介绍

## 基本信息

| 项目 | 详情 |
|------|------|
| **模组名称** | PlayerControl++ |
| **模组 ID** | `playercontrolpp` |
| **版本** | 1.5 |
| **作者** | Alonediamond |
| **许可证** | MIT |
| **代码规模** | ~7300 行 Java（61 个源文件），**一份源码构建 5 个 MC 版本** |
| **GitHub** | https://github.com/Alonediamond/playercontrolpp |

## 支持的 Minecraft 版本

| 子项目 | Minecraft | Java | 映射 | malilib | ModMenu |
|--------|-----------|------|------|---------|---------|
| `:1.21.1`  | 1.21 – 1.21.1 | 21 | Mojang + Parchment 2024.11.17 | 0.21.10 | 11.0.4 |
| `:1.21.4`  | 1.21.4        | 21 | Mojang + Parchment 2025.03.23 | 0.23.5  | 13.0.3 |
| `:1.21.11` | 1.21.11       | 21 | Mojang + Parchment 2025.12.20 | 0.27.12 | 17.0.0 |
| `:26.1.2`  | 26.1 – 26.1.2 | 25 | Mojang（未混淆）              | 0.28.6  | 18.0.0-beta.1 |
| `:26.2`    | 26.2          | 25 | Mojang（未混淆）              | 0.29.3  | 20.0.1 |

五个版本功能完全一致，唯一的行为差异见 [跨版本行为差异](#跨版本行为差异)。

## 技术架构

| 项目 | 详情 |
|------|------|
| **模组加载器** | Fabric Loader 0.19.3+ |
| **构建工具** | Gradle 9.5.1 + Fabric Loom 1.17 + Fallen_Breath preprocessor |
| **映射系统** | 统一 Mojang 官方映射（< 26.0 叠加 Parchment 参数名） |
| **核心框架** | malilib（配置 / GUI / 热键系统） |
| **GUI 集成** | ModMenu（模组菜单入口，可选） |
| **运行环境** | 纯客户端（`"environment": "client"`） |
| **Mixin** | 1 个（`MixinLocalPlayer`），compatibilityLevel 按版本自动填充 |
| **Access Widener** | 无 |

### 前置模组（必需）

| 前置模组 | 用途 |
|----------|------|
| **Fabric API** | Fabric 基础 API（由 malilib 传递引入） |
| **malilib** | 配置管理、GUI 框架、热键系统 |

### 可选联动模组

全部通过 `Class.forName()` + `FabricLoader.isModLoaded()` 反射调用，无编译时依赖，
缺失时静默降级。

| 联动模组 | 用途 |
|----------|------|
| **Litematica** | 切换投影渲染层 / 读取材料清单 / 读取投影方块状态 |
| **Baritone** | 自动寻路前往容器位置 |
| **ChestTracker** | 搜索已缓存的容器物品位置 |
| **QuickShulker** | 在背包中直接打开潜影盒（存料、取水桶） |
| **ModMenu** | 模组配置界面入口 |

## 功能模块

### 一、基础控制增强

#### 1.1 自动前进 (Auto Forward)
- **热键**: 可自定义 Toggle 键位
- **行为**: 持续按住前进键，等价于按住 W
- **实现**: 通过 `SimulatedInput` 声明"需要按住 W"，由统一的输入仲裁器落地
- **安全**: 切换世界/维度自动关闭，ActionBar 状态提示

#### 1.2 快速转向 (Quick Turn)
- **热键**: 可自定义 Trigger 键位
- **行为**: 瞬间旋转指定角度（默认 180°，范围 0-360°）
- **实现**: 同时设置 `yRot` 与 `yRotO`，转向在一帧内完成。
  相机会在一个 tick 内对这两个值做插值，只设前者会让转向变成 50 ms 的平滑扫过

### 二、路径流系统 (Route Flow System)

#### 2.1 系统定位
轻量级自动移动系统，核心原则为**模拟真人输入而非直接控制实体**。

```
热键 → RouteFlowRuntime → RouteExecutor → SimulatedInput → KeyMapping
```

#### 2.2 路径管理
- **数据结构**: 至少 2 个 `RouteNode`（起点 + 终点），可插入任意中间导航点
- **不变式保护**: `getNodes()` 返回只读视图，增删只能走
  `insertNode()` / `removeNode()`，节点数不会被减到 1 而使执行器越界
- **持久化**: `config/playercontrolpp_routes.json`，**原子写入**（写临时文件 + 替换）

#### 2.3 路径执行
- **移动方式**: 模拟按住前进键 + 自动 yaw 视角修正
- **转向系统**: 混合模式（允许玩家轻微移动视角，偏差过大时自动修正）
  - 分级修正速度: >45° = 25°/tick，>15° = 18°/tick，其余 15°/tick
  - 死区: ±2° 内不修正（避免明显"锁头"）
  - 到达导航点时瞬间 Snap 至下一目标方向
- **仅控制 XZ 平面**: 忽略 Y 轴，避免垂直偏差导致原地打转

#### 2.4 循环模式

| Loop Count | 行为 |
|------------|------|
| 1 | 单次: 起点 → 终点 |
| >1 | 往返 N 次 |
| 0 | 无限循环 |

#### 2.5 卡住检测
- 3 秒未移动 → 自动跳跃一次
- 跳跃后 5 秒仍未移动 → 终止路径并提示

#### 2.6 路径级别选项

| 选项 | 说明 |
|------|------|
| **Sprint (疾跑)** | 执行路径时全程按住疾跑键 |
| **LayerCtrl (图层控制)** | 每次遍历完成自动切换 Litematica 渲染层 |
| **Layer Inc (图层增量)** | 仅 LayerCtrl 开启时显示，正数向上/负数向下（默认 +1） |

#### 2.7 热键管理
路径热键在两条编辑路径上都会落盘：
- 在 **Routes** 编辑界面修改 → 关闭界面时保存
- 在 **Route Hotkeys** 标签页绑定 → `Configs.onConfigsChanged()` 同时保存路径文件

删除路径后调用 `IKeybindManager.updateUsedKeys()` 让 malilib 重建按键映射。
`IKeybindManager` 没有移除单个按键的接口，但 `KeybindProvider` 本来就是从
`RouteManager` 动态枚举路径热键的，所以重建映射就是唯一需要做的事——
按键映射只有这一条注册路径，不会出现两套注册不同步。

### 三、Litematica 投影联动

- **反射调用**: `DataManager.getRenderLayerRange().moveLayer(amount)`，
  与 Litematica 内置 PageUp/PageDown 行为一致
- **模式限制**: 仅在 SINGLE_LAYER 模式下生效
- **ActionBar 反馈**: 显示切换后的渲染层
- **性能**: 所有反射 `Method` 句柄缓存；候选方法名探测遍历 `getMethods()` 一次完成，
  不靠逐个 `getMethod()` 抛异常来试探（Litematica 在不同版本里重命名过这个 getter，
  必须容忍多种拼写，而异常栈的构造是热路径里最贵的操作之一）

### 四、玩家行为录制与回放

#### 4.1 录制内容

| 录制内容 | 说明 |
|----------|------|
| 移动 | 前后 / 左右分量（`Input.getMoveVector()`） |
| 疾跑 | 疾跑键状态（1.21.1 例外，见[跨版本行为差异](#跨版本行为差异)） |
| 跳跃 | 跳跃键状态 |
| 潜行 | 潜行键状态 |
| 视角 | Yaw + Pitch |
| 操作 | 左键（攻击）/ 右键（使用物品） |

#### 4.2 RLE 压缩
- 连续相同输入合并为一个 `RecordedSegment`，带 `duration` 表示持续 tick 数
- 任意字段变化即结束当前段并新建一段
- 每 tick 采样，靠 RLE 压缩而非跳帧降采样，保证回放精度
- 玩家连续前进 100 tick → 1 个 segment，而不是 100 个 frame

#### 4.3 存储格式

```
config/playercontrolpp/recordings/
├── index.json        # 仅元数据（名称、时长、维度），GUI 只读这个
├── record_001.pcr    # 完整数据（Segments + Keyframes），NBT 二进制压缩，按需加载
├── record_002.pcr
└── ...
```

- **GUI 打开速度与录制数量无关**：列表只读 `index.json`
- **原子写入**：索引与录制文件都是"写临时文件 + 原子替换"，
  序列化中途异常或崩溃不会把 `index.json` 截断成 0 字节
- **线程安全**：序列化（`toNbt()`）在客户端线程完成，只把不可变的 `CompoundTag`
  交给后台线程，避免后台写盘时主线程改名/删除造成数据竞争
- **单条 IO 线程**：所有读写走一个守护型 `SingleThreadExecutor`
- **写序**：`.pcr` 写成功后才回主线程更新索引，
  崩溃不会留下指向不存在文件的索引条目
- **损坏保护**：索引解析失败时另存为 `index.json.corrupt-N` 并提示用户，
  且不标记为已加载，下一次保存不会用空列表覆盖它；
  启动时剔除 `.pcr` 已丢失的索引条目，GUI 不会列出无法播放的录制

#### 4.4 回放

| 参数 | 说明 |
|------|------|
| Play Count = 0 | 无限循环 |
| Play Count = N | 重复回放 N 次 |
| 起点移动 | 模拟行走至录制起点，不使用瞬移 |
| 疾跑模拟 | 自动按下/松开疾跑键，非疾跑帧有 3 tick 释放延迟 |
| 异步加载 | 点击播放后在后台线程解压 + 反序列化，读完回主线程启动，不卡帧 |

**状态流**: `IDLE → LOADING → MOVING_TO_START → PLAYING → COMPLETED`（或循环）

播放完成或停止时释放 `recording` / `segments` / `keyframes` 引用，
长录制（可能数 MB）不会常驻内存。

#### 4.5 位置偏移处理

回放复现的是**按键输入**而不是坐标，因此因碰撞、延迟、服务端移动判定产生偏移
属于正常现象。每 20 tick 记录一个 `PositionKeyframe` 用于检测偏移：

- 偏移超过 **4 格**时 ActionBar 提示一次，说明可能的原因与如何开启修正
- 设置项 **回放位置修正**（`playbackPositionCorrection`，**默认关闭**）开启后，
  偏移超过 **2 格**时把玩家拉回关键帧位置

硬修正默认关闭是有意的：改写客户端坐标会与服务端权威位置不一致，
可能被反作弊判定为飞行，也与"不直接修改位置"的设计原则冲突。建议仅在单人世界开启。

#### 4.6 安全机制
- 播放或加载期间禁止开始新录制
- 录制/播放开始时自动退出所有 GUI
- 录制期间 ActionBar 常驻提示

### 五、全自动投影材料备货 (Auto Material Gathering)

#### 5.1 前置条件
- 需同时安装 **Baritone**、**Litematica**、**ChestTracker**
- 需在 Litematica 中开启**材料清单信息 HUD**（Litematica 只在 HUD 开启时维护该列表）
- ChestTracker 需提前缓存过存有所需物品的容器
- ChestTracker 搜索范围与项目列表范围不可设为无限

#### 5.2 执行流程（11 状态任务状态机）

| 状态 | 描述 |
|------|------|
| **IDLE** | 空闲 |
| **ANALYZING** | 读取材料清单，分析缺失物品（按缺额从多到少排序） |
| **SEARCHING** | 调用 ChestTracker 搜索容器位置 |
| **PATHING** | 调用 Baritone (`GoalGetToBlock`) 寻路 |
| **OPENING_CONTAINER** | 构造 `BlockHitResult` 精确右键目标（隔空开箱，避免相邻容器误触） |
| **TRANSFERRING_ITEM** | 按数量策略取出物品 |
| **VERIFYING** | 验证数量是否满足 |
| **NEXT_ITEM** | 切换到下一个缺失物品 |
| **COMPLETED** | 所有材料备齐 |
| **FAILED** | 单项失败，自动跳过 |
| **STOPPED** | 手动中断 |

`FAILED` 的提示是参数化的：区分"寻路卡住"与"Baritone 始终未开始寻路"，
不会让用户在无关的方向上排查。

#### 5.3 容器交互
- **精确面瞄准**: 计算容器最靠向玩家的面，构造 `BlockHitResult`
- **隔空开箱**: `useItemOn()` 直接发交互包，绕过客户端射线检测
- **多次尝试**: 首次失败 → 跳跃重试 → 再次重试 → 相邻六个方向回溯
- **内容验证**: 通过 `DataComponents.CONTAINER` 窥探潜影盒内部，确认含目标物品后才取盒
- **按键归属**: `useItemOn` 抛异常时的右键回退由 `SimulatedInput` 记账，
  功能结束时统一释放，不会留下卡住的右键
- **交互距离**: 读 `Attributes.BLOCK_INTERACTION_RANGE` 属性。
  1.20.5 之后交互距离是属性，服务端插件、其它模组、药水效果都能改它，
  硬编码会算错范围

#### 5.4 取物数量策略 (`ItemTransferStrategy`)

| 需求数量 | 策略 |
|---------|------|
| ≤ 64 | 取 1 组 |
| 65 ~ 1728 | 取 `ceil(需求 / 64)` 组 |
| > 1728 | 取 `floor(需求 / 1728)` 个整盒，余量按组取 |

盒数用**向下取整**，余量交给散装逻辑——用向上取整会超量取物
（需求 1729 时会取走 2 整盒 = 3456 个，是实际需求的两倍）。

- **散装优先**: 少量需求不触发取盒
- **潜影盒后取**: 仅在缺额 > 128（2 组以上）时才考虑取整盒
- **数量限制**: 按物品类型追踪已取数量，达到计划值即停止

该类不依赖任何 Minecraft 类型，是全项目最容易做纯 JUnit 测试的地方。

#### 5.5 材料自动存入潜影盒

背包空间不足时自动把已收集的建筑材料存入潜影盒，随后继续备货。

| 模式 | 流程 |
|------|------|
| **模拟手动放置** | 放置潜影盒 → 打开 → 存入 → 关闭 → 镐子挖掘 → 等待拾取（5 秒超时） |
| **调用 QuickShulker** | 直接发 `OpenShulkerPacket` 在背包中打开 → 存入 → 关闭（无需放置/挖掘） |

**安全与容错**:
- 满盒自动跳过：通过 GUI 实测确认满盒后记录槽位，跨存储周期保持，
  不反复打开已满的盒子（物品 NBT 可能是过期的，只有开箱才知道真实状态）
- 无空槽标记：27 槽全占（即使部分未满）时标记为"无法接受新类型物品"
- 无可用盒时停止并提示
- 取整盒物品时跳过自动存入（避免刚取的盒子又被存回去）
- 放置位判定用 `isFaceSturdy(level, pos, Direction.UP)`——
  "这个方块上面能不能放东西"的准确问法，且带缓存
- 挖掘/放置期间的按键交由 `SimulatedInput` 管理，不与回放争抢

### 六、自动缓存附近容器

#### 6.1 功能概述
帮助 ChestTracker 等容器缓存类模组自动获取附近容器内容，无需手动逐个开箱。

#### 6.2 执行流程（6 状态任务状态机）

| 状态 | 描述 |
|------|------|
| **SCANNING** | 以玩家为中心、交互距离为半径球形扫描未缓存容器（白名单过滤） |
| **OPENING_CONTAINER** | 精确右键目标（等待最多 10 tick） |
| **WAITING_AFTER_OPEN** | 等待 1 tick，让 ChestTracker 记录内容 |
| **CLOSING_GUI** | 关闭 GUI，记录坐标为已访问 |
| **COOLDOWN** | 等待配置的延迟 tick |
| **AUTO_STOP_COUNTDOWN** | 附近无可缓存容器时 3 秒倒计时自动关闭，期间发现新容器自动取消 |

#### 6.3 扫描实现

以交互距离 5 计算，扫描立方体为 11×11×11 = 1331 次迭代，因此内层做了几件事：

| 做法 | 原因 |
|------|------|
| 白名单预解析为 `Set<Block>`，仅在配置变更时重建 | 内层判断变成引用哈希查表，不做注册表反查、不分配字符串 |
| 复用一个 `MutableBlockPos` | 消除每次迭代一个 `BlockPos` 的分配（存入结果时 `.immutable()` 复制） |
| 单趟求最近目标 | 只需要最近的那一个，不必收集全部再排序 |
| 倒计时期间每 5 tick 扫一次 | 玩家在四分之一秒里走不了多远 |

#### 6.4 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 自动缓存附近容器 | `ConfigHotkey` | (空) | Toggle 热键 |
| 可缓存容器白名单 | `ConfigStringList` | 28 种原版容器 | 允许自动缓存的方块 ID |
| 缓存容器延迟 | `ConfigInteger` | 1（1-200） | 关闭当前容器后等待 N tick 再开下一个 |

白名单默认含 chest、trapped_chest、ender_chest、barrel、hopper、dispenser、
dropper、furnace、blast_furnace、smoker、brewing_stand + 全部 17 种潜影盒变种
（由 `DyeColor` 枚举生成，加了新染色不必改代码）。

#### 6.5 玩家移动与安全
- 任务运行期间持续重新扫描当前位置附近的容器，走到新位置会自动纳入流程
- 已记录过的容器不再重复打开（每个任务维护已访问集合）
- 所有操作走正常客户端交互流程（右键打开 → 等待 GUI → 正常关闭），
  不直接修改容器数据，兼容单人 / 局域网 / 多人服务器
- 玩家处在其它 GUI 中时暂停交互，不干扰当前操作
- 世界切换立即停止

### 七、自动填水 (Auto Water Fill)

#### 7.1 功能概述
自动为 Litematica 投影中"应含水但世界未含水"的方块填水。

#### 7.2 执行流程

```
SCANNING → FINDING_BUCKET → [SHULKERING] → ROTATING → PLACING_WATER → COOLDOWN → SCANNING
```

无目标时进入 `AUTO_STOP_COUNTDOWN`（3 秒），期间走到新位置会自动恢复。

#### 7.3 水桶获取（三级回退）

1. 快捷栏中有水桶 → 直接选中
2. 物品栏中有水桶 → 三次容器点击换到快捷栏 → 选中
3. 潜影盒中有水桶 + 已安装 QuickShulker → 在背包中打开盒子 → **取出一个** →
   回到第 1/2 步完成选中

第 3 步等待容器界面最多 20 tick（QuickShulker 的数据包往返需要几 tick），
并限制最多重试 3 次，失败会提示并关闭功能而不是无限重试。

选中槽位后会发送 `ServerboundSetCarriedItemPacket`：只改客户端的选中槽位，
服务端仍认为玩家手持之前的物品，`useItemOn` 会静默失败。

#### 7.4 扫描与重复右键
- 单趟求最近目标 + 复用 `MutableBlockPos`
- `recentlyAttempted` 冷却集合（20 tick）：刚填过的方块在服务端状态回传前
  不会被再次扫成候选，避免重复右键同一方块
- 投影 placement 边界的反射句柄全部缓存
- 点击前重新校验世界方块状态与投影期望，扫描结果过期不会误操作

#### 7.5 配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 自动填水 | `ConfigHotkey` | (空) | Toggle 热键 |
| 填水范围扫描半径 | `ConfigInteger` | 5（0-5） | 使用时会被限制在玩家可达距离内 |
| 填水操作延迟 | `ConfigInteger` | 1（1-200） | 两次填水之间的间隔 tick |

#### 7.6 安全机制
- 潜行时暂停（玩家随时可夺回控制权）
- 死亡 / 世界切换立即停止
- 无水桶或无投影时提示并自动关闭

## 架构

### 输入仲裁器 `SimulatedInput`

模组里有多个功能需要按住移动键或点击键：输入回放、自动前进、路径跟随、
容器开启、潜影盒挖掘。如果各自直接调用 `KeyMapping.setDown()`，
后写的会覆盖前写的——自动备货正在按住挖掘键时，若回放刚好结束并释放全部按键，
挖掘就会被打断；反之亦然。忘记释放则会留下永久按下的键。

因此模组约定：

- 功能只**声明意图**：`hold(key, owner)` / `release(key, owner)` / `releaseAll(owner)`
- 按 owner **引用计数**：A 松手但 B 还需要时，键不会被误放
- `apply()` 是**唯一**调用 `setDown` 的地方，每 tick 末尾在所有功能 tick 完之后执行
- 从未被声明过的键**永不写入**，玩家的真实输入不受影响

> 改这份代码时：不要直接调用 `KeyMapping.setDown()`。

### 功能生命周期 `ClientFeature` + `FeatureRegistry`

`ClientFeature` 定义 `onClientTick` / `onWorldChange` / `isActive`（全部有默认实现），
在 `InitHandler.registerFeatures()` 中按顺序注册。`FeatureRegistry` 统一广播，
并**捕获单个功能的异常**，避免一个功能抛错就让后面所有功能停摆。

注册顺序即 tick 顺序，也是世界切换的通知顺序：

```
AutoForwardFeature → RouteFlowRuntime → RecordingManager
→ AutoMaterialGatherer → AutoCacheNearbyContainersFeature → AutoWaterFillFeature
```

路径与录制排在前面，因为它们产生的移动输入要由 `ClientEventHandler`
在所有功能 tick 完之后读取。

> 改这份代码时：新增功能实现 `ClientFeature` 并在 `InitHandler` 注册，
> 不要往 `ClientEventHandler` 里加 tick 调用。

### 跨版本兼容层 `compat/`

预处理器能自动处理 Mojang 映射在版本间的**重命名**，但**签名变化**
（参数类型改变、返回类型变成 `Optional`、字段变成 getter）必须手工桥接。
这些差异全部集中在 `compat/` 的 9 个类里，业务代码保持单一写法。

| 类 | 桥接的差异 | 分界版本 |
|----|-----------|---------|
| `ScreenCompat` | `mc.screen` / `mc.setScreen()` → `mc.gui.screen()` / `mc.gui.setScreen()` | 26.2 |
| `DrawCtx` | `GuiGraphics` → `GuiGraphicsExtractor`；`drawString`/`drawCenteredString`/`render` 的对应关系 | 26.1 |
| `SlotActionCompat` | `handleInventoryMouseClick(…ClickType…)` → `handleContainerInput(…ContainerInput…)` | 26.1 |
| `ContainerContentsCompat` | `ItemContainerContents.nonEmptyItems()` 元素类型 `ItemStack` → `ItemStackTemplate` | 26.1 |
| `PlayerCompat` | `displayClientMessage(text, true)` → `sendOverlayMessage(text)` | 26.1 |
| `NbtCompat` | `CompoundTag` 全部 getter 改为返回 `Optional` | 1.21.5 |
| `InventoryCompat` | `Inventory.selected` 字段 → `getSelectedSlot()` / `setSelectedSlot()` | 1.21.5 |
| `InputCompat` | `Input.jumping` / `shiftKeyDown` 字段 → `input.keyPresses`（`PlayerInput` record） | 1.21.2 |
| `MaLiLibCompat` | malilib `JsonUtils` 移包 + `getConfigDirectory()` 返回类型变化 | 1.21.11 (malilib 0.27) |

另有三处差异直接写在业务代码里（因为要拆分方法签名，无法藏进工具类）：
`RouteListGui` / `RecordingListGui` / `PlayerControlppConfigGui` 的 render 与输入事件签名，
以及 `RouteManager.RouteHotkey` 的 malilib dirty 追踪方法。

### Integration 抽象层

`ModIntegration` 接口（`isLoaded()` / `initialize()`）统一封装联动反射：
`LitematicaIntegration` / `BaritoneIntegration` / `ChestTrackerIntegration` /
`QuickShulkerIntegration`。

### 工具层

| 类 | 职责 |
|----|------|
| `SimulatedInput` | 模拟按键的唯一写入点，按 owner 引用计数 |
| `AtomicFiles` | 原子写入（临时文件 + `ATOMIC_MOVE`）、损坏文件隔离 |
| `ItemUtil` | 潜影盒判定、物品比较、读取盒内物品 |
| `PlayerUtil` | 交互距离（读属性）、快捷栏大小常量 |
| `MessageUtil` | ActionBar 消息，支持格式参数 |

## 跨版本行为差异

代码逻辑五个版本完全一致，唯一的语义差异来自 Minecraft API 本身：

| 差异 | 说明 |
|------|------|
| **1.21.1 的疾跑录制** | 1.21.1 的 `Input` 没有疾跑**按键**状态。该版本回退为读取实体的疾跑**状态**（`player.isSprinting()`）。跨版本共用录制文件时，这一项的语义不同 |
| **`Inventory.SELECTION_SIZE`** | 1.21.4 起才有该常量，1.21.1 没有。因此快捷栏大小使用自有常量 `PlayerUtil.HOTBAR_SIZE`；`Inventory.INVENTORY_SIZE` 五个版本都有，直接用官方常量 |

## 多版本构建工程

主工程（mainProject）为 **26.2**：`src/main/java` 里的源码就是 26.2 的源码，
其余版本由预处理器在 `versions/<mc>/build/preprocessed/` 下自动生成。
**只编辑 `src/main/`。**

```bash
# 构建全部 5 个版本，jar 汇总到 build/libs/
./gradlew buildAndGather

# 只构建单个版本
./gradlew :1.21.4:build

# 在某个版本上启动游戏调试
./gradlew :26.2:runClient
```

`libs/` 目录里已提交各版本所需的 malilib 与 ModMenu jar，克隆后无需额外配置即可构建。

预处理器语法：

```java
//#if MC >= 260000
this.delegate.text(font, text, x, y, color, shadow);          // 生效分支
//#else
//$$ this.delegate.drawString(font, text, x, y, color, shadow); // 非生效分支
//#endif
```

版本号是整数形式：`1.21.4` → `12104`，`26.2` → `260200`。
`//$$` 前缀的行同样会被映射重命名器处理，两个分支都能跟着版本更新走。

### 新增一个 Minecraft 版本

1. `settings.json` 里加版本号；
2. `build.gradle` 的 `preprocess` 块里 `createNode(...)` 并 `link` 到相邻节点；
3. 建 `versions/<mc>/gradle.properties`（照抄邻近版本改 MC 版本号与依赖）；
4. 把该版本的 malilib / ModMenu jar 放进 `libs/`，并在上一步的 properties 里填
   `malilib_jar` / `modmenu_jar`；
5. `./gradlew :<mc>:compileJava`，按报错逐个在 `compat/` 里补桥接。

## 项目结构

```
playercontrolpp/
├── settings.json              # 参与构建的版本列表（CI 的 matrix 也读这里）
├── build.gradle               # 预处理器版本节点图
├── common.gradle              # 所有子项目共用的构建逻辑
├── gradle.properties          # 模组元信息（mod_id / mod_version / …）
├── libs/                      # 各版本的 malilib + ModMenu jar（已提交，克隆后可直接构建）
├── tools/MakeIcon.java        # 生成模组图标，可改配色/构图后重跑
├── versions/
│   ├── mainProject            # 内容为 "26.2"
│   └── <mc>/gradle.properties # 该版本的 MC 版本号、依赖版本、jar 文件名
└── src/main/
    ├── java/com/alonediamond/playercontrolpp/
    │   ├── Playercontrolpp.java              # ModInitializer 入口 + MOD_ID + LOGGER
    │   ├── client/PlayercontrolppClient.java # ClientModInitializer 入口
    │   │
    │   ├── compat/                           # ★ 跨版本兼容层（9 个类）
    │   │
    │   ├── config/
    │   │   ├── Configs.java                  # 热键 / 设置 / Baritone / 容器白名单
    │   │   ├── InitHandler.java              # malilib 注册 + 功能注册表 + 联动初始化
    │   │   └── StorageMode.java              # 潜影盒存储模式枚举
    │   │
    │   ├── event/ClientEventHandler.java     # 事件桥接 + 移动按键声明
    │   │
    │   ├── feature/
    │   │   ├── ClientFeature.java            # 功能生命周期接口
    │   │   ├── FeatureRegistry.java          # 功能注册表
    │   │   ├── AutoForwardFeature.java
    │   │   ├── QuickTurnFeature.java
    │   │   ├── AutoCacheNearbyContainersFeature.java
    │   │   ├── AutoWaterFillFeature.java
    │   │   ├── ItemTransferStrategy.java     # 取物数量策略（零 MC 依赖）
    │   │   ├── AutoMaterialGatherer.java     # 自动备货公共门面
    │   │   └── automaterial/                 # 备货子模块（9 个类）
    │   │
    │   ├── action/RotateAction.java
    │   ├── route/                            # Route / RouteNode / RouteManager
    │   │                                     # RouteExecutor / RouteFlowRuntime
    │   ├── record/                           # RecordedSegment / PositionKeyframe
    │   │                                     # RecordingFile / InputRecorder
    │   │                                     # InputPlayer / RecordingManager
    │   ├── integration/                      # 4 个联动 + ModIntegration 接口
    │   ├── input/
    │   │   ├── SimulatedInput.java           # ★ 模拟按键唯一写入点
    │   │   ├── KeybindProvider.java
    │   │   └── KeybindCallbacks.java
    │   ├── gui/                              # 主配置界面 / 路径 / 录制 / ModMenu
    │   ├── mixin/client/MixinLocalPlayer.java
    │   └── util/                             # AtomicFiles / ItemUtil / PlayerUtil / MessageUtil
    │
    └── resources/
        ├── fabric.mod.json                   # 用 ${…} 占位符，按版本填充
        ├── playercontrolpp.mixins.json
        └── assets/playercontrolpp/
            ├── icon.png                      # 128×128
            └── lang/{en_us,zh_cn}.json       # 各 154 键
```

## 配置界面结构

主配置界面（默认 P+C 打开）分为以下标签页：

| 标签页 | 内容 |
|--------|------|
| **Hotkeys** | 打开配置界面、自动前进、快速转向、切换录制、自动投影材料备货、自动缓存附近容器、自动填水 + 可缓存容器白名单 |
| **Route Hotkeys** | 动态路径热键（每个路径独立绑定） |
| **Settings** | 转向角度、缓存容器延迟、填水范围扫描半径、填水操作延迟、回放位置修正 |
| **Routes** | 路径流系统编辑界面（路径列表 / 导航点管理 / 选项开关） |
| **Recording** | 录制与回放界面（录制管理 / 播放控制 / 损坏文件检测） |
| **Baritone联动功能** | 备货热键 + 自动存盒 + 存储模式（QuickShulker 安装时）+ 全局忽略开关 + 忽略列表编辑器（仅三模组均安装时显示） |

Baritone 标签页顶部有红色警示："本模式为作弊功能，若在多人服务器使用请确保经得服主或管理员同意"。

## 设计原则

1. **模拟真人输入**: 所有移动通过模拟按键实现，不直接修改位置或 velocity。
   唯一的位置写入（回放偏移修正）是默认关闭的可选项
2. **单一写入点**: 模拟按键只能经 `SimulatedInput` 落地，任何直接 `setDown` 都是 bug
3. **客户端纯执行**: 不修改服务端状态，不发送异常数据包，尽量兼容反作弊服务器
4. **崩溃不丢数据**: 所有自有持久化文件原子写入；解析失败的文件隔离而非覆盖
5. **低耦合架构**: 功能生命周期由注册表驱动，新增功能不需要改事件层
6. **单一源码多版本**: 版本差异集中在 `compat/` 与 `//#if`，业务代码只有一份
7. **联动独立**: 所有联动通过反射，无编译时依赖，缺失时静默降级
8. **国际化**: 所有用户可见文本支持 English + 简体中文
9. **malilib 风格 GUI**: 与 Tweakeroo / Litematica / malilib 保持一致的交互体验

## 已知限制与未来方向

| 项目 | 说明 |
|------|------|
| **无自动化测试** | `ItemTransferStrategy`、`Route.getTotalSegments`、`RecordedSegment` 的 NBT 往返都是零 MC 依赖的纯逻辑，最适合先补 JUnit。多版本工程加 test 源集需要同时处理 5 个子项目的预处理配置 |
| **`GatherContext` 是共享可变状态** | 25 个 public 字段被 7 个模块任意读写。可切成 material / search / pathing / container 四个小状态对象，让每个方法的签名一眼看出它能碰什么 |
| **动作流系统** | 预留 `RotateAction / JumpAction / WaitAction / SneakAction` 等 |
| **输入录制增强** | 条件判断、编辑录制段、速度调节 |
| **潜影盒存储增强** | 多盒策略优化、优先特定类型盒子、自定义存储规则 |
| **SoA 录制存储** | 若录制时长达到数十万 tick，可把 `RecordedSegment` 拆成并行数组做到零对象分配 |

---

> **仓库地址**: https://github.com/Alonediamond/playercontrolpp
> **开发落点**: `src/main/`（主工程 26.2）
