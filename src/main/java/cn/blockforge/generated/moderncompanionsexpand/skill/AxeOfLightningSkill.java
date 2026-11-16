package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * 雷霆之钺（legendary_monsters:axe_of_lightning，传奇怪物）同伴技能：合并为一个技能（r33 拍板）。
 *
 * 原版两个入口（use = 12 段沿线雷击波柱；useOn = 5 道扇形电爆冲击波）本质都是“召唤雷电”，
 * 拍板合并：共享冷却 150 tick（axe_of_lightning_cooldown_ticks），模式 auto 情境判定——
 * 中远距离单体放雷击波（LightningBoltEntity 沿目标方向铺一列，纵深 15 格），
 * 近身多敌放电爆冲击波（ElectricityEntity ±30° 扇形 5 道）；axe_of_lightning_mode 可锁 bolts/shockwave。
 *
 * 路线（源码见 docs/ANALYSIS_ROUND33_ANCHOR_AXE.md、实现细节见 docs/IMPL_ROUND33_ANCHOR_AXE.md）：
 * - 瞬发：原版 use/useOn 都绑死 Player（context.getPlayer() / use 的 Player 参数），
 *   且无蓄力门槛，同伴版直接自研触发；
 * - 雷击波（bolts）：几何照抄原版 use——12 根沿线排布（间距 1.25×(l+1)，即 1.25~15 格）、
 *   warmup = l（越远越晚炸）、life 30、yRot = 弧度(90+朝向)、每根先向下扫描实地面
 *   （照抄 spawnFangs：从头顶 +1 向下扫到脚下 -1，悬空处不出柱）、伤害 8 点
 *   （axe_of_lightning_bolt_damage，原版 8 × BoltDamageMultiplier）；
 *   实体反射复用 LightningBoltEntity（caster 传同伴，内部每 5 tick 对柱形盒
 *   inflate(0.2, 6, 0.2) 结算 INDIRECT_MAGIC + 原版 isAlliedTo 过滤，零自研伤害代码）；
 * - 电爆冲击波（shockwave）：几何照抄原版 useOn——5 道扇形（±30°，间隔 15°）、
 *   出膛点 = 前方 1 格、y = floor(脚)、伤害读同伴 ATTACK_DAMAGE 属性（与原版同口径，
 *   读不到退回原版武器基础 12 点并打日志）、life 20、音效 WITHER_SHOOT(1.0, 0.9)；
 *   实体反射复用 ElectricityEntity（owner 传同伴，内部每 5 tick 对盒
 *   inflate(0.5, 4, 0.75) 结算，零自研伤害代码）；
 * - 冷却（拍板）：共享 150 tick（7.5 秒），两个分支同一把冷却；原版 use 分支“一根都没
 *   生成就不进冷却”照抄——spawned == 0 时不记冷却（调度器自带重试罚时防刷屏）；
 * - 友军保护：两个实体内部的原版过滤（isAlliedTo / 自己驯服的动物 / 施法者本人）+
 *   AllyDamageGuard 雷霆之钺窗口（固定 100 tick，拍板）兜底——两个实体的伤害源
 *   causing entity = 同伴，窗口内友军挨打一律取消（两个实体都不产生位移，事件层兜底即全覆盖）；
 * - 优先级：ceraunus 附近（远程系，拍板），排霆浪锚戟之后、神怒长槊之前；
 * - 命令：/mce axe_of_lightning status|equip|clear|reset|force（force 带 bolts|shockwave）。
 */
public final class AxeOfLightningSkill implements CompanionSkill {

    public static final AxeOfLightningSkill INSTANCE = new AxeOfLightningSkill();

    public static final String FORCE_MODE_BOLTS = "bolts";
    public static final String FORCE_MODE_SHOCKWAVE = "shockwave";

    /** 配置模式字符串（axe_of_lightning_mode） */
    public static final String MODE_AUTO = "auto";
    public static final String MODE_BOLTS = "bolts";
    public static final String MODE_SHOCKWAVE = "shockwave";

    /** 友军拦截窗口：雷击波柱最长 ~42 tick + 电爆 20 tick，固定 100 tick（拍板）全覆盖 */
    public static final long GUARD_BASE_TICKS = 100L;

    // ══════════════════ 原版几何常量（照抄，不另立配置） ══════════════════

