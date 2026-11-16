package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.event.ShadowInvulnGate;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * r25 错峰调度（拍板“方案一”）：绞肉进行中，其他同伴对同一目标的 AoE 技能暂缓释放。
 *
 * 背景（r24 实锤的机制）：绞肉锯的 1 点小伤害每 tick 把目标的无敌帧重置为 20、
 * lastHurt 压回 1 上下——原版 hurt() 的“这次伤比上次疼就放行”分支因此失灵，
 * 队友随后的大额 AoE（断魂 12 点实测生效 10.8、炎葬火柱等）会绕过原版限速照单全收。
 * 错峰不是禁止队友打这个目标：普通近战不受任何限制，只是技能的“开始释放”这一步
 * 在绞肉进行期间先等一等；绞肉结束（或该同伴换了目标）立即放行；
 * 等待超过 meat_shredder_aoe_defer_ticks（默认 100 tick = 5 秒）后强制放行，
 * 避免队友在“绞肉永动机”目标面前永久憋着技能。
 *
 * 实现要点：
 * - 绞肉登记：MeatShredderSkill 在 beginCharge / tickCharge 每 tick 调 registerShredTick
 *   刷新“目标 → 绞肉者”登记表；正常收锯（release/endCharge）调 clearShred 即刻解除；
 *   绞肉者中途死亡等异常退出靠 5 tick 新鲜度宽限自动过期（STALE_GRACE_TICKS），
 *   所以这张表不需要持久化——读档后绞肉者的 tickCharge 会立刻重新登记。
 * - 暂缓判定挂在调度器扫描处（SkillTickHandler.scanAndStart）：只拦“开始释放”，
 *   已在蓄力/进行中的技能不打断（保持最小干预）。
 * - /mce force 命令不走调度器扫描，不受错峰限制（管理员手动放行的口子，有意保留）。
 * - 每次暂缓都打日志：开始（原因 + 上限时长）、进行中（已等/上限，限流）、
 *   放行（超时强制 / 绞肉结束 / 目标切换，含实际等待时长）。
 * - r26：clearShred 同时是“影子无敌帧落回”的统一出口（ShadowInvulnGate.onShredEnd）——
 *   错峰拦“开始释放”、影子拦“已放出去的 AoE 结算”，两者互补不冲突。
 */
public final class ShredAoeDeferral {

    /**
     * 登记新鲜度宽限（tick）：绞肉者每 tick 刷新，正常退出会显式清除；
     * 只有“绞肉者没了但没走正常退出”（死亡/实体移除/维度切换）才靠这个宽限过期。
     * 5 tick ≈ 绞肉停手后最多 1/4 秒，队友就放行。
     */
    private static final long STALE_GRACE_TICKS = 5L;

    /** 登记表/暂缓记录的兜底清理阈值，防极端情况下无限堆积 */
    private static final long PURGE_AFTER_TICKS = 1200L;

    /** 暂缓进行中的状态日志限流间隔（40 tick = 2 秒一条，含已等时长） */
    private static final long ONGOING_LOG_INTERVAL_TICKS = 40L;

    /** 目标 → 正在绞它的登记（多把绞肉锯同时绞同一目标也支持：绞者集合 + 最近刷新时刻） */
    private static final ConcurrentHashMap<UUID, ShredEntry> ACTIVE_SHREDS = new ConcurrentHashMap<>();

    /** 暂缓等待记录：同伴 → 在等哪个目标、从哪一 tick 开始等 */
    private static final ConcurrentHashMap<UUID, Episode> EPISODES = new ConcurrentHashMap<>();

    private static final class ShredEntry {
        final Map<UUID, String> shredderNames = new ConcurrentHashMap<>();
        volatile long lastRefreshTick;
    }

    private record Episode(UUID targetId, long sinceTick) {}

    private ShredAoeDeferral() {}

    // ════════════════════════ 绞肉登记（MeatShredderSkill 每 tick 调用） ════════════════════════

