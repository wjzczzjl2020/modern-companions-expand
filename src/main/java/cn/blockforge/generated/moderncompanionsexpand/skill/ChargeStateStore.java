package cn.blockforge.generated.moderncompanionsexpand.skill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;

import java.util.UUID;

/**
 * 技能状态机持久化（每个同伴独立，全部存 getPersistentData()，随实体存档）。
 *
 * 三态：
 *   IDLE(0) ──(优先级扫描就绪 + 有目标)──▶ CHARGING(1) ──(进度满)──▶ 释放
 *      ▲                                     │(任一中断条件)
 *      │                                     ▼
 *      └──(窗口到期)──◀── ACTIVE(2)      INTERRUPTED：清状态 + 记 retry_at
 *
 * 关键设计（对应确认过的 6 点）：
 * - 蓄力进度用“开始时刻的 gameTime 差”计算，不逐 tick 自增 → 读档后自动续算，
 *   条件仍成立就继续蓄力、进度满了就直接释放（第 4 点）；
 * - 打断不消耗冷却，只写 mce_retry_at（第 1 点）；
 * - ACTIVE 是壁垒冲锋窗口（8 tick），期间调度器不做中断判定（第 5 点）；
 * - 姿态快照（AI 目标/主手物品/跟随/守卫/坐姿）在开始蓄力时写入，逐 tick 比对，
 *   变了就算打断；
 * - r8：CHARGING 期间暂停移动 AI（pauseAi/holdPosition，clearState 统一恢复），
 *   炎葬受伤改为累计伤害（mce_charge_damage_total），达到阈值才盖 hurt_at 打断标记。
 */
public final class ChargeStateStore {

    public static final byte IDLE = 0;
    public static final byte CHARGING = 1;
    public static final byte ACTIVE = 2;

    private static final String KEY_STATE = "mce_state";
    private static final String KEY_STATE_SKILL = "mce_state_skill";
    private static final String KEY_CHARGE_START = "mce_charge_start_at";
    private static final String KEY_CHARGE_TARGET = "mce_charge_target";
    private static final String KEY_ACTIVE_UNTIL = "mce_active_until";
    private static final String KEY_RETRY_AFTER = "mce_retry_after";
    private static final String KEY_SILENCE_UNTIL = "mce_silence_until";
    private static final String KEY_HURT_AT = "mce_hurt_at";
    private static final String KEY_DAMAGE_TOTAL = "mce_charge_damage_total";
    private static final String KEY_AI_PAUSED = "mce_charge_ai_paused";
    private static final String KEY_PREVIOUS_NO_AI = "mce_charge_previous_no_ai";
    /** 姿态快照：主手物品 id / AI 目标 uuid / 跟随·守卫·坐姿（-1=方法不存在，跳过比对） */
    private static final String KEY_POSE_MAINHAND = "mce_pose_mainhand";
    private static final String KEY_POSE_AITARGET = "mce_pose_aitarget";
    private static final String KEY_POSE_FOLLOWING = "mce_pose_following";
    private static final String KEY_POSE_GUARDING = "mce_pose_guarding";
    private static final String KEY_POSE_SITTING = "mce_pose_sitting";
    /** 壁垒冲锋方向冲量（hold_velocity 配置开启时窗口内每 tick 钉速度用） */
    private static final String KEY_B_DX = "mce_bulwark_dx";
    private static final String KEY_B_DZ = "mce_bulwark_dz";
    /** 壁垒窗口模式：1=方案A（复用灾变 ChargeAttachment），2=方案B（自建 AABB 判定） */
    private static final String KEY_B_MODE = "mce_bulwark_mode";
    public static final byte MODE_A = 1;
    public static final byte MODE_B = 2;

    private ChargeStateStore() {}

    // —————————————————————————— 状态 ——————————————————————————

    public static byte getState(Entity owner) {
        CompoundTag tag = owner.getPersistentData();
        if (!tag.contains(KEY_STATE)) {
            return IDLE;
        }
        byte s = tag.getByte(KEY_STATE);
        return (s == CHARGING || s == ACTIVE) ? s : IDLE;
    }

