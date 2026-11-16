package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.event.SoulGreatSwordParry;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * 灵魂巨剑（legendary_monsters:soul_great_sword，传奇怪物）同伴技能：幻影匕首。
 *
 * 对齐传奇怪物源码（详见 docs/ANALYSIS_ROUND28_SOUL_GREAT_SWORD.md）：
 * - 原版匕首在 releaseUsing：按 shift 蓄力 ≥20 tick（1 秒）才发射——同伴版复用断魂战戟的
 *   “蓄力站定”框架（chargeTicks=20，蓄力期停寻路/停 Goal/面向目标），门槛数值照抄；
 * - 发射 6 支 ThrownPhantomDaggerEntity（反射，见 LegendaryMonstersReflect.spawnDaggerVolley）：
 *   环形出膛几何/弹速 0.7/setReturnTick(10) 全部照抄原版 spreadDaggers，
 *   唯一改动是锁定——不再沿视线 15 格射线（同伴没有玩家视线），直接锁定“同伴当前攻击目标”
 *   （拍板），追踪由实体内部自驱（发射 10 tick 后持续转向，同伴版零追踪代码）；
 * - 接触伤害 8 点（原版 setDamage(8)）；r29 起同伴默认【强制走玩家 owner 分支】——
 *   只保留基础 8 点 + 正常结算，非玩家 owner 的附加（最大生命 1% / Soul Fracture / owner 回 3 点）
 *   由事件层 SoulGreatSwordDaggerGate 剥离；配置 soul_great_sword_dagger_monster_branch=true
 *   可切回怪物分支（r28 行为，判定出错按“放行”兜底，见 docs/ANALYSIS_ROUND29_PHANTOM_DAGGER_BRANCH.md）；
 * - 冷却 120 tick（6 秒），原版 player.getCooldowns() 绑 Player → 同伴版 persistentData，
 *   配置 soul_great_sword_dagger_cooldown_ticks 默认 120；
 * - 友军保护两层：发射前预扫描（目标/周围友军 → 不发射）+ 事件层拦截窗口 140 tick
 *   （覆盖匕首 110 tick 全生命周期有余量；匕首 ghostly 伤害的 causing entity = 同伴，
 *   AllyDamageGuard 按 AllyCheck 取消友军受伤）；
 * - 格挡（被动，方案 A）在 SoulGreatSwordParry（事件层），不走本类释放路径；
 *   /mce soul_great_sword force parry 预挂一次性“下次受伤必格挡”标记用于测试。
 */
public final class SoulGreatSwordSkill implements CompanionSkill {

    public static final SoulGreatSwordSkill INSTANCE = new SoulGreatSwordSkill();

    /** force 命令的模式参数 */
    public static final String FORCE_MODE_PARRY = "parry";
    public static final String FORCE_MODE_DAGGER = "dagger";

    /** 原版蓄力门槛：releaseUsing 里 timeUsed >= 20 才发射（按 shift 蓄力 1 秒） */
    public static final int DAGGER_CHARGE_TICKS = 20;
    /** 原版发射数量/单发伤害：spreadDaggers(player, 6)、setDamage(8) */
    public static final int DAGGER_COUNT = 6;
    public static final float DAGGER_DAMAGE = 8.0F;

    /** 友军拦截窗口基础时长：匕首生命周期 = 100 - 0 + 10 = 110 tick，留 30 tick 余量 */
    public static final long GUARD_BASE_TICKS = 140L;

    private static final String KEY_SGS_GUARD_UNTIL = "mce_sgs_guard_until";

