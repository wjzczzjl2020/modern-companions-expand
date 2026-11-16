package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/**
 * 炎葬（the_incinerator）同伴技能：3 秒蓄力 + 10 根灾变火柱。
 *
 * 对齐灾变源码 The_Incinerator：
 * - 蓄力 ≥60 tick 才释放（releaseUsing L53 的 i>=60），姿势 UseAnim.BOW
 *   （同伴调 startUsingItem 拿同款持握姿势，灾变 releaseUsing 有 instanceof Player
 *   守卫，同伴 stopUsingItem 不会误触发原版逻辑）；
 * - 第 60 tick 播 cataclysm:flame_burst（onUseTick L74-76）；
 * - 释放成功：ScreenShake(30, 0.15f, 0, 30) + sword_stomp（releaseUsing L65-66）；
 * - 排布/伤害与第一轮试点一致（10 根、间隔 2.25、6 点 + 2% 最大生命、持续 40 tick）。
 *
 * 与第一轮试点的差异（本轮新需求）：
 * - 就绪不再立即放，先进入“蓄力中”状态机（由调度器驱动，进度按 gameTime 差计算，
 *   读档后自动续算）；
 * - 蓄力期间每 tick 面向目标 + FLAME 粒子环随进度增强；
 * - 被打断（受伤/目标没了/超距/换姿态）不消耗冷却，只进重试延迟。
 */
public final class IncineratorSkill implements CompanionSkill {

    public static final IncineratorSkill INSTANCE = new IncineratorSkill();

    /** 与原品一致的火柱数量与间隔 */
    private static final int STRIKE_COUNT = 10;
    private static final double STEP_DISTANCE = 2.25D;
    /** 灾变原版 onUseTick 里 flame_burst 的触发时刻（60 tick）；我们蓄力时长不是 60 时自己补播音效 */
    private static final int VANILLA_SOUND_TICK = 60;

