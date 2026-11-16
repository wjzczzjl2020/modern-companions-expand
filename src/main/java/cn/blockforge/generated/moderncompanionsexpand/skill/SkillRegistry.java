package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能注册表：id → 实现。新技能接入 = 写一个 CompanionSkill 实现 + 在这里 register()
 * + 在优先级表（config/modern_companions_expand/skill_priority.json）加一行 id。
 *
 * 调度顺序不写死在代码里，来自 SkillPriorityConfig（可热重载）；
 * 表里出现但没注册的 id 会被忽略并限流告警；注册了但没进表的技能永远不会被触发。
 */
public final class SkillRegistry {

    public static final String INCINERATOR_ID = SkillPriorityConfig.SKILL_INCINERATOR;
    public static final String BULWARK_ID = SkillPriorityConfig.SKILL_BULWARK;
    public static final String INFERNAL_FORGE_ID = SkillPriorityConfig.SKILL_INFERNAL_FORGE;
    public static final String MEAT_SHREDDER_ID = SkillPriorityConfig.SKILL_MEAT_SHREDDER;

    private static final Map<String, CompanionSkill> BY_ID = new LinkedHashMap<>();

    private SkillRegistry() {}

    /** 注册顺序无所谓，实际执行顺序由优先级表决定 */
    public static void register(CompanionSkill skill) {
        BY_ID.put(skill.id(), skill);
        SafeGuard.info("skill registry: registered skills " + skill.id()
                + " (charge " + skill.chargeTicks() + " ticks configurable, cooldown "
                + skill.cooldownTicks() + " ticks configurable)");
    }

    public static CompanionSkill byId(String id) {
        return BY_ID.get(id);
    }

    /** 全部已注册技能（状态自检用） */
    public static List<CompanionSkill> all() {
        return new ArrayList<>(BY_ID.values());
    }

    /** 按优先级表排好序的技能列表；表中未知的 id 忽略并告警 */
    public static List<CompanionSkill> orderedByPriority() {
        List<CompanionSkill> out = new ArrayList<>();
        for (String id : SkillPriorityConfig.orderedIds()) {
            CompanionSkill skill = BY_ID.get(id);
            if (skill == null) {
                SafeGuard.warnThrottled("mce_unknown_skill_" + id, System.currentTimeMillis() / 50L,
                        "skill priority table: the id '" + id + "' has no skill implementation, ignored (see /mce skills list)");
                continue;
            }
            out.add(skill);
        }
        return out;
    }
}