    /**
     * r55：本实体身上是否留有本模组写过的技能键（状态技能 / 罚时 / 沉默 任一）。
     * 只给调度器做“IDLE 扫描要不要继续”的兜底判定：从未被本模组碰过的普通僵尸/牛直接跳过扫描，
     * 不再往日志里刷“主手/副手都没持 xxx”；被 /mce equip、force 或本模组技能写过的实体照常扫描，
     * 测试行为不变。任何异常按“没有数据”处理。
     */
    public static boolean hasMceData(Entity owner) {
        try {
            CompoundTag tag = owner.getPersistentData();
            return tag.contains(KEY_STATE_SKILL) || tag.contains(KEY_RETRY_AFTER)
                    || tag.contains(KEY_SILENCE_UNTIL);
        } catch (Throwable t) {
            return false;
        }
    }

    public static String stateSkill(Entity owner) {
        CompoundTag tag = owner.getPersistentData();
        if (!tag.contains(KEY_STATE_SKILL)) {
            return "";
        }
        return tag.getString(KEY_STATE_SKILL);
    }

    /** 进入蓄力：记录技能、开始时刻、锁定目标、姿态快照 */
    public static void beginCharging(Mob owner, String skillId, long gameTime, LivingEntity target) {
        try {
            CompoundTag tag = owner.getPersistentData();
            tag.putByte(KEY_STATE, CHARGING);
            tag.putString(KEY_STATE_SKILL, skillId);
            tag.putLong(KEY_CHARGE_START, gameTime);
            tag.putString(KEY_CHARGE_TARGET, target.getUUID().toString());
            capturePose(owner, tag);
            tag.remove(KEY_HURT_AT);
            tag.putDouble(KEY_DAMAGE_TOTAL, 0.0D);
            tag.putBoolean(KEY_AI_PAUSED, false);
            tag.putBoolean(KEY_PREVIOUS_NO_AI, owner.isNoAi());
        } catch (Throwable t) {
            SafeGuard.error("entering the charge state failed, game kept running", t);
        }
    }

    /** 进入冲锋窗口（壁垒）：窗口起点同时把方向存下来（可选钉速度用） */
    public static void beginActive(Mob owner, String skillId, long gameTime, long untilTick, double dx, double dz) {
        CompoundTag tag = owner.getPersistentData();
        tag.putByte(KEY_STATE, ACTIVE);
        tag.putString(KEY_STATE_SKILL, skillId);
        tag.putLong(KEY_CHARGE_START, gameTime);
        tag.putLong(KEY_ACTIVE_UNTIL, untilTick);
        tag.putDouble(KEY_B_DX, dx);
        tag.putDouble(KEY_B_DZ, dz);
    }

    public static long activeUntil(Entity owner) {
        return owner.getPersistentData().getLong(KEY_ACTIVE_UNTIL);
    }

    public static double activeDx(Entity owner) {
        return owner.getPersistentData().getDouble(KEY_B_DX);
    }

    public static double activeDz(Entity owner) {
        return owner.getPersistentData().getDouble(KEY_B_DZ);
    }

    /** 壁垒窗口走哪个方案（A=灾变附件 / B=自建判定），beginActive 后由技能写入 */
    public static void setBulwarkMode(Entity owner, byte mode) {
        owner.getPersistentData().putByte(KEY_B_MODE, mode);
    }

    public static byte getBulwarkMode(Entity owner) {
        CompoundTag tag = owner.getPersistentData();
        if (!tag.contains(KEY_B_MODE)) {
            return MODE_A;
        }
        byte m = tag.getByte(KEY_B_MODE);
        return (m == MODE_A || m == MODE_B) ? m : MODE_A;
    }

    /** 清除进行中的任务状态（蓄力/窗口），但保留冷却、沉默、重试罚时 */
    public static void clearState(Entity owner) {
        try {
            if (owner instanceof Mob mob) {
                resumeAi(mob);
            }
            CompoundTag tag = owner.getPersistentData();
            tag.remove(KEY_STATE);
            tag.remove(KEY_STATE_SKILL);
            tag.remove(KEY_CHARGE_START);
            tag.remove(KEY_CHARGE_TARGET);
            tag.remove(KEY_ACTIVE_UNTIL);
            tag.remove(KEY_DAMAGE_TOTAL);
            tag.remove(KEY_AI_PAUSED);
            tag.remove(KEY_PREVIOUS_NO_AI);
            tag.remove(KEY_POSE_MAINHAND);
            tag.remove(KEY_POSE_AITARGET);
            tag.remove(KEY_POSE_FOLLOWING);
            tag.remove(KEY_POSE_GUARDING);
            tag.remove(KEY_POSE_SITTING);
            tag.remove(KEY_B_DX);
            tag.remove(KEY_B_DZ);
            tag.remove(KEY_B_MODE);
        } catch (Throwable t) {
            SafeGuard.error("clearing skill state failed", t);
        }
    }

