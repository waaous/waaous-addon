package com.waaous.addon.modules;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import com.waaous.addon.WaaousAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Random;

/**
 * SmartMiner —— Meteor Client 插件（Addon）模块。
 *
 * <p>1.21.11 / Yarn 映射 适配版，核心修复点：
 * <ol>
 *   <li>附魔 API：不再使用旧的 {@code EnchantmentHelper.getLevel(Enchantments.FORTUNE, stack)}，
 *       改用 Meteor 自带的 {@link Utils#getEnchantmentLevel(ItemStack, net.minecraft.registry.RegistryKey)}，
 *       它内部走 1.21 的 Data Component（{@code ItemEnchantmentsComponent}）读取，天然安全。</li>
 *   <li>转向：不再直接 {@code setYaw/setPitch}，改用 {@link Rotations#rotate}（静默、防视角回弹、防踢）。</li>
 *   <li>换镐：改用 {@link InvUtils#swap} / {@link InvUtils#swapBack}，会自动向服务端发送
 *       {@code UpdateSelectedSlotC2SPacket}，避免客户端/服务端不同步，并在挖掘结束后恢复原槽位。</li>
 *   <li>扫描：把 O(r³) 的三层 for 循环改成分 tick 的近→远环形扫描（每 tick 预算 {@code scan-budget} 个方块），
 *       绝不在单个 tick 内遍历几十万方块。</li>
 *   <li>寻路：保留 Baritone {@code GoalGetToBlock}，新增卡住/无路时的安全取消。</li>
 * </ol>
 */
public class SmartMiner extends Module {

    private static final Random RANDOM = new Random();

    /** 传给 {@link Rotations#rotate} 的优先级，越高越重要（Meteor 默认 Combat 类模块用 100）。 */
    private static final int ROTATION_PRIORITY = 100;

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgSafety  = settings.createGroup("Safety");

    // ==================== 基础设置 ====================

    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("target-blocks")
        .description("要自动挖掘的方块类型。")
        .defaultValue(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("扫描目标方块的最大半径（方块）。半径越大单圈扫描越慢。")
        .defaultValue(32).min(4).max(128).sliderRange(8, 64).build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("两次挖矿决策之间的间隔（tick）。")
        .defaultValue(2).min(0).max(20).build()
    );

    private final Setting<Integer> scanBudget = sgGeneral.add(new IntSetting.Builder()
        .name("scan-budget")
        .description("每个 tick 最多扫描的方块数。调低可进一步减少卡顿，但扫描变慢。")
        .defaultValue(4096).min(256).max(65536).sliderRange(256, 16384).build()
    );

    private final Setting<Double> reach = sgGeneral.add(new DoubleSetting.Builder()
        .name("reach")
        .description("玩家与方块的距离小于该值时停止寻路并开始挖掘。")
        .defaultValue(3.0).min(2.0).max(6.0).sliderRange(2.0, 5.0).build()
    );

