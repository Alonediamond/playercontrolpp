# PlayerControl++ — 多版本构建工程

PlayerControl++ 的单一代码库多版本构建工程。一份源码同时构建 **8 个** Minecraft 版本的模组 jar。

模组功能与设计说明详见 [PlayerControl++模组详细介绍.md](PlayerControl++模组详细介绍.md)。

## 支持的版本

| 子项目 | Minecraft | Java | 映射 | malilib | ModMenu |
|--------|-----------|------|------|---------|---------|
| `:1.21.1`  | 1.21 – 1.21.1   | 21 | Mojang + Parchment | 0.21.10 | 11.0.4 |
| `:1.21.4`  | 1.21.4          | 21 | Mojang + Parchment | 0.23.5  | 13.0.3 |
| `:1.21.6`  | 1.21.6 – 1.21.7 | 21 | Mojang             | 0.25.7  | 15.0.2 |
| `:1.21.8`  | 1.21.8          | 21 | Mojang             | 0.25.7  | 15.0.2 |
| `:1.21.10` | 1.21.10         | 21 | Mojang + Parchment | 0.26.8  | 16.0.1 |
| `:1.21.11` | 1.21.11         | 21 | Mojang + Parchment | 0.27.12 | 17.0.0 |
| `:26.1.2`  | 26.1 – 26.1.2   | 25 | Mojang（未混淆）    | 0.28.9  | 18.0.0-beta.1 |
| `:26.2`    | 26.2            | 25 | Mojang（未混淆）    | 0.29.3  | 20.0.1 |

> `1.21.6` 与 `1.21.8` 的预处理产物逐字节相同，malilib / ModMenu 也是同一个 jar 覆盖
> 1.21.6–1.21.8；两个子项目只是为了各自声明 `game_versions` 与 `minecraft_dependency`。
> Parchment 没有 1.21.6–1.21.8 的发布。
>
> **Parchment 的分发**：官方 maven（maven.parchmentmc.org）会长时间整体不可达。
> 各版本用到的 parchment zip + pom 已提交在 `libs/maven/org/parchmentmc/data/parchment-<mc>/<版本>/`
> （构建优先命中，新 clone 无网络依赖），JFrog 镜像 `ldtteam.jfrog.io/artifactory/parchmentmc-public`
> 与官方 maven 作为后续兜底，CI 里另有带重试的补拉步骤。新增版本时从镜像拉对应
> `parchment-<minecraft_version>/<版本>/` 放入即可（注意 artifactId 与子项目 minecraft_version 一致）。

**主工程（mainProject）= `26.2`**：`src/main/java` 里的源码就是 26.2 版本的源码，
其余版本由预处理器在 `versions/<mc>/build/preprocessed/` 下自动生成。**只编辑 `src/main/`。**

## 构建

```bash
# 构建全部 8 个版本，并把 jar 汇总到 build/libs/
./gradlew buildAndGather

# 只构建单个版本
./gradlew :1.21.4:build

# 在某个版本上启动游戏调试
./gradlew :26.2:runClient
```

`buildAndGather` 的产物：

```
build/libs/
├── PlayerControlpp-v1.8-mc1.21.1.jar
├── PlayerControlpp-v1.8-mc1.21.4.jar
├── PlayerControlpp-v1.8-mc1.21.6.jar
├── PlayerControlpp-v1.8-mc1.21.8.jar
├── PlayerControlpp-v1.8-mc1.21.10.jar
├── PlayerControlpp-v1.8-mc1.21.11.jar
├── PlayerControlpp-v1.8-mc26.1.2.jar
└── PlayerControlpp-v1.8-mc26.2.jar
```

环境变量 `BUILD_RELEASE=false` 会给版本号加上 `-SNAPSHOT` / `+build.<N>` 后缀。

## 工程结构

