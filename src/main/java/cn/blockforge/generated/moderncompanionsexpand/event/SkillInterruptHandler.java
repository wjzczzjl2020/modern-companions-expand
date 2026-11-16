package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.ChargeStateStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.CompanionSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillRegistry;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 蓄力被打断的“受伤入口”：任何生物在蓄力状态（CHARGING）中被伤害时，
 * 先把伤害累计到 mce_charge_damage_total；累计值达到配置阈值后，才盖
 * mce_hurt_at 时间戳。调度器在同一个 tick 的下一个 CHARGING 阶段比对时间戳
 * → 打断、不消耗冷却、进入 40 tick 重试罚时。
 *
 * 当前只对炎葬启用累计伤害阈值。火焰壁垒没有 CHARGING 阶段，且本文件不触碰
 * ACTIVE 冲锋窗口；其他未来蓄力技能暂时沿用“单次受击即打断”的保守行为。
 *
 * 说明：NeoForge 1.21.1 已把 LivingHurtEvent 改名为 LivingIncomingDamageEvent
 * （在伤害真正生效前触发，拿得到受害者），这里按新名挂载。
 * 只累计、不直接清状态，是为了把状态机判定集中到 SkillTickHandler，
 * 顺带天然支持“读档后该断就断”（累计值和中断时间戳均持久化，条件逐 tick 重算）。
 */
public final class SkillInterruptHandler {

    private SkillInterruptHandler() {}

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SafeGuard.guard("charge accumulated damage marker", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            if (!(victim instanceof Mob mob)) {
                return;
            }
            if (ChargeStateStore.getState(mob) != ChargeStateStore.CHARGING) {
                return;
            }

            long now = victim.level().getGameTime();
            String stateSkill = ChargeStateStore.stateSkill(mob);
            // r23 钩子：interruptOnDamage()=false 的技能（绞肉锯）受击不打断——
            // 原版玩家按住右键绞肉时被打也不会停，同伴版对齐
            CompanionSkill skill = SkillRegistry.byId(stateSkill);
            if (skill != null && !skill.interruptOnDamage()) {
                SafeGuard.infoThrottled("mce_nodmgint_" + mob.getUUID(), now,
                        "shredder in progress: " + IncineratorSkill.describe(mob) + " took "
                                + format(event.getAmount()) + " points of damage, this skill is not interrupted by hits, shredding continues");
                return;
            }
            if (!SkillRegistry.INCINERATOR_ID.equals(stateSkill)) {
                // 非炎葬的蓄力（目前不存在）保留旧的保守规则，避免影响未来技能/壁垒。
                ChargeStateStore.markHurt(mob, now);
                return;
            }

            double threshold = MceConfig.incineratorInterruptDamage();
            double previous = ChargeStateStore.damageTotal(mob);
            double total = ChargeStateStore.addDamage(mob, event.getAmount());
            if (total < threshold) {
                SafeGuard.infoThrottled("mce_charge_damage_" + mob.getUUID(), now,
                        "Incinerator charge: accumulated damage " + format(total) + "/" + format(threshold)
                                + ", below the interrupt threshold, charging continues (" + IncineratorSkill.describe(mob) + ")");
                return;
            }

            // 阈值配成 0 = 一次受击就打断（回到旧行为）；否则只在“这次伤害跨过阈值”时盖标记
            boolean crossed = total >= threshold && (previous < threshold || threshold <= 0.0D);
            if (crossed) {
                ChargeStateStore.markHurt(mob, now);
                SafeGuard.info("Incinerator charge: accumulated damage " + format(total) + " reached the interrupt threshold "
                        + format(threshold) + " (" + IncineratorSkill.describe(mob) + "), the next state machine tick will interrupt");
            }
        });
    }

    private static String format(double value) {
        return String.format("%.1f", value);
    }
}
