package cn.blockforge.generated.moderncompanionsexpand.command;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.event.SkillTickHandler;
import cn.blockforge.generated.moderncompanionsexpand.skill.AstrapeSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.AxeOfLightningSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BrontesSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BucklerSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BulwarkSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.CataclysmReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.CeraunusSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.ChargeStateStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.CompanionSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.LegendaryMonstersReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MeatShredderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MonstrousAnchorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillCooldownStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillRegistry;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulRenderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulGreatSwordSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.TesseractSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.VoidForgeSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 测试用命令（权限等级 2 = 需 OP/创造+作弊）：
 *
 * /mce incinerator status|equip|clear|reset|force     —— 炎葬（蓄力火柱）
 * /mce bulwark   status|equip|clear|reset|force       —— 火焰壁垒（冲锋）
 * /mce infernal_forge status|equip|clear|reset|force  —— 炼狱锻锤（冲击波）
 * /mce void_forge  status|equip|clear|reset|force     —— 虚空锻锤（符文布阵）
 * /mce brontes    status|equip|clear|reset|force     —— 掣雷巨锤（投掷/贴脸，force 带 throw|burst）
 * /mce skills  list|reload                            —— 优先级表：列出 / 热重载
 * /mce skills  show <实体>                             —— 看某个同伴的状态机现场
 * /mce scan    on|off|default|status                   —— 技能扫描日志开关（r55 默认关；见 debug.scan_debug_log）
 * /mce trace   on|off|default|status                   —— 绞肉×断魂追踪日志开关（r55 默认关；见 debug.shredder_trace_log）
 *
 * equip/clear/reset/force 都是通用实现（按技能绑定的物品/冷却键操作），
 * status 每个技能有自己的自检项。所有命令体包 try-catch：出任何异常只回消息 + 写日志。
 */
public final class MceCommands {

    private MceCommands() {}

    public static void register(RegisterCommandsEvent event) {
        SafeGuard.guard("register the /mce command", () -> {
            CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
            var root = Commands.literal("mce")
                    .requires(source -> source.hasPermission(2));
            for (CompanionSkill skill : SkillRegistry.all()) {
                root.then(skillTree(skill));
            }
            root.then(Commands.literal("skills")
                    .then(Commands.literal("list").executes(MceCommands::skillsList))
                    .then(Commands.literal("reload").executes(MceCommands::skillsReload))
                    .then(Commands.literal("show").then(
                            Commands.argument("target", EntityArgument.entity()).executes(MceCommands::skillsShow))));
            // r55：两个刷屏开关的运行期开关（不改配置文件、不重启就能开排查）
            root.then(logToggleTree("scan", true));
            root.then(logToggleTree("trace", false));
            dispatcher.register(root);
            SafeGuard.info("command: /mce registered (" + SkillRegistry.all().size()
                    + " skills each with status/equip/clear/reset/force + /mce skills list/reload/show"
                    + " + /mce scan on|off|default|status + /mce trace on|off|default|status)");
        });
    }

