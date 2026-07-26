# PlayerControl++ — 多版本构建工程

PlayerControl++ 的单一代码库多版本构建工程。一份源码同时构建 **5 个** Minecraft 版本的模组 jar。

模组功能与设计说明详见仓库根目录上一层的 `PlayerControl++模组详细介绍.md`。

## 支持的版本

| 子项目 | Minecraft | Java | 映射 | malilib | ModMenu |
|--------|-----------|------|------|---------|---------|
| `:1.21.1`  | 1.21 – 1.21.1 | 21 | Mojang + Parchment | 0.21.10 | 11.0.4 |
| `:1.21.4`  | 1.21.4        | 21 | Mojang + Parchment | 0.23.5  | 13.0.3 |
| `:1.21.11` | 1.21.11       | 21 | Mojang + Parchment | 0.27.12 | 17.0.0 |
| `:26.1.2`  | 26.1 – 26.1.2 | 25 | Mojang（未混淆）    | 0.28.6  | 18.0.0-beta.1 |
| `:26.2`    | 26.2          | 25 | Mojang（未混淆）    | 0.29.3  | 20.0.1 |

**主工程（mainProject）= `26.2`**：`src/main/java` 里的源码就是 26.2 版本的源码，
其余版本由预处理器在 `versions/<mc>/build/preprocessed/` 下自动生成。**只编辑 `src/main/`。**

## 构建

```bash
# 构建全部 5 个版本，并把 jar 汇总到 build/libs/
./gradlew buildAndGather

# 只构建单个版本
./gradlew :1.21.4:build

# 在某个版本上启动游戏调试
./gradlew :26.2:runClient
```

`buildAndGather` 的产物：

```
build/libs/
├── PlayerControlpp-v1.4-mc1.21.1-SNAPSHOT.jar
├── PlayerControlpp-v1.4-mc1.21.4-SNAPSHOT.jar
├── PlayerControlpp-v1.4-mc1.21.11-SNAPSHOT.jar
├── PlayerControlpp-v1.4-mc26.1.2-SNAPSHOT.jar
└── PlayerControlpp-v1.4-mc26.2-SNAPSHOT.jar
```

设置环境变量 `BUILD_RELEASE=true` 可去掉 `-SNAPSHOT` 后缀。

## 工程结构

```
├── settings.json              # 参与构建的版本列表（CI 的 matrix 也读这里）
├── build.gradle               # 预处理器版本节点图（createNode / link）
├── common.gradle              # 所有子项目共用的构建逻辑
├── gradle.properties          # 模组元信息（mod_id / mod_version / …）
├── libs/                      # 各版本的 malilib + ModMenu jar（已提交，克隆后可直接构建）
├── versions/
│   ├── mainProject            # 内容为 "26.2"
│   └── <mc>/gradle.properties # 该版本的 MC 版本号、依赖版本、jar 文件名
└── src/main/
    ├── java/com/alonediamond/playercontrolpp/
    │   ├── compat/            # ★ 跨版本兼容层，见下节
    │   └── …                  # 其余为与版本无关的业务代码
    └── resources/
        ├── fabric.mod.json    # 用 ${…} 占位符，由 processResources 按版本填充
        └── playercontrolpp.mixins.json
```

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
| `MaLiLibCompat` | malilib `JsonUtils` 移包到 `util.data.json` | 1.21.11 (malilib 0.27) |

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
5. `./gradlew :<mc>:compileJava`，按报错逐个在 `compat/` 里补桥接。

## 致谢

多版本构建脚手架来自 [Fallen_Breath/fabric-mod-template](https://github.com/Fallen-Breath/fabric-mod-template)，
预处理器为 [Fallen-Breath/preprocessor](https://github.com/Fallen-Breath/preprocessor)（fork 自
[ReplayMod/preprocessor](https://github.com/ReplayMod/preprocessor)）。

## License

模组代码 MIT，作者 Alonediamond。
