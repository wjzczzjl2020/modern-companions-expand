package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.CataclysmReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MeatShredderSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.LastHurtAccess;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * r26 影子无敌帧闸门（拍板“影子无敌帧”方案，r25 错峰保留当双保险）。
 *
 * 问题：绞肉锯的 1 点伤害挂 bypasses_cooldown，每次都走 hurt() 的免检分支，无条件把目标写成
 * lastHurt≈1 / invulnerableTime=20——“这次伤比上次疼就放行”的原版限速被整个打穿，
 * 队友（断魂 12 点实测生效 10.8、炎葬火柱等）和蹭伤害的玩家都近乎全额生效。
 *
 * 为什么叫“影子”：1.21.1 的 hurt() 里所有伤害事件都在字段赋值【之前】触发，
 * “结算后立刻恢复”做不到（恢复完两行代码后就被 hurt() 自己覆盖，r26 评估文档第一节）。
 * 所以改为在心里维护一份“假如没有绞肉污染，目标的无敌帧/lastHurt 本该是多少”的影子账本，
 * 在每次【非绞肉伤害】进 hurt() 的入口（LivingIncomingDamageEvent，原版无敌帧检查之前）
 * 把实际字段临时修正回影子值对应的原版语义，数学上等价于“目标从未被绞肉污染”：
 *
 *   simInvuln = 影子窗口剩余无敌帧（= 基数 − (now − 窗口起点)，绞肉命中不计入）
 *   ① simInvuln > 10 且 amount ≤ 影子限速   → 取消事件（= 原版无敌帧整笔挡成 0）      【被拦】
 *   ② simInvuln > 10 且 amount > 影子限速   → setAmount(amount − 影子限速 + lastHurt现值)，
 *      原版走“比上次疼”分支，落地 = 补偿后 − lastHurt现值 = amount − 影子限速，
 *      与干净原版完全一致                                                              【被补偿】
 *   ③ simInvuln ≤ 10                        → 写 invulnerableTime = 0（public 字段），
 *      原版走正常全额分支并开新窗                                                       【被放行】
 *   ④ bypasses_cooldown 的非绞肉来源        → 原样放行（原版本来就走免检分支）          【被放行】
 *
 * 影子账本只在「目标正在被绞肉（MeatShredderSkill 每 tick 刷新登记）」时存在，收锯即“落回”
 * 实际字段并整体作废——影子闸门只拦绞肉期间的伤害，绞肉结束后立刻失效，不影响平时战斗。
 *
 * 影子状态存在哪（拍板问询）：存在本类的【服务端内存表】（目标 UUID → 影子账本），
 * 不存目标实体的 persistentData、不写存档。理由：
 * - 它是纯瞬时战斗记账（每 tick 随绞肉刷新、随收锯作废），持久化只会带来“读档后影子值
 *   和实际字段对不上”的新错乱面；
 * - 读档零残留：使用中的物品状态存档不带（r23 已知），读档后 tickCharge 会先走
 *   onShredTick 重新登记——登记那一刻对实际字段拍快照（invulnerableTime + lastHurt），
 *   影子账本从干净现场重建，不存在“旧影子值跨档复活”；
 * - 落回/过期兜底都以内存表为准，表没了 = 闸门不存在 = 完全回到原版行为。
 *
 * 落账（开新窗）只在 LivingDamageEvent.Post 确认这笔伤害真的落地后才记——
 * 被 AllyDamageGuard（友军保护）或其它监听取消的伤害不算，影子账本不被取消的空枪污染。
 * 本监听注册在 AllyDamageGuard 之后：友军保护先裁，影子闸门对已取消的伤害一律不碰。
 *
 * 兜底（拍板）：全部 try-catch + SafeGuard；任何一步判定出错（lastHurt 反射失败等）
 * 一律按“放行”处理——最坏退化 = 回到 r25 现状，不会比 r25 更糟，也绝不更伤。
 * 开关：meat_shredder_iframe_restore（默认 true；false = 整体停用，回到 r25 行为）。
 */
public final class ShadowInvulnGate {

    /** 登记新鲜度（tick）：绞肉者每 tick 刷新，超过即视为绞肉已结束（与 ShredAoeDeferral 同口径） */
    private static final int SHRED_STALE_GRACE_TICKS = 5;

