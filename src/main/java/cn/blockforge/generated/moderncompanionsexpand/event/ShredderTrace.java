package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.CataclysmReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MeatShredderSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.CompanionDetector;
import cn.blockforge.generated.moderncompanionsexpand.util.LastHurtAccess;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * r24 排查工具（只记日志、不改任何逻辑）：绞肉锯 × 断魂战戟 同打一个目标时
 * “绞肉伤害暴涨到每 tick 十几点”的多路追踪。对应排查方向的五个问题：
 *
 * 1. onUseTick 是否被多次调用 ——
 *    挂 LivingEntityUseItemEvent.Tick。这个事件在原版
 *    LivingEntity.updateUsingItem → EventHooks.onItemUseTick → stack.onUseTick(...)
 *    的调用点上触发（已用 1.21.1 neoforge-21.1.244 字节码核实，onUseTick 全游戏只有这一个调用点），
 *    所以它和真实 onUseTick 调用一一对应。同一实体同一 game tick 内出现第 2 次及以上 → 立即 WARN；
 *    正常单次调用每 20 tick 打一条校准日志（实体 UUID + 物品 + tick + “预计命中谁”）。
 *    “预计命中”按灾变 onUseTick 的原版算法复刻（眼睛射线 + 搜索盒），能看出同盒里的另一个同伴。
 *
 * 2. 绞肉窗口是否被共享 ——
 *    窗口判定在 AllyDamageGuard 里逐条打“窗口 owner UUID + 实际攻击者 UUID”
 *    （见 AllyDamageGuard 的新增段）。结构上窗口读的是每个施法者自己的
 *    persistentData（mce_state / mce_state_skill），逐实体独立，不存在全局表；
 *    日志负责实证这一点。
 *
 * 3+5. 伤害暴涨的账本 ——
 *    挂两条伤害事件：
 *    - LivingIncomingDamageEvent（hurt() 调用层，能在无敌帧拦截前看到“谁想打谁、多少点、
 *      目标当时无敌帧/lastHurt 是多少”）→ 回答“每次 hurt() 调用的伤害值和来源”；
 *    - LivingDamageEvent.Post（结算层）→ 实际生效的最终伤害。
 *    每 tick 由 ServerTickEvent.Post 汇总成台账一行（目标 + 每笔来源 + 金额 + 无敌帧现场）。
 *    台账里“调用有、生效无”的差额 = 被友军保护/无敌帧挡掉的部分，一眼可读。
 *    另有“闸门失效预警”：目标刚被绞肉命中过、无敌帧仍处高值，但 lastHurt 被绞肉压回 1 点，
 *    这时任何 > lastHurt 的非绞肉伤害按原版规则都会近乎全额生效——这正是
 *    “绞肉每 tick 重置无敌帧，把断魂幻影战戟的每 tick 连击从原版每 10 tick 一次的闸门里放出来”的
 *    机制现场，出现即 WARN。
 *
 * 4. persistentData 键冲突 ——
 *    已静态核对：所有键按技能前缀命名（mce_shred_ / mce_sr_ / mce_br_ / mce_state 等，
 *    无同名键），且 persistentData 是每个实体实例各自一份、不跨实体共享——结构上不存在
 *    “两个同伴共用一个键”的可能。台账里的攻击者 UUID 一列可以进一步实证状态没串人。
 *
 * 开关：debug.shredder_trace_log（r55 起默认 false）。排查用 /mce trace on 现开、不用重启。
 * 本类不做任何状态写入、不取消任何事件——纯观察，所以三个入口在开关关闭时立即 return：
 * 正常游玩连“攻击者描述/伤害台账字符串”都不拼，零额外开销（r55 前每笔伤害都会拼一遍）。
 */
public final class ShredderTrace {

    private ShredderTrace() {}

    // ══════════════ onUseTick 调用计数（排查方向 1） ══════════════

    private static long useCountTick = Long.MIN_VALUE;
    private static final Map<UUID, int[]> USE_COUNTS = new HashMap<>();

