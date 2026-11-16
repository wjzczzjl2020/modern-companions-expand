package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.BrontesSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.ChargeStateStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.CompanionSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.ShredAoeDeferral;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillCooldownStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillRegistry;
import cn.blockforge.generated.moderncompanionsexpand.skill.TesseractSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.CompanionDetector;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 技能优先级调度器：挂在 EntityTickEvent.Post，只在服务端跑，每个 Mob 独立状态机。
 *
 * 每 tick 的推进顺序（结构上杜绝同 tick 双放）：
 *   状态 = CHARGING → 推进蓄力状态机（完成 → 释放；任一中断条件 → 打断，不消耗冷却）。
 *                    进入 CHARGING 后先 pauseAi + holdPosition：停寻路、停 Goal、NoAI、
 *                    每 tick 清零水平速度，同伴原地只面向目标；clearState 统一恢复 AI。
 *   状态 = ACTIVE   → 壁垒冲锋窗口（每 tick 驱动；窗口期间不做中断判定）
 *   状态 = IDLE     → 每 check_interval tick 扫描一次（**只扫现代同伴**，见下）：
 *       沉默/罚时未到 → 跳过（按下面的规则写扫描日志）；
 *       按优先级表顺序找第一个【开关开 + 主手/副手持对应物品 + 冷却就绪 + 有合法目标】的技能；
 *       chargeTicks>0 → 进入 CHARGING；chargeTicks==0 → 当场释放（壁垒走这条）。
 *
 * r55 日志刷屏修复（两点）：
 *   1. **扫描范围**：技能本来就只给现代同伴用（僵尸/牛拿不到这些武器，扫一百次结论也是
 *      “主手/副手都没持”），但旧版对世界里每一个 Mob 都扫一遍并且写日志——实体一多，
 *      latest.log/debug.log 能被刷到上百 MB。现在 IDLE 扫描前先过一道 CompanionDetector.isCompanion；
 *      已进入蓄力/冲锋窗口的实体（哪怕判定不到同伴）照常驱动，保证状态机能收尾。
 *   2. **扫描日志**：旧版限流键带整行文字哈希，冷却/罚时的倒计时每扫一次都在变 →
 *      哈希一变就再打一条，等于没限流。现在按“判定阶段签名”限流：只比较每个技能卡在
 *      哪个阶段（没持/冷却/无目标/选中…），倒计时数字不参与比较；签名不变就一行不打，
 *      签名变了最快每 scan_log_min_interval_ticks 一行，长期卡在同一结论每 scan_log_repeat_ticks 提醒一行。
 *      debug.scan_debug_log 默认值同时从 true 改成 false（要排查时用 /mce scan on 现开）。
 *
 * 读档续算（确认第 4 点）：蓄力进度用 gameTime 差算，重启后条件仍成立就继续蓄力，
 * 进度已满就直接释放；中断判定（目标没了等）在续算的第一个 tick 自然生效。
 */
public final class SkillTickHandler {

    private SkillTickHandler() {}

    public static void onEntityTickPost(EntityTickEvent.Post event) {
        SafeGuard.guard("companion skill tick", () -> {
            Entity entity = event.getEntity();
            if (!(entity instanceof Mob mob)) {
                return;
            }
            Level level = mob.level();
            if (level.isClientSide || !(level instanceof ServerLevel serverLevel)) {
                return;
            }
            long now = level.getGameTime();
            // r21 掣雷巨锤：投掷锁定/强制恢复检测每 tick 驱动（内部先查 persistentData，无锁零成本直通；
            // 不受 check_interval 节流，保证“锤子一回来/一超时就恢复普攻”的实时性）
            BrontesSkill.tickLock(serverLevel, mob, now);
            // r30 超立方体：五角星传送门的“伤害自研循环”每 tick 驱动（内部先查 persistentData
            // 零成本直通；每 5 tick 一轮、第 50 tick 到期爆炸，读档按 gameTime 差续算，超时自清）
            TesseractSkill.tickSalvo(serverLevel, mob, now);
            byte state = ChargeStateStore.getState(mob);
            if (state == ChargeStateStore.CHARGING) {
                tickCharging(serverLevel, mob, now);
                return;
            }
            if (state == ChargeStateStore.ACTIVE) {
                tickActiveWindow(serverLevel, mob, now);
                return;
            }
            // r55 刷屏修复：IDLE 扫描只对现代同伴开放。
            // 已处于蓄力/冲锋窗口的实体上面已经处理掉了，不会因为这道判断被卡在半路；
            // 本模组自己造出来的实体（若有）带 mce_ 技能数据，也放行，防状态机断头。
            if (!CompanionDetector.isCompanion(mob) && !ChargeStateStore.hasMceData(mob)) {
                return;
            }
            // IDLE：启动扫描按 check_interval 节流（已在进行的蓄力/窗口不受这个阀值影响）
            if (now % MceConfig.checkIntervalTicks() != 0L) {
                return;
            }
            if (mob.isRemoved() || !mob.isAlive() || mob.isSleeping()) {
                return;
            }
            scanAndStart(serverLevel, mob, now);
        });
    }