```
├── settings.json              # 参与构建的版本列表（CI 的 matrix 也读这里）
├── build.gradle               # 预处理器版本节点图（createNode / link）
├── common.gradle              # 所有子项目共用的构建逻辑
├── gradle.properties          # 模组元信息（mod_id / mod_version / …）
├── libs/                      # malilib + ModMenu jar（根目录），可选联动的 compileOnly jar（按 <mc>/ 子目录）
│                              # 已提交，克隆后可直接构建；运行时依赖仅 malilib + ModMenu
├── tools/MakeIcon.java        # 生成 assets/playercontrolpp/icon.png，可改配色/构图后重跑
├── versions/
│   ├── mainProject            # 内容为 "26.2"
│   └── <mc>/gradle.properties # 该版本的 MC 版本号、依赖版本、jar 文件名
└── src/main/
    ├── java/com/alonediamond/playercontrolpp/
    │   ├── compat/            # ★ 跨版本兼容层，见下节
    │   ├── integration/       # ★ 可选联动 stub（默认空实现），见下节
    │   ├── mixin/             # ★ MixinPlugin + compat/<模组>/ 直连实现，见下节
    │   ├── input/SimulatedInput.java   # ★ 模拟按键的唯一写入点
    │   ├── feature/ClientFeature.java  # ★ 功能生命周期接口 + FeatureRegistry
    │   └── …                  # 其余为与版本无关的业务代码
    └── resources/
        ├── fabric.mod.json    # 用 ${…} 占位符，由 processResources 按版本填充
        └── playercontrolpp.mixins.json
```

重新生成图标：

```bash
javac -d /tmp/icon tools/MakeIcon.java
java -cp /tmp/icon MakeIcon src/main/resources/assets/playercontrolpp/icon.png
```

## 改动这份代码时要知道的两件事

1. **不要直接调用 `KeyMapping.setDown()`。** 所有模拟按键都经
   `input/SimulatedInput`：功能只声明 `hold(key, owner)` / `release(key, owner)`，
   每 tick 末尾由 `apply()` 统一落地。这样多个功能同时想按同一个键时不会互相踩踏，
   功能中止时 `releaseAll(owner)` 一行就能保证不留卡键。
2. **新增功能实现 `ClientFeature` 并在 `InitHandler.registerFeatures()` 注册**，
   不要往 `ClientEventHandler` 里加 tick 调用。注册顺序即 tick 顺序，
   世界切换的清理由注册表统一广播。

## 跨版本兼容层 `compat/`

预处理器能自动处理 Mojang 映射在版本间的**重命名**，但**签名变化**（参数类型变了、返回类型
变成 `Optional`、字段变成 getter）必须手工桥接。这些差异全部集中在 `compat/` 包里，
业务代码保持单一写法。

| 类 | 桥接的差异 | 分界版本 |
|----|-----------|---------|
| `ScreenCompat` | `mc.screen` / `mc.setScreen()` → `mc.gui.screen()` / `mc.gui.setScreen()` | 26.2 |
| `DrawCtx` | `GuiGraphics` → `GuiGraphicsExtractor`；`drawString`→`text`、`drawCenteredString`→`centeredText`、`AbstractWidget.render`→`extractRenderState` | 26.1 |
| `SlotActionCompat` | `handleInventoryMouseClick(…ClickType…)` → `handleContainerInput(…ContainerInput…)` | 26.1 |
| `ContainerContentsCompat` | `ItemContainerContents.nonEmptyItems()` 元素类型 `ItemStack` → `ItemStackTemplate` | 26.1 |
| `PlayerCompat` | `displayClientMessage(text, true)` → `sendOverlayMessage(text)` | 26.1 |
| `NbtCompat` | `CompoundTag` 全部 getter 改为返回 `Optional` | 1.21.5 |
| `InventoryCompat` | `Inventory.selected` 字段 → `getSelectedSlot()` / `setSelectedSlot()` | 1.21.5 |
| `InputCompat` | `Input.jumping` / `shiftKeyDown` 字段 → `input.keyPresses` (`PlayerInput` record) | 1.21.2 |
| `MaLiLibCompat` | malilib `JsonUtils` 移包 + `getConfigDirectory()` 返回类型 | 1.21.11 (malilib 0.27) |

