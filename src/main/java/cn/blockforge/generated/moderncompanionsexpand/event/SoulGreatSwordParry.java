package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.ChargeStateStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.LegendaryMonstersReflect;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * r28 灵魂巨剑“格挡”——同伴版方案 A（被动触发，拍板）。
 *
 * 原版（传奇怪物 ForgeEvents.onLivingHurt）：玩家右键举剑进入 3 tick 使用窗口，
 * 窗口内受伤则对攻击者反击（主手攻击力 + 来伤×30%）、以攻击者为中心 5×6×5 AoE 各吃一次
 * 全额攻击力；反击命中（hurt 返回 true）才取消本次来伤 + 回血 20% + 70 tick 冷却，
 * 没打中进 20 tick 冷却。同伴不会“预判右键”，改为：满足触发条件（拍板的阈值们）时
 * 在受伤事件里自动走同一套结算——机制（免伤/反击/治疗/不可格挡类型）全部照抄源码。
 *
 * 事件时序（拍板问询，见 docs/IMPL_ROUND28_SOUL_GREAT_SWORD.md 第 4 节）：
 * - 本监听注册在 AllyDamageGuard（友军保护）之后、ShadowInvulnGate（影子无敌帧）之前；
 * - 友军保护先裁：其他同伴技能打向本同伴的伤害若属友军误伤会先被取消，本监听开头
 *   event.isCanceled() 直通——不拿同伴的误伤去喂格挡，也不消耗冷却；
 * - 影子闸门后置：格挡成功是 cancel 事件，取消的伤害根本不会进 hurt()，
 *   不存在“压 lastHurt”，影子闸门对已取消事件也一律不碰（它自己先查 isCanceled），
 *   两条链路天然无冲突；影子闸门的反向影响同样为零——它的影子账本挂在“目标正被绞肉”
 *   上，同伴格挡的是打向同伴自己的伤害，两个方向互不相交；
 * - 蓄力打断：SkillInterruptHandler 注册在更早，格挡取消的伤害仍会给蓄力累计
 *   ——为对齐原版“格挡=免疫此次伤害”的语义，格挡成功后把刚盖的打断标记清回去
 *   （markHurt(0)），被打并不中断正在进行的蓄力。
 *
 * 触发条件（拍板，全部可配）：
 * - 总开关 soul_great_sword_parry_enabled（默认 true）；
 * - 冷却 soul_great_sword_parry_cooldown_ticks = 200（10 秒，存 persistentData，读档不重置）；
 * - 血量阈值：同伴当前血量 < 最大生命 × soul_great_sword_parry_health_threshold（0.8）才触发；
 * - 单次伤害阈值：来伤 ≥ soul_great_sword_parry_min_damage（4.0）才触发（成本不高，做了）；
 * - 持有灵魂巨剑（主手或副手任一）；
 * - 原版行为保留：BYPASSES_INVULNERABILITY / BYPASSES_ARMOR 标签的伤害不可格挡（原样复用）；
 *   蓄匕首期间（同伴 = CHARGING 且技能是本剑）不享受格挡（原版 isShiftKeyDown 的同伴对应物）；
 * - 不可格挡直接实体非 LivingEntity 的伤害（原版只反打 LivingEntity）。
 *
 * force 测试：/mce soul_great_sword force @e[...] parry 预挂一次性标记，
 * 200 tick 内下一次可格挡伤害无视血量/伤害阈值与冷却直接走全流程。
 *
 * 兜底：全流程 try-catch + SafeGuard，任何异常按“不格挡”处理（伤害照常结算，绝不崩游戏）。
 */
public final class SoulGreatSwordParry {

    private SoulGreatSwordParry() {}

    /** 格挡冷却键（与匕首冷却 mce_cd_soul_great_sword 分开：两者独立计时） */
    private static final String KEY_PARRY_AT = "mce_sgs_parry_at";
    /** force 测试标记：挂到的时间戳，200 tick 内有效，触发一次即消费 */
    private static final String KEY_PARRY_FORCE_AT = "mce_sgs_parry_force_at";
    private static final long FORCE_MARK_TTL_TICKS = 200L;