    // ════════════════════════ IDLE：优先级扫描 ════════════════════════

    // —— r55 阶段码：只进“签名”（决定要不要打日志），不进正文；正文另写给人看的中文 ——
    private static final char PHASE_RETRY = 'R';   // 整体跳过：打断罚时未到
    private static final char PHASE_SILENCE = 'S'; // 整体跳过：全局沉默未到
    private static final char PHASE_SCAN = 'A';    // 正常走优先级扫描
    private static final char CODE_OFF = '0';      // 开关关闭
    private static final char CODE_NO_ITEM = '1';  // 主手/副手都没持
    private static final char CODE_COOLDOWN = '2'; // 冷却中
    private static final char CODE_NO_TARGET = '3';// 没有合法目标
    private static final char CODE_DEFER = '4';    // 绞肉错峰暂缓
    private static final char CODE_CHARGE = '5';   // 选中 → 进入蓄力
    private static final char CODE_CAST = '6';     // 选中 → 当场释放

    /** 实体 uuid → 上次记录过的阶段签名；uuid → 上次真正打出日志的 tick。有界 LRU，服务端单线程访问 */
    private static final int SCAN_STATE_MAX = 512;
    private static final Map<UUID, String> LAST_SIGNATURE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, String> eldest) {
            return size() > SCAN_STATE_MAX;
        }
    };
    private static final Map<UUID, Long> LAST_LOG_TICK = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Long> eldest) {
            return size() > SCAN_STATE_MAX;
        }
    };

    /**
     * 一次扫描的记账本。r55：签名（阶段码序列，不含任何倒计时数字）用来决定“这次要不要打日志”，
     * 正文只在真要打的时侯才拼——scan_debug_log=false 时一次扫描零字符串开销。
     */
    private static final class ScanReport {
        final StringBuilder signature = new StringBuilder(24);
        final List<String> verdicts; // null = 日志关着，不记正文

        ScanReport(boolean withText) {
            this.verdicts = withText ? new ArrayList<>(16) : null;
        }

        void phase(char code) {
            signature.append(code);
        }

        void skill(String id, char code, String text) {
            signature.append(code);
            if (verdicts != null) {
                verdicts.add(id + ": " + text);
            }
        }

        /** 给人看的整行正文（只在日志开着、且判定要打的时候调用） */
        String detail() {
            return "order=" + SkillPriorityConfig.orderedIds() + "; verdicts: "
                    + (verdicts == null || verdicts.isEmpty()
                            ? "the priority table contains no registered skill (check ids with /mce skills list)"
                            : String.join(" | ", verdicts));
        }
    }

    private static void scanAndStart(ServerLevel level, Mob mob, long now) {
        final boolean logOn = MceConfig.scanDebugLog();
        ScanReport report = new ScanReport(logOn);
        if (now < ChargeStateStore.retryAfter(mob)) {
            report.phase(PHASE_RETRY);
            flushScan(mob, now, report, logOn
                    ? "skipped entirely: interrupt penalty still active (left " + (ChargeStateStore.retryAfter(mob) - now) + " tick)"
                    : null);
            return;
        }
        if (now < ChargeStateStore.silenceUntil(mob)) {
            report.phase(PHASE_SILENCE);
            flushScan(mob, now, report, logOn
                    ? "skipped entirely: global silence still active (left " + (ChargeStateStore.silenceUntil(mob) - now) + " tick)"
                    : null);
            return;
        }
        report.phase(PHASE_SCAN);
        for (CompanionSkill skill : SkillRegistry.orderedByPriority()) {
            String id = skill.id();
            if (!skill.enabled()) {
                report.skill(id, CODE_OFF, "skipped (disabled)");
                continue;
            }
            EquipmentSlot slot = skill.heldSlot(mob);
            if (slot == null) {
                report.skill(id, CODE_NO_ITEM, "skipped (neither main nor off hand holds " + skill.requiredItem() + ")");
                continue;
            }
            String slotLabel = CompanionSkill.slotLabel(slot);
            if (!SkillCooldownStore.isReady(mob, id, now, skill.cooldownTicks())) {
                report.skill(id, CODE_COOLDOWN, "skipped (" + slotLabel + " held OK, cooldown left "
                        + SkillCooldownStore.remainingTicks(mob, id, now, skill.cooldownTicks()) + " tick)");
                continue;
            }
            LivingEntity target = skill.findTarget(mob);
            if (target == null) {
                report.skill(id, CODE_NO_TARGET, "skipped (" + slotLabel + " held OK, cooldown ready, but no valid attack target"
                        + " -- the AI target is empty/dead/in another dimension/further than 48 blocks; Bulwark does not charge and Incinerator does not start charging without a target)");
                continue;
            }
            // r25 错峰（拍板方案一）：目标正在被其他同伴的绞肉锯绞肉时，本技能暂缓释放——
            // 绞肉每 tick 重置无敌帧，会让本技能的大额伤害绕过原版限速照单全收（r24 实锤）。
            // 只拦“开始释放”（已在蓄力/进行中的不打断）；普通近战不受影响；
            // 绞肉结束/目标切换立即放行，超过 meat_shredder_aoe_defer_ticks 强制放行。
            String deferReason = ShredAoeDeferral.deferReasonIfAny(mob, target, id, now);
            if (deferReason != null) {
                report.skill(id, CODE_DEFER, "skipped (" + slotLabel + " held OK, cooldown ready, " + deferReason + ")");
                continue;
            }
            if (skill.chargeTicks() > 0) {
                report.skill(id, CODE_CHARGE, "selected (" + slotLabel + ") -> entering charging");
                flushScan(mob, now, report, logOn ? report.detail() : null);
                ChargeStateStore.beginCharging(mob, id, now, target);
                skill.beginCharge(level, mob, target, now);
                SafeGuard.info(id + ": " + IncineratorSkill.describe(mob) + " entering charging ("
                        + skill.chargeTicks() + " tick, " + slotLabel + " held, target "
                        + IncineratorSkill.describe(target) + ")");
            } else {
                report.skill(id, CODE_CAST, "selected (" + slotLabel + ") -> cast on the spot");
                flushScan(mob, now, report, logOn ? report.detail() : null);
                skill.release(level, mob, target, now, false);
            }
            return; // 每 tick 至多推进一个技能
        }
        flushScan(mob, now, report, logOn ? report.detail() : null);
    }

    /**
     * 扫描日志的唯一出口（r55 重写，为什么旧版会刷屏）：
     * 旧版限流键是“整行文字的哈希”，而正文里带冷却/罚时倒计时——每扫一次数字都在变，
     * 哈希就每变一次，100 tick 限流形同虚设，等于每次扫描一行；再叠加“全世界每个 Mob
     * 每 5 tick 扫一次”，latest.log/debug.log 能被刷到上百 MB。
     * 新版只比较阶段签名（数字不参与），规则：
     *   · 签名没变 → 一行都不打，最多每 debug.scan_log_repeat_ticks 提醒一行同一结论；
     *   · 签名变了 → 打，但同一同伴两次之间至少隔 debug.scan_log_min_interval_ticks（防抖动刷屏）；
     *   · debug.scan_debug_log=false（r55 起的默认值）→ 完全不判、不拼正文。
     */
    private static void flushScan(Mob mob, long now, ScanReport report, String detail) {
        if (detail == null || !MceConfig.scanDebugLog()) {
            return;
        }
        UUID uuid = mob.getUUID();
        String signature = report.signature.toString();
        String last;
        Long lastTick;
        synchronized (LAST_SIGNATURE) {
            last = LAST_SIGNATURE.get(uuid);
            lastTick = LAST_LOG_TICK.get(uuid);
        }
        boolean changed = !signature.equals(last);
        long minInterval = Math.max(1L, MceConfig.scanLogMinIntervalTicks());
        long repeat = Math.max(minInterval, MceConfig.scanLogRepeatTicks());
        long since = lastTick == null ? Long.MAX_VALUE : Math.max(0L, now - lastTick);
        if (changed ? since < minInterval : since < repeat) {
            return; // 还没到该说话的时机：同结论按 repeat 提醒，换了结论也要隔满 minInterval
        }
        synchronized (LAST_SIGNATURE) {
            LAST_SIGNATURE.put(uuid, signature);
            LAST_LOG_TICK.put(uuid, now);
        }
        SafeGuard.info("scanned " + IncineratorSkill.describe(mob) + ": " + detail
                + (changed ? "" : " (conclusion unchanged, one reminder every " + repeat + " ticks)"));
    }

    /** /mce scan 用：清掉“同结论已经打过”的记忆，改完配置能立刻看到一条新日志 */
    public static void resetScanLogState() {
        try {
            synchronized (LAST_SIGNATURE) {
                LAST_SIGNATURE.clear();
                LAST_LOG_TICK.clear();
            }
        } catch (Throwable t) {
            SafeGuard.warn("clearing the scan log rate-limit memory failed (caught): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ CHARGING：蓄力状态机 ════════════════════════

    private static void tickCharging(ServerLevel level, Mob mob, long now) {
        CompanionSkill skill = SkillRegistry.byId(ChargeStateStore.stateSkill(mob));
        // 先统一维持“蓄力站定”，保证任何异常或条件变化都不会恢复寻路后继续向前冲。
        ChargeStateStore.pauseAi(mob);
        ChargeStateStore.holdPosition(mob);
        if (skill == null || !skill.enabled()) {
            interruptCharging(level, mob, now, "the skill is disabled or has no implementation");
            return;
        }
        if (mob.isRemoved() || !mob.isAlive()) {
            return; // 本体都没了，数据随实体消失，无需清理
        }
        LivingEntity target = ChargeStateStore.lockedTarget(level, mob);
        if (target == null) {
            interruptCharging(level, mob, now, "target died / vanished / changed dimension");
            return;
        }
        double distance = mob.distanceTo(target);
        // r23 钩子：绞肉锯等“进行型”技能自带更近的退出距离（射程 + 0.5 迟滞），其余技能沿用全局 32 格
        double breakDistance = skill.breakDistanceOverride() > 0.0D
                ? skill.breakDistanceOverride()
                : MceConfig.chargeBreakDistance();
        if (distance > breakDistance) {
            interruptCharging(level, mob, now,
                    "target left the charging range (" + String.format("%.1f", distance) + " > " + breakDistance + " blocks)");
            return;
        }
        long hurtAt = ChargeStateStore.hurtAt(mob);
        if (hurtAt > 0 && hurtAt >= ChargeStateStore.chargeStart(mob)) {
            interruptCharging(level, mob, now, "attacked while charging (see the combat log for the damage source)");
            return;
        }
        String poseChange = ChargeStateStore.poseChanged(mob);
        if (poseChange != null) {
            interruptCharging(level, mob, now, "pose changed: " + poseChange);
            return;
        }

        int progress = (int) Math.max(0, now - ChargeStateStore.chargeStart(mob));
        int need = skill.chargeTicks();
        if (progress >= need) {
            // —— 蓄力完成：先收姿势再结算（避免释放路径与持握 tick 互相干扰）——
            boolean cast = skill.release(level, mob, target, now, false);
            finishCharging(level, mob, now, skill);
            if (cast) {
                ChargeStateStore.markSilence(mob, now + SkillPriorityConfig.silenceTicks());
            } else {
                // 释放失败（多半前方无地面/灾变反射层异常）：冷却已由 release 内部记录，
                // 这里只补一个短罚时，防逐 tick 重试刷屏
                ChargeStateStore.setRetryAfter(mob, now + MceConfig.checkIntervalTicks() * 2L);
            }
        } else {
            skill.tickCharge(level, mob, target, progress, now);
        }
    }

    /** 打断：清状态 + 写重试罚时，不消耗冷却（确认第 1 点） */
    private static void interruptCharging(ServerLevel level, Mob mob, long now, String reason) {
        String skillId = ChargeStateStore.stateSkill(mob);
        CompanionSkill skill = SkillRegistry.byId(skillId);
        if (skill != null) {
            skill.endCharge(level, mob, now);
        }
        ChargeStateStore.clearState(mob);
        ChargeStateStore.setRetryAfter(mob, now + MceConfig.interruptRetryTicks());
        SafeGuard.info("charge interrupted: " + skillId + " on " + IncineratorSkill.describe(mob)
                + ", reason: " + reason
                + "; no cooldown consumed, movement/melee AI restored (the penalty only blocks skill retries), "
                + MceConfig.interruptRetryTicks() + " ticks before charging may start again");
    }

    /** 蓄力正常走完（不管释放成没成功）：收姿势 + 清状态 */
    private static void finishCharging(ServerLevel level, Mob mob, long now, CompanionSkill skill) {
        skill.endCharge(level, mob, now);
        ChargeStateStore.clearState(mob);
    }

    // ════════════════════════ ACTIVE：壁垒冲锋窗口 ════════════════════════

    private static void tickActiveWindow(ServerLevel level, Mob mob, long now) {
        CompanionSkill skill = SkillRegistry.byId(ChargeStateStore.stateSkill(mob));
        if (skill == null) {
            ChargeStateStore.clearState(mob);
            return;
        }
        if (mob.isRemoved() || !mob.isAlive()) {
            return;
        }
        // 确认第 5 点：窗口期间（壁垒最多 8 tick）不做任何中断判定，只推进窗口本身
        boolean keepGoing = skill.tickActive(level, mob, now);
        if (!keepGoing || now >= ChargeStateStore.activeUntil(mob)) {
            ChargeStateStore.clearState(mob);
            SafeGuard.info(skill.id() + ": cast window ended (" + IncineratorSkill.describe(mob) + ", "
                    + (keepGoing ? "window ran out" : "window closed early")
                    + "), global silence " + SkillPriorityConfig.silenceTicks() + " ticks was already recorded at cast time");
        }
    }
}
