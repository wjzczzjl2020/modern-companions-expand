package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * 虚空锻锤（cataclysm:void_forge）同伴技能：朝目标方向布下 7 射线 × 5 排虚空符文，瞬发、无蓄力。
 *
 * 对齐灾变源码 void_forge.useOn → spawnFangs（详见 docs/ANALYSIS_ROUND20_VOID_FORGE_BRONTES.md）：
 * - 原版入口 useOn 绑死 Player（必须主手持握、对方块右击），同伴不会右键方块 →
 *   照炼狱锻锤的做法：以同伴为中心、朝攻击目标方向布阵，布点循环逐行复刻；
 * - 7 条射线 = look 及 yRot ±0.3/±0.6/±0.9 弧度（同伴版 look = 朝目标的水平方向），
 *   每条 5 个符文（距离 1.75×(i+1) = 1.75~8.75 格），warmup = i（0~4 tick），
 *   f = Mth.atan2(vec.z, vec.x)（弧度），headY = getY()+1，standingOnY = floor(getY())-3；
 * - 符文实体反射复用（Void_Rune_Entity，末参 LivingEntity，同伴直接当 caster；
 *   伤害 = 灾变 CMCommonConfig.VoidForge.runeDamage 反射直读，默认 7 点），
 *   地面扫描与火柱同一套（悬空处不出符文），"五排依次出现"的时序由实体内部 warmup 驱动；
 * - 表现层对齐原版 useOn：cataclysm:explosion 音效（音量 1.5、随机音高）+ 震屏 ScreenShake(30, 0.1, 0, 30)；
 * - 冷却：原版 addCooldown(this, 100) 的门在发包层，同伴走不到 → persistentData，
 *   配置 void_forge_cooldown_ticks 默认 100；
 * - 友军保护：符文伤害源 indirectMagic(rune, caster) 的 directEntity 是符文本体，事件层拦截窗口
 *   120 tick 起步，之后按“伤害源直接实体 = 该同伴的 Void_Rune_Entity”动态顺延 40 tick
 *   （符文实体消失后窗口自然过期），照神怒长槊的模式。
 */
public final class VoidForgeSkill implements CompanionSkill {

    public static final VoidForgeSkill INSTANCE = new VoidForgeSkill();

    /** 原版射线偏移（弧度）：look 及 ±0.3/±0.6/±0.9，共 7 条 */
    private static final float[] RAY_OFFSETS = {0.0F, 0.3F, -0.3F, 0.6F, -0.6F, 0.9F, -0.9F};
    /** 原版每条射线 5 个符文，间距 1.75 格 */
    private static final int RUNES_PER_RAY = 5;
    private static final double RUNE_SPACING = 1.75D;
    /** 原版布阵中心高度：headY = player.getY() + 1.0 */
    private static final double HEAD_Y_OFFSET = 1.0D;
    /** 原版地面扫描下界：floor(getY()) - 3 */
    private static final double SCAN_BOTTOM_OFFSET = -3.0D;
    /** 原版震屏参数：ScreenShake(world, pos, 30, 0.1f, 0, 30) */
    private static final float SHAKE_RADIUS = 30.0F;
    private static final float SHAKE_MAGNITUDE = 0.1F;
    private static final int SHAKE_DURATION = 0;
    private static final int SHAKE_FADE = 30;

    /** 符文实体全程（warmup 0~4 + 伤害窗口约 20 tick + 残余 34 tick 生命）约 60 tick，120 起步覆盖有余量 */
    public static final long GUARD_BASE_TICKS = 120L;
    /** 动态顺延步长：符文还在结算伤害就续窗，实体消失后窗口自然过期 */
    private static final long GUARD_EXTEND_TICKS = 40L;

    private static final String KEY_VF_GUARD_UNTIL = "mce_vf_guard_until";

