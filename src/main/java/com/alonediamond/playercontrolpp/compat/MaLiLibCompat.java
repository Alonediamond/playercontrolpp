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
 * malilib helpers that changed shape between the malilib versions this mod targets.
 *
 * <p>Two independent changes landed in malilib 0.27.x (shipped alongside MC 1.21.11):
 * <ul>
 *   <li>{@code FileUtils.getConfigDirectory()} changed return type {@code File} &rarr;
 *       {@code Path}.</li>
 *   <li>{@code JsonUtils} moved from {@code fi.dy.masa.malilib.util} to
 *       {@code fi.dy.masa.malilib.util.data.json}, and its {@code File}-taking overloads
 *       became {@code Path}-taking ones.</li>
 * </ul>
 *
 * <p>Both malilib generations do expose a common {@code *AsPath} method pair, which would
 * avoid the {@code //#if} entirely — but those are annotated
 * {@code @Deprecated(forRemoval = true)} in 0.29.3, so this class deliberately calls the
 * non-deprecated method on each version instead.
 */
public final class MaLiLibCompat {

    private MaLiLibCompat() {}

    /**
     * @return malilib's {@code .minecraft/config} directory.
     *
     * <p>Which of the two accessors is the deprecated one flips between malilib
     * generations, so each branch picks the non-deprecated method for its own version:
     * <ul>
     *   <li>0.21.10 / 0.23.5 — {@code getConfigDirectory()} returns {@code File} and is
     *       deprecated-for-removal; {@code getConfigDirectoryAsPath()} is the good one.</li>
     *   <li>0.27.12+ — {@code getConfigDirectory()} returns {@code Path} and is the good
     *       one; {@code getConfigDirectoryAsPath()} is now the deprecated alias.</li>
     * </ul>
     */
    public static Path configDirectory() {
        //#if MC >= 12111
        return FileUtils.getConfigDirectory();
        //#else
        //$$ return FileUtils.getConfigDirectoryAsPath();
        //#endif
    }

    /** @return the parsed JSON tree, or {@code null} when the file is missing or malformed. */
    public static JsonElement parseJsonFile(Path path) {
        //#if MC >= 12111
        return JsonUtils.parseJsonFile(path);
        //#else
        //$$ return JsonUtils.parseJsonFile(path.toFile());
        //#endif
    }

    /** @return {@code true} when the file was written successfully. */
    public static boolean writeJsonToFile(JsonObject root, Path path) {
        //#if MC >= 12111
        return JsonUtils.writeJsonToFile(root, path);
        //#else
        //$$ return JsonUtils.writeJsonToFile(root, path.toFile());
        //#endif
    }
}