    /** 进入 CHARGING 时暂停寻路、Goal 和水平移动；原本 NoAI 的实体保持原值。 */
    public static void pauseAi(Mob owner) {
        try {
            CompoundTag tag = owner.getPersistentData();
            if (!tag.getBoolean(KEY_AI_PAUSED)) {
                tag.putBoolean(KEY_PREVIOUS_NO_AI, owner.isNoAi());
                tag.putBoolean(KEY_AI_PAUSED, true);
            }
            owner.getNavigation().stop();
            owner.stopInPlace();
            owner.setNoAi(true);
            var velocity = owner.getDeltaMovement();
            owner.setDeltaMovement(0.0D, velocity.y, 0.0D);
        } catch (Throwable t) {
            SafeGuard.error("pausing companion movement AI failed", t);
        }
    }

    /** 蓄力完成、被打断、命令清理时恢复蓄力前的 NoAI 状态。 */
    public static void resumeAi(Mob owner) {
        try {
            CompoundTag tag = owner.getPersistentData();
            if (!tag.getBoolean(KEY_AI_PAUSED)) {
                return;
            }
            owner.getNavigation().stop();
            owner.setNoAi(tag.getBoolean(KEY_PREVIOUS_NO_AI));
            tag.putBoolean(KEY_AI_PAUSED, false);
        } catch (Throwable t) {
            SafeGuard.error("resuming companion movement AI failed", t);
        }
    }

    /** 蓄力中每 tick 固定水平位置，保留重力和受击的竖直物理。 */
    public static void holdPosition(Mob owner) {
        try {
            owner.getNavigation().stop();
            var velocity = owner.getDeltaMovement();
            owner.setDeltaMovement(0.0D, velocity.y, 0.0D);
        } catch (Throwable t) {
            SafeGuard.error("holding the charge stand-still failed", t);
        }
    }

    /** 当前蓄力阶段累计受到的伤害。 */
    public static double damageTotal(Entity owner) {
        try {
            return Math.max(0.0D, owner.getPersistentData().getDouble(KEY_DAMAGE_TOTAL));
        } catch (Throwable t) {
            SafeGuard.error("reading accumulated charge damage failed", t);
            return 0.0D;
        }
    }

    /** 累加蓄力阶段受到的伤害，返回累加后的值。 */
    public static double addDamage(Entity owner, double amount) {
        try {
            double safeAmount = Double.isFinite(amount) ? Math.max(0.0D, amount) : 0.0D;
            CompoundTag tag = owner.getPersistentData();
            double total = Math.max(0.0D, tag.getDouble(KEY_DAMAGE_TOTAL)) + safeAmount;
            tag.putDouble(KEY_DAMAGE_TOTAL, total);
            return total;
        } catch (Throwable t) {
            SafeGuard.error("accumulating charge damage failed", t);
            return 0.0D;
        }
    }

    /** 清空本模组写入的全部状态（/mce reset 用：冷却、罚时、沉默、任务一起清） */
    public static void clearAll(Entity owner) {
        clearState(owner);
        CompoundTag tag = owner.getPersistentData();
        tag.remove(KEY_RETRY_AFTER);
        tag.remove(KEY_SILENCE_UNTIL);
        tag.remove(KEY_HURT_AT);
    }

    /** 蓄力开始时刻（gameTime）；配合技能自身 chargeTicks 求进度 */
    public static long chargeStart(Entity owner) {
        return owner.getPersistentData().getLong(KEY_CHARGE_START);
    }