    /** 原版无敌帧总长 / 限速闸门阈值（hurt()：invulnerableTime > 10 才查 lastHurt） */
    private static final int INVULN_WINDOW = 20;
    private static final int INVULN_GATE_THRESHOLD = 10;

    /** 过期登记的兜底清理阈值（tick）：正常收锯会显式移除，这里只防极端残留 */
    private static final long PURGE_AFTER_TICKS = 1200L;

    /** 落账确认的有效期（tick）：Incoming 落笔到 Post 确认必然同一 tick，超过视为空枪弃置 */
    private static final long PENDING_TTL_TICKS = 2L;

    /** 影子账本：目标 UUID → 影子记录（仅内存，不持久化，见类注释） */
    private static final ConcurrentHashMap<UUID, Shadow> SHADOWS = new ConcurrentHashMap<>();

    /** 落账暂存：目标 UUID → 本笔伤害的原始金额（Post 确认落地后写回影子账本） */
    private static final ConcurrentHashMap<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    /** 影子记录 */
    private static final class Shadow {
        final LivingEntity target;
        /** 最近一次绞肉 tick（新鲜度判定） */
        volatile long lastShredTick;
        /** 影子窗口的无敌帧基数：绞肉开始时拍快照；期间每次真实落地重置为 20（原版开新窗） */
        volatile int baseInvuln;
        /** 影子窗口起点 tick */
        volatile long windowStartTick;
        /** 影子限速（= 影子 lastHurt）：最近一次真实落地伤害的原始金额 */
        volatile float lockoutDamage;
        /** 影子限速是否可信（false = 整场绞肉没有任何真实落地，用快照兜底） */
        volatile boolean lockoutKnown;
        /** 绞肉开始时的实际 lastHurt 快照（落回兜底） */
        final float snapshotLastHurt;

        Shadow(LivingEntity target, long now) {
            this.target = target;
            this.lastShredTick = now;
            this.windowStartTick = now;
            this.baseInvuln = target.invulnerableTime;
            float lastHurt = LastHurtAccess.get(target);
            this.snapshotLastHurt = Float.isNaN(lastHurt) ? 0.0F : lastHurt;
            this.lockoutDamage = this.snapshotLastHurt;
            this.lockoutKnown = !Float.isNaN(lastHurt);
        }
    }

    private record Pending(float amount, long stageTick) {}

    private ShadowInvulnGate() {}

    // ══════════════════════ 登记（MeatShredderSkill 进入/每 tick 维持时调用） ══════════════════════

    /**
     * 登记/刷新一次“目标正在被绞肉”（每 tick 调用，幂等且廉价）。
     * 首次登记时对实际无敌帧/lastHurt 拍快照——影子账本从这一刻的干净现场起算。
     */
    public static void onShredTick(LivingEntity target, long now) {
        try {
            if (!MceConfig.meatShredderIframeRestore()) {
                return; // 开关关闭：完全不建影子账本，行为 = r25
            }
            if (target == null || !target.isAlive() || target.level().isClientSide) {
                return;
            }
            UUID targetId = target.getUUID();
            Shadow shadow = SHADOWS.get(targetId);
            if (shadow == null) {
                shadow = new Shadow(target, now);
                SHADOWS.put(targetId, shadow);
                SafeGuard.info("shadow invulnerability: target " + IncineratorSkill.describe(target)
                        + " started being shredded, the shadow ledger was created (snapshot invulnerableTime=" + shadow.baseInvuln
                        + ", lastHurt=" + fmt(shadow.snapshotLastHurt)
                        + "; during shredding, non-shredder damage is corrected by the shadow invulnerability, and it settles back to the real field when the shredder stops)");
            }
            shadow.lastShredTick = now;
            purgeStale(now);
        } catch (Throwable t) {
            SafeGuard.error("registering the shadow invulnerability failed (the shredder itself is unaffected)", t);
        }
    }

    // ══════════════════════ 闸门（LivingIncomingDamageEvent，注册在 AllyDamageGuard 之后） ══════════════════════

