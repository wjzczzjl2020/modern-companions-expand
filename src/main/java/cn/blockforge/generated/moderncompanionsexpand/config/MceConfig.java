package cn.blockforge.generated.moderncompanionsexpand.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 试点配置（COMMON 类型）。
 * 游戏启动后生成文件：config/modern_companions_expand.toml
 *
 * 四个段：
 * - incinerator：炎葬技能（开关/冷却/扫描节奏）；
 * - charge：蓄力状态机通用参数（蓄力时长/脱距打断/打断重试延迟/炎葬累计伤害打断阈值）；
 * - bulwark：火焰壁垒技能（开关/冷却/冲锋期间是否钉住速度/是否击飞友军 bulwark_friendly_knockback）；
 * - infernal_forge：炼狱锻锤技能（开关/冷却 infernal_forge_cooldown_ticks，默认 80）；
 * - debug：调度器排查开关（scan_debug_log：每次扫描输出优先级顺序+每个技能判定原因，
 *   r55 起默认 false；配套限流参数 scan_log_min_interval_ticks / scan_log_repeat_ticks）。
 *
 * 日志量提示（r55）：debug 段的两个排查开关默认全关，正常游玩不会往 latest.log/debug.log
 * 刷技能扫描日志。临时排查用游戏内命令 /mce scan on（运行期覆盖，不写配置文件、不用重启；
 * /mce scan default 交还给配置文件，/mce scan status 看当前生效值）。
 *
 * 注意：冷却以“上次施放时刻 + 当前配置的冷却值”实时计算，
 * 所以把 cooldown_ticks 从 400 改成 20，正在冷却中的同伴会立刻变就绪，方便测试。
 * 技能释放顺序（优先级表）不在这里，在 config/modern_companions_expand/skill_priority.json，
 * 那份可以 /mce skills reload 热重载。
 */
public final class MceConfig {

    public static final ModConfigSpec SPEC;

    /** 炎葬试点技能总开关（关掉后调度器直接跳过该技能） */
    public static final ModConfigSpec.BooleanValue INCINERATOR_ENABLED;

    /** 炎葬技能冷却，单位 tick（20 tick = 1 秒）。灾变源码默认 400 tick = 20 秒 */
    public static final ModConfigSpec.IntValue INCINERATOR_COOLDOWN_TICKS;

    /** 检查节奏：每隔多少 tick 扫描一次“同伴是否该开始新技能”（5 tick，纯性能阀值） */
    public static final ModConfigSpec.IntValue INCINERATOR_CHECK_INTERVAL_TICKS;

    // —————— charge 段：蓄力状态机（对所有需要蓄力的技能生效）——————

    /** 蓄力时长 tick。灾变炎葬原版为按住 ≥60 tick（3 秒）才释放 */
    public static final ModConfigSpec.IntValue CHARGE_TICKS;

    /** 蓄力期间目标超出该距离（格）→ 视为打断（不消耗冷却） */
    public static final ModConfigSpec.DoubleValue CHARGE_BREAK_DISTANCE;

    /** 蓄力被打断后的重试延迟 tick。打断不消耗冷却，但先罚站这么久防刷屏 */
    public static final ModConfigSpec.IntValue INTERRUPT_RETRY_TICKS;

    /** 炎葬蓄力阶段累计受到多少点伤害才会打断；默认 15 点，低于阈值的受击继续蓄力。 */
    public static final ModConfigSpec.DoubleValue INCINERATOR_INTERRUPT_DAMAGE;

    // —————— bulwark 段：火焰壁垒冲锋 ——————

    /** 火焰壁垒技能总开关 */
    public static final ModConfigSpec.BooleanValue BULWARK_ENABLED;

    /** 壁垒技能冷却 tick。原版 80 tick 是给玩家的，同伴按用户要求默认 200 tick（10 秒） */
    public static final ModConfigSpec.IntValue BULWARK_COOLDOWN_TICKS;

    /** 冲锋窗口内是否每 tick 钉住速度（原版是纯物理滑行；实测位移太短再开，会偏离原版手感） */
    public static final ModConfigSpec.BooleanValue BULWARK_HOLD_VELOCITY;

    /** 冲锋是否照旧击飞友军。false（默认）= 玩家/其他同伴/主人的宠物不被击退也不吃冲锋伤害 */
    public static final ModConfigSpec.BooleanValue BULWARK_FRIENDLY_KNOCKBACK;

    // —————— infernal_forge 段：炼狱锻锤冲击波 ——————

    /** 炼狱锻锤技能总开关 */
    public static final ModConfigSpec.BooleanValue INFERNAL_FORGE_ENABLED;

    /** 炼狱锻锤技能冷却 tick。灾变源码 CMCommonConfig.InfernalForge.cooldown 默认 80 tick = 4 秒 */
    public static final ModConfigSpec.IntValue INFERNAL_FORGE_COOLDOWN_TICKS;

    // —————— soul_render 段：断魂战戟（冲刺 + 螺旋召唤双技能）——————

    /** 断魂战戟技能总开关 */
    public static final ModConfigSpec.BooleanValue SOUL_RENDER_ENABLED;

    /**
     * 断魂战戟技能冷却 tick。灾变源码 CMCommonConfig.SoulRender.cooldown 默认 100，
     * 冲刺与螺旋召唤两个分支都写这一个 cooldown（addCooldown(this, cooldown)）→ 同伴版共享。
     */
    public static final ModConfigSpec.IntValue SOUL_RENDER_COOLDOWN_TICKS;

    /** 情境判定阈值：目标比这更远 → 冲刺；更近（或被 ≥2 敌人包围）→ 螺旋召唤 */
    public static final ModConfigSpec.DoubleValue SOUL_RENDER_PREFER_DASH_DISTANCE;

    /**
     * 冲刺等效蓄力 t（原版 = 按住右键的 tick 数，clamp 0~60）：冲量 0.1*t、冲刺窗口 t/2 tick。
     * 默认 20 = 玩家按住 1 秒的常见力度（原版 4 秒按满 t=60 冲得过头，同伴场景太冲）。
     */
    public static final ModConfigSpec.IntValue SOUL_RENDER_DASH_T;

    /** 冲刺窗口内是否每 tick 钉住水平速度（同伴 AI 会和冲量抢方向盘，默认钉住保证冲得出去） */
    public static final ModConfigSpec.BooleanValue SOUL_RENDER_HOLD_VELOCITY;

    // —————— astrape 段：神怒长槊（鸣雷槊投射物）——————

    /** 神怒长槊技能总开关 */
    public static final ModConfigSpec.BooleanValue ASTRAPE_ENABLED;

    /** 神怒长槊技能冷却 tick。灾变源码 CMCommonConfig.Astrape.cooldown 默认 60 tick = 3 秒 */
    public static final ModConfigSpec.IntValue ASTRAPE_COOLDOWN_TICKS;

    // —————— ceraunus 段：霆浪锚戟（投掷 + 扇形波浪双技能）——————

    /** 霆浪锚戟技能总开关 */
    public static final ModConfigSpec.BooleanValue CERAUNUS_ENABLED;

    /** 投掷冷却 tick。原版没有冷却（靠 THROWN_ANCHOR 单实例节流），同伴版按确认默认 100 tick */
    public static final ModConfigSpec.IntValue CERAUNUS_THROW_COOLDOWN_TICKS;

    /** 波浪冷却 tick。灾变源码 CMCommonConfig.Ceraunus.cooldown 默认 150 tick = 7.5 秒 */
    public static final ModConfigSpec.IntValue CERAUNUS_WAVE_COOLDOWN_TICKS;

    /** 情境判定阈值：目标比这更近 → 优先波浪（更远 → 投掷） */
    public static final ModConfigSpec.DoubleValue CERAUNUS_PREFER_WAVE_DISTANCE;

    /** “被包围”判定：该半径内敌对生物达到这个数量 → 优先波浪 */
    public static final ModConfigSpec.IntValue CERAUNUS_WAVE_MIN_ENEMIES;

    /** 波浪扇形预扫描开关：扇形内有友军一律退回投掷（默认开，不冒险） */
    public static final ModConfigSpec.BooleanValue CERAUNUS_FRIENDLY_WAVE_BLOCK;

    // —————— void_forge 段：虚空锻锤（虚空符文五排布阵）——————

    /** 虚空锻锤技能总开关 */
    public static final ModConfigSpec.BooleanValue VOID_FORGE_ENABLED;

    /** 虚空锻锤冷却 tick。灾变源码 CMCommonConfig.VoidForge.cooldown 默认 100 tick = 5 秒 */
    public static final ModConfigSpec.IntValue VOID_FORGE_COOLDOWN_TICKS;

    // —————— brontes 段：掣雷巨锤（投掷 + 贴脸风暴）——————

    /** 掣雷巨锤技能总开关 */
    public static final ModConfigSpec.BooleanValue BRONTES_ENABLED;

    /**
     * 投掷/贴脸共用冷却 tick（原版没有冷却，靠“锤没回来不能再扔”节流，同伴按确认默认 100）。
     */
    public static final ModConfigSpec.IntValue BRONTES_THROW_COOLDOWN_TICKS;

    /** 贴脸阈值（格）：目标比这更近 → 不投掷，直接在敌人脚下生成风暴+火花。默认 6.0 */
    public static final ModConfigSpec.DoubleValue BRONTES_MELEE_RANGE;

    /** 投掷锁定开关：锤子飞出去期间禁止普通攻击（同伴版新增限制，默认开；false = 原版行为） */
    public static final ModConfigSpec.BooleanValue BRONTES_LOCK_ATTACK_WHILE_THROWN;

    /** 投掷超时 tick：锤子超过这么久没回来就强制恢复（默认 200 = 10 秒） */
    public static final ModConfigSpec.IntValue BRONTES_THROW_TIMEOUT_TICKS;

    // —————— meat_shredder 段：绞肉锯（持续绞肉）——————

    /** 绞肉锯技能总开关 */
    public static final ModConfigSpec.BooleanValue MEAT_SHREDDER_ENABLED;

    /** 绞肉锯冷却 tick：每次绞肉结束（正常收锯或被打断退出）后生效，默认 60 = 3 秒 */
    public static final ModConfigSpec.IntValue MEAT_SHREDDER_COOLDOWN_TICKS;

    /** 单次绞肉最长时间 tick，默认 200 = 10 秒（原版按住右键无上限，同伴版设上限做平衡税） */
    public static final ModConfigSpec.IntValue MEAT_SHREDDER_MAX_DURATION_TICKS;

    /** 自动触发射程（格）：目标比这更近才进绞肉，默认 3.0（对齐原版 2.5 格视线 + 0.5 盒外扩的实际射程） */
    public static final ModConfigSpec.DoubleValue MEAT_SHREDDER_RANGE;

    /** 副手有盾时是否允许绞肉（默认 true 豁免，与断魂战戟一致；false = 回到原版“举盾互斥”） */
    public static final ModConfigSpec.BooleanValue MEAT_SHREDDER_ALLOW_WITH_SHIELD;

    /** 绞肉期间减速（默认 true：移速 ×0.2 临时修饰，模拟原版按住右键时输入×0.2 的站桩手感） */
    public static final ModConfigSpec.BooleanValue MEAT_SHREDDER_SLOWDOWN;

    /** 只打敌对目标（默认 true：目标须为 Monster / 正在攻击同伴的实体；预扫描遇被动生物退回普攻） */
    public static final ModConfigSpec.BooleanValue MEAT_SHREDDER_HOSTILE_ONLY;

    /**
     * r25 错峰窗口：绞肉进行中，其他同伴对同一目标的 AoE 技能暂缓释放的最长等待 tick（默认 100 = 5 秒）。
     * 绞肉结束/目标切换立即放行，超过窗口强制放行；0 = 整个错峰功能停用。
     */
    public static final ModConfigSpec.IntValue MEAT_SHREDDER_AOE_DEFER_TICKS;

    /**
     * r26 影子无敌帧开关（默认开）：绞肉期间在内存里维护“假如没有绞肉污染，目标无敌帧本该多少”的
     * 影子账本，非绞肉伤害进 hurt() 入口前按影子值修正（拦/补/放行），收锯时落回实际字段——
     * 队友的大额伤害回到原版限速，绞肉自身的 1 点/tick 特色不受影响。false = 整体停用，回到 r25 行为。
     */
    public static final ModConfigSpec.BooleanValue MEAT_SHREDDER_IFRAME_RESTORE;

