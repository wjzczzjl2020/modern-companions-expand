package cn.blockforge.generated.moderncompanionsexpand.skill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;

/**
 * 技能冷却读写（多技能版）。
 *
 * 全部走 entity.getPersistentData()（NBT，随实体存档）：
 * - 每个技能独立一个键：mce_cd_<技能id>（如 mce_cd_the_incinerator / mce_cd_bulwark_of_the_flame）；
 * - 存的是“上一次施放成功/尝试的世界游戏时间 gameTime”，不是剩余秒数；
 * - 就绪条件 = gameTime - 上次施放 >= 当前配置的冷却值；
 * - 因此重启存档后冷却不会重置（gameTime 也是跟着存档走的），
 *   而把配置里的冷却调小，对“正在冷却”的同伴也立刻生效，方便测试。
 *
 * 旧键迁移：第一轮试点用的 mce_incinerator_cast_at，读到就搬运到新键一次。
 */
public final class SkillCooldownStore {

    /** 第一轮试点的旧键（只做读迁移） */
    private static final String LEGACY_INCINERATOR_KEY = "mce_incinerator_cast_at";

    private SkillCooldownStore() {}

    public static String keyFor(String skillId) {
        return "mce_cd_" + skillId;
    }

    public static boolean isReady(Entity owner, String skillId, long gameTime, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return true;
        }
        CompoundTag tag = owner.getPersistentData();
        migrateLegacy(owner, skillId, tag);
        String key = keyFor(skillId);
        if (!tag.contains(key)) {
            return true;
        }
        long lastCast = tag.getLong(key);
        if (gameTime < lastCast) {
            // 世界时间“回退”了（换存档/异常数据），视为已就绪，避免永久卡死
            return true;
        }
        return gameTime - lastCast >= cooldownTicks;
    }

    /** 剩余冷却 tick（用于日志与 /mce status 展示） */
    public static long remainingTicks(Entity owner, String skillId, long gameTime, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return 0;
        }
        CompoundTag tag = owner.getPersistentData();
        migrateLegacy(owner, skillId, tag);
        String key = keyFor(skillId);
        if (!tag.contains(key)) {
            return 0;
        }
        long elapsed = gameTime - tag.getLong(key);
        return Math.max(0, cooldownTicks - elapsed);
    }

    public static void markCast(Entity owner, String skillId, long gameTime) {
        owner.getPersistentData().putLong(keyFor(skillId), gameTime);
    }

    public static void clearCooldown(Entity owner, String skillId) {
        CompoundTag tag = owner.getPersistentData();
        tag.remove(keyFor(skillId));
        if (SkillRegistry.INCINERATOR_ID.equals(skillId)) {
            tag.remove(LEGACY_INCINERATOR_KEY);
        }
    }

    /** 旧键 mce_incinerator_cast_at → 新键 mce_cd_the_incinerator，一次性搬运 */
    private static void migrateLegacy(Entity owner, String skillId, CompoundTag tag) {
        if (!SkillRegistry.INCINERATOR_ID.equals(skillId)) {
            return;
        }
        String key = keyFor(skillId);
        if (!tag.contains(key) && tag.contains(LEGACY_INCINERATOR_KEY)) {
            tag.putLong(key, tag.getLong(LEGACY_INCINERATOR_KEY));
            tag.remove(LEGACY_INCINERATOR_KEY);
        }
    }
}
