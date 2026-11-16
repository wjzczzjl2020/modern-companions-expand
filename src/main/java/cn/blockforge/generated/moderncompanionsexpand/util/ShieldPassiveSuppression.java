package cn.blockforge.generated.moderncompanionsexpand.util;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import net.minecraft.world.entity.Entity;

/**
 * 传奇怪物·特殊盾牌"格挡被动"的同伴豁免判定（r40 需求②）。
 *
 * 被三个 Mixin 在处理器 HEAD 调用：返回 true = 格挡者是同伴且豁免开关开着，
 * 调用方立即 ci.cancel() 掐掉【整个】事件处理器——只砍被动，
 * 普通举盾格挡（减伤、挡箭、掉耐久、blocking 外观）是原版 LivingEntity 逻辑，一概不动。
 *
 * 兜底原则（与全工程 SafeGuard 一致）：
 * - 任何异常一律按"不豁免"处理——宁可回到传奇怪物原版行为，也绝不让伤害事件链崩掉；
 * - 命中豁免时打一条限流日志（盾牌名+同伴实体类为 key，10 秒一条），
 *   说明"哪个盾牌的什么被动被豁免了"，方便排查。
 */
public final class ShieldPassiveSuppression {

    private ShieldPassiveSuppression() {}

    /**
     * @param shieldName  盾牌中文名（进日志）
     * @param passiveDesc 被豁免的被动描述（进日志）
     * @param blocker     格挡者实体（判定是否同伴）
     * @return true = 调用方应取消整个处理器
     */
    public static boolean shouldSuppress(String shieldName, String passiveDesc, Entity blocker) {
        try {
            if (blocker == null) {
                return false;
            }
            if (!MceConfig.shieldPassiveDisabledForCompanions()) {
                return false;
            }
            if (!CompanionDetector.isCompanion(blocker)) {
                return false;
            }
            long nowTick = -1L;
            try {
                if (blocker.level() != null) {
                    nowTick = blocker.level().getGameTime();
                }
            } catch (Throwable ignored) {
                // 拿不到游戏时刻就用 -1 兜底（限流表退化为"每个 key 只打一条"，无碍）
            }
            SafeGuard.infoThrottled("mce_shield_suppress_" + shieldName + "_" + blocker.getClass().getSimpleName(),
                    nowTick,
                    "companion exemption: shield [" + shieldName + "] blocking passive (" + passiveDesc + ") did not fire for the companion "
                            + blocker.getClass().getSimpleName() + " -- normal blocking damage reduction / arrow blocking / durability / visuals unaffected; "
                            + "player blocking still fires as usual. To restore vanilla behaviour (companions trigger too), "
                            + "set the config shield_passive_disabled_for_companions to false");
            return true;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_shield_suppress_err_" + shieldName, -1L,
                    "the shield companion-exemption check threw (caught, the damage event is released per vanilla behaviour): "
                            + shieldName + " / " + t.getClass().getSimpleName() + " / " + t.getMessage());
            return false;
        }
    }
}