    // —————— soul_great_sword 段：灵魂巨剑（传奇怪物：被动格挡 + 幻影匕首）——————

    /** 灵魂巨剑技能总开关（匕首 + 格挡一起管） */
    public static final ModConfigSpec.BooleanValue SOUL_GREAT_SWORD_ENABLED;

    /** 被动格挡总开关 */
    public static final ModConfigSpec.BooleanValue SOUL_GREAT_SWORD_PARRY_ENABLED;

    /** 格挡冷却 tick（拍板 200 = 10 秒），存 persistentData 独立于匕首冷却 */
    public static final ModConfigSpec.IntValue SOUL_GREAT_SWORD_PARRY_COOLDOWN_TICKS;

    /** 血量阈值：同伴血量低于最大生命 × 该值才允许触发格挡（拍板 0.8） */
    public static final ModConfigSpec.DoubleValue SOUL_GREAT_SWORD_PARRY_HEALTH_THRESHOLD;

    /** 单次伤害阈值：来伤 ≥ 该值才触发格挡（拍板 4.0，成本不高，做了） */
    public static final ModConfigSpec.DoubleValue SOUL_GREAT_SWORD_PARRY_MIN_DAMAGE;

    /** 幻影匕首冷却 tick（拍板 120 = 6 秒），也是调度器层面的技能冷却 */
    public static final ModConfigSpec.IntValue SOUL_GREAT_SWORD_DAGGER_COOLDOWN_TICKS;

    /**
     * r29：幻影匕首是否保留“非玩家 owner 分支”（最大生命 1% 附加 + Soul Fracture + owner 回 3 点）。
     * 默认 false = 强制走玩家 owner 分支（只有基础 8 点 + 正常结算）
     */
    public static final ModConfigSpec.BooleanValue SOUL_GREAT_SWORD_DAGGER_MONSTER_BRANCH;

    // —————— tesseract 段：超立方体（传奇怪物：五角星湮灭传送门）——————

    /** 超立方体技能总开关 */
    public static final ModConfigSpec.BooleanValue TESSERACT_ENABLED;

    /**
     * 超立方体冷却 tick（拍板 150 = 7.5 秒，原版 cooldown() = MathUtils.toTicks(7.5f) 照抄），
     * 原版绑 Player.getCooldowns() → 同伴版存 persistentData
     */
    public static final ModConfigSpec.IntValue TESSERACT_COOLDOWN_TICKS;

    /** 蓄力门槛 tick（拍板照抄原版 maxChargeTick() = 30 = 1.5 秒），蓄满即发 */
    public static final ModConfigSpec.IntValue TESSERACT_CHARGE_TICKS;

    /** 超立方体自动触发距离（r37 修复：距离内没有敌人不释放，默认 6.0 覆盖内环 4 格 + 中心门） */
    public static final ModConfigSpec.DoubleValue TESSERACT_TRIGGER_DISTANCE;

    // —————— buckler 段：湮灭圆盾（传奇怪物：撞击 + 击飞 + 3 枚湮灭爆弹）——————

    /** 湮灭圆盾技能总开关 */
    public static final ModConfigSpec.BooleanValue BUCKLER_ENABLED;

    /** 命中冷却 tick（拍板 200 = 10 秒：撞击至少打中一个敌人才进这条） */
    public static final ModConfigSpec.IntValue BUCKLER_COOLDOWN_TICKS;

    /** 打空冷却 tick（拍板 60 = 3 秒：撞击一个敌人都没碰到时进这条，鼓励再试） */
    public static final ModConfigSpec.IntValue BUCKLER_MISS_COOLDOWN_TICKS;

    /** 撞击伤害（原版 10 × SlashDamageMultiplier，同伴版直接一个数值，默认照抄 10.0） */
    public static final ModConfigSpec.DoubleValue BUCKLER_SLASH_DAMAGE;

    /** 撞击判定盒前伸距离（原版 r = 1.75f 照抄，几何为 AABB 矩形板不加扇形） */
    public static final ModConfigSpec.DoubleValue BUCKLER_SLASH_RANGE;

    /** 每枚湮灭爆弹伤害（原版 8 × ProjectileDamageMultiplier，同伴版直接一个数值，默认照抄 8.0） */
    public static final ModConfigSpec.DoubleValue BUCKLER_BOMB_DAMAGE;

    /** 击飞保底水平位移（拍板 0.6：原版纯乘法 ×1.25 对静止目标无效，加保底让技能有存在感） */
    public static final ModConfigSpec.DoubleValue BUCKLER_KNOCKBACK_MIN_HORIZONTAL;

    /** 击飞保底垂直位移（拍板 0.3） */
    public static final ModConfigSpec.DoubleValue BUCKLER_KNOCKBACK_MIN_VERTICAL;

    /** 自动触发距离（格）：目标比这更远不自动放（圆盾是贴脸反击向技能，远处白扔爆弹浪费冷却） */
    public static final ModConfigSpec.DoubleValue BUCKLER_TRIGGER_DISTANCE;

    // —————— monstrous_anchor 段：怪怖之锚（传奇怪物：震击晕眩 + 攻击附带 AOE）——————

    /** 怪怖之锚技能总开关（震击 + 攻击附带 AOE 一起管） */
    public static final ModConfigSpec.BooleanValue MONSTROUS_ANCHOR_ENABLED;

    /**
     * 震击冷却 tick（拍板 200 = 10 秒，源码 100 对同伴太短会控到死），
     * 原版绑 Player.getCooldowns() → 同伴版存 persistentData
     */
    public static final ModConfigSpec.IntValue MONSTROUS_ANCHOR_SHOCKWAVE_COOLDOWN_TICKS;

    /** 攻击附带 AOE 的单次伤害（原版 6 × AbilityDamageMultiplier，同伴版直接一个数值，默认照抄 6.0） */
    public static final ModConfigSpec.DoubleValue MONSTROUS_ANCHOR_AOE_DAMAGE;

    /** 攻击附带 AOE 的半径（原版以被击中目标为中心 inflate(3) 照抄） */
    public static final ModConfigSpec.DoubleValue MONSTROUS_ANCHOR_AOE_RADIUS;

    /** 攻击附带 AOE 的最小触发间隔 tick（拍板 10：一刀多目标不再重复触发） */
    public static final ModConfigSpec.IntValue MONSTROUS_ANCHOR_AOE_MIN_INTERVAL_TICKS;

    /** 震击自动触发距离（r37 修复：距离内没有敌人不释放，默认 4.0 覆盖 3 格 AABB） */
    public static final ModConfigSpec.DoubleValue MONSTROUS_ANCHOR_SHOCKWAVE_TRIGGER_DISTANCE;

    // —————— axe_of_lightning 段：雷霆之钺（传奇怪物：雷击波 + 电爆冲击波合并技能）——————

    /** 雷霆之钺技能总开关 */
    public static final ModConfigSpec.BooleanValue AXE_OF_LIGHTNING_ENABLED;

    /** 雷击波/电爆冲击波共享冷却 tick（拍板 150 = 7.5 秒，两个分支同一把） */
    public static final ModConfigSpec.IntValue AXE_OF_LIGHTNING_COOLDOWN_TICKS;

    /** 模式：auto（情境判定）/ bolts（锁雷击波）/ shockwave（锁电爆冲击波），默认 auto */
    public static final ModConfigSpec.ConfigValue<String> AXE_OF_LIGHTNING_MODE;

    /** 每根雷击波柱的伤害（原版 8 × BoltDamageMultiplier，同伴版直接一个数值，默认照抄 8.0） */
    public static final ModConfigSpec.DoubleValue AXE_OF_LIGHTNING_BOLT_DAMAGE;

    /** auto 情境判定：目标比这更近才算“近身”（默认 6.0；更远一律雷击波） */
    public static final ModConfigSpec.DoubleValue AXE_OF_LIGHTNING_MELEE_DISTANCE;

    /** auto 情境判定：“多敌”阈值——5 格内敌对达到该数量才在近身时放电爆冲击波（默认 2） */
    public static final ModConfigSpec.IntValue AXE_OF_LIGHTNING_SHOCKWAVE_MIN_ENEMIES;

    // —————— shield_passive 段：传奇怪物·特殊盾牌格挡被动对同伴的豁免 ——————

    /**
     * true（默认）= 同伴持盾格挡时不触发霜冻之盾/潜影之盾/尖刺之盾的格挡被动；
     * false = 恢复传奇怪物原版行为（同伴格挡也触发）。玩家格挡两种情况下都照常触发。
     */
    public static final ModConfigSpec.BooleanValue SHIELD_PASSIVE_DISABLED_FOR_COMPANIONS;

    // —————— debug 段：调度器排查开关 ——————

    /**
     * 调度器扫描日志：每次扫描输出一行“优先级顺序 + 每个技能被选中/跳过的具体原因”。
     * r55 起默认 false（旧版默认 true 是 latest.log/debug.log 刷到 100M+ 的主因之一）。
     */
    public static final ModConfigSpec.BooleanValue SCAN_DEBUG_LOG;

    /**
     * r24 排查开关：绞肉×断魂伤害暴涨的三路追踪日志（onUseTick 计数 / 伤害台账 / 闸门失效预警）。
     * r55 起默认 false。
     */
    public static final ModConfigSpec.BooleanValue SHREDDER_TRACE_LOG;

    /** r55：扫描日志——同一同伴两行日志之间的最小间隔（tick，判定阶段变了时用） */
    public static final ModConfigSpec.IntValue SCAN_LOG_MIN_INTERVAL_TICKS;

    /** r55：扫描日志——结论一直没变时，每隔多少 tick 再提醒一行 */
    public static final ModConfigSpec.IntValue SCAN_LOG_REPEAT_TICKS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Incinerator companion active skill (pilot)", "炎葬（The Incinerator）同伴主动技能试点").push("incinerator");

        INCINERATOR_ENABLED = builder
                .comment("master switch: false = companions never try to cast Incinerator", "总开关：false 时同伴完全不尝试释放炎葬技能")
                .translation("modern_companions_expand.config.incinerator.enabled")
                .define("enabled", true);

        INCINERATOR_COOLDOWN_TICKS = builder
                .comment("skill cooldown (ticks, 20 ticks = 1 second). Cataclysm source default is 400 = 20 seconds; use 20~60 while testing", "技能冷却（tick，20 tick = 1 秒）。灾变源码默认 400 = 20 秒；测试时可临时改成 20~60")
                .translation("modern_companions_expand.config.incinerator.cooldown")
                .defineInRange("cooldown_ticks", 400, 0, 72000);

        INCINERATOR_CHECK_INTERVAL_TICKS = builder
                .comment("how many ticks between checks for whether a companion may start a new skill (lower = snappier, higher = cheaper; minimum 1). Note: a charge/charge window already in progress still advances every tick and is not affected by this", "每隔多少 tick 检查一次同伴是否可以开始新技能（调小更跟手，调大更省性能；最小 1）。"
                        + "注意：已在进行的蓄力/冲锋仍是每 tick 推进，不受这里影响")
                .translation("modern_companions_expand.config.incinerator.check_interval")
                .defineInRange("check_interval_ticks", 5, 1, 100);

        builder.pop();

        builder.comment("charge state machine (shared)", "蓄力状态机（通用）").push("charge");

        CHARGE_TICKS = builder
                .comment("ticks required to charge (60 = 3 seconds, matching the vanilla Incinerator releaseUsing gate of >= 60). 0 = no charge needed", "蓄力需要多少 tick（60 = 3 秒，对齐灾变炎葬原版 releaseUsing 的 ≥60 才释放）。0 = 不需要蓄力")
                .translation("modern_companions_expand.config.charge.ticks")
                .defineInRange("charge_ticks", 60, 0, 2000);

        CHARGE_BREAK_DISTANCE = builder
                .comment("how far the target may run before the charge breaks (blocks). Default 32 = the vanilla AI lock-on distance", "蓄力期间目标跑多远就打断（格）。默认 32 = 原版 AI 锁距")
                .translation("modern_companions_expand.config.charge.break_distance")
                .defineInRange("charge_break_distance", 32.0D, 4.0D, 128.0D);

