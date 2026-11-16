package cn.blockforge.generated.moderncompanionsexpand;

import cn.blockforge.generated.moderncompanionsexpand.command.MceCommands;
import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.event.AllyDamageGuard;
import cn.blockforge.generated.moderncompanionsexpand.event.MonstrousAnchorAoe;
import cn.blockforge.generated.moderncompanionsexpand.event.SkillInterruptHandler;
import cn.blockforge.generated.moderncompanionsexpand.event.SkillTickHandler;
import cn.blockforge.generated.moderncompanionsexpand.event.SoulGreatSwordDaggerGate;
import cn.blockforge.generated.moderncompanionsexpand.event.SoulGreatSwordParry;
import cn.blockforge.generated.moderncompanionsexpand.event.ShadowInvulnGate;
import cn.blockforge.generated.moderncompanionsexpand.event.ShredderTrace;
import cn.blockforge.generated.moderncompanionsexpand.skill.AstrapeSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.AxeOfLightningSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BrontesSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BucklerSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BulwarkSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.CeraunusSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.InfernalForgeSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MeatShredderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MonstrousAnchorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillRegistry;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulRenderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulGreatSwordSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.TesseractSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.VoidForgeSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Modern Companions Expand 入口。
 *
 * 本轮（蓄力状态机 + 火焰壁垒 + 优先级调度）做了六件事：
 * 1. COMMON 配置扩为三段：incinerator（冷却 400）、charge（蓄力 60 tick/脱距 32/打断罚时 40）、
 *    bulwark（开关、冷却 200 tick = bulwark_cooldown_ticks、窗口钉速度）；
 * 2. 优先级表放 config/modern_companions_expand/skill_priority.json（可手改、可 /mce skills reload 热重载），
 *    数值参数留在 TOML，两处不重复维护同一个数；
 * 3. 炎葬改为 3 秒（60 tick）蓄力状态机：进度按 gameTime 差算（读档自动续算），
 *    蓄力中站定面向目标（暂停寻路/Goal/NoAI + 每 tick 清零水平速度），完成时 flame_burst 音 → 火柱 →
 *    ScreenShake → sword_stomp + 挥手；
 *    打断规则（r8）：受伤改为累计伤害 ≥ incinerator_interrupt_damage（默认 15.0）才打断，
 *    目标死亡/超 32 格/换姿态仍立即打断；打断不消耗冷却，只进 40 tick 重试罚时，
 *    罚时期间移动与近战 AI 已恢复正常；
 * 4. 火焰壁垒：固定满蓄力 t=4，朝当前攻击目标冲锋（无目标不冲），复用灾变 ChargeAttachment
 *    （反射写入 + 我们每 tick 驱动，补上灾变只给玩家驱动的那一环），8 tick 窗口期间不做中断判定；
 *    附件反射层对不上时降级为自建 AABB 判定 + 灾变 Wall_Watcher，再不行直接伤害 + 原版击退；
 * 5. SkillTickHandler 改为优先级调度器（每 tick 至多推进一个技能 + 全局沉默），
 *    SkillInterruptHandler 挂 LivingIncomingDamageEvent（1.21.1 的新事件名）做蓄力打断；
 * 6. /mce 命令扩成三组：incinerator / bulwark（status|equip|clear|reset|force）+ skills（list|reload|show）；
 * 7. r9：壁垒冲锋友军保护（bulwark_friendly_knockback=false 默认开）——统一 AllyCheck.isAlly
 *    判定（玩家/其他同伴/主人链/同主人宠物），方案 A 在 Wall_Watcher 观察名单层面摘除友军
 *    （CataclysmReflect.stripAlliesFromWatchers）+ AllyDamageGuard 事件层兜底伤害，
 *    方案 B/C 在源头跳过；冲锋速度/收窗/撞墙眩晕按原版不变。
 * 8. r10：炼狱锻锤（cataclysm:infernal_forge）瞬发冲击波——原版 useOn 绑死 Player，
 *    服务端逻辑不依赖方块坐标，故用原版 API 复刻同款结算（mobAttack 伤害源 +
 *    OriginDamage 反射取同口径攻击力 + EnchantmentHelper 附魔修正），
 *    半径 4 格（9×9×9）、命中后 (×0.5,×1,+0.6) 弹飞、cataclysm:explosion 音效 +
 *    80 粒子方块环 + 震屏；友军（AllyCheck）不受伤不被弹飞；冷却 persistentData，
 *    infernal_forge_cooldown_ticks 默认 80；按需求不含半血燃烧。
 * 9. r16：神怒长槊（cataclysm:astrape）瞬发鸣雷槊——原版 releaseUsing 绑死 Player，
 *    反射复用 Lightning_Spear_Entity（首参就是 LivingEntity，同伴直接当 owner），
 *    数值反射直读 CMCommonConfig.Astrape（11 / 2.0），emp_activated 音效保留；
 *    冷却 persistentData，astrape_cooldown_ticks 默认 60；友军拦截窗口 160 tick 起步 +
 *    按“直接实体=该同伴的雷电实体”动态顺延到静电场消失；优先级表排断魂之后、炎葬之前。
 * 10. r18：霆浪锚戟（cataclysm:ceraunus）双技能——投掷反射复用 Player_Ceraunus_Entity
 *    （伤害 = 攻击力属性，弹速 2.5 + 重力 0.08，抛物线仰角自动补偿），波浪反射复用
 *    Wave_Entity（4 道 25° 扇形逐行复刻，伤害/潮湿叠加内置在实体里，200 tick 按源码）；
 *    情境判定 ceraunus_prefer_wave_distance=4.0 / ceraunus_wave_min_enemies=2，
 *    波浪前扇形预扫描（ceraunus_friendly_wave_block 默认开，扇形有友军退回投掷）；
 *    双冷却独立存 persistentData（投掷 100 = ceraunus_throw_cooldown_ticks、
 *    波浪 150 = ceraunus_wave_cooldown_ticks）；优先级表排断魂之后、神怒之前。
     * 11. r21：虚空锻锤（cataclysm:void_forge）瞬发布阵——原版 useOn 绑死 Player，同伴不会右键方块，
     *    以同伴为中心朝目标方向复刻 7 射线 × 5 排布点（1.75~8.75 格，warmup 递增由符文实体内部驱动），
     *    反射复用 Void_Rune_Entity（末参 LivingEntity），伤害读 CMCommonConfig.VoidForge.runeDamage（默认 7）；
     *    冷却 persistentData，void_forge_cooldown_ticks 默认 100；友军拦截窗口 120 tick 起步 +
     *    按“直接实体 = 该同伴的符文实体”动态顺延。
     * 12. r21：掣雷巨锤（cataclysm:brontes）双分支——远距离投掷（反射复用 Brontes_Entity，
     *    弹速 2.5 + 重力 0.1 抛物线仰角补偿，伤害 = 攻击力属性，本体物品留在手上）；
     *    贴脸（≤6 格）直接在敌人脚下复刻原版命中落点（8 放射火花 + 闪电风暴 size 3 + 震屏，
     *    数值读 CMCommonConfig.Brontes），圈内有友军预扫描退回普攻；
     *    投掷锁定（brontes_lock_attack_while_thrown 默认开）：锤子在外面时同伴不出手打人
     *    （每 tick 清攻击目标 + 事件层取消其近战伤害），锁权威存 persistentData +
     *    best-effort 镜写灾变 THROWN_HAMMER 组件；
     *    强制恢复每 tick 检测：锤子回来/超时（brontes_throw_timeout_ticks 默认 200）/收回魂石/
     *    重进存档/死亡复活都会清锁、清组件、尽力从背包找回落锤并打 SafeGuard.info 日志。
     * 13. r23：绞肉锯（cataclysm:meat_shredder）持续绞肉——零反射、零自研伤害：同伴直接
     *    startUsingItem 进入原版使用状态，灾变 onUseTick 每 tick 自动结算（伤害公式/穿方块/
     *    粒子/循环音效 100% 保真）。状态机外壳：距离 ≤ meat_shredder_range（3.0）进、
     *    绞满 meat_shredder_max_duration_ticks（200）自动收锯、受击不打断（interruptOnDamage
     *    钩子）、退出距离射程+0.5 迟滞（breakDistanceOverride 钩子）；退出即进冷却 60
     *    （meat_shredder_cooldown_ticks）；减速 ×0.2 临时修饰可关；副手盾豁免可配
     *    （meat_shredder_allow_with_shield 默认 true）；友军保护两层（预扫描 + 事件层绞肉窗口）；
     *    无敌帧副作用与副手盾互斥各有限流观察日志；读档后绞肉状态续算，条件不成立安全退出；
     *    优先级表排壁垒之后、断魂之前。
     * 14. r26：影子无敌帧闸门（meat_shredder_iframe_restore 默认开）——绞肉期间为目标在内存里
     *    维护“无绞肉污染时的无敌帧/lastHurt”影子账本，非绞肉伤害按影子值拦/补/放行（细节日志），
     *    收锯时落回实际字段并销毁账本；绞肉自身 1 点/tick 特色不动，队友大额伤害回到原版限速；
     *    r25 错峰保留为双保险；附绞肉 DPS 滚动实测（/mce meat_shredder status + 每 40 tick 日志）。
 * 全程 SafeGuard：任何反射/外部模组异常只写日志（[MCE] 前缀），不崩游戏；
 * 编译期零依赖灾变，灾变没装 = 对应技能跳过 + 限流日志。
 */