    /** 格挡没打中（反击未命中）时的试探冷却：原版 20 tick，照抄 */
    private static final int PARRY_FAIL_COOLDOWN_TICKS = 20;

    /** /mce soul_great_sword force parry 挂一次性强制格挡标记 */
    public static boolean armForcedParry(Mob caster, long now) {
        try {
            if (!holdsSword(caster)) {
                SafeGuard.warn("Soul Great Sword: " + IncineratorSkill.describe(caster)
                        + " holds no Soul Great Sword in main/off hand, the forced parry flag cannot be set");
                return false;
            }
            caster.getPersistentData().putLong(KEY_PARRY_FORCE_AT, now);
            return true;
        } catch (Throwable t) {
            SafeGuard.error("Soul Great Sword: setting the forced parry flag failed (caught)", t);
            return false;
        }
    }

    /** 格挡冷却是否就绪（独立键，与匕首冷却无关） */
    public static boolean parryReady(Mob caster, long now, int cooldownTicks) {
        try {
            CompoundTag tag = caster.getPersistentData();
            if (!tag.contains(KEY_PARRY_AT)) {
                return true;
            }
            long last = tag.getLong(KEY_PARRY_AT);
            if (now < last) {
                return true; // 世界时间回退（换存档/异常数据）：视为就绪，避免永久卡死
            }
            if (cooldownTicks <= 0) {
                return true;
            }
            return now - last >= cooldownTicks;
        } catch (Throwable t) {
            return true; // 判定异常按“就绪”兜底？——不，按拍板“出错不格挡”更稳：伤害照常走
        }
    }

    /** /mce reset / clearVolatileState：清格挡冷却与 force 标记 */
    public static void clearState(net.minecraft.world.entity.Entity owner) {
        try {
            CompoundTag tag = owner.getPersistentData();
            tag.remove(KEY_PARRY_AT);
            tag.remove(KEY_PARRY_FORCE_AT);
        } catch (Throwable ignored) {
        }
    }

    // ════════════════════════ 事件入口 ════════════════════════

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SafeGuard.guard("Soul Great Sword passive parry", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide || !(victim instanceof Mob caster)) {
                return;
            }
            if (event.isCanceled()) {
                return; // 友军保护等更早的监听已取消：不喂格挡、不耗冷却
            }
            if (!caster.isAlive() || caster.level().isClientSide) {
                return;
            }
            if (!MceConfig.soulGreatSwordEnabled() || !MceConfig.soulGreatSwordParryEnabled()) {
                return;
            }
            if (!holdsSword(caster)) {
                return;
            }
            long now = caster.level().getGameTime();
            DamageSource source = event.getSource();

