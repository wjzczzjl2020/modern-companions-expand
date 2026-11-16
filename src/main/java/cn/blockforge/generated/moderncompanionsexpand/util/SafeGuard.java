package cn.blockforge.generated.moderncompanionsexpand.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 全模组唯一的安全出口：打日志、包异常、限流，全部在这一个类里。
 *
 * 设计目标（试点阶段）：
 * 1. 任何反射/外部模组代码抛出的异常都在这里被吞掉并记日志，绝不让游戏崩溃；
 * 2. 高频日志（比如“没有目标”“还在冷却”）按“同一个原因 + 同一条实体”限流，
 *    默认 200 tick（10 秒）内只打一条，防止刷爆日志文件；
 * 3. 日志本身如果再出问题（logger 为空等），也不会二次崩溃。
 */
public final class SafeGuard {

    public static final String PREFIX = "[MCE] ";

    private static volatile Logger logger = LoggerFactory.getLogger("modern_companions_expand");

    /** 限流表：key = 原因标识，value = 上次打印时的游戏 tick */
    private static final ConcurrentHashMap<String, Long> THROTTLE = new ConcurrentHashMap<>();

    /** 同 key 的最小打印间隔（tick），200 tick = 10 秒 */
    public static final long DEFAULT_LOG_INTERVAL = 200L;

    private SafeGuard() {}

    /** 可选：用 FML 注入的 Logger 替换默认 logger（当前用默认即可） */
    public static void init(Logger injected) {
        if (injected != null) {
            logger = injected;
        }
    }

    public static void info(String message) {
        try {
            logger.info(PREFIX + message);
        } catch (Throwable ignored) {
            // 日志系统本身出问题也不能崩
        }
    }

    public static void warn(String message) {
        try {
            logger.warn(PREFIX + message);
        } catch (Throwable ignored) {
        }
    }

    public static void warn(String message, Throwable t) {
        try {
            logger.warn(PREFIX + message, t);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String message, Throwable t) {
        try {
            logger.error(PREFIX + message, t);
        } catch (Throwable ignored) {
        }
    }

    /** 限流判定：返回 true 表示“这次可以打”，并刷新时间戳 */
    public static boolean shouldLog(String key, long nowTick, long intervalTick) {
        try {
            Long last = THROTTLE.get(key);
            if (last == null || nowTick - last >= intervalTick) {
                THROTTLE.put(key, nowTick);
                if (THROTTLE.size() > 8192) {
                    THROTTLE.clear();
                }
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void infoThrottled(String key, long nowTick, String message) {
        if (shouldLog(key, nowTick, DEFAULT_LOG_INTERVAL)) {
            info(message);
        }
    }

    public static void warnThrottled(String key, long nowTick, String message) {
        if (shouldLog(key, nowTick, DEFAULT_LOG_INTERVAL)) {
            warn(message);
        }
    }

    /**
     * 兜底执行：把任何一段逻辑包起来跑。
     * 出任何异常（包括 Error）都只记日志，不向上抛——这是“试点不崩游戏”的最后防线。
     */
    public static void guard(String stage, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            error("stage [" + stage + "] caught an exception, it was intercepted and the game is unaffected. exception type: "
                    + t.getClass().getName() + " / " + t.getMessage(), t);
        }
    }
}
