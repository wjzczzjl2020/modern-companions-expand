package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.AstrapeSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.AxeOfLightningSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BrontesSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.BucklerSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.CataclysmReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.CeraunusSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.ChargeStateStore;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.MeatShredderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.LegendaryMonstersReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.MonstrousAnchorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SkillRegistry;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulRenderSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulGreatSwordSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.VoidForgeSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/**
 * 同伴技能的友军伤害拦截（事件层兜底）。
 *
 * 各判定窗口，各管各的链路：
 * - 壁垒冲锋（r9）：灾变 ChargeAttachment.tick 命中时的 hurt() 由本类拦——
 *   Wall_Watcher 每 tick 的 setMotion 钉速度由 CataclysmReflect.stripAlliesFromWatchers 摘名单负责；
 * - 断魂战戟（r11）：冲刺/螺旋召唤立起的灾变 Phantom_Halberd_Entity 自己结算伤害
 *   （maledictio_magicae 伤害源的 causing entity = 同伴施法者，事件层能定位到人），
 *   它只认原版 isAlliedTo，玩家/其他同伴往往漏网 → 凡“该同伴的断魂拦截窗口未过”
 *   （SoulRenderSkill.guardWindowActive，释放后 100 tick）且 AllyCheck 判定为友军的伤害一律取消。
 * - 神怒长槊（r16）：鸣雷槊穿透不消失、静电场（Lightning_Area_Effect_Entity）每 5 tick 结算一次、
 *   owner 死后还会变无差别 lightningBolt → 基础窗口 160 tick 起步（AstrapeSkill.guardWindowActive，
 *   覆盖静电场全程有余量）；窗口过后按“伤害源的直接实体 = 该同伴的灾变雷电实体”
 *   （CataclysmReflect.isCataclysmLightningOf，事件驱动零扫描）动态顺延 40 tick，
 *   静电场还在结算就一直在窗内，实体消失后窗口自然过期。
 * - 霆浪锚戟（r18）：投掷锚戟（Player_Ceraunus_Entity，伤害源 causing entity = 同伴）
 *   与扇形波浪（Wave_Entity，mobProjectile(wave, owner)）都在霆浪拦截窗口内按 AllyCheck 取消
 *   （CeraunusSkill.guardWindowActive，释放后 100 tick 覆盖波浪全程 + 投掷飞行有余量）；
 *   波浪的物理击退在事件外，由 CeraunusSkill 释放前的扇形预扫描拦截。
 * - 虚空锻锤（r21）：符文伤害源 indirectMagic(rune, caster) 的 directEntity 是符文本体
 *   （Void_Rune_Entity，已加入 isCataclysmLightningOf 集合）→ 基础窗口 120 tick 起步
 *   （VoidForgeSkill.guardWindowActive），之后按直接实体动态顺延到符文实体消失。
 * - 掣雷巨锤（r21）：直接命中伤害源 directEntity = 锤子本体（Brontes_Entity，已加入拦截集合），
 *   落点风暴/火花静电场本就在集合里 → 基础窗口 max(220, 超时+40) tick 起步
 *   （BrontesSkill.guardWindowActive），之后按直接实体动态顺延。
 * - 掣雷投掷锁定（r21）：brontes_lock_attack_while_thrown 开启且锤子在外面时，
 *   同伴自己的近战伤害（directEntity = 同伴本人）一律取消——比每 tick 清攻击目标更硬的兜底，
 *   保证锁定期间“不出手打人”；锤子本体伤害 directEntity ≠ 同伴本人，不受影响。
 * - 灵魂巨剑（r28）：幻影匕首（传奇怪物 ThrownPhantomDaggerEntity，ghostly 伤害源
 *   causing entity = 同伴）在拦截窗口内按 AllyCheck 取消（SoulGreatSwordSkill.guardWindowActive，
 *   释放后 140 tick 起步——覆盖匕首 110 tick 全生命周期有余量；之后按“直接实体 = 该同伴的
 *   幻影匕首”动态顺延 40 tick，实体自然超时消失后窗口过期）。
 *   注意：灵魂巨剑的“格挡”不在这里——格挡是同伴【挨打】时的免伤（SoulGreatSwordParry，
 *   注册在本监听之后、影子闸门之前），友军误伤打向同伴会先被本监听取消，喂不到格挡。
 * - 超立方体（r30）：同伴版湮灭传送门是 dangerous=false 纯外观，数值伤害由同伴版自研循环结算；
 *   传送门/湮灭核爆残留的原版伤害（口径 legendary_monsters:void + causing entity = 同伴）
 *   一律取消（“湮灭接管”，见 TesseractSkill / LegendaryMonstersReflect.AREA_DAMAGE_CLASS_NOTE）。
 * - 湮灭圆盾（r32）：撞击（mobAttack，directEntity = 同伴本人）与湮灭爆弹（SmallAnnihilationBombEntity
 *   命中时用 causeAnnihilationDamage(owner, owner)——与传送门【同一个】void 口径！）都在
 *   拦截窗口内按 AllyCheck 取消（BucklerSkill.guardWindowActive，释放后固定 100 tick，拍板）。
 *   关键时序：湮灭接管闸门在圆盾窗口激活时必须让路——爆弹的 void 伤害是同伴版圆盾的
 *   正常伤害（不是传送门残留），窗口内只做 AllyCheck 过滤不做无条件取消；窗口外才恢复
 *   “void 口径一律取消”的接管行为。副作用说明：若同伴同时装超立方体+圆盾，窗口内 100 tick
 *   出现的传送门残留 void 伤害会被当爆弹放行——但同伴版传送门是 dangerous=false 纯外观，
 *   理论上不产生任何原版伤害，接管闸门本就是双保险，可接受。
 * - 怪怖之锚（r33）：震击本身无伤害（上抛+晕眩在源头逐个 AllyCheck 过滤）；攻击附带 AOE
 *   （PLAYER_ATTACK，directEntity = 同伴本人）在拦截窗口内按 AllyCheck 取消
 *   （MonstrousAnchorSkill.guardWindowActive，释放后固定 100 tick，拍板）。
 * - 雷霆之钺（r33）：雷击波柱（LightningBoltEntity）/ 电爆冲击（ElectricityEntity）的伤害源
 *   都是 INDIRECT_MAGIC + causing entity = 同伴，实体内置的原版过滤（isAlliedTo/主人链）
 *   漏掉的友军（玩家/其他同伴）在拦截窗口内按 AllyCheck 取消
 *   （AxeOfLightningSkill.guardWindowActive，释放后固定 100 tick，拍板；
 *   两个实体都不产生位移，事件层兜底即全覆盖）。
 * - 绞肉锯（r23）：绞肉状态（CHARGING 且技能 id = meat_shredder）内——
 *   ① cataclysm:shredder 伤害（directEntity = 同伴本人，原版 onUseTick 结算）打向友军一律取消
 *   （绞肉是持续状态，友军可能中途走进搜索盒，事件层兜底不可省）；
 *   ② 同伴绞肉期间的非绞肉出手（普攻等，directEntity = 同伴本人但伤害类型不同）一并取消；
 *   ③ 无敌帧副作用观察：绞肉每次命中把目标 invulnerableTime 重置为 20，
 *   其他同伴技能打向该目标的伤害会被原版 lastHurt 分支吸收——限流日志观察，只记录不干预。
 *
 * 判定顺序（性能）：先查各窗口（persistentData 读），窗口外零成本直通；
 * 窗口内才做 AllyCheck。bulwark_friendly_knockback=true 时只放掉壁垒窗口，其余窗口照常生效。
 */
