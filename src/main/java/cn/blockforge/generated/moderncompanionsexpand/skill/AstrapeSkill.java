package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * 神怒长槊（cataclysm:astrape）同伴技能：瞬发发射鸣雷槊投射物。
 *
 * 对齐灾变源码 Astrape.releaseUsing（详见 docs/ANALYSIS_ROUND15_ASTRAPE.md）：
 * - 原版 use() 只进举矛姿态，真正发射在 releaseUsing()，第一行 instanceof Player——
 *   同伴整段进不去（静默失败），照炎葬/锻锤套路自研复刻第 3~8 步；
 * - 蓄力门槛只有约 12 tick（getPowerForTime ≥ 0.5）且蓄力长短完全不影响伤害（固定 11 点），
 *   按确认直接当瞬发（等效满蓄力），不做蓄力状态机；
 * - 发射：反射 new Lightning_Spear_Entity(同伴, 方向, level, damage, 2.5D)
 *   （首参就是 LivingEntity，同伴直接当 owner）→ setYRot/setXRot
 *   → setPos(getX(), caster.getY(0.75), getZ()) → setAreaDamage/setAreaRadius → addFreshEntity，
 *   流程与原版逐行对齐（见 CataclysmReflect.spawnLightningSpear）；
 * - 瞄准：原版 = 玩家 look angle；同伴版先拧身朝向攻击目标，再从 0.75 高度指向目标
 *   脚部（target.getY() + 0.1，r16 修正：原先瞄胸口 + 无重力 → 静电场落在敌人头顶，
 *   电不到地面上的敌人；瞄脚部让矛命中目标后贴地下坠，静电场正好铺在敌人脚下），
 *   yRot/xRot 用原版同款公式（atan2 ×180/π ±90）；
 * - 音效：cataclysm:emp_activated（原版音量 1.0、音高 0.8、PLAYERS 声道），保留原版视觉（粒子在实体自身）；
 * - 数值：伤害读灾变 CMCommonConfig.Astrape（反射，默认 11.0 / areaDamage 2.0），
 *   静电场 2 点/5 tick 按源码不改（用户确认）；
 * - 冷却：原版 player.getCooldowns().addCooldown(this, 60) 绑 Player → 同伴版 persistentData，
 *   配置 astrape_cooldown_ticks 默认 60；
 * - 友军保护：投射物穿透不消失、静电场 owner 死后变无差别 lightningBolt，
 *   全靠事件层 AllyDamageGuard 的拦截窗口兜底——基础窗口 160 tick 起步
 *   （用户修正：覆盖静电场全程 150 tick 还有余量），之后按“伤害源的直接实体 =
 *   该同伴的灾变雷电实体”动态顺延，静电场每 5 tick 结算一次就续一次，
 *   实体消失后窗口自然过期（确认的可选方案 B）。
 */
public final class AstrapeSkill implements CompanionSkill {

    public static final AstrapeSkill INSTANCE = new AstrapeSkill();

    /** 原版加速度入参：new Lightning_Spear_Entity(…, 2.5D) */
    public static final double ACCELERATION = 2.5D;
    /** 原版静电场半径：setAreaRadius(1) */
    public static final float AREA_RADIUS = 1.0F;

    /** 友军拦截窗口的基础时长：静电场实际约 150 tick 自灭，160 tick 起步覆盖全程有余量（用户修正） */
    public static final long GUARD_BASE_TICKS = 160L;
    /** 动态顺延步长：每次雷电伤害事件续 40 tick，实体消失后不再有事件，窗口自然过期 */
    private static final long GUARD_EXTEND_TICKS = 40L;

    private static final String KEY_ASP_GUARD_UNTIL = "mce_asp_guard_until";