兼容层没有覆盖、也不需要覆盖的一处：`Inventory.SELECTION_SIZE`（快捷栏大小）
从 1.21.4 起才存在，1.21.1 没有，所以用自有常量 `PlayerUtil.HOTBAR_SIZE`；
`Inventory.INVENTORY_SIZE` 所有版本都有，直接用官方常量。

另外三处差异直接写在业务代码里（因为要拆分方法签名，无法藏进工具类）：

- `RouteListGui` / `RecordingListGui`：`render`/`renderBackground` ↔ `extractRenderState`/`extractBackground`，
  以及 `mouseClicked` / `charTyped` / `keyPressed` 的参数从散装基本类型变成了
  `MouseButtonEvent` / `CharacterEvent` / `KeyEvent`（1.21.11 起）。
  版本无关的逻辑抽成了 `handleClick()` / `focusedField()` / `renderContent()`。
- `PlayerControlppConfigGui`：同上，逻辑抽成 `renderOverlay()`。
- `RouteManager.RouteHotkey`：malilib 0.27 才给 `IConfigBase` 加了
  `isDirty`/`markDirty`/`markClean`/`checkIfClean` 四个方法。

### 预处理器语法速查

```java
//#if MC >= 260000
this.delegate.text(font, text, x, y, color, shadow);     // 生效分支：正常代码
//#else
//$$ this.delegate.drawString(font, text, x, y, color, shadow);   // 非生效分支：//$$ 前缀
//#endif
```

版本号是整数形式：`1.21.4` → `12104`，`26.2` → `260200`。
`//$$` 前缀的行同样会被映射重命名器处理，所以两个分支都能跟着版本更新走。

### 新增一个 Minecraft 版本

1. `settings.json` 里加版本号；
2. `build.gradle` 的 `preprocess` 块里 `createNode(...)` 并 `link` 到相邻节点；
3. 建 `versions/<mc>/gradle.properties`（照抄邻近版本改 MC 版本号与依赖）；
4. 把该版本的 malilib / ModMenu jar 放进 `libs/`，并在上一步的 properties 里填 `malilib_jar` / `modmenu_jar`；
   若该版本配 Parchment，把对应的 parchment zip 放进 `libs/maven/org/parchmentmc/data/parchment-<mc>/<版本>/`
   （官方 maven 偶发长时间不可达，仓库内副本优先命中；CI 的 `Fetch missing parchment data` 步骤会兜底补拉）；
5. `./gradlew :<mc>:compileJava`，按报错逐个在 `compat/` 里补桥接；
6. `diff -rq versions/<新>/build/preprocessed/main versions/<邻近>/build/preprocessed/main`
   核对差异是否只落在预期的兼容类上。

> malilib / ModMenu 1.21.4 之后的版本不在 masa 的 maven 上，走 Modrinth API：
> `https://api.modrinth.com/v2/project/{malilib,modmenu}/version?loaders=["fabric"]&game_versions=["<mc>"]`。

## 可选联动：mixin plugin 架构（v1.8 起）

Litematica / Baritone / ChestTracker / QuickShulker / LitematList 五个可选联动**零反射**：

- `integration/<X>Integration` 是 **stub**：方法体全部是默认空实现（`isLoaded()` 默认 `false`），
  不含任何联动模组的类引用，模组缺席时照常加载、调用即降级；
- `mixin/compat/<模组>/<X>IntegrationImpl` 是**直连实现**：`@Mixin(stub)` +
  `@Overwrite(remap = false)` 正常写 Java 调用；