    private IncineratorSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillRegistry.INCINERATOR_ID;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.INCINERATOR_ITEM;
    }

    @Override
    public boolean enabled() {
        return MceConfig.enabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.cooldownTicks();
    }

    @Override
    public int chargeTicks() {
        return MceConfig.chargeTicks();
    }

    @Override
    public void beginCharge(ServerLevel level, Mob caster, LivingEntity target, long now) {
        try {
            // 持握起手：哪只手拿着炎葬就用哪只手做 UseAnim.BOW 姿势（正常是主手）
            caster.startUsingItem(heldSlot(caster) == net.minecraft.world.entity.EquipmentSlot.OFFHAND
                    ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND);
        } catch (Throwable t) {
            SafeGuard.warn("Incinerator charge: start pose failed (skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void tickCharge(ServerLevel level, Mob caster, LivingEntity target, int progress, long now) {
        // —— 面向目标（每 tick）：身体+头部一起转，视觉上就是“转身瞄准” ——
        float yawDegrees = yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);

        // —— 蓄力粒子环：FLAME 绕同伴转圈，半径/密度随进度增强（替代灾变纯客户端的旋转面片特效）——
        int chargeNeed = chargeTicks();
        float ratio = chargeNeed <= 0 ? 1.0F : Math.min(1.0F, progress / (float) chargeNeed);
        double radius = 0.7D + 0.5D * ratio;
        int points = 3 + (int) (5 * ratio);
        double spin = (now % 360) * Math.PI / 180.0D;
        double baseY = caster.getY() + 0.15D;
        for (int i = 0; i < points; i++) {
            double a = spin + (Math.PI * 2 * i) / points;
            level.sendParticles(ParticleTypes.FLAME,
                    caster.getX() + Mth.cos((float) a) * radius,
                    baseY + ((i % 3) * 0.3D),
                    caster.getZ() + Mth.sin((float) a) * radius,
                    1, 0.02D, 0.03D, 0.02D, 0.0D);
        }

        // —— 蓄力满格音：灾变原版是玩家 onUseTick 第 60 tick 播 flame_burst。
        //    原版驱动只在“仍按住”时触发，同伴会在第 60 tick 直接释放并收势，
        //    所以音效统一放到 release 里打（见 tryCast）；这里只负责粒子环增强 ——
    }

    @Override
    public void endCharge(ServerLevel level, Mob caster, long now) {
        try {
            if (caster.isUsingItem()) {
                caster.stopUsingItem();
            }
        } catch (Throwable t) {
            SafeGuard.warn("Incinerator charge: finish pose failed (logic unaffected): " + t.getClass().getSimpleName());
        }
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        return tryCast(level, caster, target, now, ignoreCooldown) > 0;
    }

    // ════════════════════════ 纯释放（命令 force 也走这里） ════════════════════════

    /**
     * 尝试施放（不含蓄力：直接结算火柱）。只在服务端调用。
     *
     * @param ignoreCooldown 命令强制施放时为 true
     * @return 成功生成的火柱数量（0 = 本次没有放出技能）
     */
    public static int tryCast(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        int cooldownTicks = MceConfig.cooldownTicks();

        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, SkillRegistry.INCINERATOR_ID, now, cooldownTicks)) {
            SafeGuard.infoThrottled("mce_cooling_" + caster.getUUID(), now,
                    "Incinerator: " + describe(caster) + " is still on cooldown ("
                            + SkillCooldownStore.remainingTicks(caster, SkillRegistry.INCINERATOR_ID, now, cooldownTicks)
                            + " ticks), skipped this time");
            return 0;
        }

        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_nocate_" + level.dimension().location(), now,
                    "Incinerator: Cataclysm not detected (" + STRIKE_CLASS_HINT + "), cannot create pillars of fire through reflection, cast cancelled");
            return 0;
        }

        // —— 朝向：先把同伴整个身子拧向目标，再取偏航角，保证火柱沿“它看向目标的方向”排布 ——
        // setYRot 决定火柱排布方向（立即生效）；setYHeadRot 让头先转过去，
        // 原版 Mob 每 tick 会让身体朝向平滑跟随头部，视觉上就是“转身面向目标再开炮”。
        float yawDegrees = yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);
        float yawRadians = (float) Math.toRadians(90.0 + yawDegrees);

        double headY = caster.getY() + 1.0D;
        int standingOnY = Mth.floor(caster.getY()) - 2;

        int spawned = 0;
        for (int i = 0; i < STRIKE_COUNT; i++) {
            double distance = STEP_DISTANCE * (i + 1);
            double x = caster.getX() + Mth.cos(yawRadians) * distance;
            double z = caster.getZ() + Mth.sin(yawRadians) * distance;
            int stagger = (int) (1.5F * i); // 每根火柱的出场延迟，和原品一致，形成“依次喷出”的视觉
            if (CataclysmReflect.spawnFlameStrike(level, x, z, standingOnY, headY, yawRadians,
                    40, stagger, stagger, 1.0F, 6.0F, 2.0F, caster, now)) {
                spawned++;
            }
        }

        // 无论成功几根，都记一次施放时刻：防止“全部失败”时逐 tick 疯狂重试刷屏
        SkillCooldownStore.markCast(caster, SkillRegistry.INCINERATOR_ID, now);

        if (spawned > 0) {
            // —— 施法表现（对齐灾变 releaseUsing 尾部）：震屏 → 蓄力完成音 → 剑跺地音效 + 挥一下主手 ——
            CataclysmReflect.triggerScreenShake(level, caster.position(), 30.0F, 0.15F, 0, 30);
            // flame_burst 补播规则：原版 onUseTick 只有“仍按住”到第 60 tick 才自己响；
            // 蓄力 ≤60 时同伴在响之前就已收势，所以由这里补播；>60 时原版已经响过，不再重复。
            if (MceConfig.chargeTicks() <= VANILLA_SOUND_TICK) {
                playFlameBurst(level, caster);
            }
            caster.swing(InteractionHand.MAIN_HAND, true);
            SoundEvent stomp = CataclysmReflect.getSwordStompSound();
            if (stomp != null) {
                caster.playSound(stomp, 1.0F, 1.0F);
            } else {
                SafeGuard.infoThrottled("mce_nosound_" + level.dimension().location(), now,
                        "Incinerator: Cataclysm sound not found " + CataclysmReflect.SWORD_STOMP_SOUND + ", only the arm swing is played");
            }
            // 全局沉默统一在释放函数内写（调度器与 /mce force 两条路都覆盖）
            ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
            SafeGuard.info("Incinerator: cast successful!" + describe(caster) + " toward target " + describe(target)
                    + " spawned " + spawned + "/" + STRIKE_COUNT + " pillars of fire, cooldown " + cooldownTicks + " tick"
                    + (ignoreCooldown ? " (command forced)" : "")
                    + " left, global silence " + SkillPriorityConfig.silenceTicks() + " tick");
        } else {
            SafeGuard.warn("Incinerator: cast failed (no pillar of fire spawned)." + describe(caster)
                    + " at (" + Mth.floor(caster.getX()) + ", " + Mth.floor(caster.getY()) + ", "
                    + Mth.floor(caster.getZ()) + "), no placeable ground for pillars of fire within " + (int) (STEP_DISTANCE * STRIKE_COUNT)
                    + " blocks ahead; cooldown still recorded to avoid per-tick retries");
        }
        return spawned;
    }

    /** 蓄力完成音（flame_burst）；查不到灾变音效就静默跳过 */
    private static void playFlameBurst(ServerLevel level, Mob caster) {
        SoundEvent burst = CataclysmReflect.getFlameBurstSound();
        if (burst != null) {
            caster.playSound(burst, 1.0F, 1.0F);
        }
    }

    /** 从 caster 指向 target 的水平偏航角（方块 yaw 约定：-90° 修正） */
    public static float yawToDegrees(LivingEntity caster, LivingEntity target) {
        double dx = target.getX() - caster.getX();
        double dz = target.getZ() - caster.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        return Mth.wrapDegrees(yaw);
    }

    private static final String STRIKE_CLASS_HINT =
            "com.github.L_Ender.cataclysm.entity.effect.Flame_Strike_Entity";

    /** 日志/聊天用的实体简述：类型ID + 内部实体 ID */
    public static String describe(LivingEntity entity) {
        try {
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + "#id" + entity.getId();
        } catch (Throwable t) {
            return entity.getClass().getSimpleName();
        }
    }
}
