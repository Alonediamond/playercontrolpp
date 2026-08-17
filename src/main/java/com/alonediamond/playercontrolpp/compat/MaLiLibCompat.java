package com.alonediamond.playercontrolpp.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.util.FileUtils;

import java.nio.file.Path;

//#if MC >= 12111
import fi.dy.masa.malilib.util.data.json.JsonUtils;
//#else
//$$ import fi.dy.masa.malilib.util.JsonUtils;
//#endif

/**
 * 在本模组覆盖的 malilib 版本之间改过形状的几个 malilib 工具方法。
 *
 * <p>malilib 0.27.x（随 MC 1.21.11 发布）里有两处互不相关的改动：
 * <ul>
 *   <li>{@code FileUtils.getConfigDirectory()} 返回类型 {@code File} &rarr; {@code Path}；</li>
 *   <li>{@code JsonUtils} 从 {@code fi.dy.masa.malilib.util} 移到
 *       {@code fi.dy.masa.malilib.util.data.json}，吃 {@code File} 的重载改成吃 {@code Path}。</li>
 * </ul>
 *
 * <p>两代 malilib 其实都有一对通用的 {@code *AsPath} 方法，用它就不需要 {@code //#if}——
 * 但那些方法在 0.29.3 里被标了 {@code @Deprecated(forRemoval = true)}，
 * 所以这里刻意在每个版本上都调该版本里没被废弃的那个。
 */
public final class MaLiLibCompat {

    private MaLiLibCompat() {}

    /**
     * @return malilib 的 {@code .minecraft/config} 目录。
     *
     * <p>两个访问器里"哪个被废弃"在两代 malilib 之间是反的，所以各分支各选自己版本里没废弃的：
     * <ul>
     *   <li>0.21.10 / 0.23.5 / 0.25.7 —— {@code getConfigDirectory()} 返回 {@code File} 且已废弃，
     *       该用 {@code getConfigDirectoryAsPath()}；</li>
     *   <li>0.27.12+ —— {@code getConfigDirectory()} 返回 {@code Path} 且是正主，
     *       {@code getConfigDirectoryAsPath()} 变成废弃别名。</li>
     * </ul>
     */
    public static Path configDirectory() {
        //#if MC >= 12111
        return FileUtils.getConfigDirectory();
        //#else
        //$$ return FileUtils.getConfigDirectoryAsPath();
        //#endif
    }

    /** @return 解析出的 JSON 树；文件不存在或格式错误时返回 {@code null}。 */
    public static JsonElement parseJsonFile(Path path) {
        //#if MC >= 12111
        return JsonUtils.parseJsonFile(path);
        //#else
        //$$ return JsonUtils.parseJsonFile(path.toFile());
        //#endif
    }

    /** @return 写入成功返回 {@code true}。 */
    public static boolean writeJsonToFile(JsonObject root, Path path) {
        //#if MC >= 12111
        return JsonUtils.writeJsonToFile(root, path);
        //#else
        //$$ return JsonUtils.writeJsonToFile(root, path.toFile());
        //#endif
    }
}