public final class AllyDamageGuard {

    private AllyDamageGuard() {}

    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SafeGuard.guard("skill ally damage blocked", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            Entity causing = event.getSource().getEntity();
            if (!(causing instanceof Mob caster) || caster == victim) {
                return; // 伤害来源不是生物 / 自伤：与同伴技能无关
            }
            long now = victim.level().getGameTime();

            // —— 窗口零：掣雷投掷锁定的普攻兜底（比窗口检查更早，directEntity 必须是同伴本人）——
            if (MceConfig.brontesLockAttackWhileThrown()
                    && BrontesSkill.isLockActive(caster)
                    && event.getSource().getDirectEntity() == caster) {
                event.setCanceled(true);
                SafeGuard.infoThrottled("mce_br_lockdmg_" + caster.getUUID(), now,
                        "throw lock: blocked " + IncineratorSkill.describe(caster)
                                + " normal attack (hammer still out, brontes_lock_attack_while_thrown=true)");
                return;
            }

            // —— 窗口七：绞肉锯的绞肉状态（r23）——
            boolean shredderWindow = MeatShredderSkill.shredWindowActive(caster);
            // r24 排查日志（方向 2）：绞肉窗口 owner vs 实际攻击者。
            // 结构上窗口读的是该施法者自己的 persistentData（mce_state / mce_state_skill），
            // 每个同伴实例各一份，不存在全局共享表——这条日志负责实证 owner 与攻击者总是同实体。
            if (shredderWindow && MceConfig.shredderTraceLog()
                    && SafeGuard.shouldLog("mce_tr_win_" + caster.getUUID(), now, 40L)) {
                SafeGuard.info("shredder window check: window owner=" + caster.getUUID() + " ("
                        + IncineratorSkill.describe(caster) + ", mce_state=" + ChargeStateStore.getState(caster)
                        + ", mce_state_skill=" + ChargeStateStore.stateSkill(caster)
                        + ") | actual attacker this time=" + caster.getUUID()
                        + "(owner and attacker are always the same entity: the window is judged per entity from the caster's own persistentData, no global sharing)");
            }
            if (shredderWindow && event.getSource().getDirectEntity() == caster
                    && !CataclysmReflect.isShredderDamage(event.getSource())) {
                // 绞肉期间同伴不该有别的出手（NoAi 已停 Goal，这里对漏网普攻做硬兜底，
                // 且不误伤绞肉本身——绞肉伤害源的 directEntity 也是同伴本人，但类型不同）
                event.setCanceled(true);
                SafeGuard.infoThrottled("mce_ms_own_" + caster.getUUID(), now,
                        "shredder window: blocked " + IncineratorSkill.describe(caster)
                                + " non-shredder swing during shredding (directEntity=self)");
                return;
            }

            // —— 窗口十：湮灭圆盾（r32）——放在湮灭接管闸门之前（判定顺序是本轮的关键）：
            // 圆盾爆弹的命中伤害与传送门残留用【同一个】口径（legendary_monsters:void + causing = 同伴），
            // 窗口内它是爆弹的正常伤害，必须放行交给 AllyCheck 过滤；窗口外才按“传送门残留”无条件取消。
            boolean bucklerWindow = BucklerSkill.guardWindowActive(caster, now);

            // —— 窗口十一：怪怖之锚（r33）——震击本身无伤害（上抛+晕眩在源头 AllyCheck 过滤）；
            // 攻击附带 AOE（PLAYER_ATTACK，directEntity = 同伴本人）在窗口内按 AllyCheck 取消兜底
            boolean anchorWindow = MonstrousAnchorSkill.guardWindowActive(caster, now);

            // —— 窗口十二：雷霆之钺（r33）——雷击波柱/电爆冲击的伤害源都是
            // INDIRECT_MAGIC + causing entity = 同伴（实体内置原版过滤漏掉的友军在此取消；
            // 两个实体都不产生位移，事件层兜底即全覆盖；窗口固定 100 tick，拍板）
            boolean axeWindow = AxeOfLightningSkill.guardWindowActive(caster, now);

            // —— 窗口九：超立方体的“原版湮灭伤害接管”（r30）——
            // 同伴名下的传奇怪物湮灭伤害（伤害类型 legendary_monsters:void + causing entity = 同伴，
            // 来源：AnnihilationPortalEntity 到期收尾/中心门冲击波核爆
            // AnnihilationGroundNukeStrikeEntity——同伴版传送门是 dangerous=false 纯外观，
            // 数值伤害由同伴版自研循环结算，原版残留一律取消，避免双重结算/0 点伤害
            // 污染目标无敌帧/怪物 owner 口径的削血上限附加）
            // r32 修正：圆盾窗口激活时让路（那是爆弹伤害不是传送门残留），见上方窗口十注释。
            if (!bucklerWindow && LegendaryMonstersReflect.isAnnihilationDamageOf(event.getSource(), caster)) {
                event.setCanceled(true);
                SafeGuard.infoThrottled("mce_tess_takeover_" + caster.getUUID(), now,
                        "annihilation takeover: cancelled " + IncineratorSkill.describe(caster)
                                + " vanilla annihilation damage of its portals (target " + IncineratorSkill.describe(victim)
                                + ", damage settled by the companion-side custom loop, here we only catch leftovers)");
                return;
            }

            // —— 窗口一：壁垒冲锋（r9）——
            boolean bulwarkWindow = !MceConfig.bulwarkFriendlyKnockback()
                    && ChargeStateStore.getState(caster) == ChargeStateStore.ACTIVE
                    && SkillRegistry.BULWARK_ID.equals(ChargeStateStore.stateSkill(caster));

            // —— 窗口二：断魂战戟的幻影战戟（r11）——
            boolean soulRenderWindow = SoulRenderSkill.guardWindowActive(caster, now);

            // —— 窗口三：神怒长槊的雷电系伤害（r16）——
            // 基础窗口（释放后 160 tick）覆盖静电场全程；过期后若这次伤害的直接实体
            // 还是该同伴的灾变雷电实体（鸣雷槊/静电场/落雷爆发/掣雷锤/虚空符文）→ 续窗 40 tick。
            boolean astrapeWindow = AstrapeSkill.guardWindowActive(caster, now);
            if (!astrapeWindow && CataclysmReflect.isCataclysmLightningOf(
                    event.getSource().getDirectEntity(), caster)) {
                astrapeWindow = true;
                AstrapeSkill.extendGuardWindow(caster, now);
            }

            // —— 窗口四：霆浪锚戟的投掷/波浪伤害（r18）——
            boolean ceraunusWindow = CeraunusSkill.guardWindowActive(caster, now);

            // —— 窗口五：虚空锻锤的符文伤害（r21）——
            boolean voidForgeWindow = VoidForgeSkill.guardWindowActive(caster, now);
            if (!voidForgeWindow && CataclysmReflect.isCataclysmLightningOf(
                    event.getSource().getDirectEntity(), caster)
                    && isVoidRuneDirect(event.getSource().getDirectEntity())) {
                voidForgeWindow = true;
                VoidForgeSkill.extendGuardWindow(caster, now);
            }

            // —— 窗口六：掣雷巨锤的锤子/风暴/火花伤害（r21）——
            boolean brontesWindow = BrontesSkill.guardWindowActive(caster, now);
            if (!brontesWindow && CataclysmReflect.isCataclysmLightningOf(
                    event.getSource().getDirectEntity(), caster)
                    && isBrontesDirect(event.getSource().getDirectEntity())) {
                brontesWindow = true;
                BrontesSkill.extendGuardWindow(caster, now);
            }

            // —— 窗口八：灵魂巨剑的幻影匕首（r28；r29 修正识别）——
            // r29 源码结论：ghostly 伤害源 directEntity = causingEntity = owner 本人（不是匕首本体），
            // 旧判定 isPhantomDaggerOf(getDirectEntity(), ...) 永远匹配不上（死代码）——
            // 改按“伤害类型 = legendary_monsters:ghost 且 causing entity = 该同伴”识别，动态续窗真正生效
            boolean soulGreatSwordWindow = SoulGreatSwordSkill.guardWindowActive(caster, now);
            if (!soulGreatSwordWindow && LegendaryMonstersReflect.isGhostlyDamageOf(
                    event.getSource(), caster)) {
                soulGreatSwordWindow = true;
                SoulGreatSwordSkill.markGuardWindow(caster, now + 40L); // 匕首还在场就续窗，超时自灭后过期
            }

            boolean shredHit = shredderWindow
                    && event.getSource().getDirectEntity() == caster
                    && CataclysmReflect.isShredderDamage(event.getSource());
            if (!bulwarkWindow && !soulRenderWindow && !astrapeWindow && !ceraunusWindow
                    && !voidForgeWindow && !brontesWindow && !soulGreatSwordWindow
                    && !bucklerWindow && !anchorWindow && !axeWindow && !shredHit) {
                return; // 窗口外：与同伴技能无关，直通
            }
            if (!AllyCheck.isAlly(caster, victim)) {
                // 敌方目标：伤害照常。绞肉命中先记一笔（无敌帧副作用观察的数据源）
                if (shredHit) {
                    MeatShredderSkill.recordShredHit(victim, now);
                }
                observeInvulnSideEffect(caster, victim, now, shredHit);
                return;
            }
            event.setCanceled(true);
            SafeGuard.infoThrottled("mce_sr_allydmg_" + caster.getUUID(), now,
                    "ally protection: blocked " + IncineratorSkill.describe(caster)
                            + " " + (bulwarkWindow ? "Bulwark of the Flame charge"
                            : soulRenderWindow ? "phantom halberd"
                            : soulGreatSwordWindow ? "Soul Great Sword dagger"
                            : brontesWindow ? "Brontes"
                            : voidForgeWindow ? "void rune"
                            : ceraunusWindow ? "Ceraunus"
                            : bucklerWindow ? "Buckler of Annihilation (impact/bomb)"
                            : anchorWindow ? "Monstrous Anchor (attack carries AOE)"
                            : axeWindow ? "Axe of Lightning (pillar/burst)"
                            : shredHit ? "shredder"
                            : "Astrape lightning")
                            + " damage (target " + IncineratorSkill.describe(victim) + ", AllyCheck reports ally)");
        });
    }

    /**
     * 无敌帧副作用观察（r23 拍板）：绞肉每次成功命中都会把目标的 invulnerableTime 重置为 20，
     * 之后 10 tick 内其他来源的伤害（包括同伴的其他技能、队友的攻击）会被原版 hurt() 的
     * “amount <= lastHurt”分支吸收。这里在“同伴技能伤害打向敌方目标、且该目标刚被绞肉命中过”
     * 时打限流日志，供用户观察该副作用是否影响同伴和其他技能的联动。
     * （只观察不干预：绞肉本身是拍板要保留的原版机制。）
     */
    private static void observeInvulnSideEffect(Mob caster, LivingEntity victim, long now, boolean shredHit) {
        try {
            if (shredHit || victim.invulnerableTime <= 10 || !MeatShredderSkill.recentlyShredHit(victim, now)) {
                return;
            }
            SafeGuard.infoThrottled("mce_ms_invuln_" + victim.getUUID(), now,
                    "invulnerability tick watch: target " + IncineratorSkill.describe(victim)
                            + " invulnerability ticks are high after the shredder reset (invulnerableTime=" + victim.invulnerableTime
                            + ", hit by the shredder within 60 ticks), this damage from companion skills may be absorbed by vanilla invulnerability ticks"
                            + " - this is a side effect of the shredder's vanilla mechanics, if it really matters consider warning other skills or tuning the shredder damage interval");
        } catch (Throwable ignored) {
        }
    }

    /** 直接实体是不是虚空符文本体（符文窗口专属顺延，避免把掣雷的顺延挂到神怒头上） */
    private static boolean isVoidRuneDirect(Entity directEntity) {
        try {
            return directEntity != null && "com.github.L_Ender.cataclysm.entity.projectile.Void_Rune_Entity"
                    .equals(directEntity.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直接实体是不是掣雷锤本体（掣雷窗口专属顺延，风暴/静电场已归神怒的动态顺延管） */
    private static boolean isBrontesDirect(Entity directEntity) {
        try {
            return directEntity != null && "com.github.L_Ender.cataclysm.entity.projectile.Brontes_Entity"
                    .equals(directEntity.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }
}