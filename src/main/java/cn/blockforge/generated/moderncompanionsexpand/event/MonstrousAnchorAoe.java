package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.LegendaryMonstersReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.MonstrousAnchorSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 怪怖之锚能力 2（攻击附带 AOE）的事件层实现（r33）。
 *
 * 原版逻辑（MonstrousAnchorItem.hurtEnemy）：玩家近战命中后，以【被击中目标】为中心
 * 3 格 AABB 结算 6 点 PLAYER_ATTACK 伤害；条件 = 攻击强度满（getAttackStrengthScale ≥ 1，
 * 同伴近战永远满额，等价省略）+ fallDistance == 0（空中不触发）+ attacker instanceof Player
 * （同伴版改为“同伴近战”，即伤害源 directEntity = 同伴本人、类型 = 原版 mob_attack）。
 *
 * 触发点选 LivingDamageEvent.Post（结算层）：只有真正落地的伤害才触发 AOE——
 * 被 AllyDamageGuard 取消的友军误伤不会喂到这里（与原版“hurt 成功才走 hurtEnemy”同语义）。
 *
 * 两条硬保护：
 * - 节流：距上次触发 ≥ monstrous_anchor_aoe_min_interval_ticks（拍板默认 10）才放行——
 *   一刀多目标（每次命中各触发一次事件）不再重复结算，存 persistentData；
 * - 重入闸：AOE 自己的 hurt() 也会再次触发本事件（PLAYER_ATTACK 类型、directEntity 仍是
 *   同伴），内存 busy 集合挡住递归（同一服务端 tick 内同 companion 只进一次）。
 *
 * 友军保护：AOE 逐个 AllyCheck 过滤（见 MonstrousAnchorSkill.performAoe）+
 * AllyDamageGuard 怪怖之锚窗口（100 tick）事件层兜底。
 *
 * r37 排查（用户反馈“AOE 只对另一个目标结算了伤害”）：结算本体遍历的是
 * AABB 内【全部】实体（getEntitiesOfClass，无数量上限、无提前 break），
 * 逐目标明细（距离 + 被过滤原因 + hurt() 是否被原版无敌帧挡下 + 结算金额）
 * 改在 performAoe 内统一打印，事件层只保留一条触发摘要。
 */
public final class MonstrousAnchorAoe {

    private MonstrousAnchorAoe() {}

    /** 重入闸：正在结算 AOE 的同伴 UUID（同一 tick 内防递归） */
    private static final Set<UUID> BUSY = ConcurrentHashMap.newKeySet();

    public static void onDamagePost(LivingDamageEvent.Post event) {
        SafeGuard.guard("Monstrous Anchor AOE", () -> {
            try {
                if (event.getEntity().level().isClientSide) {
                    return;
                }
                if (!(event.getSource().getDirectEntity() instanceof Mob caster)) {
                    return; // 只关心“同伴本人近战”（原版绑 Player，同伴版换成 Mob 直击）
                }
                if (!MceConfig.monstrousAnchorEnabled()) {
                    return;
                }
                if (!event.getSource().is(DamageTypes.MOB_ATTACK)
                        || event.getSource().getEntity() != caster) {
                    return; // 非普攻直击（其他技能的魔法/投射物伤害不触发 AOE）
                }
                if (caster.fallDistance != 0.0F) {
                    return; // 原版 fallDistance == 0 照抄：空中挥砍不触发
                }
                EquipmentSlot slot = MonstrousAnchorSkill.INSTANCE.heldSlot(caster);
                if (slot == null) {
                    return; // 手上没拿怪怖之锚
                }
                LivingEntity victim = event.getEntity();
                if (victim == caster || !victim.isAlive()) {
                    return;
                }
                long now = caster.level().getGameTime();
                if (!MonstrousAnchorSkill.aoeIntervalReady(caster, now)) {
                    SafeGuard.infoThrottled("mce_ma_throttle_" + caster.getUUID(), now,
                            "Monstrous Anchor AOE: rate-limited (monstrous_anchor_aoe_min_interval_ticks="
                                    + MceConfig.monstrousAnchorAoeMinIntervalTicks()
                                    + "), this normal-attack hit does not retrigger it (prevents multi-target per-swing spam)");
                    return;
                }
                if (!BUSY.add(caster.getUUID())) {
                    return; // 重入：AOE 自身的伤害事件不再触发 AOE
                }
                try {
                    MonstrousAnchorSkill.markAoeLast(caster, now);
                    if (!(caster.level() instanceof ServerLevel level)) {
                        return;
                    }
                    int hits = MonstrousAnchorSkill.performAoe(level, caster, victim, now);
                    MonstrousAnchorSkill.markGuardWindow(caster, now + MonstrousAnchorSkill.GUARD_BASE_TICKS);
                    SafeGuard.info("Monstrous Anchor AOE: " + IncineratorSkill.describe(caster)
                            + " normal attack hit " + IncineratorSkill.describe(victim)
                            + ", centred on the target within " + MceConfig.monstrousAnchorAoeRadius() + " blocks AABB resolution "
                            + String.format("%.1f", MceConfig.monstrousAnchorAoeDamage())
                            + " points of PLAYER_ATTACK damage, hit " + hits + " enemies"
                            + (hits > 0 ? "" : " (no other enemy in range, the throttle was still recorded)"));
                } finally {
                    BUSY.remove(caster.getUUID());
                }
            } catch (Throwable t) {
                SafeGuard.warnThrottled("mce_ma_aoe_evt", System.currentTimeMillis() / 50L,
                        "Monstrous Anchor AOE: the event handler threw (caught, this damage is unaffected): "
                                + t.getClass().getSimpleName() + " / " + t.getMessage());
            }
        });
    }
}