        INTERRUPT_RETRY_TICKS = builder
                .comment("retry delay after a charge is interrupted (ticks). An interrupt costs no cooldown, but the companion stands still for this long before starting a new charge, which prevents an endless charge loop", "蓄力被打断后的重试延迟（tick）。打断不消耗冷却，但罚站这段时间不再开始蓄力，防止无限蓄力刷屏")
                .translation("modern_companions_expand.config.charge.retry_ticks")
                .defineInRange("interrupt_retry_ticks", 40, 0, 2000);

        INCINERATOR_INTERRUPT_DAMAGE = builder
                .comment("how much accumulated damage during an Incinerator charge breaks it. Default 15.0; hits below the threshold do not interrupt the charge", "炎葬蓄力期间累计受到多少点伤害才打断。默认 15.0；低于阈值的受击不会打断蓄力")
                .translation("modern_companions_expand.config.charge.incinerator_interrupt_damage")
                .defineInRange("incinerator_interrupt_damage", 15.0D, 0.0D, 1000.0D);

        builder.pop();

        builder.comment("Bulwark of the Flame companion charge skill", "火焰壁垒（Bulwark of the Flame）同伴冲锋技能").push("bulwark");

        BULWARK_ENABLED = builder
                .comment("master switch: false = companions never try to use the Bulwark of the Flame charge", "总开关：false 时同伴完全不尝试释放火焰壁垒冲锋")
                .translation("modern_companions_expand.config.bulwark.enabled")
                .define("enabled", true);

        BULWARK_COOLDOWN_TICKS = builder
                .comment("Bulwark skill cooldown (ticks, 20 ticks = 1 second). 200 = 10 seconds by our convention (the vanilla 80 is meant for players); use 20 while testing", "壁垒技能冷却（tick，20 tick = 1 秒）。按约定默认 200 = 10 秒（原版 80 是给玩家的）；测试可临时改成 20")
                .translation("modern_companions_expand.config.bulwark.cooldown")
                .defineInRange("bulwark_cooldown_ticks", 200, 0, 72000);

        BULWARK_HOLD_VELOCITY = builder
                .comment("whether the velocity is pinned back to the initial impulse every tick inside the 8-tick charge window (vanilla is pure physical sliding and slows down). Enable only if the measured travel is too short; it makes the feel deviate from vanilla", "冲锋的 8 tick 窗口内是否每 tick 把速度钉回初始冲量（原版是纯物理滑行，会越滑越慢）。"
                        + "实测位移太短再打开；打开后手感会偏离原版")
                .translation("modern_companions_expand.config.bulwark.hold_velocity")
                .define("bulwark_hold_velocity", false);

        BULWARK_FRIENDLY_KNOCKBACK = builder
                .comment("whether the charge also flings allies as in vanilla Cataclysm. Default false = ally protection on: players, other companions and pets of the caster's owner are not flung or velocity-pinned and take no charge damage (units outside the vanilla ally check are protected too); true = back to vanilla Cataclysm behaviour (only the vanilla isAlliedTo is honoured, useful to reproduce 'allies get flung')", "冲锋是否按灾变原版那样也击飞友军。默认 false = 开启友军保护："
                        + "玩家、其他同伴、施法者主人的宠物撞到冲锋时不会被击飞/钉速度，也不结算冲锋伤害（原版友军判定之外的单位也保护）；"
                        + "true = 回到灾变原版行为（只认原版 isAlliedTo，测试“撞飞友军”现象时可用）")
                .translation("modern_companions_expand.config.bulwark.friendly_knockback")
                .define("bulwark_friendly_knockback", false);

        builder.pop();

        builder.comment("Infernal Forge companion shockwave skill", "炼狱锻锤（Infernal Forge）同伴冲击波技能").push("infernal_forge");

        INFERNAL_FORGE_ENABLED = builder
                .comment("master switch: false = companions never try to release the Infernal Forge shockwave", "总开关：false 时同伴完全不尝试释放炼狱锻锤冲击波")
                .translation("modern_companions_expand.config.infernal_forge.enabled")
                .define("enabled", true);

        INFERNAL_FORGE_COOLDOWN_TICKS = builder
                .comment("shockwave cooldown (ticks, 20 ticks = 1 second). Cataclysm source default is 80 = 4 seconds; use 20 while testing. The shockwave is always centred on the companion with radius 4 blocks (9x9x9), damage is read from the companion's attack attribute (same basis as vanilla), and allies (players / other companions / owner-chain pets) take neither damage nor knockback", "冲击波冷却（tick，20 tick = 1 秒）。灾变源码默认 80 = 4 秒；测试可临时改成 20。"
                        + "冲击波固定以同伴为中心半径 4 格（9×9×9），伤害读同伴攻击力属性（与原版同口径），"
                        + "友军（玩家/其他同伴/主人链宠物）不受伤也不被弹飞")
                .translation("modern_companions_expand.config.infernal_forge.cooldown")
                .defineInRange("infernal_forge_cooldown_ticks", 80, 0, 72000);

        builder.pop();

        builder.comment("Soul Render companion dash + spiral summon", "断魂战戟（Soul Render）同伴冲刺 + 螺旋召唤双技能").push("soul_render");

        SOUL_RENDER_ENABLED = builder
                .comment("master switch: false = companions never try the Soul Render dash / spiral summon", "总开关：false 时同伴完全不尝试释放断魂战戟的冲刺/螺旋召唤")
                .translation("modern_companions_expand.config.soul_render.enabled")
                .define("enabled", true);

