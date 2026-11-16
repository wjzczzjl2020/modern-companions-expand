package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

/**
 * 掣雷巨锤（cataclysm:brontes）同伴双分支技能：抛物线投掷 + 贴脸风暴，瞬发、无蓄力。
 *
 * 对齐灾变源码 Brontes.releaseUsing / Brontes_Entity（详见 docs/ANALYSIS_ROUND20_VOID_FORGE_BRONTES.md）：
 * - 原版 releaseUsing 第一行 instanceof Player 绑死 → 同伴自研：反射构造 Brontes_Entity
 *   （末参 LivingEntity），本体物品始终留在手上（原版同款），伤害 = 攻击力属性，
 *   弹速 2.5 + 重力 0.1 抛物线（低弹道仰角解算，无解退化直接瞄准）；
 * - 贴脸（≤ brontes_melee_range，默认 6.0 格）不投掷：直接在敌人脚下复刻原版 onHit 的落点效果
 *   —— 8 个放射火花（速度 1.0、上抛 0.125、散布 5.0，落地各自生成静电场 + 小风暴）+
 *   1 个闪电风暴（size 3.0、delay -9、伤害读灾变 Brontes.stormdamage 默认 10）+ 震屏(25, 0.03, 0, 20)；
 * - 冷却：投掷/贴脸共用 100 tick（brontes_throw_cooldown_ticks）。原版无冷却、靠
 *   THROWN_HAMMER 数据组件节流“锤没回来不能再扔”；同伴版的锁权威记录存 persistentData
 *   （读档/复活都稳），并 best-effort 镜像写/清灾变的数据组件；
 * - 投掷锁定（同伴版新增限制，brontes_lock_attack_while_thrown 默认开）：锤子飞出去期间
 *   同伴不出手打人——每 tick 清攻击目标（近战 Goal 失去依据，走位/跟随/躲闪的移动 AI 不受影响）+
 *   事件层兜底取消它的近战伤害；false = 回到原版行为（投掷期间照常近战）；
 * - 强制恢复（tickLock 每 tick 检查）：锤子回到手上（实体消失）、超时
 *   （brontes_throw_timeout_ticks 默认 200 = 10 秒）、收回魂石/退出存档再进入/死亡复活后
 *   （persistentData 里的锤子 UUID 找不到对应实体）都会触发——清锁、清物品组件、
 *   尽力把掣雷巨锤从同伴背包找回归位主手（找不到只恢复普攻），每次打一条 SafeGuard.info；
 * - 友军保护：直接命中伤害源 directEntity = Brontes_Entity（已加入 isCataclysmLightningOf 拦截集合，
 *   owner 走 AbstractArrow.getOwner），风暴/静电场本就在集合里 → AllyDamageGuard 的掣雷拦截窗口
 *   （基础 = max(220, 超时+40) tick 起步，之后按“直接实体 = 该同伴的掣雷/雷电实体”动态顺延）；
 *   贴脸分支照霆浪波浪的做法做友军预扫描（圈内有友军退回普攻，不冒险生成）。
 */
public final class BrontesSkill implements CompanionSkill {

    public static final BrontesSkill INSTANCE = new BrontesSkill();

    /** force 命令的模式参数 */
    public static final String FORCE_MODE_THROW = "throw";
    public static final String FORCE_MODE_BURST = "burst";

    /** 窗口模式（persistentData，状态展示用）：1=投掷，2=贴脸风暴 */
    public static final byte MODE_THROW = 1;
    public static final byte MODE_BURST = 2;

    /** 原版弹速：shootFromRotation(…, 2.5F, 1.0F) */
    public static final float THROW_VELOCITY = 2.5F;
    /** 实体自带重力：Brontes_Entity.getDefaultGravity() = 0.1 */
    private static final double THROW_GRAVITY = 0.1D;
    /** 仰角补偿无解（目标太远/太高）时的兜底仰角 */
    private static final float FALLBACK_PITCH_DEGREES = 30.0F;