    /**
     * 与原版 onUseTick 调用点一一对应（EventHooks.onItemUseTick 就挂在那里）。
     * 只在“使用的是绞肉锯，或该同伴正处于绞肉窗口”时记录，其他物品使用零成本直通。
     */
    public static void onItemUseTick(LivingEntityUseItemEvent.Tick event) {
        SafeGuard.guard("shredder trace: onUseTick counter", () -> {
            LivingEntity entity = event.getEntity();
            if (entity.level().isClientSide || !(entity instanceof Mob caster)) {
                return;
            }
            ItemStack used = event.getItem();
            boolean shredItem = isShredderItem(used);
            boolean shredWindow = MeatShredderSkill.shredWindowActive(caster);
            if (!shredItem && !shredWindow) {
                return;
            }
            if (!MceConfig.shredderTraceLog()) {
                return;
            }
            long now = entity.level().getGameTime();
            if (now != useCountTick) {
                useCountTick = now;
                USE_COUNTS.clear();
            }
            int[] count = USE_COUNTS.computeIfAbsent(caster.getUUID(), k -> new int[1]);
            count[0]++;
            if (count[0] > 1) {
                SafeGuard.warn("onUseTick anomaly: entity " + IncineratorSkill.describe(caster)
                        + " (uuid " + caster.getUUID() + " at tick " + now
                        + ": entering the vanilla onUseTick for the " + count[0] + " time (using " + regName(used)
                        + ") -- if the measured damage spikes, please post this log line, this is the direct cause");
            } else if (SafeGuard.shouldLog("mce_tr_use_" + caster.getUUID(), now, 20L)) {
                SafeGuard.info("onUseTick trace: entity " + IncineratorSkill.describe(caster)
                        + " (uuid " + caster.getUUID() + ")tick " + now
                        + " using " + regName(used) + " (shredder window=" + shredWindow
                        + ", first call this tick), expected hits under the vanilla algorithm this time: " + previewHitTargets(caster));
            }
        });
    }

    /**
     * 复刻灾变 Meat_Shredder.onUseTick 的命中判定（只读、不结算）：
     * 眼睛 → 眼睛+2.5×视线 的射线，与“目标碰撞盒外扩 0.5”相交；搜索盒 = 自身碰撞盒
     * 沿视线延伸 2.5 + 四周 1 格。返回命中候选（名字@uuid短码），供日志展示“这次会打到谁”。
     */
    private static String previewHitTargets(Mob caster) {
        try {
            Vec3 src = caster.getEyePosition();
            Vec3 look = caster.getViewVector(1.0F);
            Vec3 dest = src.add(look.scale(2.5D));
            AABB searchArea = caster.getBoundingBox().expandTowards(look.scale(2.5D)).inflate(1.0D);
            StringBuilder sb = new StringBuilder("[");
            boolean any = false;
            for (Entity entity : caster.level().getEntities(caster, searchArea)) {
                if (!(entity instanceof LivingEntity target) || !target.isAlive()) {
                    continue;
                }
                AABB collision = target.getBoundingBox().inflate(0.5D);
                if (collision.contains(src) || collision.clip(src, dest).isPresent()) {
                    if (any) {
                        sb.append(", ");
                    }
                    sb.append(IncineratorSkill.describe(target)).append("@").append(shortUuid(target.getUUID()));
                    any = true;
                }
            }
            return sb.append(any ? "]" : "none (no hittable target in the ray or the search box)").toString();
        } catch (Throwable t) {
            return "[preview failed: " + t.getClass().getSimpleName() + "]";
        }
    }

    // ══════════════ 伤害台账（排查方向 3 / 5 / 日志要求第 3 条） ══════════════

    /** 台账时间戳：本 tick 内第一笔伤害的 gameTime（flush 时用它对账） */
    private static long ledgerTick = Long.MIN_VALUE;
    /** victim uuid → 本 tick 的 hurt() 调用明细（含被无敌帧/友军保护挡掉的） */
    private static final Map<UUID, List<String>> ATTEMPTS = new HashMap<>();
    /** victim uuid → 本 tick 实际生效的伤害明细 */
    private static final Map<UUID, List<String>> APPLIED = new HashMap<>();
    /** 单目标单 tick 最多记录的明细条数（超出折叠成“…另有 N 笔”），防极端场景撑爆日志 */
    private static final int DETAIL_CAP = 8;