@Mod(GeneratedMod.MOD_ID)
public final class GeneratedMod {
    public static final String MOD_ID = "modern_companions_expand";

    public GeneratedMod(IEventBus modBus, ModContainer container) {
        // —— 配置：COMMON 类型，文件在 config/modern_companions_expand.toml ——
        container.registerConfig(ModConfig.Type.COMMON, MceConfig.SPEC);

        // —— 技能注册表：新技能在这里 + 优先级表里各加一行 ——
        SkillRegistry.register(IncineratorSkill.INSTANCE);
        SkillRegistry.register(BulwarkSkill.INSTANCE);
        SkillRegistry.register(InfernalForgeSkill.INSTANCE);
        SkillRegistry.register(SoulRenderSkill.INSTANCE);
        SkillRegistry.register(AstrapeSkill.INSTANCE);
        SkillRegistry.register(CeraunusSkill.INSTANCE);
        SkillRegistry.register(VoidForgeSkill.INSTANCE);
        SkillRegistry.register(BrontesSkill.INSTANCE);
        SkillRegistry.register(MeatShredderSkill.INSTANCE);
        // r28 传奇怪物·灵魂巨剑：幻影匕首（技能）+ 被动格挡（事件层）
        SkillRegistry.register(SoulGreatSwordSkill.INSTANCE);
        // r30 传奇怪物·超立方体：五角星湮灭传送门（外观复用 + 伤害自研循环）
        SkillRegistry.register(TesseractSkill.INSTANCE);
        // r32 传奇怪物·湮灭圆盾：贴脸撞击（几何照抄 AABB + 击飞保底）+ 反射复用 3 枚湮灭爆弹；
        // 冷却按命中/打空拆分存 persistentData；友军拦截窗口内接管闸门给爆弹的 void 口径让路
        SkillRegistry.register(BucklerSkill.INSTANCE);
        // r33 传奇怪物·怪怖之锚：震击晕眩（瞬发 + 3 格 AABB + STUN 60 + push 0.6，冷却 200）；
        // 攻击附带 AOE 是被动，在事件层 MonstrousAnchorAoe（节流 10 tick + AllyCheck + 窗口兜底）
        SkillRegistry.register(MonstrousAnchorSkill.INSTANCE);
        // r33 传奇怪物·雷霆之钺：两分支合并一个技能（雷击波 12 柱 / 电爆冲击波 5 道扇形），
        // 共享冷却 150；auto 情境判定（近身多敌冲击波、其余雷击波），mode 可锁 bolts/shockwave
        SkillRegistry.register(AxeOfLightningSkill.INSTANCE);

        // —— 游戏总线 ——
        NeoForge.EVENT_BUS.addListener(SkillTickHandler::onEntityTickPost);
        // r24 排查：hurt() 调用记录必须排在所有拦截器之前——被友军保护取消的调用也要留痕
        NeoForge.EVENT_BUS.addListener(ShredderTrace::onDamageAttempt);
        NeoForge.EVENT_BUS.addListener(SkillInterruptHandler::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(AllyDamageGuard::onIncomingDamage);
        // r28 灵魂巨剑被动格挡：注册在友军保护之后（友军误伤先被裁掉，不喂格挡）、
        // 影子无敌帧之前（格挡是 cancel 事件，取消后不进 hurt、不压 lastHurt，影子闸门天然不介入）
        NeoForge.EVENT_BUS.addListener(SoulGreatSwordParry::onIncomingDamage);
        // r26 影子无敌帧：注册在友军保护之后——友军保护先裁，影子对已取消的伤害一律不碰
        NeoForge.EVENT_BUS.addListener(ShadowInvulnGate::onIncomingDamage);
        // r29 幻影匕首强制玩家分支：认出同伴名下匕首的 ghost 伤害后压回基础 8 点 + 剥离
        // Soul Fracture / owner 回血（注册在友军保护之后——友军误伤已被裁，不会进本闸门；
        // 闸门只改金额不取消事件，对影子闸门透明）
        NeoForge.EVENT_BUS.addListener(SoulGreatSwordDaggerGate::onIncomingDamage);
        NeoForge.EVENT_BUS.addListener(SoulGreatSwordDaggerGate::onDamagePost);
        NeoForge.EVENT_BUS.addListener(SoulGreatSwordDaggerGate::onHeal);
        NeoForge.EVENT_BUS.addListener(ShredderTrace::onItemUseTick);
        NeoForge.EVENT_BUS.addListener(ShredderTrace::onDamageApplied);
        NeoForge.EVENT_BUS.addListener(ShadowInvulnGate::onDamageApplied);
        NeoForge.EVENT_BUS.addListener(ShredderTrace::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(SoulGreatSwordDaggerGate::onServerTickPost);
        // r33 怪怖之锚能力 2：同伴近战命中后触发攻击附带 AOE（结算层事件，只认真正落地的伤害；
        // 注册位置无先后敏感——节流 + 重入闸 + AllyCheck 自带全部保护，AllyDamageGuard 窗口再兜底）
        NeoForge.EVENT_BUS.addListener(MonstrousAnchorAoe::onDamagePost);
        NeoForge.EVENT_BUS.addListener(MceCommands::register);
        // 服务端启动时落盘/读取优先级表（文件不存在会先写默认表）
        NeoForge.EVENT_BUS.addListener((ServerStartingEvent event) ->
                SafeGuard.guard("load skill priority table", SkillPriorityConfig::ensureLoaded));

        SafeGuard.info("Modern Companions Expand loaded: Incinerator 3s charge + Bulwark of the Flame charge + priority scheduling"
                + " (zero compile-time dependency on Cataclysm, runtime reuses its pillars of fire / charge attachment / screen shake through reflection)");
    }
}