    /** 登记/刷新一次“目标正在被 shredder 绞肉”（每 tick 调用，幂等且廉价） */
    public static void registerShredTick(LivingEntity target, Mob shredder, long now) {
        try {
            if (target == null || shredder == null || !target.isAlive()) {
                return;
            }
            UUID targetId = target.getUUID();
            ShredEntry entry = ACTIVE_SHREDS.get(targetId);
            if (entry == null) {
                entry = new ShredEntry();
                ACTIVE_SHREDS.put(targetId, entry);
                SafeGuard.info("stagger registration: target " + IncineratorSkill.describe(target)
                        + " started being shredded, other companions' AoE skills against this target enter deferred monitoring"
                        + " (normal melee unaffected; cap " + MceConfig.meatShredderAoeDeferTicks() + " tick)");
            }
            entry.shredderNames.put(shredder.getUUID(), IncineratorSkill.describe(shredder));
            if (now > entry.lastRefreshTick) {
                entry.lastRefreshTick = now;
            }
            purgeStale(now);
        } catch (Throwable t) {
            SafeGuard.error("stagger registration failed (the shredder itself is unaffected)", t);
        }
    }

    /** 绞肉正常结束（收锯/被打断退出）时显式解除：其他同伴立刻放行 + r26 影子无敌帧落回 */
    public static void clearShred(String rawTargetUuid, long now) {
        try {
            if (rawTargetUuid == null || rawTargetUuid.isEmpty()) {
                return;
            }
            UUID targetId = UUID.fromString(rawTargetUuid);
            ShredEntry removed = ACTIVE_SHREDS.remove(targetId);
            if (removed != null) {
                SafeGuard.info("stagger released: shredding on this target ended (stopped / interrupted exit), "
                        + "deferred monitoring lifted, waiting companions are released on the next scan");
            }
        } catch (IllegalArgumentException bad) {
            // uuid 串非法（理论上不发生）：按无登记处理
        } catch (Throwable t) {
            SafeGuard.error("stagger release failed (the exit flow is unaffected)", t);
        }
        // r26 影子无敌帧落回：无论错峰登记存不存在，只要影子账本在就把目标的无敌帧/lastHurt
        // 归位到“无绞肉污染”的样子——这是所有退出路径的统一出口（release / endCharge 都走这里）
        ShadowInvulnGate.onShredEnd(rawTargetUuid, now);
    }

    // ════════════════════════ 暂缓判定（调度器扫描处调用） ════════════════════════