    /**
     * LivingIncomingDamageEvent（伤害生效前、无敌帧拦截前）：记录每次 hurt() 调用现场。
     * 注册顺序在最前——排在友军保护之前，保证“被友军保护取消的调用”也留痕；
     * 取消的调用只会出现在 ATTEMPTS、不会出现在 APPLIED，差值就是被挡掉的部分。
     */
    public static void onDamageAttempt(LivingIncomingDamageEvent event) {
        SafeGuard.guard("shredder trace: hurt call records", () -> {
            // r55：开关关着就直接走（默认关）。纯观察类，不记任何东西不影响游戏。
            if (!MceConfig.shredderTraceLog()) {
                return;
            }
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            DamageSource source = event.getSource();
            if (!traceRelevant(source)) {
                return;
            }
            long now = victim.level().getGameTime();
            addEntry(ATTEMPTS, victim, now,
                    "type=" + typeId(source) + " attacker=" + attackerDesc(source)
                            + " called amount=" + fmt(event.getAmount())
                            + " (scene: target invulnerability ticks=" + victim.invulnerableTime
                            + ", target lastHurt=" + fmt(lastHurtOf(victim)) + ")");

            // 闸门失效预警（绞肉重置无敌帧 → 其他来源伤害绕过原版限速）：
            // 目标 60 tick 内被绞肉命中过 + 本次是非绞肉伤害 + 目标无敌帧仍 > 10
            // + 金额 > lastHurt（即原版闸门本来挡不住它）→ 这笔伤害将近乎全额生效
            if (!CataclysmReflect.isShredderDamage(source)
                    && source.getEntity() instanceof Mob
                    && MeatShredderSkill.recentlyShredHit(victim, now)
                    && victim.invulnerableTime > 10
                    && event.getAmount() > lastHurtOf(victim)
                    && event.getAmount() > 3.0F
                    && SafeGuard.shouldLog("mce_tr_gate_" + victim.getUUID(), now, 40L)) {
                SafeGuard.warn("invulnerability gate failure warning: target " + IncineratorSkill.describe(victim)
                        + " (uuid " + victim.getUUID() + " was just hit by the shredder (within 60 ticks), invulnerability ticks are still high at "
                        + victim.invulnerableTime + ", but lastHurt was pushed back to "
                        + fmt(lastHurtOf(victim)) + " by the shredder's 1-point hit -- this " + attackerDesc(source) + " of "
                        + fmt(event.getAmount()) + " points of damage would apply almost in full per vanilla rules, "
                        + "while normally (no shredder resetting invulnerability nearby) this damage would be blocked to 0 by the invulnerability ticks."
                        + "Please watch whether this source's damage frequency in the ledger spikes because of it");
            }
        });
    }

    /** LivingDamageEvent.Post：实际结算的最终伤害（无敌帧/护甲/友军保护后的净结果） */
    public static void onDamageApplied(LivingDamageEvent.Post event) {
        SafeGuard.guard("shredder trace: applied damage records", () -> {
            if (!MceConfig.shredderTraceLog()) {
                return;
            }
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            DamageSource source = event.getSource();
            if (!traceRelevant(source)) {
                return;
            }
            addEntry(APPLIED, victim, victim.level().getGameTime(),
                    "type=" + typeId(source) + " attacker=" + attackerDesc(source)
                            + " applied=" + fmt(event.getNewDamage())
                            + " (original=" + fmt(event.getOriginalDamage()) + ")");
        });
    }