    /** 非绞肉伤害的入口修正：三判一放，见类注释的 ①②③④ */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SafeGuard.guard("shadow invulnerability gate", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide || !victim.isAlive()) {
                return;
            }
            DamageSource source = event.getSource();
            if (CataclysmReflect.isShredderDamage(source)) {
                return; // 绞肉本体照旧：1 点/tick、绕无敌帧的特色一根毫毛都不动（DPS 不受影响）
            }
            Shadow shadow = SHADOWS.get(victim.getUUID());
            if (shadow == null) {
                return; // 不在被绞肉：零成本直通，平时战斗完全不受影响
            }
            if (event.isCanceled()) {
                return; // 已被友军保护等监听取消：不碰、不留账
            }
            long now = victim.level().getGameTime();
            if (now - shadow.lastShredTick > SHRED_STALE_GRACE_TICKS) {
                // 绞肉已停但没走正常收锯（绞肉者死亡/移除/维度切换等异常退出）：
                // 先落回实际字段再放行本笔——这一笔之后就是原版语义
                settleBack(shadow, victim, now, "shredder registration expired (the shredder exited abnormally, no normal stop)");
                return;
            }
            if (!MceConfig.meatShredderIframeRestore()) {
                return; // 运行中被关掉：存量影子不再生效，下一次收锯/过期清理后完全回到 r25
            }

            float amount = event.getAmount();
            String attacker = safeAttacker(source);

            // ④ bypasses_cooldown 的非绞肉来源（原版虚空伤害等）：原版本来就走免检分支，
            //    干净世界里也是全额生效——放行不修正，Post 落地后照常开新窗
            if (source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
                PENDING.put(victim.getUUID(), new Pending(amount, now));
                SafeGuard.info("shadow invulnerability [released - exempt source]: target " + IncineratorSkill.describe(victim)
                        + " took " + fmt(amount) + " points (source " + attacker
                        + ", the vanilla bypasses_cooldown branch, a clean vanilla would apply it in full as well), "
                        + "after landing the shadow opens a new window (shadow rate -> " + fmt(amount) + ")");
                return;
            }

            int simInvuln = shadow.baseInvuln - (int) Math.max(0L, now - shadow.windowStartTick);

            // ① 影子无敌帧还高、伤害不比影子限速疼 → 整笔挡成 0（= 干净原版的无敌帧拦截）
            if (simInvuln > INVULN_GATE_THRESHOLD && amount <= shadow.lockoutDamage) {
                event.setCanceled(true);
                SafeGuard.info("shadow invulnerability [blocked]: target " + IncineratorSkill.describe(victim)
                        + " of " + fmt(amount) + " points of damage (source " + attacker + ") blocked the whole amount to 0 -- "
                        + "shadow invulnerability has " + simInvuln + " ticks (>10) left, the amount is <= the shadow rate "
                        + fmt(shadow.lockoutDamage)
                        + ", so vanilla invulnerability semantics alone would have blocked it; without the shadow gate this hit would nearly fully apply because the shredder pushed "
                        + " lastHurt down to 1 (the damage spike path confirmed in r24)");
                return;
            }

            // ② 影子无敌帧还高、但这次更疼 → 补偿：落地 = amount − 影子限速，与干净原版一致
            if (simInvuln > INVULN_GATE_THRESHOLD) {
                float lastHurtCur = LastHurtAccess.get(victim);
                if (Float.isNaN(lastHurtCur)) {
                    SafeGuard.warn("shadow invulnerability [released - fallback]: target " + IncineratorSkill.describe(victim)
                            + " of " + fmt(amount) + " points of damage (source " + attacker
                            + ") should have been compensated to " + fmt(amount - shadow.lockoutDamage)
                            + " points, but reading lastHurt by reflection failed -- released as-is per the agreed fallback"
                            + " (this hit may be amplified by shredder-polluted invulnerability, a known r25 situation, game stability unaffected)");
                    return;
                }
                float adjusted = amount - shadow.lockoutDamage + lastHurtCur;
                event.setAmount(adjusted);
                PENDING.put(victim.getUUID(), new Pending(amount, now));
                SafeGuard.info("shadow invulnerability [compensated]: target " + IncineratorSkill.describe(victim)
                        + " damage " + fmt(amount) + " → " + fmt(adjusted) + " points (source " + attacker
                        + "; = original " + fmt(amount) + " - shadow rate " + fmt(shadow.lockoutDamage)
                        + " + the target's current lastHurt " + fmt(lastHurtCur) + "), the shadow invulnerability has "
                        + simInvuln + " ticks (>10) left so the vanilla 'hurts more than last time' branch runs, the amount actually applied should be "
                        + fmt(amount - shadow.lockoutDamage)
                        + " points, matching a clean vanilla with no shredder pollution");
                return;
            }

