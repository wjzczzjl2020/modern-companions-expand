package cn.blockforge.generated.moderncompanionsexpand.util;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * “这个实体是不是现代同伴（Modern Companions）的同伴”统一判定。
 *
 * 编译期零依赖（和灾变反射层同样的策略）：本工程不链接现代同伴的任何类，
 * 只按类名链判定，判定结果按实体类缓存（一类只翻一次继承链）：
 * 1. 继承链上出现 com.majorbonghits.moderncompanions.entity.AbstractHumanCompanionEntity
 *    （现代同伴源码里所有职业同伴 Knight/Archer/Battlemage… 都挂在它下面）；
 * 2. 兜底：继承链上同时有 public boolean isFollowing() 与 isGuarding()
 *    两个无参方法的类也视为同伴基类（防未来版本改包名/类名，MobStance 依赖的正是这两个方法）。
 *
 * 现代同伴没装时两条都命中不了，全部返回 false，不影响任何判定。
 */
public final class CompanionDetector {

    /** 现代同伴源码核对：public abstract class AbstractHumanCompanionEntity extends TamableAnimal */
    public static final String COMPANION_BASE_CLASS =
            "com.majorbonghits.moderncompanions.entity.AbstractHumanCompanionEntity";

    /** 实体类名 → 是否同伴（缓存，避免每 tick 翻继承链） */
    private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private CompanionDetector() {}

    /** 是否现代同伴实体的实例（任何异常按“不是”处理，调用方还有通用兜底） */
    public static boolean isCompanion(Object entity) {
        if (entity == null) {
            return false;
        }
        try {
            Class<?> cls = entity.getClass();
            String key = cls.getName();
            if (CACHE.size() > 2048) {
                CACHE.clear(); // 防极端环境（动态生成类）把缓存撑爆
            }
            Boolean cached = CACHE.get(key);
            if (cached == null) {
                cached = probe(cls);
                CACHE.put(key, cached);
            }
            return cached;
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_companion_probe", -1L,
                    "companion check failed (caught, treated as 'not a companion'): " + t.getClass().getSimpleName()
                            + " / " + t.getMessage());
            return false;
        }
    }

    /** 清空缓存（/mce 自检或热重载时用得上，量极小无所谓性能） */
    public static void clearCache() {
        try {
            CACHE.clear();
        } catch (Throwable ignored) {
        }
    }

    private static boolean probe(Class<?> start) {
        try {
            for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
                if (COMPANION_BASE_CLASS.equals(c.getName())) {
                    return true;
                }
                if (hasNoArgBoolean(c, "isFollowing") && hasNoArgBoolean(c, "isGuarding")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 类加载/反射被拒绝：按“不是同伴”处理
        }
        return false;
    }

    /** 该类是否【直接声明】了返回 boolean 的无参方法（沿链逐类查，不查 Object） */
    private static boolean hasNoArgBoolean(Class<?> c, String methodName) {
        try {
            Method m = c.getDeclaredMethod(methodName);
            return m.getReturnType() == boolean.class;
        } catch (Throwable t) {
            return false;
        }
    }
}
