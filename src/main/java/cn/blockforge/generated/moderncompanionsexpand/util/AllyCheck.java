package cn.blockforge.generated.moderncompanionsexpand.util;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;

import java.util.UUID;

/**
 * 统一友军判定工具：所有同伴技能在做“伤害 + 击退/击飞”之前都问它一句。
 *
 * 为什么不能直接用原版 isAlliedTo（r8 实测事故根因）：
 * - 原版只认「计分板同队」和「Owner/TamableAnimal 主人链」两种关系；
 * - 测试里冲锋的施法者常常是命令装备出来的普通生物（没有主人链），
 *   被撞到的却是玩家/别的同伴 → 原版判定为“非友军”→ 灾变 Wall_Watcher 照样钉着速度把人击飞；
 * - 主模组（现代同伴）的友伤保护是伤害事件层的，只吞伤害不吞位移。
 * 所以这里把“友军”口径放大成显式规则，判定结果供壁垒及后续所有技能复用。
 *
 * 口径（按用户需求）：
 *   1. 目标 = 施法者自己 → 友军；
 *   2. 目标是明确敌对生物（Monster，含灾变/多数 mod 的怪）→ 永远不是友军（先判，防误保）；
 *   3. 原版 isAlliedTo 任一方向成立 → 友军（保留同队/主人链等原生语义）；
 *   4. 目标是玩家（任何 Player）→ 友军：同伴冲锋永不击飞玩家；
 *   5. 主人链（双向）：目标是施法者的主人 / 施法者是目标的主人 / 两者同主人 → 友军；
 *   6. 目标是现代同伴（CompanionDetector）→ 友军：同伴之间互不击飞。
 *
 * 兜底哲学：判定过程出任何异常 → 按“友军”处理（宁可少击飞一次，不可误击飞玩家）。
 */
public final class AllyCheck {

    private AllyCheck() {}

    /** attacker 视角下 target 是不是友军（任何异常返回 true，保守保护） */
    public static boolean isAlly(LivingEntity attacker, LivingEntity target) {
        try {
            if (attacker == null || target == null) {
                return false;
            }
            if (attacker == target) {
                return true;
            }
            // 2. 敌对硬判：Monster 系（灾变怪物、原版怪、僵尸村民等）永远是合法打击目标
            if (isExplicitHostile(target)) {
                return false;
            }
            // 3. 原版语义（同队、坐骑/主人链）双向取并集
            if (attacker.isAlliedTo(target) || target.isAlliedTo(attacker)) {
                return true;
            }
            // 4. 玩家一律按友军处理
            if (target instanceof Player) {
                return true;
            }
            // 5. 主人链双向 + 同主人
            UUID ownerOfAttacker = ownerOf(attacker);
            UUID ownerOfTarget = ownerOf(target);
            if (ownerOfAttacker != null) {
                if (ownerOfAttacker.equals(target.getUUID())) {
                    return true; // 目标是施法者的主人
                }
                if (ownerOfTarget != null && ownerOfAttacker.equals(ownerOfTarget)) {
                    return true; // 同一个主人的宠物/同伴
                }
            }
            if (ownerOfTarget != null && ownerOfTarget.equals(attacker.getUUID())) {
                return true; // 施法者是目标的主人（反向）
            }
            // 6. 现代同伴一律按友军处理（跨主人也算，见需求“其他同伴”）
            if (CompanionDetector.isCompanion(target)) {
                return true;
            }
            return false;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ally_" + attackerId(attacker), gameTimeOf(attacker),
                    "ally check failed (caught, treated as ally, no knockback this time): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return true;
        }
    }

    /** 是否明确敌对（判定失败按“不是敌对”处理，交给后面的规则） */
    public static boolean isExplicitHostile(LivingEntity entity) {
        try {
            return entity instanceof Monster;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 以 caster 为中心 inflate(radius) 的扫描盒内找一个“可以打的目标”（r37 新增）。
     * 口径与各技能源头过滤一致：跳过自己/已死/已移除/无敌单位，AllyCheck.isAlly 判友军；
     * 找不到返回 null——贴脸系技能（怪怖之锚震击/超立方体）靠它实现
     * “距离内没有敌人不释放、不进冷却”，异常时按“没敌人”兜底（宁可少放一次）。
     */
    public static LivingEntity findEnemyNear(LivingEntity caster, double radius) {
        try {
            if (caster == null || caster.level().isClientSide) {
                return null;
            }
            AABB area = caster.getBoundingBox().inflate(radius);
            for (LivingEntity candidate : caster.level().getEntitiesOfClass(LivingEntity.class, area)) {
                if (candidate == caster || !candidate.isAlive() || candidate.isRemoved() || candidate.isInvulnerable()) {
                    continue;
                }
                if (isAlly(caster, candidate)) {
                    continue;
                }
                return candidate;
            }
            return null;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_enemy_scan_" + (caster == null ? "null" : caster.getUUID()),
                    gameTimeOf(caster),
                    "trigger distance scan failed (caught, treated as 'no enemy', not cast this time): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
            return null;
        }
    }

    /** 主人 UUID：TamableAnimal（现代同伴基类也走这条）优先，其次通用 OwnableEntity；取不到返回 null */
    private static UUID ownerOf(LivingEntity entity) {
        try {
            if (entity instanceof TamableAnimal tame) {
                return tame.getOwnerUUID();
            }
            if (entity instanceof OwnableEntity ownable) {
                return ownable.getOwnerUUID();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String attackerId(LivingEntity attacker) {
        try {
            return String.valueOf(attacker.getUUID());
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static long gameTimeOf(LivingEntity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}