            // ③ 影子无敌帧已 ≤10 → 放行开新窗：实际字段被绞肉钉在 20，归 0 让原版走正常全额分支
            int oldInvuln = victim.invulnerableTime;
            victim.invulnerableTime = 0;
            PENDING.put(victim.getUUID(), new Pending(amount, now));
            SafeGuard.info("shadow invulnerability [released - new window]: target " + IncineratorSkill.describe(victim)
                    + " took " + fmt(amount) + " points (source " + attacker + "), the shadow invulnerability decayed naturally to "
                    + simInvuln + " ticks (<=10), the target's real invulnerableTime (" + oldInvuln
                    + ", pinned at 20 by the shredder) was set to 0 so vanilla opens a fresh full window; after landing the shadow rate becomes " + fmt(amount));
        });
    }

    // ══════════════════════ 落账确认（LivingDamageEvent.Post：这笔真的落地了） ══════════════════════

    /**
     * Post 只做两件事：①把落地确认写回影子账本（开新窗）；②r26 DPS 实测记账
     * （绞肉伤害落地额滚动记录最近 200 tick，/mce meat_shredder status 可查）。
     */
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        SafeGuard.guard("shadow invulnerability ledger settle", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            DamageSource source = event.getSource();
            long now = victim.level().getGameTime();

            // —— DPS 实测记账（与影子开关无关：开关关掉也要能验证绞肉 DPS 没被削弱）——
            if (CataclysmReflect.isShredderDamage(source)
                    && source.getDirectEntity() instanceof Mob shredder) {
                MeatShredderSkill.recordShredApplied(shredder, event.getNewDamage(), now);
            }

            if (!MceConfig.meatShredderIframeRestore()) {
                return;
            }
            Pending pending = PENDING.remove(victim.getUUID());
            if (pending == null) {
                return;
            }
            Shadow shadow = SHADOWS.get(victim.getUUID());
            if (shadow == null) {
                return; // 落账瞬间绞肉已结束（同 tick 收锯）：影子已落回，无需再记
            }
            if (now - pending.stageTick() > PENDING_TTL_TICKS) {
                SafeGuard.info("shadow invulnerability [ledger dropped]: a staged hit for target " + IncineratorSkill.describe(victim)
                        + " was confirmed outdated (" + (now - pending.stageTick())
                        + " tick > " + PENDING_TTL_TICKS + " apart, most likely a swing cancelled mid-way), not written to the shadow ledger");
                return;
            }
            float oldLockout = shadow.lockoutDamage;
            shadow.windowStartTick = now;
            shadow.baseInvuln = INVULN_WINDOW;
            shadow.lockoutDamage = pending.amount();
            shadow.lockoutKnown = true;
            SafeGuard.info("shadow invulnerability [ledger]: " + IncineratorSkill.describe(victim)
                    + " of " + fmt(pending.amount()) + " points of damage confirmed landed -- the shadow window was reset"
                    + " (invulnerability base 20, start = now, shadow rate " + fmt(oldLockout) + " → "
                    + fmt(pending.amount()) + "). From now on, other damage during shredding is corrected per this shadow ledger");
        });
    }

    // ══════════════════════ 落回（绞肉结束） ══════════════════════

    /**
     * 绞肉结束的“影子落回”（ShredAoeDeferral.clearShred 在所有退出路径上统一调用）：
     * 把影子值写回实际字段——invulnerableTime = 影子剩余、lastHurt = 影子限速——
     * 堵住“绞肉刚停手、实际字段还是被绞肉钉住的 20/1”的最后一个窗口（r25 漏掉的那一半）。
     *
     * @param rawTargetUuid 目标 uuid（收锯路径从状态机读出的原始串；非法按无登记处理）
     * @param now 落回时刻的 gameTime（无来源时传 -1，用目标所在维度现算）
     */
    public static void onShredEnd(String rawTargetUuid, long now) {
        try {
            if (rawTargetUuid == null || rawTargetUuid.isEmpty()) {
                return;
            }
            UUID targetId;
            try {
                targetId = UUID.fromString(rawTargetUuid);
            } catch (IllegalArgumentException bad) {
                return; // 非法 uuid：按无影子登记处理
            }
            Shadow shadow = SHADOWS.remove(targetId);
            if (shadow == null) {
                return; // 没建过影子账本（绞肉期间没打中过任何东西也可能）——无事可落
            }
            LivingEntity victim = shadow.target;
            if (victim == null || !victim.isAlive() || victim.level().isClientSide) {
                return; // 目标已死/无效：字段随实体消亡，无需落回
            }
            long settleNow = now >= 0 ? now : victim.level().getGameTime();
            settleBack(shadow, victim, settleNow, "shredding ended normally (stopped / interrupted exit)");
        } catch (Throwable t) {
            SafeGuard.error("restoring the shadow invulnerability failed (the exit flow is unaffected; worst case = the real field stays high for 10 more ticks)", t);
        }
    }

    /** 落回动作：影子值写回实际字段 + 打明细日志（正常收锯 / 过期兜底共用） */
    private static void settleBack(Shadow shadow, LivingEntity victim, long now, String reason) {
        try {
            SHADOWS.remove(victim.getUUID());
            PENDING.remove(victim.getUUID());
            int simInvuln = shadow.baseInvuln - (int) Math.max(0L, now - shadow.windowStartTick);
            if (simInvuln < 0) {
                simInvuln = 0;
            }
            int oldInvuln = victim.invulnerableTime;
            float oldLastHurt = LastHurtAccess.get(victim);
            float restoreLastHurt = shadow.lockoutKnown ? shadow.lockoutDamage : shadow.snapshotLastHurt;
            victim.invulnerableTime = simInvuln;
            boolean lastHurtOk = Float.isNaN(restoreLastHurt) || LastHurtAccess.set(victim, restoreLastHurt);
            SafeGuard.info("shadow invulnerability [restored]: " + reason + " -- target " + IncineratorSkill.describe(victim)
                    + " real field put back: invulnerableTime " + oldInvuln + " → " + simInvuln
                    + ", lastHurt " + (Float.isNaN(oldLastHurt) ? "read failed" : fmt(oldLastHurt)) + " → "
                    + (Float.isNaN(restoreLastHurt) ? "not written (reflection failed)" : fmt(restoreLastHurt))
                    + (lastHurtOk ? "" : " (writing lastHurt failed, the original value was kept)")
                    + ". The shadow ledger was destroyed, all combat on this target returns to normal vanilla semantics afterwards");
        } catch (Throwable t) {
            SafeGuard.error("restoring the shadow invulnerability threw (released per the fallback)", t);
        }
    }

    // ══════════════════════ 查询 / 清理 ══════════════════════

    /** 目标此刻是否有影子账本在生效（绞肉中 + 新鲜度内；/mce 状态自检用） */
    public static boolean shadowActive(LivingEntity target, long now) {
        try {
            if (target == null) {
                return false;
            }
            Shadow shadow = SHADOWS.get(target.getUUID());
            return shadow != null && now - shadow.lastShredTick <= SHRED_STALE_GRACE_TICKS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** /mce reset 等清理路径：影子账本与落账暂存一并清掉（不落回——reset 后按原版语义自然过期） */
    public static void clearAll(LivingEntity target) {
        try {
            if (target != null) {
                SHADOWS.remove(target.getUUID());
                PENDING.remove(target.getUUID());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 兜底清理：过期影子（>1200 tick 未刷新）静默移除；目标已死的登记一并清 */
    private static void purgeStale(long now) {
        try {
            for (Map.Entry<UUID, Shadow> e : SHADOWS.entrySet()) {
                Shadow shadow = e.getValue();
                boolean stale = now - shadow.lastShredTick > PURGE_AFTER_TICKS;
                LivingEntity holder = shadow.target;
                boolean dead = holder == null || !holder.isAlive();
                if (stale || dead) {
                    if (stale && !dead) {
                        settleBack(shadow, holder, now, "shadow ledger timeout fallback cleanup");
                    } else {
                        SHADOWS.remove(e.getKey());
                        PENDING.remove(e.getKey());
                    }
                }
            }
            for (Map.Entry<UUID, Pending> e : PENDING.entrySet()) {
                if (now - e.getValue().stageTick() > PENDING_TTL_TICKS) {
                    PENDING.remove(e.getKey());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String safeAttacker(DamageSource source) {
        try {
            if (source.getEntity() == null) {
                return "none (environmental damage)";
            }
            return source.getEntity() instanceof LivingEntity living
                    ? IncineratorSkill.describe(living)
                    : source.getEntity().getClass().getSimpleName() + "#id" + source.getEntity().getId();
        } catch (Throwable t) {
            return "resolve failed (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String fmt(float value) {
        return String.format("%.1f", value);
    }
}