    private VoidForgeSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_VOID_FORGE;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.VOID_FORGE_ITEM;
    }

    /** 虚空锻锤是镐类武器，原版 useOn 明确要求主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.voidForgeEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.voidForgeCooldownTicks();
    }

    /** 瞬发：原版对方块右击立即布阵，无蓄力 */
    @Override
    public int chargeTicks() {
        return 0;
    }

    /** 符文最远 8.75 格：目标超出就没意义，超 9.5 格（含碰撞箱容差）不释放，防止空放浪费冷却 */
    @Override
    public LivingEntity findTarget(Mob caster) {
        LivingEntity target = CompanionSkill.super.findTarget(caster);
        if (target == null) {
            return null;
        }
        try {
            double limit = RUNE_SPACING * RUNES_PER_RAY + 0.75D;
            if (caster.distanceToSqr(target) > limit * limit) {
                return null;
            }
            return target;
        } catch (Throwable t) {
            return target;
        }
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_vf_cooling_" + caster.getUUID(), now,
                    "Void Forge: " + IncineratorSkill.describe(caster) + " cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_vf_nocate_" + level.dimension().location(), now,
                    "Void Forge: Cataclysm not detected (rune entity / sounds / values all depend on it), layout cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Void Forge: " + IncineratorSkill.describe(caster) + " no longer holding the Void Forge, layout cancelled");
            return false;
        }
        if (target == null) {
            SafeGuard.infoThrottled("mce_vf_notarget_" + caster.getUUID(), now,
                    "Void Forge: the layout needs an attack target (for direction), cancelled this time");
            return false;
        }

        // —— 拧身面向目标（对齐原版“按 look angle 布阵”的观感）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setXRot(0.0F);
        caster.setYHeadRot(yawDegrees);

        float runeDamage = CataclysmReflect.voidForgeRuneDamage();
        double headY = caster.getY() + HEAD_Y_OFFSET;
        int lowestYCheck = Mth.floor(caster.getY()) + (int) SCAN_BOTTOM_OFFSET;
        double dirX = target.getX() - caster.getX();
        double dirZ = target.getZ() - caster.getZ();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 1.0E-4D) {
            dirX = 1.0D;
            dirZ = 0.0D;
            len = 1.0D;
        }
        dirX /= len;
        dirZ /= len;

        // —— 表现层（对齐原版 useOn 顺序）：explosion 音效 → 震屏 → 挥手 → 布符文 ——
        playExplosionSound(level, caster);
        CataclysmReflect.triggerScreenShake(level, caster.position(), SHAKE_RADIUS, SHAKE_MAGNITUDE,
                SHAKE_DURATION, SHAKE_FADE);

        // —— 布点循环逐行复刻：7 条射线 × 5 排，warmup 递增由实体内部驱动“五排依次出现”——
        int spawned = 0;
        for (float offset : RAY_OFFSETS) {
            try {
                // 原版：looking.yRot(offset) 后取 f = atan2(vec.z, vec.x)。yRot 是绕 Y 轴旋转，
                // 对水平向量 (dx, dz) 等价于直接转角度，这里用三角恒等式一步算出旋转后的方向。
                double sin = Math.sin(offset);
                double cos = Math.cos(offset);
                double rx = dirX * cos + dirZ * sin;
                double rz = -dirX * sin + dirZ * cos;
                float f = (float) Mth.atan2(rz, rx);
                for (int i = 0; i < RUNES_PER_RAY; i++) {
                    double d2 = RUNE_SPACING * (i + 1);
                    boolean ok = CataclysmReflect.spawnVoidRune(level, caster,
                            caster.getX() + Math.cos(f) * d2,
                            headY,
                            caster.getZ() + Math.sin(f) * d2,
                            lowestYCheck, f, i, runeDamage, now);
                    if (ok) {
                        spawned++;
                    }
                }
            } catch (Throwable perRay) {
                SafeGuard.warnThrottled("mce_vf_ray_" + offset, now,
                        "Void Forge: the rune layout of the " + offset + " radian ray failed (that ray skipped): "
                                + perRay.getClass().getSimpleName() + " / " + perRay.getMessage());
            }
        }
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

        // 冷却照常记录（原版右击就进冷却，与命中无关），防止逐 tick 空放刷屏
        SkillCooldownStore.markCast(caster, id(), now);
        if (spawned <= 0) {
            SafeGuard.warn("Void Forge: " + IncineratorSkill.describe(caster) + " layout failed (not one of the 35 runes stood up, "
                    + "usually the reflection layer mismatches or all landing spots are void, see the log above)");
            return false;
        }
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        SafeGuard.info("Void Forge: " + IncineratorSkill.describe(caster) + " toward target "
                + IncineratorSkill.describe(target) + " laid down " + spawned + "/" + (RAY_OFFSETS.length * RUNES_PER_RAY)
                + " void runes (7 rays x 5 rows, 1.75~8.75 blocks, each row 1 tick later, damage per rune "
                + String.format("%.1f", runeDamage) + " read from Cataclysm VoidForge.runeDamage), cooldown "
                + cooldownTicks() + " ticks, ally interception window " + GUARD_BASE_TICKS + " ticks baseline (dynamically extended)"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    /** 原版施放音效：world.playSound(null, x, y, z, EXPLOSION, PLAYERS, 1.5f, 1F/(rand*0.4+0.8F)) */
    private static void playExplosionSound(ServerLevel level, Mob caster) {
        try {
            net.minecraft.sounds.SoundEvent explosion = CataclysmReflect.getExplosionSound();
            if (explosion == null) {
                SafeGuard.infoThrottled("mce_vf_nosound_" + level.dimension().location(),
                        level.getGameTime(), "Void Forge: Cataclysm sound not found " + CataclysmReflect.EXPLOSION_SOUND
                                + ", this layout is silent (skill unaffected)");
                return;
            }
            float pitch = 1.0F / (level.getRandom().nextFloat() * 0.4F + 0.8F);
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    explosion, SoundSource.PLAYERS, 1.5F, pitch);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_vf_snd", level.getGameTime(),
                    "Void Forge: playing the sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 友军拦截窗口（AllyDamageGuard 读） ════════════════════════

    /** 基础窗口：释放后 120 tick 起步；窗口内该同伴的符文伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_VF_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_VF_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 动态顺延：伤害源的直接实体还是该同伴的符文实体时续 40 tick，实体消失后窗口自然过期 */
    public static void extendGuardWindow(Entity owner, long now) {
        markGuardWindow(owner, now + GUARD_EXTEND_TICKS);
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_VF_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce void_forge reset 时一并清掉本技能的易变状态 */
    public static void clearVolatileState(Entity owner) {
        clearGuardWindow(owner);
    }
}