    /** 这笔伤害值不值得记：绞肉类型，或攻击者（causing entity）是现代同伴（绞肉同伴 / 断魂同伴都在内） */
    private static boolean traceRelevant(DamageSource source) {
        try {
            if (CataclysmReflect.isShredderDamage(source)) {
                return true;
            }
            return source.getEntity() instanceof Mob mob && CompanionDetector.isCompanion(mob);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void addEntry(Map<UUID, List<String>> ledger, LivingEntity victim, long now, String detail) {
        try {
            if (ledgerTick != now) {
                flushLedger(); // 上一个 tick 的账还没结（正常情况下每 tick 都结），先结旧账
                ledgerTick = now;
            }
            List<String> list = ledger.computeIfAbsent(victim.getUUID(), k -> new ArrayList<>());
            if (list.size() < DETAIL_CAP) {
                list.add(detail);
            } else if (list.size() == DETAIL_CAP) {
                list.add("... more entries omitted (over the per-tick " + DETAIL_CAP + " entry cap)");
            }
        } catch (Throwable t) {
            SafeGuard.error("shredder trace: ledger recording failed (no game impact)", t);
        }
    }

    /** ServerTickEvent.Post（本 tick 所有实体 tick 跑完后）：把台账汇总成每目标一行 */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        SafeGuard.guard("shredder trace: per-tick ledger summary", () -> {
            flushLedger();
        });
    }

    private static void flushLedger() {
        try {
            if (ATTEMPTS.isEmpty() && APPLIED.isEmpty()) {
                return;
            }
            if (!MceConfig.shredderTraceLog()) {
                ATTEMPTS.clear();
                APPLIED.clear();
                ledgerTick = Long.MIN_VALUE;
                return;
            }
            long tick = ledgerTick;
            java.util.Set<UUID> victims = new java.util.HashSet<>(ATTEMPTS.keySet());
            victims.addAll(APPLIED.keySet());
            for (UUID victimId : victims) {
                List<String> attempts = ATTEMPTS.getOrDefault(victimId, java.util.List.of());
                List<String> applied = APPLIED.getOrDefault(victimId, java.util.List.of());
                StringBuilder sb = new StringBuilder("shredder ledger tick=" + tick
                        + " target@" + shortUuid(victimId)
                        + ": hurt calls " + attempts.size() + " entries");
                for (String a : attempts) {
                    sb.append(" | ").append(a);
                }
                sb.append("  applied ").append(applied.size()).append(" entries");
                for (String a : applied) {
                    sb.append(" | ").append(a);
                }
                SafeGuard.info(sb.toString());
            }
        } catch (Throwable t) {
            SafeGuard.error("shredder trace: ledger summary failed", t);
        } finally {
            try {
                ATTEMPTS.clear();
                APPLIED.clear();
                ledgerTick = Long.MIN_VALUE;
            } catch (Throwable ignored) {
            }
        }
    }

    // ══════════════ 工具 ══════════════

    private static boolean isShredderItem(ItemStack stack) {
        try {
            return !stack.isEmpty() && CataclysmReflect.MEAT_SHREDDER_ITEM.equals(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String regName(ItemStack stack) {
        try {
            return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        } catch (Throwable t) {
            return stack.getClass().getSimpleName();
        }
    }

    /** 伤害类型的注册 id（cataclysm:shredder / cataclysm:maledictio_magicae 等） */
    private static String typeId(DamageSource source) {
        try {
            Optional<ResourceLocation> id = source.typeHolder().unwrapKey().map(k -> k.location());
            return id.map(ResourceLocation::toString).orElseGet(source::getMsgId);
        } catch (Throwable t) {
            try {
                return source.getMsgId();
            } catch (Throwable t2) {
                return "unknown";
            }
        }
    }

    /** 攻击者全描述：causing entity（含 uuid）+ direct entity（含 uuid、类名），绞肉/战戟一目了然 */
    private static String attackerDesc(DamageSource source) {
        try {
            Entity causing = source.getEntity();
            Entity direct = source.getDirectEntity();
            return (causing == null ? "none" : describe(causing))
                    + "/direct=" + (direct == null ? "none" : describe(direct));
        } catch (Throwable t) {
            return "resolve failed (" + t.getClass().getSimpleName() + ")";
        }
    }

    /** 实体短描述：名字#id（生物）/类名#id（投射物等非生物）+ uuid 短码 */
    private static String describe(Entity entity) {
        try {
            String base = entity instanceof LivingEntity living
                    ? IncineratorSkill.describe(living)
                    : entity.getClass().getSimpleName() + "#id" + entity.getId();
            return base + "(uuid " + entity.getUUID() + ")";
        } catch (Throwable t) {
            return "resolve failed (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String fmt(float value) {
        return String.format("%.1f", value);
    }

    // —— lastHurt 反射读取已统一收敛到 LastHurtAccess（r26 起影子闸门要读写同字段），
    //    取不到返回 -1（日志里显示为 -1），与本类原有口径一致 ——
    private static float lastHurtOf(LivingEntity victim) {
        float value = LastHurtAccess.get(victim);
        return Float.isNaN(value) ? -1.0F : value;
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.substring(0, Math.min(8, s.length()));
    }
}