    /**
     * 该同伴此刻要不要对这个目标暂缓 AoE 技能。
     * 返回 null = 放行；返回非 null = 暂缓原因（供扫描日志 verdicts 使用，日志已在本方法内打）。
     */
    public static String deferReasonIfAny(Mob caster, LivingEntity target, String skillId, long now) {
        try {
            int deferTicks = MceConfig.meatShredderAoeDeferTicks();
            if (deferTicks <= 0 || caster == null || target == null) {
                return null; // 配置关成 0 = 整个错峰功能停用
            }
            UUID targetId = target.getUUID();
            ShredEntry entry = ACTIVE_SHREDS.get(targetId);
            boolean beingShredded = entry != null
                    && now - entry.lastRefreshTick <= STALE_GRACE_TICKS;
            Episode episode = EPISODES.get(caster.getUUID());

            if (!beingShredded) {
                // 绞肉结束（或登记过期）→ 立即放行，并交代实际等了多久
                if (episode != null) {
                    EPISODES.remove(caster.getUUID());
                    SafeGuard.info("stagger released: " + IncineratorSkill.describe(caster) + " of " + skillId
                            + " can cast again -- the target's shredding ended (or the target changed), deferred for "
                            + Math.max(0, now - episode.sinceTick()) + " ticks (under the cap, released normally)");
                }
                return null;
            }

            // 绞肉者本人（理论上它在 CHARGING 不走扫描；双保险跳过）
            if (entry.shredderNames.containsKey(caster.getUUID())) {
                return null;
            }

            if (episode == null || !targetId.equals(episode.targetId())) {
                // 第一次暂缓（或换了个也在被绞的目标 → 重新计时）：交代原因 + 上限
                EPISODES.put(caster.getUUID(), new Episode(targetId, now));
                SafeGuard.info("stagger deferred: " + IncineratorSkill.describe(caster) + " of " + skillId
                        + " cast deferred -- the target " + IncineratorSkill.describe(target)
                        + " is being shredded by " + String.join(", ", entry.shredderNames.values())
                        + " (the shredder resets invulnerability ticks every tick, which lets large AoE hits bypass the vanilla rate limit, see the r24 investigation); "
                        + "deferred for at most " + deferTicks + " ticks (meat_shredder_aoe_defer_ticks), forced release on timeout; "
                        + "normal melee unaffected");
                return "deferred (target is being shredded, cap " + deferTicks + " tick)";
            }

            long waited = now - episode.sinceTick();
            if (waited >= deferTicks) {
                // 超时强制放行（拍板：避免队友永久憋着不放）
                EPISODES.remove(caster.getUUID());
                SafeGuard.info("stagger released (forced on timeout): " + IncineratorSkill.describe(caster) + " of " + skillId
                        + " has been deferred for " + waited + " ticks, reaching the meat_shredder_aoe_defer_ticks="
                        + deferTicks + " cap, forced release (shredding may still be running)");
                return null;
            }

            SafeGuard.infoThrottled("mce_defer_" + caster.getUUID() + "_" + targetId, now,
                    "stagger in progress: " + IncineratorSkill.describe(caster) + " of " + skillId
                            + " still deferred (waited " + waited + "/" + deferTicks
                            + " ticks; released as soon as shredding ends, forced release on timeout)");
            return "deferred (target is being shredded, waited " + waited + "/" + deferTicks + " tick)";
        } catch (Throwable t) {
            SafeGuard.error("the stagger check threw (treated as released, skills are not blocked)", t);
            return null;
        }
    }

    // ════════════════════════ 查询 / 清理 ════════════════════════

    /** 目标此刻是否正处于绞肉中（含 5 tick 宽限；日志与状态自检用） */
    public static boolean isBeingShredded(LivingEntity target, long now) {
        try {
            if (target == null) {
                return false;
            }
            ShredEntry entry = ACTIVE_SHREDS.get(target.getUUID());
            return entry != null && now - entry.lastRefreshTick <= STALE_GRACE_TICKS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 日志用：谁在绞这个目标（不在绞则返回 null） */
    public static String shredderNamesOf(LivingEntity target, long now) {
        try {
            if (target == null) {
                return null;
            }
            ShredEntry entry = ACTIVE_SHREDS.get(target.getUUID());
            if (entry == null || now - entry.lastRefreshTick > STALE_GRACE_TICKS) {
                return null;
            }
            List<String> names = new ArrayList<>(entry.shredderNames.values());
            return names.isEmpty() ? null : String.join(", ", names);
        } catch (Throwable t) {
            return null;
        }
    }

    /** /mce reset 等清理路径：同伴的暂缓等待记录一并清掉 */
    public static void clearEpisode(Mob caster) {
        try {
            if (caster != null) {
                EPISODES.remove(caster.getUUID());
            }
        } catch (Throwable ignored) {
        }
    }

    /** 兜底清理：过期登记与过期暂缓记录（表很小，摊到每次登记时顺手做） */
    private static void purgeStale(long now) {
        try {
            for (Map.Entry<UUID, ShredEntry> e : ACTIVE_SHREDS.entrySet()) {
                if (now - e.getValue().lastRefreshTick > PURGE_AFTER_TICKS) {
                    ACTIVE_SHREDS.remove(e.getKey());
                }
            }
            long episodeMax = Math.max(MceConfig.meatShredderAoeDeferTicks() * 4L, PURGE_AFTER_TICKS);
            for (Map.Entry<UUID, Episode> e : EPISODES.entrySet()) {
                if (now - e.getValue().sinceTick() > episodeMax) {
                    EPISODES.remove(e.getKey());
                }
            }
        } catch (Throwable ignored) {
        }
    }
}