    /** 雷击波柱数量（原版 use 的 for l < 12） */
    private static final int BOLT_COUNT = 12;
    /** 相邻波柱间距（原版 d2 = 1.25 × (l+1)） */
    private static final double BOLT_STEP_DISTANCE = 1.25D;
    /** 波柱生命周期（原版 spawnFangs 传 30） */
    private static final int BOLT_LIFE_TICKS = 30;
    /** 电爆冲击数量（原版 useOn 的 numberOfSkulls = 5） */
    private static final int BURST_COUNT = 5;
    /** 扇形间隔（原版 angleStep = 15°，i-2..2 → ±30°） */
    private static final float BURST_ANGLE_STEP = 15.0F;
    /** 电爆生命周期（原版 useOn 传 20） */
    private static final float BURST_LIFE_TICKS = 20.0F;
    /** “被包围”判定半径（沿用霆浪同款 5 格） */
    private static final double SURROUNDED_RADIUS = 5.0D;
    /** 电爆伤害退回值：同伴读不到 ATTACK_DAMAGE 属性时用原版武器基础伤害（getDefaultAttributeModifiers 12D） */
    private static final float BURST_DAMAGE_FALLBACK = 12.0F;

    /** persistentData 键：友军拦截窗口截止 tick / 最近一次模式（status 展示用） */
    private static final String KEY_GUARD_UNTIL = "mce_ax_guard_until";
    private static final String KEY_LAST_MODE = "mce_ax_last";

