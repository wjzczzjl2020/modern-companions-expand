package cn.blockforge.generated.moderncompanionsexpand.util;

/**
 * LivingEntity.lastHurt 的反射读写（1.21.1 官方映射下该字段为 protected、无公开 getter）。
 *
 * r24 起就在读它（ShredderTrace 台账），r26 影子无敌帧开始也要写它——读写集中到这一个类，
 * 字段名/失败处理只维护一处。Field 反射一次解析后缓存。
 *
 * 失败约定：get 返回 NaN（不是 -1——负数是合法伤害值，NaN 一定代表解析失败）；
 * set 返回 false。所有调用方按“失败 = 无法修正 = 放行兜底”处理。
 */
public final class LastHurtAccess {

    private static volatile java.lang.reflect.Field FIELD;

    private LastHurtAccess() {}

    /** 读目标当前 lastHurt；解析失败返回 NaN（调用方按放行兜底处理） */
    public static float get(net.minecraft.world.entity.LivingEntity victim) {
        try {
            java.lang.reflect.Field field = field();
            if (field == null) {
                return Float.NaN;
            }
            return field.getFloat(victim);
        } catch (Throwable t) {
            return Float.NaN;
        }
    }

    /** 写目标 lastHurt；成功返回 true（解析失败/字段丢失返回 false，调用方按放行兜底） */
    public static boolean set(net.minecraft.world.entity.LivingEntity victim, float value) {
        try {
            java.lang.reflect.Field field = field();
            if (field == null) {
                return false;
            }
            field.setFloat(victim, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.lang.reflect.Field field() {
        java.lang.reflect.Field field = FIELD;
        if (field != null) {
            return field;
        }
        try {
            field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("lastHurt");
            field.setAccessible(true);
            FIELD = field;
            return field;
        } catch (Throwable t) {
            SafeGuard.error("lastHurt reflection resolution failed (shadow invulnerability falls back to the 'let it through' default, back to r25 behaviour)", t);
            return null;
        }
    }
}
