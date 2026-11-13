package com.alonediamond.playercontrolpp.inventory.shulker;

import com.alonediamond.playercontrolpp.util.PlayerUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 潜影盒放置位置与侧移方向的选择，不发送交互或模拟输入。 */
public final class ShulkerPlacement {
    private ShulkerPlacement() {}

    /**
     * 一个候选放置位。
     *
     * @param pos     放下去之后潜影盒占据的格子
     * @param clicked 要右键的那个方块
     * @param face    在那个方块上点击的面；{@code pos} 正好是 {@code clicked.relative(face)}
     */
    public record Placement(BlockPos pos, BlockPos clicked, Direction face) {}

    /**
     * 找一个能放下潜影盒、并且放完还打得开的位置。
     *
     * <p>旧实现只看脚步方块的四个水平邻居，玩家站在两格交界处、贴墙、或脚下没有坚实方块时就会失败——
     * 玩家不是一个点，而是一个 0.6×1.8 的碰撞箱，位置判定必须按碰撞箱算。
     *
     * <p>候选范围：以玩家为中心、配置半径内、脚下方块上下各一格的立方体。逐个检查：
     * <ol>
     *   <li>目标格与上方一格都是空气/可替换方块；</li>
     *   <li>不与玩家碰撞箱相交（盒子放下去后玩家仍能站着，而不是把自己埋进去）；</li>
     *   <li>下方是坚实的面；否则找一个相邻的实心方块作为点击面；</li>
     *   <li>眼睛到点击面中心在交互距离内；</li>
     *   <li>放完后能打开——潜影盒的盒盖会朝点击的面翻开，所以盖上那一层也必须是空的。</li>
     * </ol>
     *
     * <p>评分：优先正前方（玩家朝向）、其次距离近。
     *
     * @return 选中的位置；没有合法位置时 {@code null}
     */
    public static Placement find(Minecraft mc, int radius) {
        BlockPos feet = mc.player.blockPosition();
        radius = Math.max(1, radius);
        double reachSq = PlayerUtil.blockReachSq(mc.player);
        Vec3 eye = mc.player.getEyePosition();
        AABB playerBox = mc.player.getBoundingBox().inflate(0.05);
        Direction facing = mc.player.getDirection();

        Placement best = null;
        double bestScore = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    // 玩家自己站的那一格放不了；目标格与上方一格都要空。
                    if (candidate.equals(feet) || !isPlacementSpotFree(mc, candidate)) continue;

                    // 放下去之后盒子会占一格，玩家必须还站得下。
                    if (new AABB(candidate).intersects(playerBox)) continue;

                    Direction clickFace = findClickableFace(mc, candidate);
                    if (clickFace == null) continue;

                    BlockPos clicked = candidate.relative(clickFace.getOpposite());
                    Vec3 hitPos = Vec3.atCenterOf(clicked).add(
                            clickFace.getStepX() * 0.5, clickFace.getStepY() * 0.5, clickFace.getStepZ() * 0.5);
                    double distSq = eye.distanceToSqr(hitPos);
                    if (distSq > reachSq) continue;

                    // 盒子会朝点击面翻开：那个方向必须是空的，否则开不了。
                    BlockPos lidSpace = candidate.relative(clickFace);
                    if (!isOpenSpace(mc, lidSpace) || lidSpace.equals(feet)) continue;

                    double score = score(candidate, facing, feet, distSq);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Placement(candidate, clicked, clickFace);
                    }
                }
            }
        }
        return best;
    }

    /** 盒体和上方空间均须可替换；保留原有放置过滤规则。 */
    private static boolean isPlacementSpotFree(Minecraft mc, BlockPos pos) {
        return isOpenSpace(mc, pos) && isOpenSpace(mc, pos.above());
    }

    public static boolean isOpenSpace(Minecraft mc, BlockPos pos) {
        var state = mc.level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    /**
     * 找不到放置位时往哪边走一格。
     *
     * <p>按左右、前后顺序检查相邻两格是否可替换，沿用既有侧移判据。
     *
     * @return 移动方向；四周都走不了时 {@code null}
     */
    public static Direction pickNudgeDirection(Minecraft mc) {
        Direction facing = mc.player.getDirection();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        for (Direction dir : new Direction[]{left, right, facing, facing.getOpposite()}) {
            BlockPos next = mc.player.blockPosition().relative(dir);
            if (isOpenSpace(mc, next) && isOpenSpace(mc, next.above())) return dir;
        }
        return null;
    }

    /** 世界方向换成相对玩家朝向的移动键。 */
    public static net.minecraft.client.KeyMapping sideKey(Minecraft mc, Direction dir) {
        Direction facing = mc.player.getDirection();
        if (dir == facing) return mc.options.keyUp;
        if (dir == facing.getOpposite()) return mc.options.keyDown;
        if (dir == facing.getClockWise()) return mc.options.keyRight;
        return mc.options.keyLeft;
    }

    /**
     * 找一个能右键出放置动作的面：下方坚实就用 UP，否则找六个方向里第一个实心的邻居。
     *
     * @return 点击面；四周都没有可附着方块时 {@code null}
     */
    private static Direction findClickableFace(Minecraft mc, BlockPos pos) {
        BlockPos below = pos.below();
        if (mc.level.getBlockState(below).isFaceSturdy(mc.level, below, Direction.UP)) return Direction.UP;
        for (Direction dir : Direction.values()) {
            BlockPos neighbor = pos.relative(dir);
            // 点击这个邻居的 dir.getOpposite() 面，方块会被放到 pos 上。
            if (mc.level.getBlockState(neighbor).isFaceSturdy(mc.level, neighbor, dir.getOpposite())) {
                return dir.getOpposite();
            }
        }
        return null;
    }

    /** 越小越优先：正前方 → 同高度 → 距离近。 */
    private static double score(BlockPos candidate, Direction facing, BlockPos feet, double distSq) {
        double score = distSq;
        if (candidate.getY() != feet.getY()) score += 2.0;
        BlockPos delta = candidate.subtract(feet);
        if (delta.getX() == facing.getStepX() && delta.getZ() == facing.getStepZ() && delta.getY() == 0) {
            score -= 4.0;   // 正前方优先
        }
        return score;
    }

}