    private AxeOfLightningSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return LegendaryMonstersReflect.AXE_OF_LIGHTNING_ITEM;
    }

    /** 雷霆之钺是斧类主手武器 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.axeOfLightningEnabled();
    }

    /** 拍板：两分支合并共享冷却 150 tick（7.5 秒） */
    @Override
    public int cooldownTicks() {
        return MceConfig.axeOfLightningCooldownTicks();
    }

    /** 瞬发：原版 use/useOn 都无蓄力门槛 */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        return tryCast(level, caster, target, now, ignoreCooldown);
    }

    /**
     * 纯释放：情境判定（拍板 auto）→ 反射生成波柱/电爆 → 记冷却。
     *
     * @return 是否释放成功（至少生成了一个实体；原版“一根都没生成不进冷却”照抄）
     */
    public static boolean tryCast(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        int cooldownTicks = MceConfig.axeOfLightningCooldownTicks();
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING, now, cooldownTicks)) {
            SafeGuard.infoThrottled("mce_ax_cooling_" + caster.getUUID(), now,
                    "Axe of Lightning: " + IncineratorSkill.describe(caster) + " cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING, now, cooldownTicks)
                            + " ticks, skipped this time");
            return false;
        }
        if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
            SafeGuard.warnThrottled("mce_ax_nolm_" + level.dimension().location(), now,
                    "Axe of Lightning: Legendary Monsters mod not detected (pillar/burst entities depend on it), cast cancelled");
            return false;
        }
        EquipmentSlot slot = INSTANCE.heldSlot(caster);
        if (slot == null || caster.getItemBySlot(slot).isEmpty()) {
            SafeGuard.warn("Axe of Lightning: " + IncineratorSkill.describe(caster) + " no longer holds the Axe of Lightning, cast cancelled");
            return false;
        }
        if (target == null) {
            SafeGuard.infoThrottled("mce_ax_notarget_" + caster.getUUID(), now,
                    "Axe of Lightning: attack target required (bolt line direction / shockwave facing), cancelled this time");
            return false;
        }

        // —— 拧身面向目标（两个分支都以“当前朝向”为基准，照抄原版读 yRot/yHeadRot 的口径）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);

        boolean shockwave = decideShockwave(level, caster, target, now);
        setLastMode(caster, shockwave ? FORCE_MODE_SHOCKWAVE : FORCE_MODE_BOLTS);
        int spawned = shockwave
                ? castShockwave(level, caster, now)
                : castBolts(level, caster, now);

        if (spawned <= 0) {
            // 原版 use：一根都没生成 → InteractionResultHolder.pass，不进冷却。照抄。
            SafeGuard.warn("Axe of Lightning: " + IncineratorSkill.describe(caster)
                    + (shockwave ? " burst shockwave spawn failed (reflection mismatch, see log [MCE])"
                                  : " bolt pillars are all over air along the path (same as vanilla: no spawn, no cooldown, see log [MCE])"));
            return false;
        }

        // 冷却照常记录（拍板：共享 150 tick，两个分支同一把）
        SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING, now);
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        SafeGuard.info("Axe of Lightning: " + IncineratorSkill.describe(caster)
                + (shockwave
                        ? " releases burst shockwaves: 5 waves in a ±30° fan (15° apart, muzzle 1 block ahead, y=feet plane), "
                          + "damage reads the attack damage attribute (same scale as vanilla), life 20 ticks (every 5 ticks the box "
                          + "inflate(0.5, 4, 0.75) settles INDIRECT_MAGIC)"
                        : " releases the bolt wave: " + spawned + "/" + BOLT_COUNT + " pillars along the path (1.25 blocks apart, "
                          + "warmup increases, life 30 ticks, damage "
                          + String.format("%.1f", MceConfig.axeOfLightningBoltDamage())
                          + " points, every 5 ticks the cylinder box inflate(0.2, 6, 0.2) settles)")
                + ", spawned " + spawned + " entities, cooldown " + cooldownTicks + " tick"
                + (ignoreCooldown ? " (command forced)" : "") + ", ally interception window " + GUARD_BASE_TICKS + " tick");
        return true;
    }

    // ════════════════════════ 情境判定（拍板 auto） ════════════════════════

    /**
     * auto 情境判定（拍板：中远距离单体放雷击波，近身多敌放电爆冲击波）：
     * 目标距离 ≤ axe_of_lightning_melee_distance（6.0）且 5 格内敌对 ≥
     * axe_of_lightning_shockwave_min_enemies（2）→ 冲击波；否则雷击波。
     * 配置 axe_of_lightning_mode 锁 bolts / shockwave 时直接走锁定的分支。
     */
    static boolean decideShockwave(ServerLevel level, Mob caster, LivingEntity target, long now) {
        try {
            String mode = MceConfig.axeOfLightningMode();
            if (MODE_SHOCKWAVE.equalsIgnoreCase(mode)) {
                return true;
            }
            if (MODE_BOLTS.equalsIgnoreCase(mode)) {
                return false;
            }
            double distance = caster.distanceTo(target);
            if (distance > MceConfig.axeOfLightningMeleeDistance()) {
                return false; // 中远距离：单体雷击波（铺列纵深 15 格够得着）
            }
            int minEnemies = MceConfig.axeOfLightningShockwaveMinEnemies();
            List<LivingEntity> hostiles = level.getEntitiesOfClass(LivingEntity.class,
                    caster.getBoundingBox().inflate(SURROUNDED_RADIUS),
                    e -> e != caster && e.isAlive() && !AllyCheck.isAlly(caster, e));
            if (hostiles.size() >= minEnemies) {
                return true; // 近身多敌：扇形电爆冲击波
            }
            return false; // 近身单体：雷击波照样铺列（首根 1.25 格在目标方向上）
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ax_mode", now,
                    "Axe of Lightning: mode check failed (caught, falls back to bolt wave): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return false;
        }
    }

    // ════════════════════════ 分支一：雷击波（照抄原版 use + spawnFangs） ════════════════════════

    /** @return 成功生成的波柱数量 */
    private static int castBolts(ServerLevel level, Mob caster, long now) {
        float boltDamage = (float) MceConfig.axeOfLightningBoltDamage();
        // 原版 use 开头：playSound(LIGHTNING_BOLT_THUNDER, 3f, 1f)
        playSound(level, caster, SoundEvents.LIGHTNING_BOLT_THUNDER, 3.0F, 1.0F, "lightning thunder");
        int standingOnY = Mth.floor(caster.getY()) - 1; // 原版 lowestYCheck 照抄
        double headY = caster.getY() + 1.0D;            // 原版 headY 照抄
        float yawRadians = (float) Math.toRadians(90.0F + caster.getYRot()); // 原版 yawRadians 照抄
        int spawned = 0;
        for (int l = 0; l < BOLT_COUNT; l++) {
            try {
                double d2 = BOLT_STEP_DISTANCE * (double) (l + 1);
                double x = caster.getX() + (double) Mth.cos(yawRadians) * d2;
                double z = caster.getZ() + (double) Mth.sin(yawRadians) * d2;
                double y = scanGroundForBolt(level, x, headY, z, standingOnY);
                if (y == Double.NEGATIVE_INFINITY) {
                    continue; // 原版 spawnFangs：没扫到实地面就不出该根
                }
                if (LegendaryMonstersReflect.spawnLightningStrike(level, caster,
                        x, y, z, yawRadians, l, BOLT_LIFE_TICKS, boltDamage, now)) {
                    spawned++;
                }
            } catch (Throwable per) {
                SafeGuard.warnThrottled("mce_ax_boltloop_" + l, now,
                        "Axe of Lightning: # " + (l + 1) + " bolt pillar spawn failed (caught, that pillar skipped): "
                                + per.getClass().getSimpleName() + " / " + per.getMessage());
            }
        }
        return spawned;
    }

    /**
     * 向下地面扫描（逐行照抄原版 spawnFangs）：从 (x, headY, z) 向下找第一个“上表面坚实”
     * 的方块，只扫到脚下 -1（lowestYCheck = floor(y) - 1，原版扫描带就是这么窄，
     * 同伴站在高处边缘时远处点位会悬空——与原版表现一致）；没找到返回 NEGATIVE_INFINITY。
     */
    static double scanGroundForBolt(ServerLevel level, double x, double headY, double z, int lowestYCheck) {
        try {
            BlockPos blockpos = BlockPos.containing(x, headY, z);
            boolean flag = false;
            double d0 = 0.0D;
            do {
                BlockPos below = blockpos.below();
                BlockState state = level.getBlockState(below);
                if (state.isFaceSturdy(level, below, Direction.UP)) {
                    if (!level.isEmptyBlock(blockpos)) {
                        BlockState state1 = level.getBlockState(blockpos);
                        VoxelShape shape = state1.getCollisionShape(level, blockpos);
                        if (!shape.isEmpty()) {
                            d0 = shape.max(Direction.Axis.Y);
                        }
                    }
                    flag = true;
                    break;
                }
                blockpos = below;
            } while (blockpos.getY() >= lowestYCheck);
            if (!flag) {
                return Double.NEGATIVE_INFINITY;
            }
            return blockpos.getY() + d0;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ax_scan", level.getGameTime(),
                    "Axe of Lightning: ground scan failed (caught, that spot skipped): " + t.getClass().getSimpleName());
            return Double.NEGATIVE_INFINITY;
        }
    }

    // ════════════════════════ 分支二：电爆冲击波（照抄原版 useOn） ════════════════════════

    /** @return 成功生成的电爆数量 */
    private static int castShockwave(ServerLevel level, Mob caster, long now) {
        // 原版 useOn：伤害 = 玩家 ATTACK_DAMAGE 属性值；同伴读不到就退回原版武器基础 12 点
        float damage;
        AttributeInstance attackAttr = caster.getAttribute(Attributes.ATTACK_DAMAGE);
        if (attackAttr != null) {
            damage = (float) attackAttr.getValue();
        } else {
            damage = BURST_DAMAGE_FALLBACK;
            SafeGuard.infoThrottled("mce_ax_attnull_" + caster.getUUID(), now,
                    "Axe of Lightning: " + IncineratorSkill.describe(caster)
                            + " has no attack damage attribute (companion entity has no ATTACK_DAMAGE), burst damage falls back to the vanilla weapon base "
                            + BURST_DAMAGE_FALLBACK + " points");
        }
        // 原版 useOn 开头：playSound(WITHER_SHOOT, 1, 0.9f)
        playSound(level, caster, SoundEvents.WITHER_SHOOT, 1.0F, 0.9F, "wither shoot");
        double theta = Math.toRadians(caster.getYHeadRot()) + Math.PI / 2.0D; // 原版 theta 照抄
        double vecX = Math.cos(theta);
        double vecZ = Math.sin(theta);
        double spawnX = caster.getX() + vecX * 1.0D; // 原版 spawnX = player.getX() + vecX * 1
        double spawnY = Mth.floor(caster.getY());    // 原版 float y = Mth.floor(player.getY())
        double spawnZ = caster.getZ() + vecZ * 1.0D;
        int spawned = 0;
        for (int i = 0; i < BURST_COUNT; i++) {
            try {
                float angle = caster.getYHeadRot() + (i - (BURST_COUNT / 2)) * BURST_ANGLE_STEP;
                float rad = (float) Math.toRadians(angle);
                double dx = -Math.sin(rad);
                double dz = Math.cos(rad);
                if (LegendaryMonstersReflect.spawnElectricityBurst(level, caster,
                        dx, 0.0D, dz, damage, angle, BURST_LIFE_TICKS, spawnX, spawnY, spawnZ, now)) {
                    spawned++;
                }
            } catch (Throwable per) {
                SafeGuard.warnThrottled("mce_ax_burstloop_" + i, now,
                        "Axe of Lightning: # " + (i + 1) + " burst shockwave spawn failed (caught, that wave skipped): "
                                + per.getClass().getSimpleName() + " / " + per.getMessage());
            }
        }
        if (spawned > 0) {
            spawnFanParticles(level, caster);
        }
        return spawned;
    }

    /** 观感：出膛点一圈 ELECTRIC_SPARK（原版电爆粒子是模组客户端粒子，反射不划算，补同观感） */
    private static void spawnFanParticles(ServerLevel level, Mob caster) {
        try {
            double y = caster.getY() + 1.0D;
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK,
                    caster.getX(), y, caster.getZ(), 20, 1.0D, 0.5D, 1.0D, 0.1D);
        } catch (Throwable ignored) {
        }
    }

    private static void playSound(ServerLevel level, Mob caster, SoundEvent sound,
                                  float volume, float pitch, String label) {
        try {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    sound, SoundSource.PLAYERS, volume, pitch);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ax_snd_" + label, level.getGameTime(),
                    "Axe of Lightning: playing " + label + " sound failed (caught, skill unaffected): "
                            + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ force / 状态键 ════════════════════════

    /**
     * force 命令入口：指定模式直接释放（跳过冷却与情境判定）。
     * bolts / shockwave 都需要目标定方向；不带模式走 auto 判定（tryCast）。
     */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            boolean shockwave;
            if (FORCE_MODE_SHOCKWAVE.equals(mode)) {
                shockwave = true;
            } else if (FORCE_MODE_BOLTS.equals(mode)) {
                shockwave = false;
            } else {
                return tryCast(level, caster, target, now, true); // auto
            }
            if (target == null) {
                SafeGuard.warn("Axe of Lightning: " + mode + " mode needs an attack target (direction source) - "
                        + "get it into combat first (e.g. hit it) then run /mce axe_of_lightning force @e[...] " + mode);
                return false;
            }
            if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
                SafeGuard.warn("Axe of Lightning: Legendary Monsters mod not detected, forced cast cancelled");
                return false;
            }
            EquipmentSlot slot = INSTANCE.heldSlot(caster);
            if (slot == null) {
                SafeGuard.warn("Axe of Lightning: " + IncineratorSkill.describe(caster)
                        + " is not holding the Axe of Lightning (force does not require holding it but intent must be confirmed, cancelled)");
                return false;
            }
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);
            setLastMode(caster, mode);
            int spawned = shockwave
                    ? castShockwave(level, caster, now)
                    : castBolts(level, caster, now);
            if (spawned <= 0) {
                SafeGuard.warn("Axe of Lightning: force " + mode + " spawn failed (0 entities, see log [MCE])");
                return false;
            }
            SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING, now);
            markGuardWindow(caster, now + GUARD_BASE_TICKS);
            caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
            SafeGuard.info("Axe of Lightning: force " + mode + " completed - spawned " + spawned + " entities, "
                    + "cooldown " + MceConfig.axeOfLightningCooldownTicks() + " tick, "
                    + "ally interception window " + GUARD_BASE_TICKS + " tick");
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Axe of Lightning: forceCast failed (caught): ", t);
            return false;
        }
    }

    /** 友军拦截窗口（AllyDamageGuard 读） */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一次模式（status 展示用） */
    private static void setLastMode(Entity owner, String mode) {
        try {
            owner.getPersistentData().putString(KEY_LAST_MODE, mode);
        } catch (Throwable ignored) {
        }
    }

    public static String getLastMode(Entity owner) {
        try {
            String mode = owner.getPersistentData().getString(KEY_LAST_MODE);
            return mode == null || mode.isEmpty() ? "none" : mode;
        } catch (Throwable t) {
            return "none";
        }
    }

    /** /mce axe_of_lightning reset 时一并清掉本技能的易变状态（拦截窗口/结果标记） */
    public static void clearVolatileState(Entity owner) {
        try {
            CompoundTag data = owner.getPersistentData();
            data.remove(KEY_GUARD_UNTIL);
            data.remove(KEY_LAST_MODE);
        } catch (Throwable ignored) {
        }
    }
}