    private SoulGreatSwordSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_SOUL_GREAT_SWORD;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return LegendaryMonstersReflect.SOUL_GREAT_SWORD_ITEM;
    }

    /** 灵魂巨剑是双手巨剑，按原版习惯主手持握 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.soulGreatSwordEnabled();
    }

    /** 调度器层面的冷却 = 匕首冷却（格挡冷却独立，见 SoulGreatSwordParry） */
    @Override
    public int cooldownTicks() {
        return MceConfig.soulGreatSwordDaggerCooldownTicks();
    }

    /** 原版门槛 20 tick（1 秒）照抄 */
    @Override
    public int chargeTicks() {
        return DAGGER_CHARGE_TICKS;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, id(), now, cooldownTicks())) {
            SafeGuard.infoThrottled("mce_sgs_cooling_" + caster.getUUID(), now,
                    "Soul Great Sword: " + IncineratorSkill.describe(caster) + " dagger cooldown left "
                            + SkillCooldownStore.remainingTicks(caster, id(), now, cooldownTicks()) + " ticks, skipped this time");
            return false;
        }
        if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
            SafeGuard.warnThrottled("mce_sgs_nolm_" + level.dimension().location(), now,
                    "Soul Great Sword: Legendary Monsters not detected (the Phantom Dagger entity depends on it), skill cancelled");
            return false;
        }
        EquipmentSlot slot = heldSlot(caster) == EquipmentSlot.OFFHAND
                ? EquipmentSlot.OFFHAND : EquipmentSlot.MAINHAND;
        ItemStack stack = caster.getItemBySlot(slot);
        if (stack.isEmpty()) {
            SafeGuard.warn("Soul Great Sword: " + IncineratorSkill.describe(caster) + " no longer holding the Soul Great Sword, skill cancelled");
            return false;
        }
        if (target == null) {
            SafeGuard.infoThrottled("mce_sgs_notarget_" + caster.getUUID(), now,
                    "Soul Great Sword: the Phantom Dagger locks on the companion's current attack target, the target is empty, cancelled this time");
            return false;
        }
        // 友军保护第一层（预扫描）：目标是友军绝不发射（正常调度器不会选中友军目标，force 路径兜底）
        if (AllyCheck.isAlly(caster, target)) {
            SafeGuard.infoThrottled("mce_sgs_allytarget_" + caster.getUUID(), now,
                    "Soul Great Sword: the locked target " + IncineratorSkill.describe(target)
                            + " was judged an ally by AllyCheck, no dagger fired");
            return false;
        }

        // —— 拧身面向目标（视觉：举剑蓄力后甩出匕首）——
        float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
        caster.setYRot(yawDegrees);
        caster.setYHeadRot(yawDegrees);

        int spawned = LegendaryMonstersReflect.spawnDaggerVolley(level, caster, target,
                DAGGER_COUNT, DAGGER_DAMAGE, now);

        // 防刷屏口径与断魂一致：无论成败都记一次施放时刻，防止失败时逐 tick 疯狂重试
        SkillCooldownStore.markCast(caster, id(), now);
        if (spawned <= 0) {
            SafeGuard.warn("Soul Great Sword: the Phantom Dagger launch failed (not a single dagger left, usually Legendary Monsters missing / reflection layer mismatch, see the [MCE] log)."
                    + IncineratorSkill.describe(caster));
            return false;
        }
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        playReleaseSound(level, caster);
        spawnRingParticles(level, caster);
        SafeGuard.info("Soul Great Sword: " + IncineratorSkill.describe(caster) + " toward target "
                + IncineratorSkill.describe(target) + " fired " + spawned + "/" + DAGGER_COUNT
                + " daggers (base damage per hit " + String.format("%.1f", DAGGER_DAMAGE)
                + " points; r29 forces the player-owner branch -- each hit resolves only the base 8 points, "
                + (MceConfig.soulGreatSwordDaggerMonsterBranch()
                        ? "note: monster_branch=true switched back to the monster branch (the 1% max-health bonus / Soul Fracture / heal are kept)"
                        : "the 1% max-health bonus / Soul Fracture / owner heal are stripped by the gate")
                + "; the entity starts homing 10 ticks after launch, lock-on = current attack target), the ally interception window is " + GUARD_BASE_TICKS
                + " ticks, cooldown " + cooldownTicks() + " tick"
                + (ignoreCooldown ? " (command forced)" : ""));
        return true;
    }

    /** 原版发射音效：SoundEvents.WITHER_SHOOT（vanilla，直接用） */
    private static void playReleaseSound(ServerLevel level, Mob caster) {
        try {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1.0F, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sgs_snd", level.getGameTime(),
                    "Soul Great Sword: playing the launch sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    /** 原版自定义环形粒子（Circle/ModParticles）不可反射复用 → 原版 PORTAL 圈补同观感 */
    private static void spawnRingParticles(ServerLevel level, Mob caster) {
        try {
            double y = caster.getY() + 0.25D;
            for (int i = 0; i < 12; i++) {
                double a = Math.PI * 2 * i / 12.0D;
                level.sendParticles(ParticleTypes.PORTAL,
                        caster.getX() + Math.cos(a) * 0.8D, y, caster.getZ() + Math.sin(a) * 0.8D,
                        1, 0.02D, 0.02D, 0.02D, 0.02D);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sgs_ring", level.getGameTime(),
                    "Soul Great Sword: the launch particles failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    /** 蓄力期表现：面向目标 + 原版 SOUL 粒子向内收（原版 GHOSTLY_SOUL 是模组粒子，反射不划算） */
    @Override
    public void tickCharge(ServerLevel level, Mob caster, LivingEntity target, int progress, long now) {
        try {
            if (target != null) {
                float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
                caster.setYRot(yawDegrees);
                caster.setYHeadRot(yawDegrees);
            }
            double y = caster.getY() + caster.getBbHeight() * 0.6D;
            level.sendParticles(ParticleTypes.SOUL,
                    caster.getX(), y, caster.getZ(),
                    2, 0.6D, 0.2D, 0.6D, 0.01D);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sgs_charge", level.getGameTime(),
                    "Soul Great Sword: the charge visual threw (caught, charging unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ force / 状态键 ════════════════════════

    /** force 命令入口：parry = 预挂一次性强制格挡标记；dagger = 跳过冷却直接发射 */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            if (FORCE_MODE_PARRY.equals(mode)) {
                boolean ok = SoulGreatSwordParry.armForcedParry(caster, now);
                if (ok) {
                    SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_SOUL_GREAT_SWORD, now);
                    SafeGuard.info("Soul Great Sword: the command set a one-shot forced parry flag on " + IncineratorSkill.describe(caster)
                            + " (the next parriable hit of >= "
                            + String.format("%.1f", MceConfig.soulGreatSwordParryMinDamage())
                            + " points within 200 ticks will ignore the health threshold and the cooldown and run the whole parry flow, for easy testing)");
                }
                return ok;
            }
            if (FORCE_MODE_DAGGER.equals(mode)) {
                if (target == null) {
                    SafeGuard.info("Soul Great Sword: the dagger mode needs an attack target (the lock-on source) -- get it into combat first (for example hit it once)");
                    return false;
                }
                boolean ok = INSTANCE.release(level, caster, target, now, true);
                if (ok) {
                    ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
                    SafeGuard.info("Soul Great Sword: command forced launch finished, cooldown recorded as usual");
                }
                return ok;
            }
            SafeGuard.warn("Soul Great Sword: unknown mode '" + mode + "' (available: parry / dagger)");
            return false;
        } catch (Throwable t) {
            SafeGuard.error("Soul Great Sword: the forced cast threw (caught)", t);
            return false;
        }
    }

    /** 匕首友军拦截窗口（AllyDamageGuard 读）：窗口内该同伴的幻影匕首伤害参与 AllyCheck 判定 */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_SGS_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_SGS_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearGuardWindow(Entity owner) {
        try {
            owner.getPersistentData().remove(KEY_SGS_GUARD_UNTIL);
        } catch (Throwable ignored) {
        }
    }

    /** /mce soul_great_sword reset 时一并清掉本技能的易变状态 */
    public static void clearVolatileState(Entity owner) {
        clearGuardWindow(owner);
        SoulGreatSwordParry.clearState(owner);
    }
}