    /** 解析蓄力期间锁定的目标；不在本维度/已消失/死亡 → null */
    public static LivingEntity lockedTarget(ServerLevel level, Mob owner) {
        String raw = owner.getPersistentData().getString(KEY_CHARGE_TARGET);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(raw);
        } catch (IllegalArgumentException bad) {
            return null;
        }
        Entity e = level.getEntity(uuid);
        if (!(e instanceof LivingEntity living) || !living.isAlive() || living.isRemoved()) {
            return null;
        }
        return living;
    }

    public static String lockedTargetRawUuid(Entity owner) {
        return owner.getPersistentData().getString(KEY_CHARGE_TARGET);
    }

    // —————————————————————————— 罚时 / 沉默 ——————————————————————————

    /** 被打断后的重试延迟：now 之前不允许开始新的蓄力/释放 */
    public static void setRetryAfter(Entity owner, long untilTick) {
        owner.getPersistentData().putLong(KEY_RETRY_AFTER, untilTick);
    }

    public static long retryAfter(Entity owner) {
        return owner.getPersistentData().getLong(KEY_RETRY_AFTER);
    }

    /** 成功释放后的全局沉默（只挡“开始”，不挡已在进行的蓄力/窗口） */
    public static void markSilence(Entity owner, long untilTick) {
        owner.getPersistentData().putLong(KEY_SILENCE_UNTIL, untilTick);
    }

    public static long silenceUntil(Entity owner) {
        return owner.getPersistentData().getLong(KEY_SILENCE_UNTIL);
    }

    /** 打断标记：蓄力期累计伤害达到阈值后盖时间戳，tick 状态机比对 → 打断（不消耗冷却） */
    public static void markHurt(Entity owner, long gameTime) {
        owner.getPersistentData().putLong(KEY_HURT_AT, gameTime);
    }

    public static long hurtAt(Entity owner) {
        return owner.getPersistentData().getLong(KEY_HURT_AT);
    }

    // —————————————————————————— 姿态快照 ——————————————————————————

    /** 开始蓄力时拍一张“姿态照片”：AI 目标、主手物品、跟随/守卫/坐姿（反射读，方法不存在记 -1） */
    public static void capturePose(Mob owner, CompoundTag tag) {
        String mainhand = "-";
        try {
            var stack = owner.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            if (!stack.isEmpty()) {
                mainhand = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString();
            }
        } catch (Throwable ignored) {
        }
        tag.putString(KEY_POSE_MAINHAND, mainhand);
        LivingEntity aiTarget = null;
        try {
            aiTarget = owner.getTarget();
        } catch (Throwable ignored) {
        }
        tag.putString(KEY_POSE_AITARGET, aiTarget == null ? "-" : aiTarget.getUUID().toString());
        tag.putByte(KEY_POSE_FOLLOWING, MobStance.readFlag(owner, "isFollowing"));
        tag.putByte(KEY_POSE_GUARDING, MobStance.readFlag(owner, "isGuarding"));
        tag.putByte(KEY_POSE_SITTING, MobStance.readFlag(owner, "isOrderedToSit"));
    }

    /** 当前姿态 vs 快照，逐项比对；返回第一处不同的说明文字，全部一致返回 null（-1 = 该项没法检查，跳过） */
    public static String poseChanged(Mob owner) {
        CompoundTag tag = owner.getPersistentData();
        try {
            var stack = owner.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
            String now = stack.isEmpty() ? "-"
                    : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            String then = tag.getString(KEY_POSE_MAINHAND);
            if (!then.isEmpty() && !now.equals(then)) {
                return "main hand item changed (" + then + " → " + now + ")";
            }
        } catch (Throwable ignored) {
        }
        try {
            LivingEntity ai = owner.getTarget();
            String now = ai == null ? "-" : ai.getUUID().toString();
            String then = tag.getString(KEY_POSE_AITARGET);
            if (!then.isEmpty() && !now.equals(then)) {
                return "AI attack target switched";
            }
        } catch (Throwable ignored) {
        }
        String posture = compareFlag(tag, KEY_POSE_FOLLOWING, owner, "isFollowing", "following state");
        if (posture != null) return posture;
        posture = compareFlag(tag, KEY_POSE_GUARDING, owner, "isGuarding", "guarding state");
        if (posture != null) return posture;
        posture = compareFlag(tag, KEY_POSE_SITTING, owner, "isOrderedToSit", "sitting state");
        if (posture != null) return posture;
        return null;
    }

    private static String compareFlag(CompoundTag tag, String key, Mob owner, String method, String label) {
        byte then = tag.getByte(key);
        if (then < 0 || !tag.contains(key)) {
            return null;
        }
        byte now = MobStance.readFlag(owner, method);
        if (now < 0) {
            return null;
        }
        if (now != then) {
            return label + " changed (" + (then == 1 ? "on" : "off") + " → " + (now == 1 ? "on" : "off") + ")";
        }
        return null;
    }
}
