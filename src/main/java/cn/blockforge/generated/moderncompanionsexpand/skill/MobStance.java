package cn.blockforge.generated.moderncompanionsexpand.skill;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 姿态反射小工具：安全读取 Mob（含现代同伴）上的无参布尔方法。
 *
 * 现代同伴（AbstractHumanCompanionEntity）有 public isFollowing()/isGuarding()，
 * 坐着的 isOrderedToSit() 在原版 TamableAnimal 上。方法沿类继承链向上找，
 * 找到就缓存；找不到（不是这类生物/版本改名）返回 -1，调用方跳过该项检查——
 * 普通怪物只用通用三项，绝不报错。
 */
public final class MobStance {

    /** 缓存：类名#方法名 → 方法（NULL 哨兵表示确认不存在，别再翻） */
    private static final Map<String, Method> FOUND = new ConcurrentHashMap<>();
    private static final Method ABSENT = sentinel();
    private static final byte UNKNOWN = -1;

    private MobStance() {}

    /** 1=true，0=false，-1=读不到（跳过比对） */
    public static byte readFlag(Object owner, String methodName) {
        try {
            Class<?> cls = owner.getClass();
            String key = cls.getName() + "#" + methodName;
            Method cached = FOUND.get(key);
            if (cached == null) {
                Method m = findMethod(cls, methodName);
                if (m == null) {
                    m = ABSENT;
                }
                cached = m;
                FOUND.put(key, m);
            }
            if (cached == ABSENT) {
                return UNKNOWN;
            }
            Object v = cached.invoke(owner);
            if (v instanceof Boolean b) {
                return (byte) (b ? 1 : 0);
            }
            return UNKNOWN;
        } catch (Throwable t) {
            return UNKNOWN;
        }
    }

    private static Method findMethod(Class<?> start, String name) {
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // 继续往上层找
            } catch (Throwable only) {
                // setAccessible 被模块系统拒绝时退回 public 查询
                try {
                    return c.getMethod(name);
                } catch (Throwable again) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 仅用作“该方法不存在”的占位哨兵（不会被调用） */
    private static Method sentinel() {
        try {
            return Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException impossible) {
            throw new InternalError(impossible);
        }
    }
}