    /** 原版命中落点效果：8 个放射火花 + 1 个闪电风暴 */
    private static final int SPARK_COUNT = 8;
    /** 火花出膛速度 shoot(x, 0.125, z, 1.0F, 5.0F) */
    private static final float SPARK_SPEED = 1.0F;
    private static final double SPARK_Y_VELOCITY = 0.125D;
    private static final float SPARK_INACCURACY = 5.0F;
    /** 原版风暴参数：new Lightning_Storm_Entity(level, x, y, z, yRot, -9, stormDamage, 0, owner, 3.0F) */
    private static final int STORM_DELAY = -9;
    private static final float STORM_SIZE = 3.0F;
    /** 原版命中震屏：ScreenShake(level, pos, 25, 0.03f, 0, 20) */
    private static final float SHAKE_RADIUS = 25.0F;
    private static final float SHAKE_MAGNITUDE = 0.03F;
    private static final int SHAKE_DURATION = 0;
    private static final int SHAKE_FADE = 20;
    /** 贴脸友军预扫描半径：火花最远飞约 3~4 格，按 4 格采样留余量 */
    private static final double BURST_SCAN_RADIUS = 4.0D;

    /** 友军拦截窗口的基础时长（投掷）：覆盖锤子飞行+返回（默认超时 200），窗口过后按直接实体动态顺延 */
    public static final long GUARD_BASE_TICKS = 220L;
    /** 动态顺延步长：锤子/风暴还在结算伤害就续窗，实体消失后窗口自然过期 */
    private static final long GUARD_EXTEND_TICKS = 40L;

    /** 锁权威键（persistentData）：锤子实体 UUID / 投掷时刻 / 模式 / 友军拦截窗口 */
    private static final String KEY_BR_THROWN = "mce_br_thrown";
    private static final String KEY_BR_THROW_AT = "mce_br_throw_at";
    private static final String KEY_BR_MODE = "mce_br_mode";
    private static final String KEY_BR_GUARD_UNTIL = "mce_br_guard_until";