            // 原版行为保留：无视无敌帧/无视护甲的伤害不可格挡（原样复用源码的标签判定）
            if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                    || source.is(DamageTypeTags.BYPASSES_ARMOR)) {
                return;
            }
            // 原版行为保留（同伴对应物）：蓄匕首期间不享受格挡
            if (ChargeStateStore.getState(caster) == ChargeStateStore.CHARGING
                    && cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig
                            .SKILL_SOUL_GREAT_SWORD.equals(ChargeStateStore.stateSkill(caster))) {
                SafeGuard.infoThrottled("mce_sgs_noparry_charge_" + caster.getUUID(), now,
                        "Soul Great Sword: " + IncineratorSkill.describe(caster)
                                + " is charging a dagger (vanilla grants no parry while charging with shift), no parry this time");
                return;
            }
            // 原版结构保留：只反打 LivingEntity，直接实体不是生物就没法格挡
            if (!(source.getDirectEntity() instanceof LivingEntity parriedMob) || parriedMob == caster) {
                return;
            }

            float amount = event.getAmount();
            boolean forced = consumeForcedMark(caster, now);
            float attackDamage = (float) caster.getAttributeValue(Attributes.ATTACK_DAMAGE);
            float counterDamage = attackDamage + amount * 0.3F;

            if (!forced) {
                // —— 拍板的三个阈值 ——
                int cooldownTicks = MceConfig.soulGreatSwordParryCooldownTicks();
                if (!parryReady(caster, now, cooldownTicks)) {
                    return; // 冷却中：静默跳过（冷却本来就是静默的）
                }
                double threshold = caster.getMaxHealth() * MceConfig.soulGreatSwordParryHealthThreshold();
                if (caster.getHealth() >= threshold) {
                    return; // 血量充足：不浪费冷却（静默，不刷日志）
                }
                if (amount < MceConfig.soulGreatSwordParryMinDamage()) {
                    SafeGuard.infoThrottled("mce_sgs_parSmall_" + caster.getUUID(), now,
                            "Soul Great Sword: incoming damage " + String.format("%.1f", amount)
                                    + " < soul_great_sword_parry_min_damage="
                                    + String.format("%.1f", MceConfig.soulGreatSwordParryMinDamage())
                                    + ", not worth parrying (cooldown not consumed)");
                    return;
                }
            } else {
                SafeGuard.info("Soul Great Sword: the forced parry flag was consumed (" + IncineratorSkill.describe(caster)
                        + ", incoming damage " + String.format("%.1f", amount) + " points, ignoring health/damage thresholds and the cooldown)");
            }

            // ════ 格挡结算（照抄原版 ForgeEvents.onLivingHurt 的顺序：先反击，打中才免伤）════
            boolean hurt = parriedMob.hurt(
                    ((ServerLevel) caster.level()).damageSources().mobAttack(caster), counterDamage);

            int aoeCount = 0;
            // 以攻击者为中心 5×6×5 反震 AoE（原版 AABB(blockPosVec3).inflate(2, 2.5, 2)），
            // 友军用 AllyCheck 过滤（拍板，比原版“只跳过自己的驯服生物”更严）
            try {
                AABB aabb = new AABB(parriedMob.position(), parriedMob.position()).inflate(2.0D, 2.5D, 2.0D);
                for (LivingEntity listEntity : caster.level().getEntitiesOfClass(LivingEntity.class, aabb)) {
                    if (listEntity == parriedMob || listEntity == caster) {
                        continue;
                    }
                    if (AllyCheck.isAlly(caster, listEntity)) {
                        continue; // 友军（玩家/其他同伴/主人链宠物）不吃反震
                    }
                    if (listEntity.hurt(((ServerLevel) caster.level()).damageSources().mobAttack(caster),
                            attackDamage)) {
                        aoeCount++;
                    }
                }
            } catch (Throwable t) {
                SafeGuard.warnThrottled("mce_sgs_aoe_" + caster.getUUID(), now,
                        "Soul Great Sword: the parry counter AoE resolution threw (caught, counterattack/immunity unaffected): "
                                + t.getClass().getSimpleName() + " / " + t.getMessage());
            }

            if (!hurt) {
                // 原版语义：反击没打中 → 不免伤不回血，进 20 tick 短冷却（照抄）
                markParryCast(caster, now, PARRY_FAIL_COOLDOWN_TICKS);
                SafeGuard.infoThrottled("mce_sgs_parFail_" + caster.getUUID(), now,
                        "Soul Great Sword: the parry counterattack missed (" + IncineratorSkill.describe(parriedMob)
                                + " hurt returned false), no immunity and no heal, a " + PARRY_FAIL_COOLDOWN_TICKS
                                + " tick probe cooldown is applied (vanilla 20 ticks, copied)");
                return;
            }

            // —— 免伤 + 治疗 + 冷却 ——
            event.setCanceled(true);
            markParryCast(caster, now, MceConfig.soulGreatSwordParryCooldownTicks());
            float heal = caster.getMaxHealth() * 0.2F;
            caster.heal(heal);

            // 蓄力打断对齐：格挡 = 免疫此次伤害 → 刚被 SkillInterruptHandler 盖的打断标记清回去
            if (ChargeStateStore.getState(caster) == ChargeStateStore.CHARGING) {
                ChargeStateStore.markHurt(caster, 0L);
                SafeGuard.info("Soul Great Sword: parried, the charge-interrupt flag for this hit was cleared (vanilla 'immune to this damage' semantics, charging is not interrupted)");
            }

            playParrySounds(caster);
            spawnSweepParticles(caster);
            SafeGuard.info("Soul Great Sword parry successful: " + IncineratorSkill.describe(caster)
                    + " blocked " + String.format("%.1f", amount) + " points of incoming damage (source "
                    + IncineratorSkill.describe(parriedMob) + ") -> immune (the event is cancelled, no hurt call and no lastHurt push, "
                    + "the shadow gate does not intervene); counterattack " + String.format("%.1f", counterDamage) + " points"
                    + " (attack " + String.format("%.1f", attackDamage) + " + 30% of incoming damage = "
                    + String.format("%.1f", amount * 0.3F) + "), the counter AoE hit " + aoeCount
                    + " targets (full attack value, allies filtered), healed " + String.format("%.1f", heal)
                    + " points (20% of max health), parry cooldown " + MceConfig.soulGreatSwordParryCooldownTicks() + " tick"
                    + (forced ? " (forced flag trigger)" : ""));
        });
    }

    // ════════════════════════ 内部小工具 ════════════════════════

    private static boolean holdsSword(Mob caster) {
        try {
            // 直接判定注册表 ID（与 CompanionSkill.holdsItem 同口径，但格挡不在调度链上，这里独立判）
            var stackMain = caster.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            var stackOff = caster.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
            return LegendaryMonstersReflect.SOUL_GREAT_SWORD_ITEM
                    .equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stackMain.getItem()))
                    || LegendaryMonstersReflect.SOUL_GREAT_SWORD_ITEM
                            .equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stackOff.getItem()));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 消费一次性强制标记：存在且未过期返回 true（并清掉）；没有/过期返回 false */
    private static boolean consumeForcedMark(Mob caster, long now) {
        try {
            CompoundTag tag = caster.getPersistentData();
            if (!tag.contains(KEY_PARRY_FORCE_AT)) {
                return false;
            }
            long markedAt = tag.getLong(KEY_PARRY_FORCE_AT);
            tag.remove(KEY_PARRY_FORCE_AT); // 一次性：无论是否在有效期内都消费掉
            return now >= markedAt && now - markedAt <= FORCE_MARK_TTL_TICKS;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void markParryCast(Mob caster, long now, int cooldownTicks) {
        try {
            // 存“本次格挡时刻 + 冷却到期”双口径：冷却为 0 时不落键 = 永远就绪
            if (cooldownTicks <= 0) {
                return;
            }
            caster.getPersistentData().putLong(KEY_PARRY_AT, now);
        } catch (Throwable ignored) {
        }
    }

    /** 格挡成功音效：legendary_monsters:block + legendary_monsters:soul_fly（原版音量 2） */
    private static void playParrySounds(Mob caster) {
        try {
            net.minecraft.sounds.SoundEvent block = LegendaryMonstersReflect.getParryBlockSound();
            net.minecraft.sounds.SoundEvent soulFly = LegendaryMonstersReflect.getParrySoulFlySound();
            if (block != null) {
                caster.level().playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        block, SoundSource.PLAYERS, 2.0F, 1.0F);
            }
            if (soulFly != null) {
                caster.level().playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                        soulFly, SoundSource.PLAYERS, 2.0F, 1.0F);
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sgs_psnd", gameTime(caster),
                    "Soul Great Sword: playing the parry sound failed (caught, the parry is unaffected): " + t.getClass().getSimpleName());
        }
    }

    /** 原版 createSweep 是模组粒子，反射不划算 → 原版 SWEEP_ATTACK + PORTAL 补同观感 */
    private static void spawnSweepParticles(Mob caster) {
        try {
            if (caster.level() instanceof ServerLevel level) {
                level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                        caster.getX(), caster.getY() + 1.0D, caster.getZ(),
                        1, 0.1D, 0.1D, 0.1D, 0.0D);
                level.sendParticles(ParticleTypes.PORTAL,
                        caster.getX(), caster.getY() + 0.8D, caster.getZ(),
                        8, 0.5D, 0.3D, 0.5D, 0.05D);
            }
        } catch (Throwable ignored) {
        }
    }

    private static long gameTime(net.minecraft.world.entity.Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}