    private final Setting<Boolean> autoTool = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-tool")
        .description("自动切换到最佳的镐子（优先时运，其次效率）。")
        .defaultValue(true).build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("挖掘前用 Meteor 的 Rotations.rotate() 转向目标方块。")
        .defaultValue(true).build()
    );

    private final Setting<Integer> uselessMining = sgGeneral.add(new IntSetting.Builder()
        .name("useless-mining")
        .description("每次挖掘循环先挖普通方块的概率（%）。0 = 只挖目标矿。")
        .defaultValue(0).min(0).max(100).sliderRange(0, 50).build()
    );

    // ==================== 安全设置 ====================

    private final Setting<Boolean> avoidLava = sgSafety.add(new BoolSetting.Builder()
        .name("avoid-lava")
        .description("目标方块周围有岩浆则跳过。")
        .defaultValue(true).build()
    );

    private final Setting<Boolean> avoidFall = sgSafety.add(new BoolSetting.Builder()
        .name("avoid-fall")
        .description("目标方块下方有坠落风险则跳过。")
        .defaultValue(true).build()
    );

    private final Setting<Integer> safeFallDistance = sgSafety.add(new IntSetting.Builder()
        .name("safe-fall-distance")
        .description("允许的最大坠落高度（格）。")
        .defaultValue(3).min(1).max(10).build()
    );

    private final Setting<Integer> stuckTimeout = sgSafety.add(new IntSetting.Builder()
        .name("stuck-timeout")
        .description("寻路时连续多少 tick 位置没有移动就判定为卡住并取消寻路。")
        .defaultValue(100).min(20).max(600).sliderRange(20, 300).build()
    );

    // ==================== 内部状态 ====================

    private BlockPos currentTarget = null;
    private boolean currentTargetIsUseless = false;
    private boolean isMining = false;
    private boolean toolSwapped = false;
    private int tickCounter = 0;
    private BlockPos collectPos = null; // 挖完后去拾取掉落物的位置
    private int collectTicks = 0;

    // 背景扫描状态（近→远环形、分 tick 限流）
    private int scanRing = -1;          // 当前扫描的 Chebyshev 环号（0..range）；-1 表示未开始
    private long ringIndex = 0;         // 当前环内部的扁平化游标
    private BlockPos bestTarget = null; // 目前扫描到的最近目标
    private double bestDistSq = Double.MAX_VALUE;

    // Baritone 卡住检测
    private int stuckTicks = 0;
    private BlockPos lastPathPos = null;
    private BlockPos lastFailedPos = null; // 上次寻路失败的目标，避免立刻重选同一个方块导致死循环

    public SmartMiner() {
        super(WaaousAddon.CATEGORY, "smart-miner",
              "通过 ESP 定位目标方块，自动寻路、换镐并安全挖掘。");
    }

    // ==================== 主循环 ====================

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // 背景扫描：每 tick 只做 scan-budget 个方块，彻底避免单 tick 卡顿。
        updateScan();

        // 寻路中：每 tick 检测是否卡住。
        IBaritone baritone = getBaritone();
        if (baritone != null && baritone.getPathingBehavior().isPathing() && currentTarget != null) {
            updateStuckDetection();
        }

        // 0. 拾取掉落物：挖完方块后，先走到掉落物位置捡起来。
        if (collectPos != null) {
            if (mc.player.getEyePos().distanceTo(collectPos.toCenterPos()) < 1.5 || ++collectTicks > 40) {
                collectPos = null;
                collectTicks = 0;
                stopBaritone();
                finishMining();
            }
            return;
        }

        // 1. 正在挖掘：持续瞄准 + 持续破坏，直到方块消失。
        if (isMining && currentTarget != null) {
            if (mc.world.getBlockState(currentTarget).isAir()) {
                // 方块已挖掉：记录掉落位置，走过去拾取。
                collectPos = currentTarget;
                collectTicks = 0;
                isMining = false;
                currentTarget = null;
                stopBaritone();
                navigateToCollect(collectPos);
            } else {
                if (rotate.get()) {
                    Rotations.rotate(Rotations.getYaw(currentTarget), Rotations.getPitch(currentTarget), ROTATION_PRIORITY);
                }
                BlockUtils.breakBlock(currentTarget, true);
            }
            return;
        }

        // 2. 决策限流。
        tickCounter++;
        if (tickCounter < delay.get()) return;
        tickCounter = 0;

        // 3. 选择目标。
        currentTarget = null;
        currentTargetIsUseless = false;

        boolean wantUseless = uselessMining.get() > 0 && RANDOM.nextInt(100) < uselessMining.get();
        if (wantUseless) {
            currentTarget = findRandomUselessTarget();
            currentTargetIsUseless = currentTarget != null;
        }

        if (currentTarget == null) {
            // 背景扫描得到的“最近目标”。
            currentTarget = bestTarget;
            currentTargetIsUseless = false;
        }

        // 兜底校验：目标必须仍可挖掘（不是空气），且不是刚失败过的方块。
        if (currentTarget != null && (!isBreakable(currentTarget) || currentTarget.equals(lastFailedPos))) {
            currentTarget = null;
            currentTargetIsUseless = false;
        }

        if (currentTarget == null) {
            stopBaritone();
            return;
        }

        // 4. 安全检测。
        if (!isSafe(currentTarget)) {
            currentTarget = null;
            currentTargetIsUseless = false;
            return;
        }

        // 5. 距离过远：交给 Baritone 寻路。
        if (mc.player.getEyePos().distanceTo(currentTarget.toCenterPos()) > reach.get()) {
            navigateTo(currentTarget);
            return;
        }

        // 6. 已到达：停止寻路、换镐、开始挖掘。
        stopBaritone();
        if (autoTool.get()) selectBestPickaxe(currentTargetIsUseless);

        isMining = true;
        if (rotate.get()) {
            Rotations.rotate(Rotations.getYaw(currentTarget), Rotations.getPitch(currentTarget), ROTATION_PRIORITY);
        }
        BlockUtils.breakBlock(currentTarget, true);
    }

    // ==================== 方块扫描（近→远环形 + Tick 限流） ====================

    /**
     * 以玩家为中心，按 Chebyshev 距离从近到远逐环扫描，每 tick 只处理 {@code scan-budget} 个方块。
     * <p>关键点：近处方块先被扫到，所以 {@link #bestTarget} 在整个扫描过程中始终“接近”真正的最近目标，
     * 不会像原版三层 for 循环那样单 tick 遍历几十万方块导致客户端冻结。
     */
    private void updateScan() {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = range.get();
        int budget = scanBudget.get();
        List<Block> targets = targetBlocks.get();

        if (scanRing < 0) {
            scanRing = 0;
            ringIndex = 0;
            bestDistSq = Double.MAX_VALUE;
        }

        BlockPos.Mutable mpos = new BlockPos.Mutable();

        while (budget > 0 && scanRing <= r) {
            int k = scanRing;                       // 当前环的 Chebyshev 半径
            int side = 2 * k + 1;                   // 外接立方体边长
            long total = (long) side * side * side; // 外接立方体方块总数

            for (; ringIndex < total && budget > 0; ringIndex++, budget--) {
                long idx = ringIndex;
                long dz = idx % side - k;
                long dy = (idx / side) % side - k;
                long dx = idx / (side * side) - k;

                // 只处理“表面”方块（max(|dx|,|dy|,|dz|) == k），内层已在更小的环扫过。
                long maxAbs = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
                if (maxAbs != k) continue;

                int py = playerPos.getY() + (int) dy;

                mpos.set(playerPos.getX() + (int) dx, py, playerPos.getZ() + (int) dz);
                if (targets.contains(mc.world.getBlockState(mpos).getBlock())) {
                    double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (d < bestDistSq) {
                        bestDistSq = d;
                        bestTarget = mpos.toImmutable();
                    }
                }
            }

            if (ringIndex >= total) {
                scanRing++;
                ringIndex = 0;
            }
        }

        // 一整圈扫完，下一 tick 重新开始。
        if (scanRing > r) scanRing = -1;
    }

    private boolean isBreakable(BlockPos pos) {
        return !mc.world.getBlockState(pos).isAir();
    }

    // ==================== 无用挖掘 ====================

    /** 在玩家附近随机取坐标，向下找实体方块，通过安全检查就返回；8 次失败返回 null。 */
    private BlockPos findRandomUselessTarget() {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = Math.min(range.get(), 16);

        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = RANDOM.nextInt(r * 2 + 1) - r;
            int dz = RANDOM.nextInt(r * 2 + 1) - r;
            int dy = RANDOM.nextInt(7) - 3;

            BlockPos candidate = playerPos.add(dx, dy, dz);
            BlockPos target = findSolidBlockAt(candidate);
            if (target == null) continue;

            Block block = mc.world.getBlockState(target).getBlock();
            if (targetBlocks.get().contains(block)) continue; // 不误挖目标矿
            if (!isUselessBlock(block)) continue;
            if (!isSafe(target)) continue;

            return target;
        }
        return null;
    }

    /** 从起点向下扫最多 5 格，返回第一个可挖的实体方块。 */
    private BlockPos findSolidBlockAt(BlockPos start) {
        for (int i = 0; i <= 5; i++) {
            BlockPos check = start.down(i);
            Block b = mc.world.getBlockState(check).getBlock();

            if (b == Blocks.AIR
                    || b == Blocks.CAVE_AIR
                    || b == Blocks.VOID_AIR
                    || b == Blocks.WATER
                    || b == Blocks.LAVA
                    || b == Blocks.BEDROCK) {
                continue;
            }
            return check;
        }
        return null;
    }

    /** 白名单：只有这些常见方块允许作为“无用挖掘”目标。 */
    private boolean isUselessBlock(Block block) {
        return block == Blocks.STONE
            || block == Blocks.DEEPSLATE
            || block == Blocks.COBBLESTONE
            || block == Blocks.COBBLED_DEEPSLATE
            || block == Blocks.ANDESITE
            || block == Blocks.DIORITE
            || block == Blocks.GRANITE
            || block == Blocks.TUFF
            || block == Blocks.DIRT
            || block == Blocks.GRAVEL
            || block == Blocks.NETHERRACK
            || block == Blocks.BLACKSTONE
            || block == Blocks.BASALT;
    }

    // ==================== 自动换镐 ====================

    /**
     * 选择最好的镐子并切换。
     *
     * @param efficiencyOnly true = 只按效率选（挖普通方块）；false = 时运优先、其次效率（挖目标矿）。
     */
    private void selectBestPickaxe(boolean efficiencyOnly) {
        int bestSlot = -1;
        int bestFortune = -1;
        int bestEfficiency = -1;

        for (int i = 0; i < 9; i++) { // 0..8 = 快捷栏
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isSuitableFor(Blocks.STONE.getDefaultState())) continue;

            // 1.21 Data Component 安全读取（替代已废弃的 EnchantmentHelper.getLevel(Enchantment, stack)）。
            int fortune = Utils.getEnchantmentLevel(stack, Enchantments.FORTUNE);
            int efficiency = Utils.getEnchantmentLevel(stack, Enchantments.EFFICIENCY);

            if (efficiencyOnly) {
                if (efficiency > bestEfficiency) {
                    bestEfficiency = efficiency;
                    bestSlot = i;
                }
            } else if (fortune > bestFortune || (fortune == bestFortune && efficiency > bestEfficiency)) {
                bestFortune = fortune;
                bestEfficiency = efficiency;
                bestSlot = i;
            }
        }

        if (bestSlot != -1 && bestSlot != mc.player.getInventory().getSelectedSlot()) {
            // InvUtils.swap 会发 UpdateSelectedSlotC2SPacket 与服务端同步，并记住原槽位。
            InvUtils.swap(bestSlot, true);
            toolSwapped = true;
        }
    }

    private void restoreTool() {
        if (toolSwapped) {
            InvUtils.swapBack();
            toolSwapped = false;
        }
    }

    private void finishMining() {
        restoreTool();
        isMining = false;
        currentTarget = null;
        currentTargetIsUseless = false;
        bestTarget = null;              // 触发重新扫描，避免立刻重选刚挖掉的方块
        bestDistSq = Double.MAX_VALUE;
        scanRing = -1;                  // 重置扫描，从玩家附近重新找最近目标（矿脉）
        ringIndex = 0;
    }

    /** 走到掉落物位置拾取（GoalBlock 让玩家站到该方块上）。 */
    private void navigateToCollect(BlockPos pos) {
        IBaritone baritone = getBaritone();
        if (baritone == null) return;
        baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
        stuckTicks = 0;
        lastPathPos = mc.player.getBlockPos();
    }

    // ==================== Baritone 寻路 ====================

    private IBaritone getBaritone() {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone();
        } catch (Throwable t) {
            return null; // Baritone 未加载时安全降级
        }
    }

    private void navigateTo(BlockPos pos) {
        IBaritone baritone = getBaritone();
        if (baritone == null) return;

        if (baritone.getPathingBehavior().isPathing()) {
            return; // 已在寻路，卡住检测由 updateStuckDetection 负责
        }

        baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
        stuckTicks = 0;
        lastPathPos = mc.player.getBlockPos();
    }

    private void updateStuckDetection() {
        BlockPos now = mc.player.getBlockPos();
        if (lastPathPos != null && now.equals(lastPathPos)) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
            lastPathPos = now;
        }

        if (stuckTicks >= stuckTimeout.get()) {
            // 卡住或找不到路：安全取消寻路并放弃当前目标。
            stopBaritone();
            lastFailedPos = currentTarget;
            currentTarget = null;
            currentTargetIsUseless = false;
            stuckTicks = 0;
        }
    }

    private void stopBaritone() {
        IBaritone baritone = getBaritone();
        if (baritone != null && baritone.getPathingBehavior().isPathing()) {
            baritone.getPathingBehavior().cancelEverything();
        }
        stuckTicks = 0;
        lastPathPos = null;
    }

    // ==================== 安全检测 ====================

    private boolean isSafe(BlockPos target) {
        // 岩浆检测：四周 + 上方 1-2 格。
        if (avoidLava.get()) {
            for (Direction dir : Direction.values()) {
                if (mc.world.getBlockState(target.offset(dir)).getBlock() == Blocks.LAVA) {
                    return false;
                }
            }
            if (mc.world.getBlockState(target.up()).getBlock() == Blocks.LAVA
                    || mc.world.getBlockState(target.up(2)).getBlock() == Blocks.LAVA) {
                return false;
            }
        }

        // 坠落检测。
        if (avoidFall.get() && calculateDropBelow(target) > safeFallDistance.get()) {
            return false;
        }

        return true;
    }

    private int calculateDropBelow(BlockPos pos) {
        for (int i = 1; i <= safeFallDistance.get() + 1; i++) {
            if (!mc.world.getBlockState(pos.down(i)).isAir()) {
                return i - 1;
            }
        }
        return safeFallDistance.get() + 1;
    }

    // ==================== 模块生命周期 ====================

    @Override
    public void onDeactivate() {
        stopBaritone();
        restoreTool();
        isMining = false;
        toolSwapped = false;
        currentTarget = null;
        currentTargetIsUseless = false;
        bestTarget = null;
        scanRing = -1;
        ringIndex = 0;
        bestDistSq = Double.MAX_VALUE;
        stuckTicks = 0;
        lastPathPos = null;
        lastFailedPos = null;
        collectPos = null;
        collectTicks = 0;
    }
}
