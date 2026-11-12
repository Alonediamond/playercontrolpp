# 26.x Minecraft 源码的 Parchment 注释合并

## 为什么需要这个工具

- Parchment 最后一次发布是 **1.21.11**（`2025.12.20`），此后不再有新版本数据。
- Minecraft 从 **26.1** 起官方 jar 不再混淆，参数名已经在字节码里；缺的只是 Javadoc。
- Loom 对非混淆版本直接禁止映射层：
  `UnsupportedOperationException: Cannot configure layered mappings in a non-obfuscated environment`。
  所以 26.x 不能走 `loom.layered() + parchment(...)` 的官方通道。

本工具把 1.21.11 的 `parchment.json` 当作**注释来源**，按成员签名保守合并到 26.x 的反编译源码，
生成一个独立的 `-sources-parchment.jar` 供 IDEA 附加。它不修改 Loom 的原始产物。

## 实测效果（2026-11-12）

| 版本 | 类注释 | 方法注释 | 字段注释 | 因歧义/类型不符跳过 |
|---|---:|---:|---:|---:|
| 26.1.2 | 180 | 2,960 | 299 | 86 |
| 26.2 | 180 | 2,886 | 285 | 92 |

匹配规则（必须全部满足）：

1. 类名完全一致；
2. 方法名一致（构造函数统一为 `<init>`）；
3. 参数个数一致；
4. 参数简单类型与数组维度一致（无法确定类型的参数按“通过”处理）。

同一 `(方法名, 参数个数)` 有多个候选、或类型对不上时**直接跳过**，宁可少注入也不贴错。
`@param` 名称按 JVM 局部变量槽位换算成当前版本源码里的真实参数名
（`long`/`double` 占两槽，静态方法没有 `this` 槽），已做全量校验：注入的 199 / 180 条
`@param` 与声明参数名 100% 一致。

> 注释来自 1.21.11，26.x 中类/方法的语义可能已变化。只把它当参考，不要盲信；
> 有疑问时以代码本身为准。

## 依赖

Python 3.10+，以及：

```powershell
python -m pip install tree_sitter tree_sitter_java
```

## 使用

1. 先生成反编译源码（每个版本一次）：

   ```powershell
   ./gradlew :26.1.2:genSources
   ./gradlew :26.2:genSources
   ```

2. 运行合并：

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools/parchment-docs/merge.ps1 -Mc 26.1.2
   ```

   或直接调用 Python：

   ```powershell
   python tools/parchment-docs/merge_docs.py `
     libs/maven/org/parchmentmc/data/parchment-1.21.11/2025.12.20/parchment-1.21.11-2025.12.20.zip `
     .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-e73fa6849b/26.1.2/minecraft-merged-e73fa6849b-26.1.2-sources.jar `
     versions/26.1.2/build/minecraft-26.1.2-sources-parchment.jar 26.1.2
   ```

3. IDEA 中打开任意 Minecraft 类 → 顶部 **Choose Sources...** → 选择
   `versions/<版本>/build/minecraft-<版本>-sources-parchment.jar`。

## 注意

- 不要覆盖 `.gradle/loom-cache/...` 里的原始 sources jar；重新 `genSources` 后需要重新合并。
- 输出在 `versions/<mc>/build/`，`gradlew clean` 会删除。
- Loom 缓存目录里的 hash（`minecraft-merged-e73fa6849b`）可能随配置变化；
  `merge.ps1` 会自动搜索，不依赖具体 hash。
- 工具只处理 `class/interface/enum/record/annotation` 的直接成员；记录类组件、枚举常量等隐式成员不注入。