    private BrontesSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_BRONTES;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.BRONTES_ITEM;
    }

    /** 掣雷巨锤是镐类投掷武器，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.brontesEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.brontesThrowCooldownTicks();
    }

    /** 瞬发：原版蓄力门槛只有约 12 tick 且不影响数值（与神怒/霆浪同款，就绪即发） */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_br_cooling_" + caster.getUUID(), now,
                    "Brontes: " + IncineratorSkill.describe(caster) + " cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (isLockActive(caster)) {
            // 原版语义“锤没回来不能再扔”：即使冷却配置被调短，锤子还在外面也不允许二次投掷
            SafeGuard.infoThrottled("mce_br_thrown_" + caster.getUUID(), now,
                    "Brontes: " + IncineratorSkill.describe(caster) + " hammer is still out (throw locked), cannot throw again, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_br_nocate_" + level.dimension().location(), now,
                    "Brontes: Cataclysm mod not detected (thrown entity/storm/sparks/values all depend on Cataclysm), skill cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Brontes: " + IncineratorSkill.describe(caster) + " no longer holds the Brontes, skill cancelled");
            return false;
        }
        if (target == null) {
            SafeGuard.infoThrottled("mce_br_notarget_" + caster.getUUID(), now,
                    "Brontes: both throw and point-blank need an attack target (source of direction and landing point), cancelled this time");
            return false;
        }

        // —— 情境判定：贴脸（≤ brontes_melee_range）走风暴，远距离走投掷 ——
        byte mode = decideMode(caster, target);
        boolean ok;
        if (mode == MODE_BURST) {
            ok = castBurst(level, caster, target, slot, now, ignoreCooldown);
        } else {
            ok = castThrow(level, caster, target, stack, slot, now, ignoreCooldown);
        }
        if (ok) {
            setMode(caster, mode);
            long guardBase = Math.max(GUARD_BASE_TICKS, MceConfig.brontesThrowTimeoutTicks() + 40L);
            markGuardWindow(caster, now + guardBase);
            ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        }
        return ok;
    }

    // ════════════════════════ 情境判定 ════════════════════════

    /** 目标 ≤ brontes_melee_range（默认 6.0 格）→ 贴脸风暴；更远 → 投掷 */
    private static byte decideMode(Mob caster, LivingEntity target) {
        try {
            return caster.distanceTo(target) <= MceConfig.brontesMeleeRange() ? MODE_BURST : MODE_THROW;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_mode", gameTime(caster),
                    "Brontes: context check failed (caught, fell back to throw): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return MODE_THROW;
        }
    }

    // ════════════════════════ 投掷（自研瞄准 + 原版实体） ════════════════════════

    private boolean castThrow(ServerLevel level, Mob caster, LivingEntity target,
                              ItemStack stack, EquipmentSlot slot, long now, boolean ignoreCooldown) {
        try {
            // —— 瞄准：先拧身面向目标，再解抛物线仰角（弹速 2.5 + 重力 0.1，低弹道解）——
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            double spawnY = caster.getEyeY() - 0.1D; // 与实体构造器内部生成点同口径
            double aimY = target.getY() + target.getBbHeight() * 0.5D;
            double dx = target.getX() - caster.getX();
            double dz = target.getZ() - caster.getZ();
            double distH = Math.sqrt(dx * dx + dz * dz);
            float pitchUp = ballisticPitch(distH, aimY - spawnY);
            boolean compensated = true;
            if (Float.isNaN(pitchUp) || Float.isInfinite(pitchUp)) {
                // 边界保护：解算出 NaN/∞（目标太近/太远的极端几何）→ 退回直接朝目标中身高发射
                pitchUp = (float) Math.toDegrees(Math.atan2(aimY - spawnY, Math.max(distH, 1.0E-3D)));
                compensated = false;
                SafeGuard.warnThrottled("mce_br_pitch_" + caster.getUUID(), now,
                        "Brontes: pitch solve failed (no valid parabola solution, horizontal distance "
                                + String.format("%.2f", distH) + " blocks, height difference "
                                + String.format("%.2f", aimY - spawnY) + " blocks), "
                                + "falls back to firing straight at the target (no elevation compensation)");
            }
            float xRot = -pitchUp; // MC 俯仰：负=向上
            caster.setYRot(yawDegrees);
            caster.setXRot(xRot);
            caster.setYHeadRot(yawDegrees);

            // 方向向量的 Y 分量（归一化后），排查“锤子朝上飞”用：应 ≤ 0 或微小正值
            double dirY = -Math.sin(Math.toRadians(xRot));
            float baseDamage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float stormDamage = CataclysmReflect.brontesStormDamage();
            float areaDamage = CataclysmReflect.brontesStormAreaDamage();
            UUID hammerUuid = CataclysmReflect.spawnThrownBrontes(level, caster, xRot, yawDegrees,
                    baseDamage, THROW_VELOCITY, areaDamage, stormDamage, now);
            caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
            if (hammerUuid == null) {
                SafeGuard.warn("Brontes: throw failed (hammer entity never spawned, see [MCE] log lines for each failed step)."
                        + IncineratorSkill.describe(caster));
                return false;
            }
            // —— 投掷锁定：权威记录写 persistentData + best-effort 镜像灾变的 THROWN_HAMMER 组件 ——
            long guardBase = Math.max(GUARD_BASE_TICKS, MceConfig.brontesThrowTimeoutTicks() + 40L);
            markLock(caster, hammerUuid, now);
            CataclysmReflect.writeThrownHammerComponent(stack, hammerUuid);
            SkillCooldownStore.markCast(caster, id(), now);
            SafeGuard.info("Brontes: " + IncineratorSkill.describe(caster) + " toward target "
                    + IncineratorSkill.describe(target) + " threw the hammer (direct hit damage = attack attribute "
                    + String.format("%.1f", baseDamage) + ", projectile speed " + THROW_VELOCITY + " + gravity 0.1 parabola, "
                    + (compensated ? "elevation compensation " : "elevation compensation degraded (direct aim)") + String.format("%.1f", pitchUp) + "°, "
                    + "direction Y component " + String.format("%+.2f", dirY) + ", horizontal distance "
                    + String.format("%.2f", distH) + " blocks; on hit 8 sparks + storm, hammer flies back after), "
                    + "cooldown " + MceConfig.brontesThrowCooldownTicks() + " ticks, throw lock enabled"
                    + (MceConfig.brontesLockAttackWhileThrown() ? "(normal attack disabled)" : "(brontes_lock_attack_while_thrown=false, normal attack kept)")
                    + ", intercept window " + guardBase + " ticks baseline (dynamically extended)"
                    + (ignoreCooldown ? " (command forced)" : ""));
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Brontes: throw branch error (caught, no game impact)", t);
            return false;
        }
    }

    /**
     * 抛物线低弹道仰角解（与霆浪同款公式，重力 0.1）：
     * tanθ = (v² − √(v⁴ − g(g·d² + 2·h·v²))) / (g·d)。空气阻力（每 tick ×0.99）忽略不计。
     */
    private static float ballisticPitch(double distH, double heightDiff) {
        try {
            if (distH < 1.0E-3D) {
                return FALLBACK_PITCH_DEGREES;
            }
            double v = THROW_VELOCITY;
            double v2 = v * v;
            double v4 = v2 * v2;
            double disc = v4 - THROW_GRAVITY * (THROW_GRAVITY * distH * distH + 2.0D * heightDiff * v2);
            if (disc < 0.0D) {
                return FALLBACK_PITCH_DEGREES;
            }
            double tan = (v2 - Math.sqrt(disc)) / (THROW_GRAVITY * distH);
            return (float) Math.toDegrees(Math.atan(tan));
        } catch (Throwable t) {
            return FALLBACK_PITCH_DEGREES;
        }
    }

    // ════════════════════════ 贴脸风暴（原版 onHit 落点效果 1:1 复刻） ════════════════════════

    private boolean castBurst(ServerLevel level, Mob caster, LivingEntity target,
                              EquipmentSlot slot, long now, boolean ignoreCooldown) {
        try {
            // —— 友军预扫描（照霆浪波浪的做法，固定开启）：圈内（火花波及范围 4 格）有友军退回普攻 ——
            if (!ignoreCooldown && burstHasAlly(level, caster, target)) {
                return false;
            }

            // —— 拧身面向目标（风暴/火花无方向性，纯观感）——
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);

            double x = target.getX();
            double y = target.getY();
            double z = target.getZ();
            float stormDamage = CataclysmReflect.brontesStormDamage();
            float areaDamage = CataclysmReflect.brontesStormAreaDamage();

            // —— 落点效果逐行复刻 Brontes_Entity.onHit：震屏 + 8 火花 + 1 风暴 ——
            CataclysmReflect.triggerScreenShake(level, target.position(), SHAKE_RADIUS, SHAKE_MAGNITUDE,
                    SHAKE_DURATION, SHAKE_FADE);
            playHeavySmashSound(level, caster, x, y, z);

            int sparks = 0;
            for (int i = 0; i < SPARK_COUNT; i++) {
                try {
                    double angleRad = Math.toRadians(i * (360.0F / SPARK_COUNT));
                    double dirX = Math.cos(angleRad);
                    double dirZ = Math.sin(angleRad);
                    if (CataclysmReflect.spawnSpark(level, caster,
                            x, y + 0.1D, z, dirX, dirZ, SPARK_SPEED, SPARK_INACCURACY,
                            areaDamage, stormDamage, 1.0F, now)) {
                        sparks++;
                    }
                } catch (Throwable perSpark) {
                    SafeGuard.warnThrottled("mce_br_spark_" + i, now,
                            "Brontes: spark " + (i + 1) + " spawn failed (that spark skipped): "
                                    + perSpark.getClass().getSimpleName() + " / " + perSpark.getMessage());
                }
            }
            boolean storm = CataclysmReflect.spawnLightningStorm(level, caster,
                    x, y, z, (float) Math.toRadians(yawDegrees), STORM_DELAY,
                    stormDamage, 0.0F, STORM_SIZE, now);
            caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

            if (!storm && sparks <= 0) {
                SafeGuard.warn("Brontes: point-blank storm failed (neither storm nor sparks spawned, likely reflection mismatch)."
                        + IncineratorSkill.describe(caster));
                return false;
            }
            SkillCooldownStore.markCast(caster, id(), now);
            SafeGuard.info("Brontes: " + IncineratorSkill.describe(caster) + " at target "
                    + IncineratorSkill.describe(target) + " feet triggered landing storm (lightning storm "
                    + (storm ? "1 (size " + STORM_SIZE + ", damage " + String.format("%.1f", stormDamage) + ", "
                    + "per source code delay=-9)" : "spawn failed") + ", radial sparks " + sparks + "/" + SPARK_COUNT
                    + " (each spawning a static field on landing, 1 dmg/5 ticks + mini storm), screen shake(25, 0.03)), "
                    + "cooldown " + MceConfig.brontesThrowCooldownTicks() + " tick"
                    + (ignoreCooldown ? " (command forced)" : ""));
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Brontes: point-blank storm branch error (caught, no game impact)", t);
            return false;
        }
    }

    /**
     * 贴脸友军预扫描：以目标为圆心、半径 4 格采样（火花最远飞约 3~4 格），任一采样点附近
     * （±1.0 格、高 2.5 格）有友军 → true，贴脸出局退回普攻。纯几何判定，成本可忽略。
     */
    private static boolean burstHasAlly(ServerLevel level, Mob caster, LivingEntity target) {
        try {
            double[] radii = {0.0D, 1.5D, 3.0D, BURST_SCAN_RADIUS};
            for (double radius : radii) {
                for (int i = 0; i < 8; i++) {
                    double angle = (double) i / 8.0D * (Math.PI * 2.0D);
                    double px = target.getX() + Math.cos(angle) * radius;
                    double pz = target.getZ() + Math.sin(angle) * radius;
                    AABB box = new AABB(px - 1.0D, target.getY() - 0.5D, pz - 1.0D,
                            px + 1.0D, target.getY() + 2.0D, pz + 1.0D);
                    for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box)) {
                        if (e != caster && e != target && e.isAlive() && AllyCheck.isAlly(caster, e)) {
                            SafeGuard.infoThrottled("mce_br_scan_" + caster.getUUID(),
                                    level.getGameTime(), "Brontes: point-blank pre-scan found ally "
                                            + IncineratorSkill.describe(e) + ", storm dropped, fell back to normal attack");
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (Throwable t) {
            // 预扫描本身出错时按“有友军”保守处理：宁可退回普攻，不冒险炸友军
            SafeGuard.warnThrottled("mce_br_scan", gameTime(caster),
                    "Brontes: point-blank pre-scan error (caught, treated as ally present): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return true;
        }
    }

    /** 原版命中音效：锤子撞地的 HEAVY_SMASH（getHitGroundSoundEvent），同伴贴脸分支对齐用 */
    private static void playHeavySmashSound(ServerLevel level, Mob caster, double x, double y, double z) {
        try {
            net.minecraft.sounds.SoundEvent smash = CataclysmReflect.getHeavySmashSound();
            if (smash == null) {
                SafeGuard.infoThrottled("mce_br_nosound_" + level.dimension().location(),
                        level.getGameTime(), "Brontes: Cataclysm sound not found " + CataclysmReflect.HEAVY_SMASH_SOUND
                                + ", this point-blank storm is silent (no skill impact)");
                return;
            }
            level.playSound(null, x, y, z, smash, SoundSource.PLAYERS, 1.0F, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_snd", level.getGameTime(),
                    "Brontes: play sound failed (caught, no skill impact): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 投掷锁定 + 强制恢复（每 tick 驱动） ════════════════════════

    /**
     * 锁定驱动：由 SkillTickHandler 每 tick 调（所有 Mob，先查 persistentData 键，无锁零成本直通）。
     *
     * 锁激活期间：同伴不出手打人（清攻击目标，近战 Goal 失去依据；移动/跟随/躲闪 AI 不受影响）
     * —— 是否锁定由 brontes_lock_attack_while_thrown 决定，锁定本身（恢复检测）始终进行。
     */
    public static void tickLock(ServerLevel level, Mob mob, long now) {
        try {
            if (!mob.getPersistentData().hasUUID(KEY_BR_THROWN)) {
                return;
            }
            UUID hammerUuid = mob.getPersistentData().getUUID(KEY_BR_THROWN);
            long throwAt = mob.getPersistentData().getLong(KEY_BR_THROW_AT);

            // —— 恢复条件一：锤子实体已不存在（回到手上自动消失 / 自毁 / 丢失 / 收回魂石后重进）——
            Entity hammer = null;
            try {
                hammer = level.getEntity(hammerUuid);
            } catch (Throwable ignored) {
            }
            boolean hammerGone = hammer == null || !hammer.isAlive() || hammer.isRemoved();
            // —— 恢复条件二：超时（brontes_throw_timeout_ticks，默认 200 = 10 秒）——
            boolean timedOut = (now - throwAt) >= MceConfig.brontesThrowTimeoutTicks();

            if (hammerGone) {
                recover(mob, now, "hammer entity gone (back in hand/self-destructed/lost/companion recalled then re-added)");
            } else if (timedOut) {
                recover(mob, now, "timed out (" + MceConfig.brontesThrowTimeoutTicks()
                        + " ticks, throw time " + throwAt + ")");
            } else if (MceConfig.brontesLockAttackWhileThrown()) {
                // 锁定中：不出手打人。清攻击目标后近战 Goal 失去依据，同伴继续走位/跟随/躲闪；
                // 即使同伴模组的索敌 Goal 偶尔重新锁上，事件层也会把它的近战伤害兜底取消。
                try {
                    if (mob.getTarget() != null) {
                        mob.setTarget(null);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_tick", now,
                    "Brontes: throw lock driver error (caught, no game impact): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    /**
     * 强制恢复：清锁定状态 + 清物品组件 + 尽力把掣雷巨锤找回归位主手（找不到只恢复普攻）。
     * 触发点：锤子回到手上 / 超时 / 收回魂石 / 退出存档再进入 / 同伴死亡后复活。
     */
    private static void recover(Mob mob, long now, String reason) {
        try {
            boolean hadLock = isLockActive(mob);
            clearLock(mob);
            // 清掣雷物品上的 THROWN_HAMMER 组件（best-effort，主手/副手都查一遍）
            for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
                ItemStack stack = mob.getItemBySlot(slot);
                if (!stack.isEmpty() && requiredItemIsBrontes(stack)) {
                    CataclysmReflect.clearThrownHammerComponent(stack);
                }
            }
            int restored = ensureHammerInMainHand(mob);
            SafeGuard.info("Brontes force recovery: " + IncineratorSkill.describe(mob) + " -- reason: " + reason
                    + (hadLock ? "" : "(lock record missing, recovery check still run)")
                    + "; throw lock cleared, normal attack restored"
                    + (restored > 0 ? ", Brontes hammer found in inventory and returned to main hand"
                            : restored < 0 ? "; no Brontes hammer in main hand and inventory reflection unavailable, not recovered (normal attack restored)"
                            : "; main hand item unchanged (Brontes hammer still in hand)"));
        } catch (Throwable t) {
            SafeGuard.error("Brontes: force recovery flow error (caught, lock state still cleared)", t);
            try {
                clearLock(mob);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 从同伴背包把掣雷巨锤找回归位主手（确认要求的“物品丢了从背包找回来”）。
     * 同伴背包是现代同伴自己的 Container（protected SimpleContainer inventory，有 public getInventory），
     * 走反射；找不到方法/没有该物品返回 0（跳过，不硬造物品），反射不可用返回 -1。
     */
    private static int ensureHammerInMainHand(Mob mob) {
        try {
            ItemStack main = mob.getItemBySlot(EquipmentSlot.MAINHAND);
            if (!main.isEmpty() && requiredItemIsBrontes(main)) {
                return 0; // 物品没丢，无需找回
            }
            java.lang.reflect.Method getInventory;
            try {
                getInventory = mob.getClass().getMethod("getInventory");
            } catch (Throwable notFound) {
                return -1;
            }
            Object container = getInventory.invoke(mob);
            if (container instanceof net.minecraft.world.Container inv) {
                for (int i = 0; i < inv.getContainerSize(); i++) {
                    ItemStack stack = inv.getItem(i);
                    if (!stack.isEmpty() && requiredItemIsBrontes(stack)) {
                        inv.setItem(i, ItemStack.EMPTY);
                        mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
                        SafeGuard.info("Brontes force recovery: found Brontes hammer in companion inventory slot " + i + " and returned it to main hand");
                        return 1;
                    }
                }
                return 0; // 背包里也没有：按确认跳过，只恢复普攻
            }
            return -1;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_br_find", gameTime(mob),
                    "Brontes force recovery: inventory lookup error (caught, recovery skipped): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return -1;
        }
    }

    private static boolean requiredItemIsBrontes(ItemStack stack) {
        try {
            return CataclysmReflect.BRONTES_ITEM.equals(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** force 命令入口：指定模式直接释放（跳过冷却、情境判定与贴脸预扫描） */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            if (!CataclysmReflect.isCataclysmPresent()) {
                SafeGuard.warn("Brontes: Cataclysm mod not detected, force cast cancelled");
                return false;
            }
            if (isLockActive(caster)) {
                SafeGuard.warn("Brontes: " + IncineratorSkill.describe(caster) + " hammer still out (throw lock active), "
                        + "cannot force a second throw; use /mce brontes reset to clear state");
                return false;
            }
            EquipmentSlot slot = INSTANCE.heldSlot(caster) == EquipmentSlot.OFFHAND
                    ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
            ItemStack stack = caster.getItemBySlot(slot);
            if (stack.isEmpty()) {
                SafeGuard.warn("Brontes: " + IncineratorSkill.describe(caster) + " has no Brontes hammer in hand, force cast cancelled");
                return false;
            }
            boolean ok;
            if (FORCE_MODE_THROW.equals(mode)) {
                if (target == null) {
                    SafeGuard.info("Brontes: throw mode needs an attack target (direction source), force cast cancelled");
                    return false;
                }
                ok = INSTANCE.castThrow(level, caster, target, stack, slot, now, true);
            } else if (FORCE_MODE_BURST.equals(mode)) {
                if (target == null) {
                    SafeGuard.info("Brontes: point-blank mode needs an attack target (landing source), force cast cancelled");
                    return false;
                }
                ok = INSTANCE.castBurst(level, caster, target, slot, now, true);
            } else {
                SafeGuard.warn("Brontes: unknown mode '" + mode + "' (available: throw / burst)");
                return false;
            }
            if (ok) {
                setMode(caster, FORCE_MODE_THROW.equals(mode) ? MODE_THROW : MODE_BURST);
                long guardBase = Math.max(GUARD_BASE_TICKS, MceConfig.brontesThrowTimeoutTicks() + 40L);
                markGuardWindow(caster, now + guardBase);
                ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
                SafeGuard.info("Brontes: command force cast '" + mode + "' completed, cooldown recorded as usual");
            }
            return ok;
        } catch (Throwable t) {
            SafeGuard.error("Brontes: force cast error (caught)", t);
            return false;
        }
    }

    // ════════════════════════ 状态键 ════════════════════════

    private static void markLock(Mob caster, UUID hammerUuid, long now) {
        try {
            caster.getPersistentData().putUUID(KEY_BR_THROWN, hammerUuid);
            caster.getPersistentData().putLong(KEY_BR_THROW_AT, now);
        } catch (Throwable ignored) {
        }
    }

    /** 投掷锁定是否激活（锤子 UUID 还挂在 persistentData 上） */
    public static boolean isLockActive(Entity owner) {
        try {
            return owner.getPersistentData().hasUUID(KEY_BR_THROWN);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 锁定的锤子 UUID（/mce brontes status 展示用），无锁返回 null */
    public static UUID thrownHammerUuid(Entity owner) {
        try {
            return owner.getPersistentData().getUUID(KEY_BR_THROWN);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 投掷时刻（status 展示用），无锁返回 -1 */
    public static long throwAtTick(Entity owner) {
        try {
            return owner.getPersistentData().getLong(KEY_BR_THROW_AT);
        } catch (Throwable t) {
            return -1L;
        }
    }

    public static void setMode(Entity owner, byte mode) {
        try {
            owner.getPersistentData().putByte(KEY_BR_MODE, mode);
        } catch (Throwable ignored) {
        }
    }

    public static byte getMode(Entity owner) {
        try {
            return owner.getPersistentData().getByte(KEY_BR_MODE);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 清锁定（不动冷却与拦截窗口） */
    private static void clearLock(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_BR_THROWN);
            owner.getPersistentData().remove(KEY_BR_THROW_AT);
        } catch (Throwable ignored) {
        }
    }

    /** 掣雷友军拦截窗口（AllyDamageGuard 读）：窗口内该同伴的锤子/风暴/火花伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_BR_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_BR_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 动态顺延：伤害源的直接实体还是该同伴的掣雷锤/雷电实体时续 40 tick，实体消失后窗口自然过期 */
    public static void extendGuardWindow(Entity owner, long now) {
        markGuardWindow(owner, now + GUARD_EXTEND_TICKS);
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_BR_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce brontes reset 时一并清掉本技能的易变状态（含锁定与拦截窗口） */
    public static void clearVolatileState(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_BR_MODE);
            owner.getPersistentData().remove(KEY_BR_THROWN);
            owner.getPersistentData().remove(KEY_BR_THROW_AT);
            owner.getPersistentData().remove(KEY_BR_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}