- `mixin/PlayercontrolppMixinPlugin` 在 Mixin 配置加载期用 `FabricLoader.isModLoaded`
  判定模组在不在（Baritone 认 `baritone` / `zbaritone` / `baritone-meteor` 三个 ID），
  `shouldApplyMixin` 按 `.compat.<模组>.` 包段放行——模组在场才注入，缺席时 impl
  根本不进 JVM；
- 每个子项目编译时绑定 `libs/<mc>/` 里**该版本真实的联动模组 jar**（compileOnly，
  不进运行时、不打包），API 漂移在编译期报错而不是运行期静默失效；
- impl 方法体统一 `catch (Throwable)`：第三方 fork 的签名漂移抛 `NoSuchMethodError`
  这类 Error，要与旧反射时代一样静默降级，不能炸 tick 循环。

### 新增一个可选联动模组该怎么做

1. **收集 compileOnly jar**：放进 `libs/<mc>/`（按 MC 版本分目录；Java 21 的 jar
   只能给 1.21.x 子项目用，Java 25 的给 26.x），在对应 `versions/<mc>/gradle.properties`
   里登记 `xxx_jar=<子目录>/<文件名>`。目标模组没覆盖全部 MC 版本时，借用最接近版本的
   API jar 编译即可，运行时由插件判定（LitematList 就是这么处理的）；
2. **common.gradle** 的 `dependencies` 块照抄现有五行加一条
   `autoCompileOnly files(rootProject.file("libs/${project.xxx_jar}"))`；
3. **写 stub** `integration/XxxIntegration`：单例 + 全部公共方法给默认空实现 +
   `isLoaded()` 默认 `false`。签名里只能出现 JDK / MC / malilib 类型，**不能出现
   目标模组的类型**（返回原始对象可以用 `Object`，如 `getMaterialList()`）；
4. **写 impl** `mixin/compat/xxx/XxxIntegrationImpl`：`@Mixin(XxxIntegration.class)`，
   每个要覆盖的方法 `@Overwrite(remap = false)`，方法名与签名和 stub 完全一致；
   私有辅助方法标 `@Unique`；要访问目标模组的 protected/private 成员时，另写一个
   `@Accessor` mixin（参考 `MaterialListBaseAccessor`）；
5. **登记 mixins.json**：`client` 数组加相对类名（如 `compat.xxx.XxxIntegrationImpl`）；
6. **登记 MixinPlugin**：`onLoad` 加一行判定并缓存布尔，`shouldApplyMixin` 加一条
   包段分发，`InitHandler` 的自检清单加一行；
7. **fabric.mod.json** 的 `suggests` 加 `"xxx": "*"`；
8. **验证**：先用 `javap -cp <jar> <类>` 核对目标 API 在全部 8 个版本 jar 上的签名，
   确认要不要写预处理器分支（五模组实测零漂移）；然后 `./gradlew buildAndGather`，
   编译通过即代表 8 个版本都对该版本真实 jar 完成类型校验；最后检查产物 jar
   （mixin 类齐全、无第三方包泄漏）并抽一个混淆版本（1.21.1）反查重映射结果。

> 实机测试注意：compileOnly 不进 dev 运行时，`runClient` 环境里联动恒为关闭态；
> 测联动功能请直接装发布 jar，或在 IDE 运行配置里手动把 lib jar 加进运行时。
> 启动日志的 `Compat mixins to apply: ...` 一行是插件判定结果。

## 致谢

多版本构建脚手架来自 [Fallen_Breath/fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)，
预处理器为 [Fallen-Breath/preprocessor](https://github.com/Fallen-Breath/preprocessor)（fork 自
[ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor)）。

## License

模组代码 MIT，作者 Alonediamond，见 [LICENSE](LICENSE)。

> 构建脚本（`build.gradle` / `common.gradle` / `versions/` / `.github/`）源自
> Fallen_Breath 的 fabric-mod-template，其原始仓库以 LGPL-3.0 发布。
> 本仓库的 `LICENSE` 是模组自身代码的 MIT 授权；若需要严格区分脚手架部分的授权，
> 请参考上游仓库的许可条款。