        SOUL_RENDER_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second). Cataclysm source default is 100 = 5 seconds, shared by the dash and the spiral summon (vanilla writes both branches to the same cooldown); use 20 while testing. Stored in persistentData; it still casts with a shield in the off hand (the companion version does not go through the vanilla blocking entry)", "冷却（tick，20 tick = 1 秒）。灾变源码默认 100 = 5 秒，冲刺与螺旋召唤共享（原版两个分支写同一个 cooldown）；"
                        + "测试可临时改成 20。冷却存 persistentData，副手有盾牌也照常释放（同伴版不经过原版举盾入口）")
                .translation("modern_companions_expand.config.soul_render.cooldown")
                .defineInRange("soul_render_cooldown_ticks", 100, 0, 72000);

        SOUL_RENDER_PREFER_DASH_DISTANCE = builder
                .comment("mode threshold (blocks): target further away than this -> dash; target closer, or 2+ hostile mobs within 5 blocks (surrounded) -> spiral summon. Default 4.0", "情境判定阈值（格）：目标比这更远 → 冲刺；目标更近、或 5 格内敌对生物 ≥ 2（被包围）→ 螺旋召唤。默认 4.0")
                .translation("modern_companions_expand.config.soul_render.prefer_dash_distance")
                .defineInRange("soul_render_prefer_dash_distance", 4.0D, 0.0D, 64.0D);

        SOUL_RENDER_DASH_T = builder
                .comment("equivalent dash charge t (vanilla = ticks the player held right click, max 60): impulse 0.1*t, dash window t/2 ticks, dash damage read from the attack attribute (same basis as vanilla). Default 20 = the strength a player usually reaches after holding 1 second", "冲刺等效蓄力 t（原版 = 玩家按住右键的 tick 数，最大 60）：冲量 0.1*t、冲刺窗口 t/2 tick、"
                        + "冲刺伤害读攻击力属性（与原版同口径）。默认 20 = 原版按住 1 秒的常见力度")
                .translation("modern_companions_expand.config.soul_render.dash_t")
                .defineInRange("soul_render_dash_t", 20, 4, 60);

        SOUL_RENDER_HOLD_VELOCITY = builder
                .comment("whether horizontal velocity is pinned every tick inside the dash window (direction = the facing towards the target at the start). A companion's movement AI fights the dash impulse for the steering wheel, so the default true makes sure it actually moves; false = pure vanilla physical sliding", "冲刺窗口内是否每 tick 钉住水平速度（方向 = 起手时朝向目标的方向）。"
                        + "同伴的移动 AI 会和冲刺冲量抢方向盘，默认 true 保证冲得出去；false = 原版纯物理滑行手感")
                .translation("modern_companions_expand.config.soul_render.hold_velocity")
                .define("soul_render_hold_velocity", true);

        builder.pop();

        builder.comment("Astrape companion lightning spear skill", "神怒长槊（Astrape）同伴鸣雷槊技能").push("astrape");

        ASTRAPE_ENABLED = builder
                .comment("master switch: false = companions never try to fire the Astrape spear", "总开关：false 时同伴完全不尝试发射鸣雷槊")
                .translation("modern_companions_expand.config.astrape.enabled")
                .define("enabled", true);

        ASTRAPE_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second). Cataclysm source default is 60 = 3 seconds; use 20 while testing. Instant with no charge (the vanilla gate is only 12 ticks and does not affect damage, so companions count as fully charged); damage is read from Cataclysm CMCommonConfig.Astrape (default 11 direct, static field 2 per 5 ticks, unchanged per source); ally protection: the event-layer interception window starts at 160 ticks and extends automatically while the static field is still present, until the entity disappears", "冷却（tick，20 tick = 1 秒）。灾变源码默认 60 = 3 秒；测试可临时改成 20。"
                        + "瞬发无蓄力（原版蓄力门槛只有 12 tick 且不影响伤害，同伴直接视为蓄满）；"
                        + "伤害读灾变 CMCommonConfig.Astrape（默认直接 11 点，静电场 2 点/5 tick，按源码不改）；"
                        + "友军保护：事件层拦截窗口 160 tick 起步，静电场还在场时自动顺延直到实体消失")
                .translation("modern_companions_expand.config.astrape.cooldown")
                .defineInRange("astrape_cooldown_ticks", 60, 0, 72000);

        builder.pop();

        builder.comment("Ceraunus companion throw + fan wave skills", "霆浪锚戟（Ceraunus）同伴投掷 + 扇形波浪双技能").push("ceraunus");

        CERAUNUS_ENABLED = builder
                .comment("master switch: false = companions never try the Ceraunus throw / fan wave", "总开关：false 时同伴完全不尝试释放霆浪锚戟的投掷/波浪")
                .translation("modern_companions_expand.config.ceraunus.enabled")
                .define("enabled", true);

        CERAUNUS_THROW_COOLDOWN_TICKS = builder
                .comment("throw cooldown (ticks, 20 ticks = 1 second). Vanilla has no throw cooldown (it throttles through the THROWN_ANCHOR single instance, which companions do not use); confirmed default 100 = 5 seconds; use 20 while testing", "投掷冷却（tick，20 tick = 1 秒）。原版投掷没有冷却（靠 THROWN_ANCHOR 单实例节流，"
                        + "同伴不走那个组件），按确认默认 100 = 5 秒；测试可临时改成 20")
                .translation("modern_companions_expand.config.ceraunus.throw_cooldown")
                .defineInRange("ceraunus_throw_cooldown_ticks", 100, 0, 72000);

        CERAUNUS_WAVE_COOLDOWN_TICKS = builder
                .comment("wave cooldown (ticks). Cataclysm source CMCommonConfig.Ceraunus.cooldown defaults to 150 = 7.5 seconds and is independent of the throw cooldown (each stored in persistentData)", "波浪冷却（tick）。灾变源码 CMCommonConfig.Ceraunus.cooldown 默认 150 = 7.5 秒，"
                        + "与投掷冷却互相独立（各自存 persistentData）")
                .translation("modern_companions_expand.config.ceraunus.wave_cooldown")
                .defineInRange("ceraunus_wave_cooldown_ticks", 150, 0, 72000);

        CERAUNUS_PREFER_WAVE_DISTANCE = builder
                .comment("mode threshold (blocks): target closer than this -> prefer the fan wave; further away -> throw. Default 4.0. The waves always spawn 2 blocks in front of the caster, so a point-blank target (< 2.5 blocks) also falls back to the throw", "情境判定阈值（格）：目标比这更近 → 优先扇形波浪；更远 → 投掷。默认 4.0。"
                        + "波浪固定在施法者前方 2 格生成，目标贴脸（<2.5 格）时同样会退回投掷")
                .translation("modern_companions_expand.config.ceraunus.prefer_wave_distance")
                .defineInRange("ceraunus_prefer_wave_distance", 4.0D, 0.0D, 64.0D);

        CERAUNUS_WAVE_MIN_ENEMIES = builder
                .comment("surrounded check: this many hostile mobs within the radius (5 blocks) -> prefer the fan wave. Default 2", "“被包围”判定：该半径（5 格）内敌对生物达到这个数量 → 优先扇形波浪。默认 2")
                .translation("modern_companions_expand.config.ceraunus.wave_min_enemies")
                .defineInRange("ceraunus_wave_min_enemies", 2, 1, 16);

        CERAUNUS_FRIENDLY_WAVE_BLOCK = builder
                .comment("wave ally pre-scan (default true): the wave's physical push happens outside the damage event and cannot be intercepted -- so before casting, the vanilla fan (+-37.5 degrees, 5 blocks deep) is sampled and any ally inside it forces a fallback to the throw. false = vanilla behaviour (damage is intercepted at the event layer but allies still get nudged)", "波浪友军预扫描（默认 true）：波浪的物理击退发生在伤害事件之外，拦不住——"
                        + "所以释放前先按原版扇形（±37.5°、纵深 5 格）采样检查，扇形内有友军一律退回投掷。"
                        + "false = 回到“伤害靠事件层拦、友军仍会被推一下”的原版行为")
                .translation("modern_companions_expand.config.ceraunus.friendly_wave_block")
                .define("ceraunus_friendly_wave_block", true);

        builder.pop();

        builder.comment("Void Forge companion void rune layout skill", "虚空锻锤（Void Forge）同伴虚空符文布阵技能").push("void_forge");

        VOID_FORGE_ENABLED = builder
                .comment("master switch: false = companions never try to lay out void runes", "总开关：false 时同伴完全不尝试布下虚空符文")
                .translation("modern_companions_expand.config.void_forge.enabled")
                .define("enabled", true);

        VOID_FORGE_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second). Cataclysm source CMCommonConfig.VoidForge.cooldown defaults to 100 = 5 seconds; use 20 while testing. The vanilla cooldown gate sits in the packet layer, which companions do not use, so this is stored in persistentData; the layout is always 7 rays x 5 rows (1.75~8.75 blocks, each row 1 tick later), rune damage is read from Cataclysm CMCommonConfig.VoidForge.runeDamage (default 7)", "冷却（tick，20 tick = 1 秒）。灾变源码 CMCommonConfig.VoidForge.cooldown 默认 100 = 5 秒；"
                        + "测试可临时改成 20。原版的冷却门在发包层，同伴不走那条路，这里存 persistentData；"
                        + "布阵固定 7 条射线 × 5 排（1.75~8.75 格，每排晚 1 tick），符文伤害读灾变 CMCommonConfig.VoidForge.runeDamage（默认 7）")
                .translation("modern_companions_expand.config.void_forge.cooldown")
                .defineInRange("void_forge_cooldown_ticks", 100, 0, 72000);

        builder.pop();

        builder.comment("Brontes companion throw + point-blank storm branches", "掣雷巨锤（Brontes）同伴投掷 + 贴脸风暴双分支").push("brontes");

        BRONTES_ENABLED = builder
                .comment("master switch: false = companions never throw or start the point-blank storm", "总开关：false 时同伴完全不尝试投掷/贴脸风暴")
                .translation("modern_companions_expand.config.brontes.enabled")
                .define("enabled", true);

        BRONTES_THROW_COOLDOWN_TICKS = builder
                .comment("cooldown shared by throw / point-blank (ticks). Vanilla has none (the THROWN_HAMMER component throttles it: no second throw until the hammer returns); for companions the confirmed default is 100 = 5 seconds, covering the round trip; use 20 while testing", "投掷/贴脸共用冷却（tick）。原版没有冷却（靠 THROWN_HAMMER 组件节流，锤没回来不能再扔），"
                        + "同伴按确认默认 100 = 5 秒，覆盖锤子往返时间；测试可临时改成 20")
                .translation("modern_companions_expand.config.brontes.throw_cooldown")
                .defineInRange("brontes_throw_cooldown_ticks", 100, 0, 72000);

        BRONTES_MELEE_RANGE = builder
                .comment("point-blank threshold (blocks): target closer than this -> no throw (a hammer cannot hit something that close), instead spawn the lightning storm plus 8 radial sparks right under the enemy (the visuals match a vanilla hit). Default 6.0", "贴脸阈值（格）：目标比这更近 → 不投掷（锤子扔远打不中近身敌人），"
                        + "直接在敌人脚下生成闪电风暴 + 8 个放射火花（视觉与原版命中后一致）。默认 6.0")
                .translation("modern_companions_expand.config.brontes.melee_range")
                .defineInRange("brontes_melee_range", 6.0D, 0.0D, 32.0D);

        BRONTES_LOCK_ATTACK_WHILE_THROWN = builder
                .comment("throw lock (added for companions, default true): while the hammer is away the companion makes no melee attacks (it still repositions, follows and dodges; movement AI untouched, only normal attacks are cancelled); false = vanilla behaviour (melee as usual while throwing). The lock clears itself when the hammer returns, on timeout or on forced recovery; stored in persistentData and not reset across reloads", "投掷锁定（同伴版新增限制，默认 true）：锤子飞出去期间同伴不出手打人"
                        + "（继续走位/跟随/躲闪，移动 AI 不动，只取消普攻）；false = 回到原版行为（投掷期间照常近战）。"
                        + "锁定会随锤子回手、超时、强制恢复自动解除，存 persistentData，读档不重置")
                .translation("modern_companions_expand.config.brontes.lock_attack_while_thrown")
                .define("brontes_lock_attack_while_thrown", true);

        BRONTES_THROW_TIMEOUT_TICKS = builder
                .comment("throw timeout (ticks): if the hammer has been away longer than this and has not come back (entity lost / stuck in terrain / save reload), melee is restored forcibly and the hammer is treated as lost (default 200 = 10 seconds)", "投掷超时（tick）：锤子飞出去超过这么久还没回来（实体丢失/卡地形/存档重载等），"
                        + "强制恢复普攻并按“物品丢失”处理（默认 200 = 10 秒）")
                .translation("modern_companions_expand.config.brontes.throw_timeout")
                .defineInRange("brontes_throw_timeout_ticks", 200, 20, 72000);

        builder.pop();

        builder.comment("Meat Shredder companion sustained shredding skill", "绞肉锯（Meat Shredder）同伴持续绞肉技能").push("meat_shredder");

        MEAT_SHREDDER_ENABLED = builder
                .comment("master switch: false = companions never try to shred", "总开关：false 时同伴完全不尝试绞肉")
                .translation("modern_companions_expand.config.meat_shredder.enabled")
                .define("enabled", true);

        MEAT_SHREDDER_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second), applied after each shredding ends (stopped or interrupted). Vanilla has none (it depends on holding/releasing the button); the companion version pays 60 = 3 seconds of pure balance tax; use 20 while testing", "冷却（tick，20 tick = 1 秒）：每次绞肉结束（收锯/被打断退出）后生效。"
                        + "原版无冷却（全靠按住/松开），同伴版默认 60 = 3 秒纯平衡税；测试可临时改成 20")
                .translation("modern_companions_expand.config.meat_shredder.cooldown")
                .defineInRange("meat_shredder_cooldown_ticks", 60, 0, 72000);

        MEAT_SHREDDER_MAX_DURATION_TICKS = builder
                .comment("longest single shredding run (ticks). Vanilla has no limit while holding right click (getUseDuration=72000); the companion version defaults to 200 = 10 seconds; a huge value (e.g. 72000) effectively disables the cap", "单次绞肉最长时间（tick）。原版按住右键无上限（getUseDuration=72000），"
                        + "同伴版默认 200 = 10 秒；设为很大（如 72000）≈ 关闭上限制")
                .translation("modern_companions_expand.config.meat_shredder.max_duration")
                .defineInRange("meat_shredder_max_duration_ticks", 200, 20, 72000);

        MEAT_SHREDDER_RANGE = builder
                .comment("auto-trigger range (blocks): only enter shredding when the target is closer than this. Default 3.0, matching the real vanilla reach of a 2.5-block sight ray + 0.5 target-box inflation; the exit threshold is range + 0.5 to avoid flapping", "自动触发射程（格）：目标比这更近才进入绞肉。默认 3.0，"
                        + "对齐原版 2.5 格视线射线 + 0.5 目标盒外扩的实际射程；退出阈值 = 射程 + 0.5 防抖动")
                .translation("modern_companions_expand.config.meat_shredder.range")
                .defineInRange("meat_shredder_range", 3.0D, 1.0D, 16.0D);

        MEAT_SHREDDER_ALLOW_WITH_SHIELD = builder
                .comment("whether shredding is allowed with a shield in the off hand (default true = exempt, same as Soul Render: companions do not use the vanilla blocking entry; false = vanilla exclusivity, no shredding while an off-hand shield can block)", "副手有盾时是否允许绞肉（默认 true 豁免，与断魂战戟一致：同伴不经过原版举盾入口；"
                        + "false = 回到原版“副手能举盾就不许绞”的互斥）")
                .translation("modern_companions_expand.config.meat_shredder.allow_with_shield")
                .define("meat_shredder_allow_with_shield", true);

        MEAT_SHREDDER_SLOWDOWN = builder
                .comment("slowness while shredding (default true): attaches a temporary x0.2 movement-speed modifier (mimicking the vanilla standing-still input multiplier of x0.2 while holding right click, stacked with the stand-still command). false = no slowness", "绞肉期间减速（默认 true）：给移速挂 ×0.2 的临时修饰（模拟原版按住右键时"
                        + "输入×0.2 的站桩手感，与站桩指令叠加）。false = 不减速")
                .translation("modern_companions_expand.config.meat_shredder.slowdown")
                .define("meat_shredder_slowdown", true);

        MEAT_SHREDDER_HOSTILE_ONLY = builder
                .comment("hostile targets only (default true): the target must be a Monster (including Cataclysm and most mod monsters) or an entity currently attacking the companion; if the pre-scan finds a passive mob (livestock / villagers, etc.) in shredding range it falls back to normal attacks, to avoid shredding villagers. false = vanilla behaviour (anything in the search box gets shredded)", "只打敌对目标（默认 true）：目标必须为 Monster（含灾变/多数模组怪），"
                        + "或正在攻击同伴的实体；预扫描发现绞肉范围内有被动生物（牲畜/村民等）一律退回普攻，"
                        + "避免“绞到村民”。false = 回到原版“进搜索盒的都绞”")
                .translation("modern_companions_expand.config.meat_shredder.hostile_only")
                .define("meat_shredder_hostile_only", true);

        MEAT_SHREDDER_AOE_DEFER_TICKS = builder
                .comment("stagger window (ticks, r25 plan one): while one companion is shredding a target, other companions' AoE skills against that same target (Soul Render spiral, Incinerator pillars, Soul Render dash, Ceraunus waves, etc.) are deferred until the shredding ends or the target changes -- the shredder resets invulnerability ticks every tick, which would let teammates' large hits bypass the vanilla rate limit and land in full (confirmed in r24). Normal melee attacks are never restricted; beyond this window the skill is released by force so teammates cannot be locked out forever; 0 = disable staggering", "错峰窗口（tick，r25 拍板“方案一”）：某同伴正在绞肉一个目标时，其他同伴对同一目标的"
                        + "AoE 技能（断魂螺旋、炎葬火柱、断魂冲刺、霆浪波浪等）暂缓释放，等绞肉结束或目标切换再放——"
                        + "绞肉每 tick 重置无敌帧，会让队友的大额伤害绕过原版限速照单全收（r24 实锤）。"
                        + "普通近战攻击不受任何限制；超过本窗口强制放行，避免队友永久憋技能；0 = 停用错峰")
                .translation("modern_companions_expand.config.meat_shredder.aoe_defer_ticks")
                .defineInRange("meat_shredder_aoe_defer_ticks", 100, 0, 72000);

        MEAT_SHREDDER_IFRAME_RESTORE = builder
                .comment("shadow invulnerability (r26, default true): during shredding, a shadow ledger of the victim's invulnerability ticks / lastHurt is maintained as if no shredder were present, and non-shredder damage is corrected against the shadow values -- hits inside the shadow window are blocked to 0 entirely (matching clean vanilla), and only the part above the shadow rate gets through (compensation); once the shadow decays naturally, a fresh full window opens; when the shredder stops, the shadow values settle back into the real fields. The shredder's own 1 point per tick (about 20 DPS) is completely untouched; false = disable the shadow gate, back to the r25 stagger-only behaviour", "影子无敌帧（r26，默认 true）：绞肉期间为被绞目标维护一份“无绞肉污染时的无敌帧/lastHurt”"
                        + "影子账本，非绞肉伤害按影子值修正——无敌帧内的被整笔挡成 0（与干净原版一致），"
                        + "更疼的只生效超出影子限速的部分（补偿），影子自然衰减完后开新全额窗；收锯时影子值落回实际字段。"
                        + "绞肉自身的每 tick 1 点（约 20 DPS）完全不受影响；false = 停用影子闸门，回到 r25 错峰-only 行为")
                .translation("modern_companions_expand.config.meat_shredder.iframe_restore")
                .define("meat_shredder_iframe_restore", true);

        builder.pop();

        builder.comment("Soul Great Sword (Legendary Monsters) companion passive parry + Phantom Dagger", "灵魂巨剑（Soul Great Sword，传奇怪物）同伴被动格挡 + 幻影匕首").push("soul_great_sword");

        SOUL_GREAT_SWORD_ENABLED = builder
                .comment("master switch: false = companions never throw the Phantom Dagger and the passive parry is disabled as well", "总开关：false 时同伴完全不尝试释放幻影匕首，被动格挡也一并停用")
                .translation("modern_companions_expand.config.soul_great_sword.enabled")
                .define("enabled", true);

        SOUL_GREAT_SWORD_PARRY_ENABLED = builder
                .comment("passive parry switch (plan A, default true): the companion decides inside the hurt event whether to parry -- damage immunity + counterattack (attack + 30% of incoming damage) + a 5x6x5 counter AoE + 20% heal, all copied from vanilla; false = no parry, only the Phantom Dagger remains", "被动格挡开关（方案 A，默认 true）：同伴在受伤事件里自动判定是否格挡——"
                        + "免伤 + 反击（攻击力 + 来伤×30%）+ 5×6×5 反震 AoE + 回血 20% 全套照抄原版；"
                        + "false = 同伴不格挡，只剩幻影匕首")
                .translation("modern_companions_expand.config.soul_great_sword.parry_enabled")
                .define("soul_great_sword_parry_enabled", true);

        SOUL_GREAT_SWORD_PARRY_COOLDOWN_TICKS = builder
                .comment("parry cooldown (ticks). Decided default 200 = 10 seconds (vanilla player: 70 on success / 20 on failure; the companion's passive trigger is longer by decision); a failed parry (counterattack missed) always uses the 20-tick probe cooldown (copied from vanilla). Stored in persistentData, not reset across reloads", "格挡冷却（tick）。拍板默认 200 = 10 秒（原版玩家成功 70/失败 20，同伴被动触发按拍板加长）；"
                        + "格挡失败（反击未命中）固定进 20 tick 试探冷却（原版照抄）。存 persistentData，读档不重置")
                .translation("modern_companions_expand.config.soul_great_sword.parry_cooldown")
                .defineInRange("soul_great_sword_parry_cooldown_ticks", 200, 0, 72000);

        SOUL_GREAT_SWORD_PARRY_HEALTH_THRESHOLD = builder
                .comment("parry health threshold: the parry may only trigger while the companion's current health < max health x this value. Decided default 0.8 -- no parry at full or high health, so the cooldown is not wasted. 1.0 = always allowed", "格挡血量阈值：同伴当前血量 < 最大生命 × 该值才允许触发格挡。"
                        + "拍板默认 0.8——满血/高血量不格挡，避免浪费冷却。1.0 = 始终允许")
                .translation("modern_companions_expand.config.soul_great_sword.parry_health_threshold")
                .defineInRange("soul_great_sword_parry_health_threshold", 0.8D, 0.0D, 1.0D);

        SOUL_GREAT_SWORD_PARRY_MIN_DAMAGE = builder
                .comment("parry damage threshold: the parry triggers only when incoming damage >= this value (decided default 4.0, so small chip damage does not waste the cooldown). 0 = no threshold", "格挡单次伤害阈值：来伤 ≥ 该值才触发格挡（拍板默认 4.0，低额持续伤害不浪费冷却）。"
                        + "0 = 不限")
                .translation("modern_companions_expand.config.soul_great_sword.parry_min_damage")
                .defineInRange("soul_great_sword_parry_min_damage", 4.0D, 0.0D, 1000.0D);

        SOUL_GREAT_SWORD_DAGGER_COOLDOWN_TICKS = builder
                .comment("Phantom Dagger cooldown (ticks). The vanilla player.getCooldowns() is bound to Player, so the companion stores it in persistentData; decided default 120 = 6 seconds (same as vanilla) and it is also the scheduler-level skill cooldown. Since r29 the companion is forced onto the player-owner branch by default (8 base damage per hit, normal resolution), see soul_great_sword_dagger_monster_branch", "幻影匕首冷却（tick）。原版 player.getCooldowns() 绑 Player，同伴存 persistentData；"
                        + "拍板默认 120 = 6 秒（与原版一致），也是调度器层面的技能冷却。"
                        + "r29 起同伴默认强制走玩家 owner 分支（单发 8 点 + 正常结算），见 soul_great_sword_dagger_monster_branch")
                .translation("modern_companions_expand.config.soul_great_sword.dagger_cooldown")
                .defineInRange("soul_great_sword_dagger_cooldown_ticks", 120, 0, 72000);

        SOUL_GREAT_SWORD_DAGGER_MONSTER_BRANCH = builder
                .comment("r29: whether to keep the branch Legendary Monsters designed for mob owners (bonus damage of 1% of the target's max health, applying the Soul Fracture max-health reduction, owner heals 3 points per hit). Default false = the companion is forced onto the player-owner branch (only the base 8 damage with normal resolution, all bonuses removed); true = back to the mob branch (r28 behaviour, kept as-is)", "r29：幻影匕首是否保留传奇怪物给“怪物 owner”设计的分支（伤害附加目标最大生命 1%、"
                        + "施加 Soul Fracture 削血上限、owner 每次命中回 3 点血）。"
                        + "默认 false = 同伴强制走玩家 owner 分支（只保留基础 8 点伤害 + 正常结算，附加全部去掉）；"
                        + "true = 切回怪物分支（r28 行为，原样保留）")
                .translation("modern_companions_expand.config.soul_great_sword.dagger_monster_branch")
                .define("soul_great_sword_dagger_monster_branch", false);

        builder.pop();

        builder.comment("The Tesseract (Legendary Monsters) companion pentagram annihilation portals", "超立方体（The Tesseract，传奇怪物）同伴五角星湮灭传送门").push("tesseract");

        TESSERACT_ENABLED = builder
                .comment("master switch: false = companions never cast The Tesseract (the pull effect was dropped by decision; only the portal visuals + periodic damage + expiry explosion remain)", "总开关：false 时同伴完全不尝试释放超立方体（引力按拍板放弃，只保留传送门外观 + 周期伤害 + 到期爆炸）")
                .translation("modern_companions_expand.config.tesseract.enabled")
                .define("enabled", true);

        TESSERACT_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second). Vanilla cooldown() = 7.5 seconds = 150 ticks, copied by decision; use 20 while testing. Vanilla binds Player.getCooldowns(), the companion version stores it in persistentData and it survives reloads. Damage copies vanilla: outer/inner ring points 8 each, centre portal 12.5, one round every 5 ticks for 10 rounds, the last round is the expiry explosion; the centre portal's 43~52 tick shockwave is omitted in the first version by decision (vanilla shock damage is cancelled by the event-layer takeover gate)", "冷却（tick，20 tick = 1 秒）。原版 cooldown() = 7.5 秒 = 150 tick，拍板照抄；测试可临时改成 20。"
                        + "原版绑 Player.getCooldowns()，同伴版存 persistentData，读档不重置。"
                        + "伤害照抄原版：外/内环每点 8 点、中心门 12.5 点，每 5 tick 一轮共 10 轮，最后一轮即到期爆炸；"
                        + "中心门的 43~52 tick 冲击波按拍板首版省略（原版冲击伤害由事件层接管闸门取消）")
                .translation("modern_companions_expand.config.tesseract.cooldown")
                .defineInRange("tesseract_cooldown_ticks", 150, 0, 72000);

        TESSERACT_CHARGE_TICKS = builder
                .comment("charge gate (ticks). Vanilla maxChargeTick() = 30 = 1.5 seconds, copied by decision, fires when full; 0 = fire as soon as ready. The charge stand-still reuses the Incinerator framework: stop pathfinding / stop goals / face the target, and an interrupt costs no cooldown", "蓄力门槛（tick）。原版 maxChargeTick() = 30 = 1.5 秒，拍板照抄，蓄满即发；0 = 就绪即放。"
                        + "蓄力站定复用炎葬框架：停寻路/停 Goal/面向目标，被打断不消耗冷却")
                .translation("modern_companions_expand.config.tesseract.charge_ticks")
                .defineInRange("tesseract_charge_ticks", 30, 0, 2000);

        TESSERACT_TRIGGER_DISTANCE = builder
                .comment("auto-trigger distance (blocks). r37 fix: companions used to fire The Tesseract from far away (the portals are arranged around the companion, so casting at range wasted the cooldown). The core damage area = inner ring 4 blocks + centre portal, default trigger distance 6.0; if the scan box of inflate(this value) around the companion contains no hostile target at all, it does not cast and takes no cooldown. The force command ignores this limit", "自动触发距离（格）。r37 修复：同伴离敌人很远时也曾空放超立方体"
                        + "（传送门以同伴为中心排布，远处放了纯浪费冷却）。核心伤害区域 = 内环 4 格 + 中心门，"
                        + "触发距离默认 6.0；以同伴为中心 inflate(本值) 的扫描盒内一个敌对目标都没有时"
                        + "不释放、不进冷却。命令 force 不受此限")
                .translation("modern_companions_expand.config.tesseract.trigger_distance")
                .defineInRange("tesseract_trigger_distance", 6.0D, 1.0D, 32.0D);

        builder.pop();

        builder.comment("Buckler of Annihilation (Legendary Monsters) companion bash + annihilation bombs", "湮灭圆盾（Buckler of Annihilation，传奇怪物）同伴撞击 + 湮灭爆弹").push("buckler");

        BUCKLER_ENABLED = builder
                .comment("master switch: false = companions never use the Buckler of Annihilation (instant: the vanilla 10-tick charge gate is meaningless in auto combat, so by decision there is no charge stand-still)", "总开关：false 时同伴完全不尝试释放湮灭圆盾（瞬发：原版 10 tick 蓄力门槛对自动战斗无意义，拍板不做蓄力站定）")
                .translation("modern_companions_expand.config.buckler.enabled")
                .define("enabled", true);

        BUCKLER_COOLDOWN_TICKS = builder
                .comment("cooldown on hit (ticks, 20 ticks = 1 second), used when the bash hits at least one enemy. Decided default 200 = 10 seconds (same as Bulwark of the Flame); use 20 while testing", "命中冷却（tick，20 tick = 1 秒）：撞击至少打中一个敌人才进这条。拍板默认 200 = 10 秒（与火焰壁垒一致）；测试可临时改成 20")
                .translation("modern_companions_expand.config.buckler.cooldown")
                .defineInRange("buckler_cooldown_ticks", 200, 0, 72000);

        BUCKLER_MISS_COOLDOWN_TICKS = builder
                .comment("cooldown on miss (ticks), used when the bash touches no enemy at all (decided 60 = 3 seconds, to encourage another try; vanilla takes no cooldown on a miss). Without this throttle an auto-combat companion would spam bombs every 3 seconds, hence the two separate cooldowns, each in persistentData", "打空冷却（tick）：撞击一个敌人都没碰到时进这条（拍板 60 = 3 秒，鼓励再试；原版是“没打中不进冷却”，"
                        + "同伴自动战斗没有这个节流会 3 秒刷一次爆弹，故拆分两条冷却，各自存 persistentData）")
                .translation("modern_companions_expand.config.buckler.miss_cooldown")
                .defineInRange("buckler_miss_cooldown_ticks", 60, 0, 72000);

        BUCKLER_SLASH_DAMAGE = builder
                .comment("bash damage (vanilla 10 x BucklerOfAnnihilationSlashDamageMultiplier; the companion version is a single value, copied default 10.0). Basis = mobAttack (same family as the vanilla playerAttack, no void armour penetration, armour/enchantments reduce it normally)", "撞击伤害（原版 10 × BucklerOfAnnihilationSlashDamageMultiplier，同伴版直接一个数值，默认照抄 10.0）。"
                        + "口径 = mobAttack（与原版 playerAttack 同类，不吃湮灭穿甲，护甲/附魔正常减免）")
                .translation("modern_companions_expand.config.buckler.slash_damage")
                .defineInRange("buckler_slash_damage", 10.0D, 0.0D, 1000.0D);

        BUCKLER_SLASH_RANGE = builder
                .comment("forward reach of the bash hitbox (blocks). Vanilla r = 1.75f copied: hitbox = own bounding box .inflate(0.05, 1.5, 0.05).expandTowards(facing x this value) -- a rectangle right in front; vanilla has no angular fan, so by decision none is added", "撞击判定盒前伸距离（格）。原版 r = 1.75f 照抄：判定 = 自身碰撞箱.inflate(0.05, 1.5, 0.05)"
                        + ".expandTowards(朝向×本值)——一块贴脸矩形板，原版就没有角度扇形，拍板不加扇形")
                .translation("modern_companions_expand.config.buckler.slash_range")
                .defineInRange("buckler_slash_range", 1.75D, 0.5D, 16.0D);

        BUCKLER_BOMB_DAMAGE = builder
                .comment("damage per annihilation bomb (vanilla 8 x BucklerOfAnnihilationProjectileDamageMultiplier; the companion version is a single value, copied default 8.0). Damage basis = legendary_monsters:void (a Legendary Monsters custom type); whether it pierces armour depends on that damage type's tags, and the same resolution is reused through reflection", "每枚湮灭爆弹伤害（原版 8 × BucklerOfAnnihilationProjectileDamageMultiplier，同伴版直接一个数值，默认照抄 8.0）。"
                        + "爆弹伤害口径 = legendary_monsters:void（传奇怪物自定义类型），是否穿甲由该类型的标签决定，反射复用同一结算")
                .translation("modern_companions_expand.config.buckler.bomb_damage")
                .defineInRange("buckler_bomb_damage", 8.0D, 0.0D, 1000.0D);

        BUCKLER_KNOCKBACK_MIN_HORIZONTAL = builder
                .comment("minimum horizontal launch velocity (blocks, decided 0.6): vanilla multiplies the target's velocity by 1.25 on all three axes, so a standing target barely moves; the companion version adds a floor after the multiplication -- if the resulting horizontal speed is below this value it is topped up in the direction away from the companion", "击飞保底水平位移（格/拍板 0.6）：原版把目标当前速度三轴各乘 1.25，静止目标几乎没位移，"
                        + "同伴版在乘法之后加保底——乘完水平速度低于该值就按“背向同伴”方向补到该值")
                .translation("modern_companions_expand.config.buckler.knockback_min_horizontal")
                .defineInRange("buckler_knockback_min_horizontal", 0.6D, 0.0D, 10.0D);

        BUCKLER_KNOCKBACK_MIN_VERTICAL = builder
                .comment("minimum vertical launch velocity (blocks, decided 0.3): if the resulting vertical speed is below this value it is raised to it", "击飞保底垂直位移（格/拍板 0.3）：乘完垂直速度低于该值就抬到该值")
                .translation("modern_companions_expand.config.buckler.knockback_min_vertical")
                .defineInRange("buckler_knockback_min_vertical", 0.3D, 0.0D, 10.0D);

        BUCKLER_TRIGGER_DISTANCE = builder
                .comment("auto-trigger distance (blocks): no auto cast when the target is further than this (the buckler is a point-blank counter tool, the bash only reaches 1.75 blocks, and throwing bombs from far away wastes the cooldown; the bombs themselves can still arc over a dozen blocks). Default 4.0; the force command ignores this limit", "自动触发距离（格）：目标比这更远不自动释放（圆盾是贴脸反击向技能，撞击只够得着 1.75 格，"
                        + "远处白扔爆弹纯浪费冷却；爆弹本身靠抛物线还能飞十几格）。默认 4.0；命令 force 不受此限")
                .translation("modern_companions_expand.config.buckler.trigger_distance")
                .defineInRange("buckler_trigger_distance", 4.0D, 1.0D, 32.0D);

        builder.pop();

        builder.comment("Monstrous Anchor (Legendary Monsters) companion shockwave stun + attack AOE", "怪怖之锚（Monstrous Anchor，传奇怪物）同伴震击晕眩 + 攻击附带 AOE").push("monstrous_anchor");

        MONSTROUS_ANCHOR_ENABLED = builder
                .comment("master switch: false = companions never use the shockwave, and the attack AOE is disabled as well", "总开关：false 时同伴完全不尝试震击，攻击附带 AOE 也一并停用")
                .translation("modern_companions_expand.config.monstrous_anchor.enabled")
                .define("enabled", true);

        MONSTROUS_ANCHOR_SHOCKWAVE_COOLDOWN_TICKS = builder
                .comment("shockwave cooldown (ticks, 20 ticks = 1 second). Decided default 200 = 10 seconds (the source's 100 is too short for companions: a 3-second stun plus a 5-second cooldown would perma-lock targets); use 20 while testing. Vanilla starts the cooldown whether or not anything was hit (copied), and the companion version stores it in persistentData across reloads. The shockwave itself is instant with no charge (the vanilla 15-tick gate is bound to Player): 3-block AABB + 0.6 launch + STUN 60 ticks + no damage (all numbers copied from vanilla, no extra config)", "震击冷却（tick，20 tick = 1 秒）。拍板默认 200 = 10 秒（源码 100 对同伴太短，"
                        + "晕眩 3 秒 + 冷却 5 秒会控到死）；测试可临时改成 20。"
                        + "原版不管震没震到人都进冷却（照抄），同伴版存 persistentData，读档不重置。"
                        + "震击本体瞬发无蓄力（原版 15 tick 蓄力门槛绑 Player），"
                        + "3 格 AABB + 上抛 0.6 + STUN 60 tick + 无伤害（数值全部照抄原版，不另立配置）")
                .translation("modern_companions_expand.config.monstrous_anchor.shockwave_cooldown")
                .defineInRange("monstrous_anchor_shockwave_cooldown_ticks", 200, 0, 72000);

        MONSTROUS_ANCHOR_AOE_DAMAGE = builder
                .comment("damage per attack AOE (vanilla 6 x MonstrousAnchorAbilityDamageMultiplier; the companion version is a single value, copied default 6.0). Basis = PLAYER_ATTACK (copied from vanilla, no armour penetration), resolved around the victim", "攻击附带 AOE 的单次伤害（原版 6 × MonstrousAnchorAbilityDamageMultiplier，"
                        + "同伴版直接一个数值，默认照抄 6.0）。口径 = PLAYER_ATTACK（照抄原版，不穿甲），"
                        + "以被击中目标为中心结算")
                .translation("modern_companions_expand.config.monstrous_anchor.aoe_damage")
                .defineInRange("monstrous_anchor_aoe_damage", 6.0D, 0.0D, 1000.0D);

        MONSTROUS_ANCHOR_AOE_RADIUS = builder
                .comment("radius of the attack AOE (blocks): vanilla uses inflate(3) around the victim, copied as-is", "攻击附带 AOE 的半径（格）：原版以被击中目标为中心 inflate(3) 照抄")
                .translation("modern_companions_expand.config.monstrous_anchor.aoe_radius")
                .defineInRange("monstrous_anchor_aoe_radius", 3.0D, 0.5D, 16.0D);

        MONSTROUS_ANCHOR_AOE_MIN_INTERVAL_TICKS = builder
                .comment("minimum interval between attack AOE triggers (ticks). Decided default 10: one swing hitting several targets fires the event once per target, and the throttle stops repeated resolution (stored in persistentData); 0 = no throttle", "攻击附带 AOE 的最小触发间隔（tick）。拍板默认 10：一刀打中多个目标时"
                        + "每次命中都会触发一次事件，节流后不再重复结算（存 persistentData）；0 = 关闭节流")
                .translation("modern_companions_expand.config.monstrous_anchor.aoe_min_interval")
                .defineInRange("monstrous_anchor_aoe_min_interval_ticks", 10, 0, 72000);

        MONSTROUS_ANCHOR_SHOCKWAVE_TRIGGER_DISTANCE = builder
                .comment("shockwave auto-trigger distance (blocks). r37 fix: vanilla needs 0.75 seconds of point-blank charging, and the instant companion version used to slam from far away (missing, then walking up to melee). The shockwave covers a 3-block AABB around the companion, and r38 decided a default trigger distance of 1.0 (cast point-blank, smaller than the 3-block AABB, so enemies are definitely inside the real reach); if the scan box of inflate(this value) around the companion contains no hostile target at all, it does not cast and takes no cooldown. The force command ignores this limit", "震击自动触发距离（格）。r37 修复：原版震击要近身蓄力 0.75 秒，同伴版瞬发后"
                        + "曾出现离敌人很远也空放震击（打空后冲过去普攻）。震击本体以同伴为中心 3 格 AABB，"
                        + "r38 拍板：触发距离默认 1.0（贴脸释放，比 3 格 AABB 小，确保敌人在实际打击范围内）；"
                        + "以同伴为中心 inflate(本值) 的扫描盒内一个敌对目标都没有时不释放、不进冷却。"
                        + "命令 force 不受此限")
                .translation("modern_companions_expand.config.monstrous_anchor.shockwave_trigger_distance")
                .defineInRange("monstrous_anchor_shockwave_trigger_distance", 1.0D, 1.0D, 32.0D);

        builder.pop();

        builder.comment("Axe of Lightning (Legendary Monsters): combined lightning bolts + electric burst skill", "雷霆之钺（Axe of Lightning，传奇怪物）雷击波 + 电爆冲击波合并技能").push("axe_of_lightning");

        AXE_OF_LIGHTNING_ENABLED = builder
                .comment("master switch: false = companions never use the Axe of Lightning", "总开关：false 时同伴完全不尝试释放雷霆之钺")
                .translation("modern_companions_expand.config.axe_of_lightning.enabled")
                .define("enabled", true);

        AXE_OF_LIGHTNING_COOLDOWN_TICKS = builder
                .comment("cooldown (ticks, 20 ticks = 1 second). Decision: both branches are merged into one skill sharing a cooldown, default 150 = 7.5 seconds; vanilla uses 90 / useOn 60 independently and bound to Player.getCooldowns(), the merged companion version stores it in persistentData. Note the vanilla rule 'no cooldown if nothing spawned' is copied as well", "冷却（tick，20 tick = 1 秒）。拍板：两个分支合并为一个技能、共享冷却，"
                        + "默认 150 = 7.5 秒；原版 use 90 / useOn 60 各自为政且绑 Player.getCooldowns()，"
                        + "同伴版合并后存 persistentData。注意原版口径“一根都没生成不进冷却”也照抄")
                .translation("modern_companions_expand.config.axe_of_lightning.cooldown")
                .defineInRange("axe_of_lightning_cooldown_ticks", 150, 0, 72000);

        AXE_OF_LIGHTNING_MODE = builder
                .comment("mode: auto = context check (target <= melee_distance and 2+ hostiles within 5 blocks -> electric burst, otherwise lightning bolts); bolts = force lightning bolts; shockwave = force electric burst", "模式：auto = 情境判定（目标 ≤ melee_distance 且 5 格内敌对 ≥ shockwave_min_enemies "
                        + "→ 电爆冲击波，否则雷击波）；bolts = 锁定雷击波；shockwave = 锁定电爆冲击波")
                .translation("modern_companions_expand.config.axe_of_lightning.mode")
                .define("axe_of_lightning_mode", "auto");

        AXE_OF_LIGHTNING_BOLT_DAMAGE = builder
                .comment("damage per lightning bolt pillar (vanilla 8 x AxeOfLightningBoltDamageMultiplier; the companion version is a single value, copied default 8.0). Basis = INDIRECT_MAGIC (built into the entity, copied from vanilla; armour penetration depends on that type); the electric burst damage is read from the companion's attack attribute (same basis as vanilla)", "每根雷击波柱的伤害（原版 8 × AxeOfLightningBoltDamageMultiplier，"
                        + "同伴版直接一个数值，默认照抄 8.0）。波柱伤害口径 = INDIRECT_MAGIC"
                        + "（实体内置，照抄原版，是否穿甲由该类型决定）；电爆冲击波伤害读同伴攻击力属性（与原版同口径）")
                .translation("modern_companions_expand.config.axe_of_lightning.bolt_damage")
                .defineInRange("axe_of_lightning_bolt_damage", 8.0D, 0.0D, 1000.0D);

        AXE_OF_LIGHTNING_MELEE_DISTANCE = builder
                .comment("auto mode check: how close counts as point-blank (blocks). Default 6.0; anything further always uses the lightning bolts (15 blocks of line depth, single mid/far targets are exactly its niche)", "auto 情境判定：目标比这更近才算“近身”（格）。默认 6.0；更远一律放雷击波"
                        + "（铺列纵深 15 格，中远距离单体正是它的主场）")
                .translation("modern_companions_expand.config.axe_of_lightning.melee_distance")
                .defineInRange("axe_of_lightning_melee_distance", 6.0D, 0.0D, 64.0D);

        AXE_OF_LIGHTNING_SHOCKWAVE_MIN_ENEMIES = builder
                .comment("auto mode check: the many-enemies threshold -- this many hostiles within 5 blocks AND the target within point-blank distance triggers the electric burst (decided wording: close range plus many enemies). Default 2", "auto 情境判定：“多敌”阈值——5 格内敌对生物达到该数量，且目标在近身距离内，"
                        + "才放电爆冲击波（拍板措辞“近身多敌”）。默认 2")
                .translation("modern_companions_expand.config.axe_of_lightning.shockwave_min_enemies")
                .defineInRange("axe_of_lightning_shockwave_min_enemies", 2, 1, 16);

        builder.pop();

        builder.comment("exemption of companions from the Legendary Monsters special-shield blocking passives", "传奇怪物·特殊盾牌格挡被动对同伴的豁免").push("shield_passive");

        SHIELD_PASSIVE_DISABLED_FOR_COMPANIONS = builder
                .comment("true (default) = when a companion (Modern Companions) blocks with a shield, the blocking passives of the Frostbitten Shield (freeze), Shulker Shield (levitation) and Spiky Shield (thorns retaliation) do not trigger -- ordinary blocking damage reduction, arrow blocking, durability loss and the blocking animation stay exactly the same; players still trigger all three passives, and mobs holding shields are unaffected either. false = back to Legendary Monsters vanilla behaviour (companions trigger the passives too). Implementation: a Mixin stops those three event handlers for companion entities; if Legendary Monsters is not installed this switch does nothing", "true（默认）= 同伴（现代同伴）持盾格挡时，不触发霜冻之盾（冰冻）、潜影之盾（漂浮）、"
                        + "尖刺之盾（荆棘反击）的格挡被动——普通举盾格挡的减伤、挡箭、掉耐久、blocking 外观完全不变；"
                        + "玩家格挡三种被动照常触发，怪物互持盾牌也不受影响。"
                        + "false = 恢复传奇怪物原版行为（同伴格挡也触发被动）。"
                        + "实现方式：Mixin 掐掉这三个事件处理器对同伴实体的执行；传奇怪物没装时本开关无任何效果")
                .translation("modern_companions_expand.config.shield_passive.disabled_for_companions")
                .define("shield_passive_disabled_for_companions", true);

        builder.pop();

        builder.comment("scheduler troubleshooting", "调度器排查").push("debug");

        SCAN_DEBUG_LOG = builder
                .comment("master switch for the companion skill scan log. Defaults to false since r55 -- the old default true was one of the main reasons latest.log/debug.log ballooned past 100 MB. true = every scan prints the priority order plus why each skill was selected or skipped. Rate-limit rules (rewritten in r55): per companion only the verdict-phase signature is compared, countdown numbers do not count as a change -- an unchanged verdict prints nothing, one reminder line every scan_log_repeat_ticks; a changed verdict prints immediately but never closer than scan_log_min_interval_ticks apart. For quick checks the easier route is the in-game command /mce scan on (toggles live, no file edit and no restart)", "同伴技能扫描日志总开关。r55 起默认 false —— 旧版默认 true 是把 latest.log/debug.log 刷到 100M+ 的主因之一。"
                        + "true = 每次扫描输出“优先级顺序 + 每个技能被选中/跳过的具体原因”。"
                        + "限流规则（r55 重写）：同一同伴只比“判定阶段签名”，倒计时数字不算变化——"
                        + "结论没变一行都不打，每 scan_log_repeat_ticks 才提醒一行；结论变了马上打，"
                        + "但两次之间至少隔 scan_log_min_interval_ticks。"
                        + "排查时更省事的做法是用游戏内命令 /mce scan on（现开现关，不用改文件、不用重启）")
                .translation("modern_companions_expand.config.debug.scan_log")
                .define("scan_debug_log", false);

        SCAN_LOG_MIN_INTERVAL_TICKS = builder
                .comment("scan log: minimum ticks between two lines for the same companion (only applies when the verdict phase changed, so toggling between 'on cooldown' and 'no target' in a fight cannot flood the log). Default 100 = 5 seconds", "扫描日志：同一个同伴两行日志之间至少隔多少 tick（只在“判定阶段变了”时起作用，"
                        + "防战斗里“冷却中↔无目标”来回切着刷屏）。默认 100 = 5 秒")
                .translation("modern_companions_expand.config.debug.scan_log_min_interval")
                .defineInRange("scan_log_min_interval_ticks", 100, 1, 20000);

        SCAN_LOG_REPEAT_TICKS = builder
                .comment("scan log: while the verdict stays unchanged, print another reminder line every this many ticks. Default 1200 = 60 seconds. For 'log only on change' set it very high (e.g. 72000 = 1 hour)", "扫描日志：结论一直没变时，每隔多少 tick 再提醒一行。默认 1200 = 60 秒。"
                        + "想“只在结论变化时打”就调到很大（例如 72000 = 1 小时）")
                .translation("modern_companions_expand.config.debug.scan_log_repeat")
                .defineInRange("scan_log_repeat_ticks", 1200, 20, 72000);

        SHREDDER_TRACE_LOG = builder
                .comment("r24 troubleshooting switch (shredder x soul-render damage spike): true = print three traces -- (1) vanilla onUseTick call counter (LivingEntityUseItemEvent.Tick, one-to-one with the onUseTick call sites, an entity called several times in the same tick logs a WARN immediately); (2) a per-tick damage ledger (the hurt() call layer plus the real resolution layer, every entry with attacker UUID / damage type / amount / the victim's invulnerability ticks and lastHurt at the time); (3) invulnerability gate failure warnings (after the shredder resets the victim's invulnerability ticks, a large hit from another source bypassing the vanilla rate limit logs a WARN). The log volume is large (one line per tick during a fight), so it defaults to off since r55 -- remember to switch it back off after investigating", "r24 排查开关（绞肉×断魂伤害暴涨排查）：true = 输出三路追踪日志——"
                        + "①原版 onUseTick 调用计数（LivingEntityUseItemEvent.Tick，与 onUseTick 调用点一一对应，"
                        + "同实体同 tick 多次调用立即 WARN）；②每 tick 伤害台账（hurt() 调用层 + 实际结算层，"
                        + "每笔含攻击者 UUID / 伤害类型 / 金额 / 目标无敌帧与 lastHurt 现场）；"
                        + "③无敌帧闸门失效预警（绞肉重置目标无敌帧后，其他来源的大额伤害绕过原版限速即 WARN）。"
                        + "日志量很大（战斗中每 tick 一行），r55 起默认关闭，排查完记得关回去")
                .translation("modern_companions_expand.config.debug.shredder_trace_log")
                .define("shredder_trace_log", false);

        builder.pop();

        SPEC = builder.build();
    }

    private MceConfig() {}

    private static boolean bool(net.neoforged.neoforge.common.ModConfigSpec.BooleanValue v, boolean fallback) {
        try {
            return v.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int clampInt(net.neoforged.neoforge.common.ModConfigSpec.IntValue v, int min, int fallback) {
        try {
            return Math.max(min, v.get());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static double dbl(net.neoforged.neoforge.common.ModConfigSpec.DoubleValue v, double fallback) {
        try {
            return v.get();
        } catch (Throwable t) {
            return fallback;
        }
    }

    // —————— incinerator ——————

    public static boolean enabled() {
        return bool(INCINERATOR_ENABLED, true);
    }

    public static int cooldownTicks() {
        return clampInt(INCINERATOR_COOLDOWN_TICKS, 0, 400);
    }

    public static int checkIntervalTicks() {
        return clampInt(INCINERATOR_CHECK_INTERVAL_TICKS, 1, 5);
    }

    // —————— charge ——————

    public static int chargeTicks() {
        return clampInt(CHARGE_TICKS, 0, 60);
    }

    public static double chargeBreakDistance() {
        return dbl(CHARGE_BREAK_DISTANCE, 32.0D);
    }

    public static int interruptRetryTicks() {
        return clampInt(INTERRUPT_RETRY_TICKS, 0, 40);
    }

    public static double incineratorInterruptDamage() {
        return dbl(INCINERATOR_INTERRUPT_DAMAGE, 15.0D);
    }

    // —————— bulwark ——————

    public static boolean bulwarkEnabled() {
        return bool(BULWARK_ENABLED, true);
    }

    public static int bulwarkCooldownTicks() {
        return clampInt(BULWARK_COOLDOWN_TICKS, 0, 200);
    }

    public static boolean bulwarkHoldVelocity() {
        return bool(BULWARK_HOLD_VELOCITY, false);
    }

    /** 友军击飞开关（默认 false = 保护友军；读取异常同样按“保护”兜底） */
    public static boolean bulwarkFriendlyKnockback() {
        return bool(BULWARK_FRIENDLY_KNOCKBACK, false);
    }

    // —————— infernal_forge ——————

    public static boolean infernalForgeEnabled() {
        return bool(INFERNAL_FORGE_ENABLED, true);
    }

    public static int infernalForgeCooldownTicks() {
        return clampInt(INFERNAL_FORGE_COOLDOWN_TICKS, 0, 72000);
    }

    // —————— soul_render ——————

    public static boolean soulRenderEnabled() {
        return bool(SOUL_RENDER_ENABLED, true);
    }

    public static int soulRenderCooldownTicks() {
        return clampInt(SOUL_RENDER_COOLDOWN_TICKS, 0, 72000);
    }

    public static double soulRenderPreferDashDistance() {
        return dbl(SOUL_RENDER_PREFER_DASH_DISTANCE, 4.0D);
    }

    public static int soulRenderDashT() {
        return clampInt(SOUL_RENDER_DASH_T, 4, 20);
    }

    public static boolean soulRenderHoldVelocity() {
        return bool(SOUL_RENDER_HOLD_VELOCITY, true);
    }

    // —————— astrape ——————

    public static boolean astrapeEnabled() {
        return bool(ASTRAPE_ENABLED, true);
    }

    public static int astrapeCooldownTicks() {
        return clampInt(ASTRAPE_COOLDOWN_TICKS, 0, 72000);
    }

    // —————— ceraunus ——————

    public static boolean ceraunusEnabled() {
        return bool(CERAUNUS_ENABLED, true);
    }

    public static int ceraunusThrowCooldownTicks() {
        return clampInt(CERAUNUS_THROW_COOLDOWN_TICKS, 0, 72000);
    }

    public static int ceraunusWaveCooldownTicks() {
        return clampInt(CERAUNUS_WAVE_COOLDOWN_TICKS, 0, 72000);
    }

    public static double ceraunusPreferWaveDistance() {
        return dbl(CERAUNUS_PREFER_WAVE_DISTANCE, 4.0D);
    }

    public static int ceraunusWaveMinEnemies() {
        try {
            return Math.max(1, CERAUNUS_WAVE_MIN_ENEMIES.get());
        } catch (Throwable t) {
            return 2;
        }
    }

    public static boolean ceraunusFriendlyWaveBlock() {
        return bool(CERAUNUS_FRIENDLY_WAVE_BLOCK, true);
    }

    // —————— void_forge ——————

    public static boolean voidForgeEnabled() {
        return bool(VOID_FORGE_ENABLED, true);
    }

    public static int voidForgeCooldownTicks() {
        return clampInt(VOID_FORGE_COOLDOWN_TICKS, 0, 72000);
    }

    // —————— brontes ——————

    public static boolean brontesEnabled() {
        return bool(BRONTES_ENABLED, true);
    }

    public static int brontesThrowCooldownTicks() {
        return clampInt(BRONTES_THROW_COOLDOWN_TICKS, 0, 72000);
    }

    public static double brontesMeleeRange() {
        return dbl(BRONTES_MELEE_RANGE, 6.0D);
    }

    public static boolean brontesLockAttackWhileThrown() {
        return bool(BRONTES_LOCK_ATTACK_WHILE_THROWN, true);
    }

    public static int brontesThrowTimeoutTicks() {
        return clampInt(BRONTES_THROW_TIMEOUT_TICKS, 20, 200);
    }

    // —————— meat_shredder ——————

    public static boolean meatShredderEnabled() {
        return bool(MEAT_SHREDDER_ENABLED, true);
    }

    public static int meatShredderCooldownTicks() {
        return clampInt(MEAT_SHREDDER_COOLDOWN_TICKS, 0, 60);
    }

    public static int meatShredderMaxDurationTicks() {
        return clampInt(MEAT_SHREDDER_MAX_DURATION_TICKS, 20, 200);
    }

    public static double meatShredderRange() {
        return dbl(MEAT_SHREDDER_RANGE, 3.0D);
    }

    /** 绞肉退出的迟滞距离 = 射程 + 0.5（进圈用射程、出圈留余量防抖动） */
    public static double meatShredderExitDistance() {
        return meatShredderRange() + 0.5D;
    }

    public static boolean meatShredderAllowWithShield() {
        return bool(MEAT_SHREDDER_ALLOW_WITH_SHIELD, true);
    }

    public static boolean meatShredderSlowdown() {
        return bool(MEAT_SHREDDER_SLOWDOWN, true);
    }

    public static boolean meatShredderHostileOnly() {
        return bool(MEAT_SHREDDER_HOSTILE_ONLY, true);
    }

    /** r25 错峰窗口（tick）：0 = 停用错峰；读取异常按默认 100 兜底 */
    public static int meatShredderAoeDeferTicks() {
        return clampInt(MEAT_SHREDDER_AOE_DEFER_TICKS, 0, 100);
    }

    /** r26 影子无敌帧开关（默认 true；读取异常按“开”兜底——影子闸门更接近原版语义） */
    public static boolean meatShredderIframeRestore() {
        return bool(MEAT_SHREDDER_IFRAME_RESTORE, true);
    }

    // —————— soul_great_sword ——————

    public static boolean soulGreatSwordEnabled() {
        return bool(SOUL_GREAT_SWORD_ENABLED, true);
    }

    public static boolean soulGreatSwordParryEnabled() {
        return bool(SOUL_GREAT_SWORD_PARRY_ENABLED, true);
    }

    public static int soulGreatSwordParryCooldownTicks() {
        return clampInt(SOUL_GREAT_SWORD_PARRY_COOLDOWN_TICKS, 0, 72000);
    }

    public static double soulGreatSwordParryHealthThreshold() {
        return dbl(SOUL_GREAT_SWORD_PARRY_HEALTH_THRESHOLD, 0.8D);
    }

    public static double soulGreatSwordParryMinDamage() {
        return dbl(SOUL_GREAT_SWORD_PARRY_MIN_DAMAGE, 4.0D);
    }

    public static int soulGreatSwordDaggerCooldownTicks() {
        return clampInt(SOUL_GREAT_SWORD_DAGGER_COOLDOWN_TICKS, 0, 72000);
    }

    /**
     * r29：幻影匕首是否保留“非玩家 owner 分支”（最大生命 1% 附加 / Soul Fracture / owner 回 3 点）。
     * 默认 false = 强制玩家分支；读取异常按“强制玩家分支”兜底（拍板默认行为优先）
     */
    public static boolean soulGreatSwordDaggerMonsterBranch() {
        return bool(SOUL_GREAT_SWORD_DAGGER_MONSTER_BRANCH, false);
    }

    // —————— tesseract ——————

    public static boolean tesseractEnabled() {
        return bool(TESSERACT_ENABLED, true);
    }

    /** 拍板默认 150 tick（7.5 秒，原版照抄）；读取异常按默认兜底 */
    public static int tesseractCooldownTicks() {
        return clampInt(TESSERACT_COOLDOWN_TICKS, 0, 72000);
    }

    /** 拍板默认 30 tick（1.5 秒，原版 maxChargeTick() 照抄） */
    public static int tesseractChargeTicks() {
        return clampInt(TESSERACT_CHARGE_TICKS, 0, 2000);
    }

    /** 自动触发距离（r37 拍板默认 6.0 = 内环 4 格 + 中心门全覆盖）；读取异常按默认兜底 */
    public static double tesseractTriggerDistance() {
        return dbl(TESSERACT_TRIGGER_DISTANCE, 6.0D);
    }

    // —————— buckler ——————

    public static boolean bucklerEnabled() {
        return bool(BUCKLER_ENABLED, true);
    }

    /** 命中冷却（拍板默认 200 tick）；读取异常按默认兜底 */
    public static int bucklerCooldownTicks() {
        return clampInt(BUCKLER_COOLDOWN_TICKS, 0, 200);
    }

    /** 打空冷却（拍板默认 60 tick）；读取异常按默认兜底 */
    public static int bucklerMissCooldownTicks() {
        return clampInt(BUCKLER_MISS_COOLDOWN_TICKS, 0, 60);
    }

    public static double bucklerSlashDamage() {
        return dbl(BUCKLER_SLASH_DAMAGE, 10.0D);
    }

    public static double bucklerSlashRange() {
        return dbl(BUCKLER_SLASH_RANGE, 1.75D);
    }

    public static double bucklerBombDamage() {
        return dbl(BUCKLER_BOMB_DAMAGE, 8.0D);
    }

    public static double bucklerKnockbackMinHorizontal() {
        return dbl(BUCKLER_KNOCKBACK_MIN_HORIZONTAL, 0.6D);
    }

    public static double bucklerKnockbackMinVertical() {
        return dbl(BUCKLER_KNOCKBACK_MIN_VERTICAL, 0.3D);
    }

    public static double bucklerTriggerDistance() {
        return dbl(BUCKLER_TRIGGER_DISTANCE, 4.0D);
    }

    // —————— monstrous_anchor ——————

    public static boolean monstrousAnchorEnabled() {
        return bool(MONSTROUS_ANCHOR_ENABLED, true);
    }

    /** 震击冷却（拍板默认 200 tick）；读取异常按默认兜底 */
    public static int monstrousAnchorShockwaveCooldownTicks() {
        return clampInt(MONSTROUS_ANCHOR_SHOCKWAVE_COOLDOWN_TICKS, 0, 72000);
    }

    public static double monstrousAnchorAoeDamage() {
        return dbl(MONSTROUS_ANCHOR_AOE_DAMAGE, 6.0D);
    }

    public static double monstrousAnchorAoeRadius() {
        return dbl(MONSTROUS_ANCHOR_AOE_RADIUS, 3.0D);
    }

    /** AOE 节流间隔（拍板默认 10 tick）；读取异常按默认兜底 */
    public static int monstrousAnchorAoeMinIntervalTicks() {
        return clampInt(MONSTROUS_ANCHOR_AOE_MIN_INTERVAL_TICKS, 0, 72000);
    }

    /** 震击自动触发距离（r37 拍板默认 4.0 = 覆盖 3 格 AABB 有余量）；读取异常按默认兜底 */
    public static double monstrousAnchorShockwaveTriggerDistance() {
        return dbl(MONSTROUS_ANCHOR_SHOCKWAVE_TRIGGER_DISTANCE, 4.0D);
    }

    // —————— axe_of_lightning ——————

    public static boolean axeOfLightningEnabled() {
        return bool(AXE_OF_LIGHTNING_ENABLED, true);
    }

    /** 共享冷却（拍板默认 150 tick）；读取异常按默认兜底 */
    public static int axeOfLightningCooldownTicks() {
        return clampInt(AXE_OF_LIGHTNING_COOLDOWN_TICKS, 0, 72000);
    }

    /** 模式（auto/bolts/shockwave，默认 auto）；读取异常按 auto 兜底 */
    public static String axeOfLightningMode() {
        try {
            String mode = AXE_OF_LIGHTNING_MODE.get();
            return mode == null || mode.isEmpty() ? "auto" : mode;
        } catch (Throwable t) {
            return "auto";
        }
    }

    public static double axeOfLightningBoltDamage() {
        return dbl(AXE_OF_LIGHTNING_BOLT_DAMAGE, 8.0D);
    }

    public static double axeOfLightningMeleeDistance() {
        return dbl(AXE_OF_LIGHTNING_MELEE_DISTANCE, 6.0D);
    }

    public static int axeOfLightningShockwaveMinEnemies() {
        try {
            return Math.max(1, AXE_OF_LIGHTNING_SHOCKWAVE_MIN_ENEMIES.get());
        } catch (Throwable t) {
            return 2;
        }
    }

    // —————— shield_passive ——————

    /** 同伴豁免开关：读不到配置时按默认 true（豁免同伴）处理 */
    public static boolean shieldPassiveDisabledForCompanions() {
        return bool(SHIELD_PASSIVE_DISABLED_FOR_COMPANIONS, true);
    }

    // —————— debug（r55：刷屏修复后的排查开关） ——————

    /**
     * /mce scan 的运行期覆盖：null = 老实跟配置文件走，TRUE/FALSE = 命令临时指定。
     * 只影响日志，不改配置文件、不用重启；服务端单线程读写在 volatile 上足够。
     */
    private static volatile Boolean scanDebugLogOverride = null;

    /**
     * 扫描日志总开关的生效值。
     * r55 起兜底值从 true 改成 false：配置文件读不到时也不能继续刷屏（默认就是安静）。
     */
    public static boolean scanDebugLog() {
        Boolean override = scanDebugLogOverride;
        return override != null ? override : bool(SCAN_DEBUG_LOG, false);
    }

    /** /mce scan on|off|default 用：传 null 交还给配置文件 */
    public static void setScanDebugLogOverride(Boolean value) {
        scanDebugLogOverride = value;
    }

    /** 当前是否有运行期覆盖（null = 跟配置文件） */
    public static Boolean scanDebugLogOverride() {
        return scanDebugLogOverride;
    }

    /** 配置文件里写的值（不含命令覆盖），status 用来显示“文件默认是什么” */
    public static boolean scanDebugLogInFile() {
        return bool(SCAN_DEBUG_LOG, false);
    }

    /** 同一同伴两行扫描日志的最小间隔（tick）。只保证下限，读不到时按默认 100 */
    public static int scanLogMinIntervalTicks() {
        return clampInt(SCAN_LOG_MIN_INTERVAL_TICKS, 1, 100);
    }

    /** 结论一直没变时的提醒间隔（tick）。下限 20 防手滑填 0 变回刷屏 */
    public static int scanLogRepeatTicks() {
        return clampInt(SCAN_LOG_REPEAT_TICKS, 20, 1200);
    }

    // —————— debug（r24 排查） ——————

    /** r24 绞肉追踪日志的运行期覆盖（/mce trace），语义同 scanDebugLogOverride */
    private static volatile Boolean shredderTraceLogOverride = null;

    /**
     * 绞肉×断魂追踪日志的生效值。r55 起默认关闭、兜底也改成 false
     * （它比扫描日志更啰嗦：战斗中每 tick 每实体一行）。
     */
    public static boolean shredderTraceLog() {
        Boolean override = shredderTraceLogOverride;
        return override != null ? override : bool(SHREDDER_TRACE_LOG, false);
    }

    /** /mce trace on|off|default 用：传 null 交还给配置文件 */
    public static void setShredderTraceLogOverride(Boolean value) {
        shredderTraceLogOverride = value;
    }

    /** 当前是否有运行期覆盖（null = 跟配置文件） */
    public static Boolean shredderTraceLogOverride() {
        return shredderTraceLogOverride;
    }

    /** 配置文件里写的值（不含命令覆盖） */
    public static boolean shredderTraceLogInFile() {
        return bool(SHREDDER_TRACE_LOG, false);
    }
}