    private AstrapeSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_ASTRAPE;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return CataclysmReflect.ASTRAPE_ITEM;
    }

    /** 神怒长槊是投掷长柄武器，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.astrapeEnabled();
    }

    @Override
    public int cooldownTicks() {
        return MceConfig.astrapeCooldownTicks();
    }

    /** 瞬发：原版蓄力门槛只有 12 tick 且不影响任何数值（用户确认直接视为就绪即发） */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_a_cooling_" + caster.getUUID(), now,
                    "Astrape: " + IncineratorSkill.describe(caster) + " cooldown remaining "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!CataclysmReflect.isCataclysmPresent()) {
            SafeGuard.warnThrottled("mce_a_nocate_" + level.dimension().location(), now,
                    "Astrape: Cataclysm mod not detected (lance entity/sound/damage values all depend on Cataclysm), fire cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Astrape: " + IncineratorSkill.describe(caster) + " no longer holds the Astrape, fire cancelled");
            return false;
        }

        // —— 瞄准：先拧身面向目标（对齐原版“按 look angle 发射”的观感），方向从 0.75 高度指向目标胸口 ——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        Vec3 direction = aimDirection(caster, target, yawDegrees);

        // —— 发射表现（对齐原版 releaseUsing 顺序）：EMP_ACTIVATED 音效 → 鸣雷槊 → 挥手 ——
        playEmpSound(level, caster);
        float directDamage = CataclysmReflect.astrapeDirectDamage();
        boolean spawned = CataclysmReflect.spawnLightningSpear(level, caster, direction,
                directDamage, ACCELERATION,
                CataclysmReflect.astrapeAreaDamage(), AREA_RADIUS,
                yawToCataclysmYaw(direction), pitchToCataclysmPitch(direction),
                caster.getY(0.75D), now);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);

        // 防刷屏口径与断魂一致：无论成败都记一次施放时刻，防止失败时逐 tick 疯狂重试
        SkillCooldownStore.markCast(caster, id(), now);
        if (!spawned) {
            SafeGuard.warn("Astrape: fire failed (the lance entity was not added to the world, most likely a reflection mismatch, see log [MCE])."
                    + IncineratorSkill.describe(caster));
            return false;
        }

        // 友军拦截窗口：160 tick 起步覆盖静电场全程，之后事件层按雷电实体是否还在场动态顺延
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        double distH = Math.sqrt(Math.pow(target.getX() - caster.getX(), 2)
                + Math.pow(target.getZ() - caster.getZ(), 2));
        SafeGuard.info("Astrape: " + IncineratorSkill.describe(caster) + " toward target "
                + IncineratorSkill.describe(target) + " fires the Astrape lance (direct damage "
                + String.format("%.1f", directDamage) + ", static field radius " + AREA_RADIUS
                + ", damage per source 2 points/5 ticks, gravity-free straight flight + accel 2.5, aims at the target's feet, pierces entities on hit / leaves a static field on block hit), "
                + "direction Y component " + String.format("%.3f", direction.y)
                + "(horizontal distance " + String.format("%.2f", distH) + " blocks, "
                + (direction.y > 0.05D ? "flying up - normal only when the target's feet are above the launch point, otherwise the math is wrong"
                                       : "flying horizontal/down, the static field will land at the enemy's feet") + "), "
                + "cooldown " + cooldownTicks() + " ticks, ally interception window " + GUARD_BASE_TICKS + " ticks baseline (dynamically extended)"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    // ════════════════════════ 瞄准 ════════════════════════

    /**
     * 同伴版 look angle：从 0.75 高度指向目标<b>脚部</b>（target.getY() + 0.1）。
     * <p>r16 修正：原先瞄胸口，矛无重力直飞，向上分量一直保留，静电场落在敌人头顶。
     * 瞄脚部后方向必然水平或向下，矛命中目标（穿透）后贴地下坠，撞到敌人脚下的地面，
     * 静电场正好铺在敌人脚边。原版玩家是手动瞄准，同伴自动锁目标。</p>
     */
    private static Vec3 aimDirection(Mob caster, LivingEntity target, float yawDegrees) {
        try {
            double originY = caster.getY(0.75D);
            // r16：瞄准点从“目标胸口”改为“目标脚部上方 0.1 格”，保证矛水平或略微向下飞
            double aimY = target.getY() + 0.1D;
            Vec3 dir = new Vec3(target.getX() - caster.getX(),
                    aimY - originY,
                    target.getZ() - caster.getZ());
            double norm = dir.horizontalDistance();
            if (norm < 1.0E-4D) {
                // 目标几乎在正上/正下方：退化为纯竖直方向（原版公式对这种极端情况也能算出 ±90 俯仰）
                return new Vec3(0.0D, Math.signum(dir.y) * 1.0D, 0.0D);
            }
            Vec3 normalized = dir.normalize();
            // 自查：目标脚部不高于发射点时，Y 分量理论上必然 ≤ 0；冒正说明计算出了问题（r16 要求的排查日志）
            if (normalized.y > 0.05D && aimY <= originY) {
                SafeGuard.warnThrottled("mce_a_aimup", gameTime(caster),
                        "Astrape: direction Y component still positive after aiming at the feet (" + String.format("%.3f", normalized.y)
                                + "), not as expected, check target/launch coordinates: launch y=" + String.format("%.3f", originY)
                                + ", aim y=" + String.format("%.3f", aimY));
            }
            // 拧身：让同伴的朝向与飞行方向一致（头 + 身体一起转），视觉上就是“举矛瞄准再掷出”
            caster.setYRot(yawDegrees);
            caster.setXRot(pitchToCataclysmPitch(normalized));
            caster.setYHeadRot(yawDegrees);
            return normalized;
        } catch (Throwable t) {
            // 瞄准计算出任何异常都退化为“当前朝向直飞”，绝不影响发射本身
            SafeGuard.warnThrottled("mce_a_aim", gameTime(caster),
                    "Astrape: aim calculation failed (caught, falls back to current facing): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return caster.getViewVector(1.0F).normalize();
        }
    }

    /** 灾变原版 yRot 公式：atan2(z, x) ×180/π + 90（Astrape.releaseUsing L59） */
    private static float yawToCataclysmYaw(Vec3 direction) {
        return (float) (Math.atan2(direction.z, direction.x) * (180.0D / Math.PI)) + 90.0F;
    }

    /** 灾变原版 xRot 公式：-atan2(y, √(x²+z²)) ×180/π（Astrape.releaseUsing L60） */
    private static float pitchToCataclysmPitch(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        return (float) -(Math.atan2(direction.y, horizontal) * (180.0D / Math.PI));
    }

    /** 原版发射音效：level.playSound(null, x, y, z, EMP_ACTIVATED, PLAYERS, 1.0F, 0.8F) */
    private static void playEmpSound(ServerLevel level, Mob caster) {
        try {
            net.minecraft.sounds.SoundEvent emp = CataclysmReflect.getEmpActivatedSound();
            if (emp == null) {
                SafeGuard.infoThrottled("mce_a_nosound_" + level.dimension().location(),
                        level.getGameTime(), "Astrape: Cataclysm sound not found " + CataclysmReflect.EMP_ACTIVATED_SOUND
                                + ", this fire is silent (skill unaffected)");
                return;
            }
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    emp, SoundSource.PLAYERS, 1.0F, 0.8F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_a_snd", level.getGameTime(),
                    "Astrape: play sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ 友军拦截窗口（AllyDamageGuard 读） ════════════════════════

    /** 基础窗口：释放后 160 tick 起步；窗口内该同伴的雷电系伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_ASP_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_ASP_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 动态顺延（用户确认的可选方案）：伤害源的直接实体还是该同伴的灾变雷电实体时续 40 tick——
     * 静电场每 5 tick 结算一次就续一次，直到实体自灭；实体消失后不再有事件，窗口自然过期。
     */
    public static void extendGuardWindow(Entity owner, long now) {
        markGuardWindow(owner, now + GUARD_EXTEND_TICKS);
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_ASP_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce astrape reset 时一并清掉本技能的易变状态 */
    public static void clearVolatileState(Entity owner) {
        clearGuardWindow(owner);
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}