    /**
     * 生成 /mce &lt;name&gt; on|off|default|status 子命令树。
     * scanDebug=true → 操作 debug.scan_debug_log（技能扫描日志）；false → debug.shredder_trace_log（绞肉追踪日志）。
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
    logToggleTree(String name, boolean scanDebug) {
        return Commands.literal(name)
                .then(Commands.literal("on").executes(ctx -> guarded(ctx,
                        () -> setLogToggle(ctx, scanDebug, Boolean.TRUE))))
                .then(Commands.literal("off").executes(ctx -> guarded(ctx,
                        () -> setLogToggle(ctx, scanDebug, Boolean.FALSE))))
                .then(Commands.literal("default").executes(ctx -> guarded(ctx,
                        () -> setLogToggle(ctx, scanDebug, null))))
                .then(Commands.literal("status").executes(ctx -> guarded(ctx,
                        () -> logToggleStatus(ctx, scanDebug))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack>
    skillTree(CompanionSkill skill) {
        String name = SkillPriorityConfig.SKILL_INCINERATOR.equals(skill.id()) ? "incinerator"
                : SkillPriorityConfig.SKILL_BULWARK.equals(skill.id()) ? "bulwark" : skill.id();

        // force 的目标参数节点：双技能武器（断魂 dash/windmill、霆浪 throw/wave）在它下面
        // 追加模式子命令，不带模式 = 按情境自动判定（一次构建，避免同名节点合并的边界情况）
        var forceArg = Commands.argument("target", EntityArgument.entity())
                .executes(ctx -> guarded(ctx, () -> force(ctx, skill)));
        var forceLiteral = Commands.literal("force");
        if (SkillPriorityConfig.SKILL_TESSERACT.equals(skill.id())) {
            // r30 超立方体：五角星固定角度公式不随朝向/目标偏移，force 无需目标——
            // /mce tesseract force（不带 target）= 原地直接释放
            forceLiteral = forceLiteral.executes(ctx -> guarded(ctx, () -> forceHere(ctx, skill)));
        }
        if (SkillPriorityConfig.SKILL_SOUL_RENDER.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(SoulRenderSkill.FORCE_MODE_DASH)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    SoulRenderSkill.FORCE_MODE_DASH))))
                    .then(Commands.literal(SoulRenderSkill.FORCE_MODE_WINDMILL)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    SoulRenderSkill.FORCE_MODE_WINDMILL))));
        } else if (SkillPriorityConfig.SKILL_CERAUNUS.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(CeraunusSkill.FORCE_MODE_THROW)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    CeraunusSkill.FORCE_MODE_THROW))))
                    .then(Commands.literal(CeraunusSkill.FORCE_MODE_WAVE)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    CeraunusSkill.FORCE_MODE_WAVE))));
        } else if (SkillPriorityConfig.SKILL_BRONTES.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(BrontesSkill.FORCE_MODE_THROW)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    BrontesSkill.FORCE_MODE_THROW))))
                    .then(Commands.literal(BrontesSkill.FORCE_MODE_BURST)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    BrontesSkill.FORCE_MODE_BURST))));
        } else if (SkillPriorityConfig.SKILL_SOUL_GREAT_SWORD.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(SoulGreatSwordSkill.FORCE_MODE_PARRY)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    SoulGreatSwordSkill.FORCE_MODE_PARRY))))
                    .then(Commands.literal(SoulGreatSwordSkill.FORCE_MODE_DAGGER)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    SoulGreatSwordSkill.FORCE_MODE_DAGGER))));
        } else if (SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(MonstrousAnchorSkill.FORCE_MODE_SHOCKWAVE)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    MonstrousAnchorSkill.FORCE_MODE_SHOCKWAVE))))
                    .then(Commands.literal(MonstrousAnchorSkill.FORCE_MODE_AOE)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    MonstrousAnchorSkill.FORCE_MODE_AOE))));
        } else if (SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING.equals(skill.id())) {
            forceArg = forceArg
                    .then(Commands.literal(AxeOfLightningSkill.FORCE_MODE_BOLTS)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    AxeOfLightningSkill.FORCE_MODE_BOLTS))))
                    .then(Commands.literal(AxeOfLightningSkill.FORCE_MODE_SHOCKWAVE)
                            .executes(ctx -> guarded(ctx, () -> forceWithMode(ctx, skill,
                                    AxeOfLightningSkill.FORCE_MODE_SHOCKWAVE))));
        }

        return Commands.literal(name)
                .then(Commands.literal("status").executes(ctx -> guarded(ctx, () -> statusFor(ctx, skill))))
                .then(Commands.literal("equip").then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> guarded(ctx, () -> equip(ctx, skill)))))
                .then(Commands.literal("clear").then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> guarded(ctx, () -> clear(ctx)))))
                .then(Commands.literal("reset").then(Commands.argument("target", EntityArgument.entity())
                        .executes(ctx -> guarded(ctx, () -> reset(ctx, skill)))))
                .then(forceLiteral);
    }

    // —————————————————————————— status ——————————————————————————

    private static int statusFor(CommandContext<CommandSourceStack> ctx, CompanionSkill skill) throws CommandSyntaxException {
        tell(ctx, " -- " + skill.id() + " -- self-check --");
        if (SkillPriorityConfig.SKILL_INCINERATOR.equals(skill.id())) {
            tell(ctx, "Cataclysm pillar of fire entity class: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (Cataclysm not installed or the class name changed)"));
            tell(ctx, "pillar of fire constructor: " + (CataclysmReflect.isConstructorResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "Incinerator item (" + CataclysmReflect.INCINERATOR_ITEM + "): " + (CataclysmReflect.isItemRegistered(CataclysmReflect.INCINERATOR_ITEM) ? "registered" : "not registered"));
            tell(ctx, "cast sound (" + CataclysmReflect.SWORD_STOMP_SOUND + "): " + (CataclysmReflect.getSwordStompSound() != null ? "registered" : "not registered (sound only, skill unaffected)"));
            tell(ctx, "charge-complete sound (" + CataclysmReflect.FLAME_BURST_SOUND + "): " + (CataclysmReflect.getFlameBurstSound() != null ? "registered" : "not registered (charge completion only plays the arm wave, no sound)"));
            tell(ctx, "playing screen shake (ScreenShake): " + (CataclysmReflect.isScreenShakeResolved() ? "resolved" : "not resolved (no screen shake, skill unaffected)"));
            tell(ctx, "config: enabled=" + MceConfig.enabled()
                    + ", cooldown_ticks=" + MceConfig.cooldownTicks()
                    + ", charge_ticks=" + MceConfig.chargeTicks()
                    + ", charge_break_distance=" + MceConfig.chargeBreakDistance()
                    + ", interrupt_retry_ticks=" + MceConfig.interruptRetryTicks()
                    + ", incinerator_interrupt_damage=" + MceConfig.incineratorInterruptDamage()
                    + ", check_interval_ticks=" + MceConfig.checkIntervalTicks());
        } else if (SkillPriorityConfig.SKILL_INFERNAL_FORGE.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Infernal Forge skill is skipped)"));
            tell(ctx, "Infernal Forge item (" + CataclysmReflect.INFERNAL_FORGE_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.INFERNAL_FORGE_ITEM) ? "registered" : "not registered"));
            tell(ctx, "shockwave damage basis: " + (CataclysmReflect.isOriginDamageResolved()
                    ? "Cataclysm AttributeUtils.OriginDamage reflection (includes item attack bonus, same basis as vanilla)"
                    : "degraded to reading the companion attack attribute directly (reflection layer not resolved, see the [MCE] log)"));
            tell(ctx, "explosion sound (" + CataclysmReflect.EXPLOSION_SOUND + "): "
                    + (CataclysmReflect.getExplosionSound() != null ? "registered" : "not registered (sound only)"));
            tell(ctx, "playing screen shake (ScreenShake): " + (CataclysmReflect.isScreenShakeResolved() ? "resolved" : "not resolved (no screen shake, skill unaffected)"));
            tell(ctx, "ally protection: on (AllyCheck: players / other companions / pets of the owner chain take no damage and are not flung; hostile Monsters still take full damage + knockback)");
            tell(ctx, "shockwave: radius 4 blocks (9x9x9), instant with no charge, launches victims at (x0.5, y+1, z+0.6) after being hit; "
                    + "per requirements the 'burn below half health' effect is not included; auto-cast only fires when the target is within 4.5 blocks");
            tell(ctx, "config: enabled=" + MceConfig.infernalForgeEnabled()
                    + ", infernal_forge_cooldown_ticks=" + MceConfig.infernalForgeCooldownTicks());
        } else if (SkillPriorityConfig.SKILL_SOUL_RENDER.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Soul Render skill is skipped)"));
            tell(ctx, "Soul Render item (" + CataclysmReflect.SOUL_RENDER_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.SOUL_RENDER_ITEM) ? "registered" : "not registered"));
            tell(ctx, "phantom halberd reflection layer (Phantom_Halberd_Entity): "
                    + (CataclysmReflect.isPhantomHalberdResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "two skills: dash (own loop, resolves 3 blocks ahead inside the window, one phantom halberd every 2 ticks along the path, "
                    + "allies hard-filtered by AllyCheck) + spiral summon (7 branches x 5 columns of vanilla phantom halberds, ally damage blocked at the event layer)");
            tell(ctx, "mode check: target >" + MceConfig.soulRenderPreferDashDistance() + " blocks -> dash; "
                    + "closer, or 2+ hostiles within 5 blocks (surrounded) -> spiral summon; still casts with a shield in the off hand (does not go through the vanilla blocking entry)");
            tell(ctx, "ally protection: AllyCheck.isAlly hard filter (players / other companions / pets of the owner chain take no damage and are not displaced), "
                    + "event-layer interception window = 100 ticks after cast");
            tell(ctx, "config: enabled=" + MceConfig.soulRenderEnabled()
                    + ", soul_render_cooldown_ticks=" + MceConfig.soulRenderCooldownTicks()
                    + " (shared by dash/summon)"
                    + ", soul_render_prefer_dash_distance=" + MceConfig.soulRenderPreferDashDistance()
                    + ", soul_render_dash_t=" + MceConfig.soulRenderDashT()
                    + ", soul_render_hold_velocity=" + MceConfig.soulRenderHoldVelocity());
        } else if (SkillPriorityConfig.SKILL_ASTRAPE.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Astrape skill is skipped)"));
            tell(ctx, "Astrape item (" + CataclysmReflect.ASTRAPE_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.ASTRAPE_ITEM) ? "registered" : "not registered"));
            tell(ctx, "lightning spear reflection layer (Lightning_Spear_Entity): "
                    + (CataclysmReflect.isLightningSpearResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "launch sound (" + CataclysmReflect.EMP_ACTIVATED_SOUND + "): "
                    + (CataclysmReflect.getEmpActivatedSound() != null ? "registered" : "not registered (sound only)"));
            tell(ctx, "damage numbers: direct " + String.format("%.1f", CataclysmReflect.astrapeDirectDamage())
                    + " (read from Cataclysm CMCommonConfig.Astrape.damage) / static field "
                    + String.format("%.1f", CataclysmReflect.astrapeAreaDamage()) + " points per 5 ticks (per source, unchanged)");
            tell(ctx, "cast style: instant, no charge (the vanilla charge gate is only about 12 ticks and does not affect damage, so companions fire as soon as ready), "
                    + "gravity-free straight flight + 2.5 acceleration, passes through entities on hit, leaves a static field on block impact (radius 1, actually dies out after about 150 ticks)");
            tell(ctx, "ally protection: AllyCheck.isAlly intercepted at the event layer -- window starts at 160 ticks (covers the whole static field), "
                    + "then extended dynamically while 'damage source direct entity = this companion's lightning entity', until the static field disappears");
            tell(ctx, "config: enabled=" + MceConfig.astrapeEnabled()
                    + ", astrape_cooldown_ticks=" + MceConfig.astrapeCooldownTicks());
        } else if (SkillPriorityConfig.SKILL_VOID_FORGE.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Void Forge skill is skipped)"));
            tell(ctx, "Void Forge item (" + CataclysmReflect.VOID_FORGE_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.VOID_FORGE_ITEM) ? "registered" : "not registered"));
            tell(ctx, "rune reflection layer (Void_Rune_Entity): "
                    + (CataclysmReflect.isVoidRuneResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "screen shake (ScreenShake): " + (CataclysmReflect.isScreenShakeResolved() ? "resolved" : "not resolved (no screen shake, skill unaffected)"));
            tell(ctx, "damage numbers: each rune " + String.format("%.1f", CataclysmReflect.voidForgeRuneDamage())
                    + " (read from Cataclysm CMCommonConfig.VoidForge.runeDamage, default 7.0, magic damage)");
            tell(ctx, "layout: 7 rays (+-0.3/+-0.6/+-0.9 radians) x 5 rows (1.75~8.75 blocks, each row 1 tick later), "
                    + "instant with no charge; landing spots do a ground scan, no rune where there is no floor; auto-cast requires the target within 9.5 blocks");
            tell(ctx, "ally protection: AllyCheck.isAlly intercepted at the event layer -- window starts at " + VoidForgeSkill.GUARD_BASE_TICKS
                    + " ticks, then extended dynamically while 'damage source direct entity = this companion's rune entity', until the runes disappear");
            tell(ctx, "config: enabled=" + MceConfig.voidForgeEnabled()
                    + ", void_forge_cooldown_ticks=" + MceConfig.voidForgeCooldownTicks());
        } else if (SkillPriorityConfig.SKILL_BRONTES.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Brontes skill is skipped)"));
            tell(ctx, "Brontes item (" + CataclysmReflect.BRONTES_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.BRONTES_ITEM) ? "registered" : "not registered"));
            tell(ctx, "throw reflection layer (Brontes_Entity): "
                    + (CataclysmReflect.isThrownBrontesResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "spark reflection layer (Spark_Entity): "
                    + (CataclysmReflect.isSparkResolved() ? "resolved" : "not resolved (the point-blank branch will miss sparks, see the log)"));
            tell(ctx, "storm reflection layer (Lightning_Storm_Entity): "
                    + (CataclysmReflect.isLightningStormResolved() ? "resolved" : "not resolved (the point-blank branch will miss the storm, see the log)"));
            tell(ctx, "damage numbers: direct hit = attack attribute (same basis as vanilla setBaseDamage) / storm "
                    + String.format("%.1f", CataclysmReflect.brontesStormDamage())
                    + " (read from Brontes.stormdamage, default 10.0) / static field "
                    + String.format("%.1f", CataclysmReflect.brontesStormAreaDamage())
                    + " (read from Brontes.stormareadamage, default 1.0)");
            tell(ctx, "two branches: throw (velocity 2.5 + gravity 0.1, parabola elevation auto-compensated, the hammer stays in hand and flies back after a hit) "
                    + "+ point-blank (target within " + MceConfig.brontesMeleeRange() + " blocks: no throw, the vanilla landing spots are replicated right under the enemy: "
                    + "8 radial sparks + lightning storm size 3 + screen shake; a pre-scan that finds an ally inside the ring falls back to a normal attack)");
            tell(ctx, "throw lock: " + (MceConfig.brontesLockAttackWhileThrown() ? "on (brontes_lock_attack_while_thrown=true, "
                    + "the companion does not attack while the hammer is out; positioning/following/dodging unchanged)" : "off (false, vanilla behaviour: melee attacks continue during the throw)")
                    + "; forced recovery is checked every tick: hammer returned / timeout " + MceConfig.brontesThrowTimeoutTicks()
                    + " ticks (brontes_throw_timeout_ticks) / soul stone recalled / re-entering the world / death and respawn "
                    + "all clear the lock and try to find the dropped hammer in the inventory and put it back in the main hand (search the log for 'Brontes force recovery')");
            tell(ctx, "ally protection: AllyCheck.isAlly intercepted at the event layer -- window starts at max(220, timeout+40) ticks, "
                    + "then extended dynamically while 'direct entity = this companion's Brontes hammer / lightning entity'; "
                    + "Brontes_Entity is included in the isCataclysmLightningOf interception set");
            tell(ctx, "config: enabled=" + MceConfig.brontesEnabled()
                    + ", brontes_throw_cooldown_ticks=" + MceConfig.brontesThrowCooldownTicks()
                    + " (shared by throw/point-blank), brontes_melee_range=" + MceConfig.brontesMeleeRange()
                    + ", brontes_lock_attack_while_thrown=" + MceConfig.brontesLockAttackWhileThrown()
                    + ", brontes_throw_timeout_ticks=" + MceConfig.brontesThrowTimeoutTicks());
        } else if (SkillPriorityConfig.SKILL_CERAUNUS.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Ceraunus skill is skipped)"));
            tell(ctx, "Ceraunus item (" + CataclysmReflect.CERAUNUS_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.CERAUNUS_ITEM) ? "registered" : "not registered"));
            tell(ctx, "throw reflection layer (Player_Ceraunus_Entity + shootFromRotation): "
                    + (CataclysmReflect.isThrownCeraunusResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "wave reflection layer (Wave_Entity): "
                    + (CataclysmReflect.isWaveResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "wave sound (" + CataclysmReflect.HEAVY_SMASH_SOUND + "): "
                    + (CataclysmReflect.getHeavySmashSound() != null ? "registered" : "not registered (sound only)"));
            tell(ctx, "damage numbers: throw = attack attribute (same basis as vanilla setBaseDamage) / wave "
                    + String.format("%.1f", CataclysmReflect.ceraunusWaveDamage())
                    + " (read from Cataclysm CMCommonConfig.Ceraunus.waveDamage, default 6.0), waterlogged 200 ticks per source");
            tell(ctx, "two skills: throw (velocity 2.5 + gravity 0.08, parabola elevation auto-compensated, flies back to hand on hit) "
                    + "+ fan waves (4 waves, 25 degrees apart, spawned 2 blocks ahead, the entity climbs 2-block steps on its own)");
            tell(ctx, "mode check: target within " + MceConfig.ceraunusPreferWaveDistance() + " blocks or "
                    + MceConfig.ceraunusWaveMinEnemies() + "+ hostiles within 5 blocks -> wave; otherwise throw; "
                    + "wave drops out (on cooldown / ally in the fan) and falls back to throw");
            tell(ctx, "ally protection: event-layer interception window " + CeraunusSkill.GUARD_BASE_TICKS
                    + " ticks (covers the whole wave path + the throw flight) + wave fan pre-scan "
                    + (MceConfig.ceraunusFriendlyWaveBlock() ? "on (ceraunus_friendly_wave_block=true, "
                            + "any ally inside the fan forces a fallback to throw)" : "off (ceraunus_friendly_wave_block=false, "
                            + "allies are still pushed by the wave, only the damage is blocked)"));
            tell(ctx, "config: enabled=" + MceConfig.ceraunusEnabled()
                    + ", ceraunus_throw_cooldown_ticks=" + MceConfig.ceraunusThrowCooldownTicks()
                    + ", ceraunus_wave_cooldown_ticks=" + MceConfig.ceraunusWaveCooldownTicks()
                    + " (two independent cooldowns)"
                    + ", ceraunus_prefer_wave_distance=" + MceConfig.ceraunusPreferWaveDistance()
                    + ", ceraunus_wave_min_enemies=" + MceConfig.ceraunusWaveMinEnemies()
                    + ", ceraunus_friendly_wave_block=" + MceConfig.ceraunusFriendlyWaveBlock());
        } else if (SkillPriorityConfig.SKILL_SOUL_GREAT_SWORD.equals(skill.id())) {
            tell(ctx, "Legendary Monsters mod: " + (LegendaryMonstersReflect.isLegendaryMonstersPresent() ? "detected" : "not detected (the whole Soul Great Sword skill is skipped)"));
            tell(ctx, "Soul Great Sword item (" + LegendaryMonstersReflect.SOUL_GREAT_SWORD_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(LegendaryMonstersReflect.SOUL_GREAT_SWORD_ITEM) ? "registered" : "not registered"));
            tell(ctx, "Phantom Dagger reflection layer (ThrownPhantomDaggerEntity): "
                    + (LegendaryMonstersReflect.isDaggerClassResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "Phantom Dagger: stand still while charging " + SoulGreatSwordSkill.DAGGER_CHARGE_TICKS + " ticks (reuses the Soul Render framework) -> fire "
                    + SoulGreatSwordSkill.DAGGER_COUNT + " daggers (each " + String.format("%.1f", SoulGreatSwordSkill.DAGGER_DAMAGE)
                    + " points + non-player-owner bonuses: 1% of target max health / Soul Fracture / heal self 3 points -- the branch the mod designed for mob owners, kept per source); "
                    + "lock-on = the companion's current attack target (decided, not a sight ray), the entity starts homing 10 ticks after launch");
            tell(ctx, "passive parry (plan A): " + (MceConfig.soulGreatSwordParryEnabled() ? "on" : "off (soul_great_sword_parry_enabled=false)")
                    + "-- health < max health x " + String.format("%.1f", MceConfig.soulGreatSwordParryHealthThreshold())
                    + " and incoming damage >= " + String.format("%.1f", MceConfig.soulGreatSwordParryMinDamage()) + " triggers automatically: "
                    + "damage immunity + counterattack (attack + 30% of incoming damage) + counter AoE (ally-filtered) + heal 20%; "
                    + "cooldown " + MceConfig.soulGreatSwordParryCooldownTicks() + " tick; "
                    + "armour/invulnerability-ignoring damage cannot be parried (the source check is reused as-is); no parry while charging the dagger (vanilla behaviour)");
            tell(ctx, "event order: parry runs after ally protection and before the shadow gate -- parrying cancels the event, "
                    + "so a cancelled hit never enters hurt and never raises lastHurt, and the shadow gate does not intervene (the two paths cannot conflict)");
            tell(ctx, "ally protection: pre-launch scan (no launch if the target is an ally) + event-layer interception window "
                    + SoulGreatSwordSkill.GUARD_BASE_TICKS + " ticks (covers the dagger's 110-tick lifetime, extended while it is present)");
            tell(ctx, "config: enabled=" + MceConfig.soulGreatSwordEnabled()
                    + ", soul_great_sword_parry_enabled=" + MceConfig.soulGreatSwordParryEnabled()
                    + ", soul_great_sword_parry_cooldown_ticks=" + MceConfig.soulGreatSwordParryCooldownTicks()
                    + ", soul_great_sword_parry_health_threshold=" + MceConfig.soulGreatSwordParryHealthThreshold()
                    + ", soul_great_sword_parry_min_damage=" + MceConfig.soulGreatSwordParryMinDamage()
                    + ", soul_great_sword_dagger_cooldown_ticks=" + MceConfig.soulGreatSwordDaggerCooldownTicks());
        } else if (SkillPriorityConfig.SKILL_TESSERACT.equals(skill.id())) {
            tell(ctx, "Legendary Monsters mod: " + (LegendaryMonstersReflect.isLegendaryMonstersPresent() ? "detected" : "not detected (the whole The Tesseract skill is skipped)"));
            tell(ctx, "The Tesseract item (" + LegendaryMonstersReflect.TESSERACT_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(LegendaryMonstersReflect.TESSERACT_ITEM) ? "registered" : "not registered"));
            tell(ctx, "annihilation portal reflection layer (AnnihilationPortalEntity): "
                    + (LegendaryMonstersReflect.isPortalClassResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "full-charge sound (ultimate_flame_impact): "
                    + (LegendaryMonstersReflect.getTesseractChargeSound() != null ? "registered" : "not registered (sound only)"));
            tell(ctx, "cast sound (flame_burst): "
                    + (LegendaryMonstersReflect.getTesseractFlameBurstSound() != null ? "registered" : "not registered (sound only)"));
            tell(ctx, "cast style: stand still while charging " + MceConfig.tesseractChargeTicks()
                    + " ticks (copied from the vanilla maxChargeTick, 1.5 seconds) -> pentagram of 11 portals (outer ring 5 x r=7.5 + inner ring 5 x r=4 + 1 centre, "
                    + "dangerous=false purely cosmetic, the pull effect was dropped by decision, landing spots copy the vanilla downward ground scan, no portal where there is no floor)");
            tell(ctx, "custom damage: one round every 5 ticks x 10 rounds, outer/inner ring points " + String.format("%.1f", TesseractSkill.RING_DAMAGE)
                    + " points, centre portal " + String.format("%.1f", TesseractSkill.CENTER_DAMAGE)
                    + " points each (vanilla numbers copied, allies filtered by AllyCheck), the 50th tick is the final round and expires with an explosion; "
                    + "the centre portal's 43~52 tick shockwave was dropped by decision (residual vanilla annihilation damage is cancelled by the takeover gate)");
            tell(ctx, "annihilation takeover: vanilla void-basis damage (portal / annihilation nuke residue with causing entity = the companion) is always cancelled "
                    + "to avoid double resolution and 0-damage hits polluting the target's invulnerability ticks; AnnihilationPortalEntity and "
                    + "AnnihilationGroundNukeStrikeEntity are noted in the future Legendary Monsters interception set");
            tell(ctx, "config: enabled=" + MceConfig.tesseractEnabled()
                    + ", tesseract_cooldown_ticks=" + MceConfig.tesseractCooldownTicks()
                    + ", tesseract_charge_ticks=" + MceConfig.tesseractChargeTicks());
        } else if (SkillPriorityConfig.SKILL_BUCKLER.equals(skill.id())) {
            tell(ctx, "Legendary Monsters mod: " + (LegendaryMonstersReflect.isLegendaryMonstersPresent() ? "detected" : "not detected (the whole Buckler of Annihilation skill is skipped)"));
            tell(ctx, "Buckler of Annihilation item (" + LegendaryMonstersReflect.BUCKLER_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(LegendaryMonstersReflect.BUCKLER_ITEM) ? "registered" : "not registered"));
            tell(ctx, "annihilation bomb reflection layer (SmallAnnihilationBombEntity): "
                    + (LegendaryMonstersReflect.isBombClassResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "sounds: cast (dimensional_bomb_explosion2) " + (LegendaryMonstersReflect.getBucklerReleaseSound() != null ? "registered" : "not registered")
                    + " / arm swing (posessed_paladin_swing) " + (LegendaryMonstersReflect.getBucklerSwingSound() != null ? "registered" : "not registered")
                    + " / hit (the_warped_one_stun)" + (LegendaryMonstersReflect.getBucklerHitSound() != null ? "registered" : "not registered (an unregistered sound is the only loss)"));
            tell(ctx, "cast style: instant, no charge (the vanilla 'sneak + hold right click >= 11 ticks + release' is hard-bound to Player, the companion version does not use that entry), "
                    + "auto-cast requires the target within " + MceConfig.bucklerTriggerDistance() + " blocks (buckler_trigger_distance, force is unrestricted)");
            tell(ctx, "impact: the geometry copies the vanilla AABB (own bounding box inflated 0.05/1.5 plus a forward " + MceConfig.bucklerSlashRange()
                    + " block rectangle, no fan added), damage " + String.format("%.1f", MceConfig.bucklerSlashDamage())
                    + " (mobAttack basis, no armour penetration) + knockback (x1.25 floor h=" + MceConfig.bucklerKnockbackMinHorizontal()
                    + "/v=" + MceConfig.bucklerKnockbackMinVertical() + ") + burning for 3 seconds");
            tell(ctx, "annihilation bombs: 3 (inaccuracy 0/15/30 copied, velocity 1, gravity 0.03 parabola), each "
                    + String.format("%.1f", MceConfig.bucklerBombDamage())
                    + " points of legendary_monsters:void basis (whether it pierces armour is decided by that damage type tag, the original resolution is reused through reflection)");
            tell(ctx, "cooldown split: on hit " + MceConfig.bucklerCooldownTicks() + " ticks (buckler_cooldown_ticks) / on miss "
                    + MceConfig.bucklerMissCooldownTicks() + " ticks (buckler_miss_cooldown_ticks), each stored in persistentData; "
                    + "the durability mechanic was dropped by decision; the ally interception window is fixed at " + BucklerSkill.GUARD_BASE_TICKS + " ticks (inside the window the takeover gate yields to the bombs)");
            tell(ctx, "config: enabled=" + MceConfig.bucklerEnabled()
                    + ", buckler_cooldown_ticks=" + MceConfig.bucklerCooldownTicks()
                    + ", buckler_miss_cooldown_ticks=" + MceConfig.bucklerMissCooldownTicks()
                    + ", buckler_slash_damage=" + MceConfig.bucklerSlashDamage()
                    + ", buckler_slash_range=" + MceConfig.bucklerSlashRange()
                    + ", buckler_bomb_damage=" + MceConfig.bucklerBombDamage()
                    + ", buckler_knockback_min_horizontal=" + MceConfig.bucklerKnockbackMinHorizontal()
                    + ", buckler_knockback_min_vertical=" + MceConfig.bucklerKnockbackMinVertical()
                    + ", buckler_trigger_distance=" + MceConfig.bucklerTriggerDistance());
        } else if (SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR.equals(skill.id())) {
            tell(ctx, "Legendary Monsters mod: " + (LegendaryMonstersReflect.isLegendaryMonstersPresent() ? "detected" : "not detected (the whole Monstrous Anchor skill is skipped)"));
            tell(ctx, "Monstrous Anchor item (" + LegendaryMonstersReflect.MONSTROUS_ANCHOR_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(LegendaryMonstersReflect.MONSTROUS_ANCHOR_ITEM) ? "registered" : "not registered"));
            tell(ctx, "stun effect (" + LegendaryMonstersReflect.STUN_EFFECT_ID + "): "
                    + (LegendaryMonstersReflect.stunEffectHolder() != null ? "registered (movement and attack speed each -1 = hard freeze, copied from vanilla)"
                    : "not registered (Legendary Monsters missing / effect renamed, the launch still works but no stun)"));
            tell(ctx, "screen shake reflection layer (CameraShakeEntity.cameraShake): cosmetic only (a mismatch only loses the shake, skill unaffected)");
            tell(ctx, "shockwave: instant, no charge (the vanilla 'hold >= 15 ticks + release' is bound to Player, decided not to add a charge stand-still), "
                    + "centred on self, " + String.format("%.1f", MonstrousAnchorSkill.SHOCKWAVE_RADIUS)
                    + " block AABB (vanilla inflate(3) copied), launch upward 0.6 + STUN for " + MonstrousAnchorSkill.STUN_TICKS / 20
                    + " seconds, no damage (vanilla has none either), the cooldown starts whether or not anything was hit (copied)");
            tell(ctx, "attack carries AOE: passive (event layer) -- after the companion's melee hits, an AOE centred on the victim deals "
                    + String.format("%.1f", MceConfig.monstrousAnchorAoeRadius()) + " blocks AABB resolution "
                    + String.format("%.1f", MceConfig.monstrousAnchorAoeDamage())
                    + " points of PLAYER_ATTACK damage (vanilla hurtEnemy copied), "
                    + "throttled to " + MceConfig.monstrousAnchorAoeMinIntervalTicks()
                    + " ticks (one swing hitting several targets does not trigger repeatedly), no trigger while falling (fallDistance==0 copied)");
            tell(ctx, "cooldown: shockwave " + MceConfig.monstrousAnchorShockwaveCooldownTicks()
                    + " ticks (monstrous_anchor_shockwave_cooldown_ticks, decided 200 = 10 seconds), stored in persistentData");
            tell(ctx, "ally protection: shockwave/AOE filtered one by one via AllyCheck + event-layer interception window "
                    + MonstrousAnchorSkill.GUARD_BASE_TICKS + " ticks; last mode used: "
                    + MonstrousAnchorSkill.getLastMode(ctx.getSource().getEntity()));
            tell(ctx, "config: enabled=" + MceConfig.monstrousAnchorEnabled()
                    + ", monstrous_anchor_shockwave_cooldown_ticks=" + MceConfig.monstrousAnchorShockwaveCooldownTicks()
                    + ", monstrous_anchor_aoe_damage=" + MceConfig.monstrousAnchorAoeDamage()
                    + ", monstrous_anchor_aoe_radius=" + MceConfig.monstrousAnchorAoeRadius()
                    + ", monstrous_anchor_aoe_min_interval_ticks=" + MceConfig.monstrousAnchorAoeMinIntervalTicks());
        } else if (SkillPriorityConfig.SKILL_AXE_OF_LIGHTNING.equals(skill.id())) {
            tell(ctx, "Legendary Monsters mod: " + (LegendaryMonstersReflect.isLegendaryMonstersPresent() ? "detected" : "not detected (the whole Axe of Lightning skill is skipped)"));
            tell(ctx, "Axe of Lightning item (" + LegendaryMonstersReflect.AXE_OF_LIGHTNING_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(LegendaryMonstersReflect.AXE_OF_LIGHTNING_ITEM) ? "registered" : "not registered"));
            tell(ctx, "lightning bolt reflection layer (LightningBoltEntity): "
                    + (LegendaryMonstersReflect.isLightningBoltClassResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "electric burst reflection layer (ElectricityEntity): "
                    + (LegendaryMonstersReflect.isElectricityClassResolved() ? "resolved, can be created by reflection" : "not resolved (see the lines starting with [MCE] in the log)"));
            tell(ctx, "merged skill (decided): lightning bolt wave (12 along the line, 1.25 blocks apart, increasing warmup, life 30, damage "
                    + String.format("%.1f", MceConfig.axeOfLightningBoltDamage())
                    + " points, landing spots copy the vanilla downward scan, no pillar where there is no floor) "
                    + "+ electric burst shockwave (5 in a +-30 degree fan, muzzle 1 block ahead, y = foot level, damage read from the attack attribute, life 20)");
            tell(ctx, "mode: " + MceConfig.axeOfLightningMode()
                    + (MceConfig.axeOfLightningMode().equalsIgnoreCase("auto")
                            ? " (auto mode check: target within " + MceConfig.axeOfLightningMeleeDistance()
                              + " blocks and " + MceConfig.axeOfLightningShockwaveMinEnemies()
                              + "+ hostiles within 5 blocks -> shockwave, otherwise the lightning bolt wave)" : " (branch locked)"));
            tell(ctx, "cooldown: both branches share " + MceConfig.axeOfLightningCooldownTicks()
                    + " ticks (axe_of_lightning_cooldown_ticks, decided 150 = 7.5 seconds), stored in persistentData; "
                    + "the vanilla rule 'no cooldown if nothing spawned' is copied");
            tell(ctx, "ally protection: the entities carry the vanilla filter (isAlliedTo / owner chain / caster itself) + event-layer interception window "
                    + AxeOfLightningSkill.GUARD_BASE_TICKS + " ticks (inside the window any ally damage is cancelled; "
                    + "neither entity displaces anything, so the event-layer fallback covers everything); last mode used: "
                    + AxeOfLightningSkill.getLastMode(ctx.getSource().getEntity()));
            tell(ctx, "config: enabled=" + MceConfig.axeOfLightningEnabled()
                    + ", axe_of_lightning_cooldown_ticks=" + MceConfig.axeOfLightningCooldownTicks()
                    + ", axe_of_lightning_mode=" + MceConfig.axeOfLightningMode()
                    + ", axe_of_lightning_bolt_damage=" + MceConfig.axeOfLightningBoltDamage()
                    + ", axe_of_lightning_melee_distance=" + MceConfig.axeOfLightningMeleeDistance()
                    + ", axe_of_lightning_shockwave_min_enemies=" + MceConfig.axeOfLightningShockwaveMinEnemies());
        } else if (SkillPriorityConfig.SKILL_MEAT_SHREDDER.equals(skill.id())) {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Meat Shredder skill is skipped)"));
            tell(ctx, "Meat Shredder item (" + CataclysmReflect.MEAT_SHREDDER_ITEM + "): "
                    + (CataclysmReflect.isItemRegistered(CataclysmReflect.MEAT_SHREDDER_ITEM) ? "registered" : "not registered"));
            tell(ctx, "cast style: the companion enters the vanilla use state directly (startUsingItem), vanilla onUseTick resolves every tick -- "
                    + "the damage formula (attack attribute normalised + strength + Curios + enchantments divided by 8.5), block penetration, hit particles and the looping sound are all faithful, nothing recomputed");
            tell(ctx, "state machine: enters when distance <= " + MceConfig.meatShredderRange() + " blocks, stops automatically after "
                    + MceConfig.meatShredderMaxDurationTicks() + " ticks of shredding; being hurt does not interrupt (vanilla behaviour); "
                    + "exit distance " + MceConfig.meatShredderExitDistance() + " blocks (includes 0.5 hysteresis)");
            tell(ctx, "ally protection: two layers -- pre-scan (an ally or passive mob in range falls back to a normal attack) + the event-layer shredder window "
                    + "(cataclysm:shredder damage aimed at allies is always cancelled)");
            tell(ctx, "observation log: invulnerability-tick side effects (the shredder resets the target's invulnerability ticks, teammate damage may be absorbed) + off-hand shield conflict"
                    + (MceConfig.meatShredderAllowWithShield() ? " (exempted)" : " (meat_shredder_allow_with_shield=false)"));
            tell(ctx, "config: enabled=" + MceConfig.meatShredderEnabled()
                    + ", meat_shredder_cooldown_ticks=" + MceConfig.meatShredderCooldownTicks()
                    + ", meat_shredder_max_duration_ticks=" + MceConfig.meatShredderMaxDurationTicks()
                    + ", meat_shredder_range=" + MceConfig.meatShredderRange()
                    + ", meat_shredder_allow_with_shield=" + MceConfig.meatShredderAllowWithShield()
                    + ", meat_shredder_slowdown=" + MceConfig.meatShredderSlowdown()
                    + ", meat_shredder_hostile_only=" + MceConfig.meatShredderHostileOnly()
                    + ", meat_shredder_aoe_defer_ticks=" + MceConfig.meatShredderAoeDeferTicks()
                    + ", meat_shredder_iframe_restore=" + MceConfig.meatShredderIframeRestore());
            tell(ctx, "shadow invulnerability (r26): "
                    + (MceConfig.meatShredderIframeRestore()
                            ? "on (during shredding, non-shredder damage is blocked/repaid/released per the shadow ledger, settled when the shredder stops; every action logs the details)"
                            : "off (meat_shredder_iframe_restore=false, back to the r25 stagger-only behaviour)"));
            tell(ctx, "measured shredder DPS (r26): " + MeatShredderSkill.rollingDpsSummary(
                    ctx.getSource().getLevel().getGameTime()));
        } else {
            tell(ctx, "Cataclysm mod: " + (CataclysmReflect.isCataclysmPresent() ? "detected" : "not detected (the whole Bulwark of the Flame skill is skipped)"));
            tell(ctx, "Bulwark item (" + CataclysmReflect.BULWARK_ITEM + "): " + (CataclysmReflect.isItemRegistered(CataclysmReflect.BULWARK_ITEM) ? "registered" : "not registered"));
            tell(ctx, "charge attachment reflection layer (" + CataclysmReflect.CHARGE_ATTACHMENT_NAME + "): "
                    + (CataclysmReflect.isChargeAttachmentReady() ? "plan A available (full vanilla hit/knockback/stun)" : "unavailable, see the next line"));
            tell(ctx, "current plan in use: " + BulwarkSkill.modeReport());
            tell(ctx, "config: enabled=" + MceConfig.bulwarkEnabled()
                    + ", bulwark_cooldown_ticks=" + MceConfig.bulwarkCooldownTicks()
                    + ", bulwark_hold_velocity=" + MceConfig.bulwarkHoldVelocity()
                    + ", bulwark_friendly_knockback=" + MceConfig.bulwarkFriendlyKnockback());
            tell(ctx, "ally protection: " + (MceConfig.bulwarkFriendlyKnockback()
                    ? "off (bulwark_friendly_knockback=true, Cataclysm vanilla behaviour)"
                    : "on (players / other companions / pets of the owner chain are not knocked back and take no charge damage); Wall_Watcher list removal layer: "
                            + (CataclysmReflect.isWatcherStripReady() ? "ready" : "not resolved (Cataclysm field renamed, plan A knockback will not be intercepted)")));
            tell(ctx, "window: full charge t=4 fixed, charge window 8 ticks (no interruption checks inside), direction = current attack target");
        }
        tell(ctx, "scheduling: silence_ticks=" + SkillPriorityConfig.silenceTicks()
                + ", priority order=" + SkillPriorityConfig.orderedIds());
        return 1;
    }

    // —————————————————————————— equip / clear ——————————————————————————

    private static int equip(CommandContext<CommandSourceStack> ctx, CompanionSkill skill) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (!(entity instanceof LivingEntity living)) {
            tell(ctx, "only living entities can hold weapons, target is: " + entity.getType());
            return 0;
        }
        if (entity instanceof Player) {
            tell(ctx, "for players use the vanilla /give " + skill.requiredItem() + ", this command only equips non-player mobs");
            return 0;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(skill.requiredItem()).orElse(null);
        if (item == null) {
            tell(ctx, "Cataclysm is not installed: the item " + skill.requiredItem() + " does not exist, cannot equip");
            return 0;
        }
        living.setItemSlot(skill.preferredSlot(), new ItemStack(item));
        SkillCooldownStore.clearCooldown(entity, skill.id());
        ChargeStateStore.clearAll(entity);
        tell(ctx, "equipped " + IncineratorSkill.describe(living) + " "
                + CompanionSkill.slotLabel(skill.preferredSlot()) + " with " + skill.requiredItem().getPath()
                + " and cleared cooldown/state; give it an attack target (for example hit it) and it will start automatically"
                + (skill.chargeTicks() > 0 ? "charging " + MceConfig.chargeTicks() + " ticks before releasing"
                        : skill.activeTicks() > 0 ? "charge" : "instant cast"));
        SafeGuard.info("command: equip the " + skill.id() + " item -> " + IncineratorSkill.describe(living));
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (!(entity instanceof LivingEntity living)) {
            tell(ctx, "the target is not a living entity");
            return 0;
        }
        living.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        living.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
        if (entity instanceof Mob mob && ChargeStateStore.getState(mob) == ChargeStateStore.CHARGING) {
            // r23：先走一次 endCharge 让技能自己收尾（绞肉锯要停原版使用状态 + 摘减速修饰），
            // 再清状态。收走武器本身仍不记冷却（把 endCharge 记的那次冷却也清掉）。
            CompanionSkill stateSkill = SkillRegistry.byId(ChargeStateStore.stateSkill(mob));
            if (stateSkill != null) {
                stateSkill.endCharge((ServerLevel) mob.level(), mob, mob.level().getGameTime());
            }
            ChargeStateStore.clearState(mob);
            if (stateSkill != null) {
                SkillCooldownStore.clearCooldown(mob, stateSkill.id());
            }
        }
        tell(ctx, "took back the main hand and off hand items of " + IncineratorSkill.describe(living) + " (the charge in progress was discarded)");
        return 1;
    }

    // —————————————————————————— reset / force ——————————————————————————

    /** r30 超立方体专属：/mce tesseract force（不带 target）——五角星固定角度，原地直接释放 */
    private static int forceHere(CommandContext<CommandSourceStack> ctx, CompanionSkill skill) {
        Entity entity = ctx.getSource().getEntity();
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || level.isClientSide) {
            tell(ctx, "casting in place has to run from the mob (/mce tesseract force needs the command executor attached to the target mob; "
                    + "or use the form with a target: /mce tesseract force <mob>])");
            return 0;
        }
        tell(ctx, "note: the in-place cast without a target has been triggered (mob: " + IncineratorSkill.describe(mob) + ")");
        return forceMob(level, mob, skill, ctx);
    }

    /** 原地/带目标共用的强制施放收尾（跳过蓄力状态机，直接纯释放） */
    private static int forceMob(ServerLevel level, Mob mob, CompanionSkill skill, CommandContext<CommandSourceStack> ctx) {
        LivingEntity target = skill.findTarget(mob);
        if (ChargeStateStore.getState(mob) == ChargeStateStore.CHARGING) {
            skill.endCharge(level, mob, level.getGameTime());
            ChargeStateStore.clearState(mob);
            tell(ctx, " (this mob was charging; the forced cast discarded that charge and records no cooldown)");
        }
        boolean ok = skill.release(level, mob, target, level.getGameTime(), true);
        if (ok) {
            tell(ctx, "forced cast finished (cooldown recorded as usual)");
        } else {
            tell(ctx, "cast failed: see the log for the exact reason ([MCE] prefix); usually Legendary Monsters not installed / reflection layer mismatch / all landing spots in mid-air");
        }
        return ok ? 1 : 0;
    }

    private static int reset(CommandContext<CommandSourceStack> ctx, CompanionSkill skill) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        SkillCooldownStore.clearCooldown(entity, skill.id());
        ChargeStateStore.clearAll(entity);
        SoulRenderSkill.clearVolatileState(entity); // 断魂：模式/冲刺伤害/拦截窗口等易变键一并清掉
        AstrapeSkill.clearVolatileState(entity); // 神怒：友军拦截窗口一并清掉
        CeraunusSkill.clearVolatileState(entity); // 霆浪：模式/拦截窗口/波浪冷却键一并清掉
        VoidForgeSkill.clearVolatileState(entity); // 虚空锻锤：拦截窗口一并清掉
        BrontesSkill.clearVolatileState(entity); // 掣雷：模式/投掷锁定/拦截窗口一并清掉
        MeatShredderSkill.clearVolatileState(entity); // 绞肉锯：绞肉手/减速修饰/强制收锯一并清掉
        SoulGreatSwordSkill.clearVolatileState(entity); // 灵魂巨剑：匕首拦截窗口/格挡冷却/force 标记一并清掉
        TesseractSkill.clearVolatileState(entity); // 超立方体：自研伤害循环起点/点位一并清掉
        BucklerSkill.clearVolatileState(entity); // 湮灭圆盾：拦截窗口/打空冷却键/结果标记一并清掉
        MonstrousAnchorSkill.clearVolatileState(entity); // 怪怖之锚：拦截窗口/AOE 节流键/结果标记一并清掉
        AxeOfLightningSkill.clearVolatileState(entity); // 雷霆之钺：拦截窗口/结果标记一并清掉
        tell(ctx, "cleared the " + entity.getName().getString() + " of " + skill.id()
                + " cooldown plus all charge/charge-window/silence/penalty state");
        return 1;
    }

    /** 双技能武器专属：force 指定模式（断魂 dash/windmill、霆浪 throw/wave），跳过情境判定与冷却 */
    private static int forceWithMode(CommandContext<CommandSourceStack> ctx, CompanionSkill skill,
                                     String mode) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || level.isClientSide) {
            tell(ctx, "the target must be a Mob (living creature) in the world");
            return 0;
        }
        if (!skill.holdsItem(mob)) {
            tell(ctx, "note: this mob holds neither " + skill.requiredItem().getPath()
                    + " in main/off hand (forced cast still works, first run /mce " + skill.id() + " equip)");
        }
        LivingEntity target = skill.findTarget(mob);
        boolean modeNeedsTarget = SoulRenderSkill.FORCE_MODE_DASH.equals(mode)
                || CeraunusSkill.FORCE_MODE_THROW.equals(mode)
                || BrontesSkill.FORCE_MODE_THROW.equals(mode)
                || BrontesSkill.FORCE_MODE_BURST.equals(mode)
                || SoulGreatSwordSkill.FORCE_MODE_DAGGER.equals(mode)
                || MonstrousAnchorSkill.FORCE_MODE_AOE.equals(mode)
                || AxeOfLightningSkill.FORCE_MODE_BOLTS.equals(mode)
                || AxeOfLightningSkill.FORCE_MODE_SHOCKWAVE.equals(mode);
        if (modeNeedsTarget && target == null) {
            if (BrontesSkill.FORCE_MODE_THROW.equals(mode) || BrontesSkill.FORCE_MODE_BURST.equals(mode)) {
                tell(ctx, "both Brontes modes need an attack target (the throw needs a direction, the point-blank branch needs a landing spot) -- "
                        + "get it into combat first (for example hit it once) then run /mce brontes force @e[...] " + mode);
                return 0;
            }
            if (SoulGreatSwordSkill.FORCE_MODE_DAGGER.equals(mode)) {
                tell(ctx, "the Phantom Dagger locks on the companion's current attack target -- get it into combat first (for example hit it once) then run it; "
                        + "to test only the parry use /mce soul_great_sword force @e[...] parry (no target needed)");
                return 0;
            }
            if (MonstrousAnchorSkill.FORCE_MODE_AOE.equals(mode)) {
                tell(ctx, "the Monstrous Anchor aoe mode needs an attack target (the AOE is centred on the victim) -- "
                        + "get it into combat first (for example hit it once) then run /mce monstrous_anchor force @e[...] aoe; "
                        + "to test only the shockwave use shockwave (no target needed)");
                return 0;
            }
            if (AxeOfLightningSkill.FORCE_MODE_BOLTS.equals(mode)
                    || AxeOfLightningSkill.FORCE_MODE_SHOCKWAVE.equals(mode)) {
                tell(ctx, "both Axe of Lightning modes need an attack target (direction of the bolt line / facing of the shockwave) -- "
                        + "get it into combat first (e.g. hit it) then run /mce axe_of_lightning force @e[...] " + mode);
                return 0;
            }
            tell(ctx, "mode '" + mode + "' needs an attack target (as the direction source) -- get it into combat first (for example hit it once); "
                    + "to cast in place pick the other mode (/mce " + skill.id() + " force @e[...] "
                    + (CeraunusSkill.FORCE_MODE_THROW.equals(mode)
                            ? CeraunusSkill.FORCE_MODE_WAVE : SoulRenderSkill.FORCE_MODE_WINDMILL) + ")");
            return 0;
        }
        if (ChargeStateStore.getState(mob) == ChargeStateStore.CHARGING) {
            skill.endCharge(level, mob, level.getGameTime());
            ChargeStateStore.clearState(mob);
            tell(ctx, " (this mob was charging; the forced cast discarded that charge and records no cooldown)");
        }
        boolean ok;
        if (skill instanceof SoulRenderSkill) {
            ok = SoulRenderSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else if (skill instanceof CeraunusSkill) {
            ok = CeraunusSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else if (skill instanceof BrontesSkill) {
            ok = BrontesSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else if (skill instanceof SoulGreatSwordSkill) {
            ok = SoulGreatSwordSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else if (skill instanceof MonstrousAnchorSkill) {
            ok = MonstrousAnchorSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else if (skill instanceof AxeOfLightningSkill) {
            ok = AxeOfLightningSkill.forceCast(level, mob, target, mode, level.getGameTime());
        } else {
            ok = skill.release(level, mob, target, level.getGameTime(), true);
        }
        if (ok) {
            tell(ctx, "forced cast of '" + mode + "' finished (cooldown recorded as usual)");
        } else {
            tell(ctx, "cast failed: see the log for the exact reason ([MCE] prefix); usually Legendary Monsters not installed / reflection layer mismatch / all landing spots in mid-air");
        }
        return ok ? 1 : 0;
    }

    private static int force(CommandContext<CommandSourceStack> ctx, CompanionSkill skill) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (!(entity instanceof Mob mob) || !(mob.level() instanceof ServerLevel level) || level.isClientSide) {
            tell(ctx, "the target must be a Mob (living creature) in the world");
            return 0;
        }
        if (!skill.holdsItem(mob)) {
            tell(ctx, "note: this mob holds neither " + skill.requiredItem().getPath()
                    + " in main/off hand (forced cast still works, first run /mce " + skill.id() + " equip)");
        }
        LivingEntity target = skill.findTarget(mob);
        if (target == null) {
            tell(ctx, "it currently has no valid attack target and cannot cast -- get it into combat first (for example hit it once). "
                    + (SkillPriorityConfig.SKILL_BULWARK.equals(skill.id()) ? "(Bulwark per the convention: no charge without a target)" : "")
                    + (SkillPriorityConfig.SKILL_INFERNAL_FORGE.equals(skill.id())
                            ? "(the Infernal Forge shockwave requires the target within 4.5 blocks, it will not fire at a far target)" : "")
                    + (SkillPriorityConfig.SKILL_SOUL_RENDER.equals(skill.id())
                            ? "(no mode = auto check: dash at range, summon when close/surrounded; the dash needs a target, "
                                    + "to summon in place add windmill: /mce soul_render force @e[...] windmill)" : "")
                    + (SkillPriorityConfig.SKILL_CERAUNUS.equals(skill.id())
                            ? "(no mode = auto check: wave when close/surrounded, throw at range; the throw needs a target, "
                                    + "to fire waves in place add wave: /mce ceraunus force @e[...] wave)" : "")
                    + (SkillPriorityConfig.SKILL_VOID_FORGE.equals(skill.id())
                            ? "(the Void Forge layout needs a target for direction, and the target must be within 9.5 blocks -- runes reach 8.75 blocks at most)" : "")
                    + (SkillPriorityConfig.SKILL_BRONTES.equals(skill.id())
                            ? "(auto check: within " + MceConfig.brontesMeleeRange() + " blocks the point-blank storm, further away the throw; "
                                    + "both branches need a target -- the point-blank branch needs a landing spot, the throw needs a direction; "
                                    + "you can pick a mode with throw / burst: /mce brontes force @e[...] throw)" : "")
                    + (SkillPriorityConfig.SKILL_MEAT_SHREDDER.equals(skill.id())
                            ? "(the shredder needs a target for facing -- get it into combat first (for example hit it once) then run it)" : ""));
            return 0;
        }
        // 强制 = 跳过蓄力状态机和冷却，直接走纯释放（壁垒的 8 tick 窗口由状态机接着驱动）
        if (ChargeStateStore.getState(mob) == ChargeStateStore.CHARGING) {
            skill.endCharge(level, mob, level.getGameTime());
            ChargeStateStore.clearState(mob);
            tell(ctx, " (this mob was charging; the forced cast discarded that charge and records no cooldown)");
        }
        boolean ok = skill.release(level, mob, target, level.getGameTime(), true);
        if (ok) {
            tell(ctx, "forced cast finished (cooldown recorded as usual"
                    + (skill.activeTicks() > 0 ? ", the charge window is handed over to the scheduler)" : ")"));
        } else {
            tell(ctx, "cast failed: see the log for the exact reason ([MCE] prefix); usually Cataclysm not installed / reflection layer mismatch / no ground ahead");
        }
        return ok ? 1 : 0;
    }

    // —————————————————————————— skills ——————————————————————————

    private static int skillsList(CommandContext<CommandSourceStack> ctx) {
        return guarded(ctx, () -> {
            tell(ctx, "-- skill priority table (" + SkillPriorityConfig.filePath() + ") -- ");
            var ids = SkillPriorityConfig.orderedIds();
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                CompanionSkill skill = SkillRegistry.byId(id);
                if (skill == null) {
                    tell(ctx, (i + 1) + ". " + id + " -- in the table but not registered in code, ignored by the scheduler");
                    continue;
                }
                tell(ctx, (i + 1) + ". " + id + " -- " + (skill.enabled() ? "on" : "off")
                        + ", cooldown " + skill.cooldownTicks() + " tick"
                        + (skill.chargeTicks() > 0 ? ", charge " + skill.chargeTicks() + " tick" : ", instant cast")
                        + (skill.activeTicks() > 0 ? ", window after cast " + skill.activeTicks() + " tick" : ""));
            }
            tell(ctx, "global silence: " + SkillPriorityConfig.silenceTicks() + " ticks; after editing the file, /mce skills reload applies it immediately");
            return 1;
        });
    }

    private static int skillsReload(CommandContext<CommandSourceStack> ctx) {
        return guarded(ctx, () -> {
            String report = SkillPriorityConfig.reload();
            tell(ctx, "priority table reloaded: " + report);
            return 1;
        });
    }

    private static int skillsShow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Entity entity = EntityArgument.getEntity(ctx, "target");
        if (!(entity instanceof Mob mob)) {
            tell(ctx, "the target must be a Mob (living creature)");
            return 0;
        }
        long now = entity.level().getGameTime();
        byte state = ChargeStateStore.getState(mob);
        String stateName = switch (state) {
            case ChargeStateStore.CHARGING -> "CHARGING";
            case ChargeStateStore.ACTIVE -> "ACTIVE (charge window)";
            default -> "IDLE";
        };
        tell(ctx, " -- " + IncineratorSkill.describe(mob) + " state machine snapshot --");
        tell(ctx, "state: " + stateName + (state == ChargeStateStore.IDLE ? "" : ", skill " + ChargeStateStore.stateSkill(mob)));
        if (state == ChargeStateStore.CHARGING) {
            long start = ChargeStateStore.chargeStart(mob);
            String skillId = ChargeStateStore.stateSkill(mob);
            CompanionSkill sk = SkillRegistry.byId(skillId);
            int need = sk == null ? MceConfig.chargeTicks() : sk.chargeTicks();
            tell(ctx, "progress: " + Math.max(0, now - start) + "/" + need + " ticks (computed from the gameTime delta, resumes across save/reload)");
            tell(ctx, "locked target: " + (ChargeStateStore.lockedTargetRawUuid(mob).isEmpty() ? "none"
                    : ChargeStateStore.lockedTargetRawUuid(mob)
                    + (ChargeStateStore.lockedTarget((ServerLevel) mob.level(), mob) == null ? " (lost, the next scan tick will interrupt)" : "")));
        }
        if (state == ChargeStateStore.ACTIVE) {
            tell(ctx, "window ends at tick " + ChargeStateStore.activeUntil(mob) + " (currently " + now + "), plan "
                    + (ChargeStateStore.getBulwarkMode(mob) == ChargeStateStore.MODE_A ? "A (Cataclysm attachment)" : "B (own implementation)"));
        }
        tell(ctx, "main hand: " + (mob.getItemBySlot(EquipmentSlot.MAINHAND).isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(mob.getItemBySlot(EquipmentSlot.MAINHAND).getItem())));
        tell(ctx, "off hand: " + (mob.getItemBySlot(EquipmentSlot.OFFHAND).isEmpty() ? "empty"
                : BuiltInRegistries.ITEM.getKey(mob.getItemBySlot(EquipmentSlot.OFFHAND).getItem())));
        if (MeatShredderSkill.shredWindowActive(mob)) {
            tell(ctx, "shredder state: " + MeatShredderSkill.progressText(mob, now));
        }
        for (CompanionSkill sk : SkillRegistry.all()) {
            long cd = SkillCooldownStore.remainingTicks(mob, sk.id(), now, sk.cooldownTicks());
            EquipmentSlot held = sk.heldSlot(mob);
            tell(ctx, "cooldown " + sk.id() + ": " + (cd <= 0 ? "ready" : cd + " tick")
                    + (held != null ? " (" + CompanionSkill.slotLabel(held) + " held OK)"
                            : " (holds neither in main nor off hand, the scheduler will skip it)"));
        }
        long retry = Math.max(0, ChargeStateStore.retryAfter(mob) - now);
        long silence = Math.max(0, ChargeStateStore.silenceUntil(mob) - now);
        tell(ctx, "interrupt penalty: " + (retry <= 0 ? "none" : retry + " tick") + ", global silence: " + (silence <= 0 ? "none" : silence + " tick"));
        return 1;
    }

    // —————————————————————————— r55：刷屏开关的运行期切换 ——————————————————————————

    /**
     * /mce scan|trace on|off|default：只改运行期覆盖值，不写配置文件、不用重启。
     * value=null → 取消覆盖，交还给 config/modern_companions_expand.toml 里的值。
     * 顺带清掉扫描日志的限流记忆，改完开关下一扫就能看见结果，不用等 repeat。
     */
    private static int setLogToggle(CommandContext<CommandSourceStack> ctx, boolean scanDebug, Boolean value) {
        String cmd = scanDebug ? "scan" : "trace";
        String what = scanDebug ? "skill scan log (debug.scan_debug_log)" : "shredder x soul-render trace log (debug.shredder_trace_log)";
        if (scanDebug) {
            MceConfig.setScanDebugLogOverride(value);
            SkillTickHandler.resetScanLogState();
        } else {
            MceConfig.setShredderTraceLogOverride(value);
        }
        boolean effective = scanDebug ? MceConfig.scanDebugLog() : MceConfig.shredderTraceLog();
        boolean fileValue = scanDebug ? MceConfig.scanDebugLogInFile() : MceConfig.shredderTraceLogInFile();
        tell(ctx, what + " → " + (value == null ? "default (follows the config file)" : onOff(value))
                + "; it is actually " + onOff(effective) + " now (the config file has " + onOff(fileValue) + ")");
        SafeGuard.info("command: /mce " + cmd + " " + (value == null ? "default" : onOff(value))
                + " -- effective value " + onOff(effective) + " (runtime override, reverts to the config file on restart,"
                + " config/modern_companions_expand.toml was not modified)");
        if (effective) {
            tell(ctx, "reminder: these troubleshooting switches produce a lot of log volume, switch them back with /mce " + cmd + " off or default when done");
        }
        return 1;
    }

    /** /mce scan|trace status：把“生效值 / 覆盖 / 文件值 / 限流参数”一次说清 */
    private static int logToggleStatus(CommandContext<CommandSourceStack> ctx, boolean scanDebug) {
        if (scanDebug) {
            Boolean override = MceConfig.scanDebugLogOverride();
            tell(ctx, "scan log: effectively " + onOff(MceConfig.scanDebugLog())
                    + ", runtime override " + (override == null ? "none (follows the config file)" : onOff(override))
                    + ", config file " + onOff(MceConfig.scanDebugLogInFile()));
            tell(ctx, "rate limit: for the same companion, a changed conclusion prints at most one line every " + MceConfig.scanLogMinIntervalTicks()
                    + " ticks; an unchanged conclusion prints one reminder every " + MceConfig.scanLogRepeatTicks()
                    + " ticks; scan cadence check_interval_ticks=" + MceConfig.checkIntervalTicks());
            tell(ctx, "scan scope: only Modern Companions (plus entities written by /mce or this mod's skills), plain zombies/cows are no longer scanned");
        } else {
            Boolean override = MceConfig.shredderTraceLogOverride();
            tell(ctx, "shredder trace log: effectively " + onOff(MceConfig.shredderTraceLog())
                    + ", runtime override " + (override == null ? "none (follows the config file)" : onOff(override))
                    + ", config file " + onOff(MceConfig.shredderTraceLogInFile()));
            tell(ctx, "purpose: r24 three-way trace for the shredder x soul-render damage spike (onUseTick counter / damage ledger / invulnerability gate warnings)");
        }
        return 1;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    // —————————————————————————— 工具 ——————————————————————————

    /** 命令体统一兜底：任何异常 → 回消息 + 写日志，绝不把异常抛出命令执行链 */
    @FunctionalInterface
    private interface ThrowingBody {
        int run() throws Exception;
    }

    private static int guarded(CommandContext<CommandSourceStack> ctx, ThrowingBody body) {
        try {
            return body.run();
        } catch (CommandSyntaxException cse) {
            tell(ctx, "bad arguments: " + cse.getMessage());
            return 0;
        } catch (Throwable t) {
            SafeGuard.error("command /mce threw (caught, no game impact): ", t);
            tell(ctx, "the command threw internally, written to the log (search [MCE])");
            return 0;
        }
    }

    private static void tell(CommandContext<CommandSourceStack> ctx, String message) {
        try {
            ctx.getSource().sendSystemMessage(Component.literal(SafeGuard.PREFIX + message));
        } catch (Throwable ignored) {
            // 发消息失败也不能崩
        }
    }
}
