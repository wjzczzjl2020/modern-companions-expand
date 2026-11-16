package cn.blockforge.generated.moderncompanionsexpand.event;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.skill.IncineratorSkill;
import cn.blockforge.generated.moderncompanionsexpand.skill.LegendaryMonstersReflect;
import cn.blockforge.generated.moderncompanionsexpand.skill.SoulGreatSwordSkill;
import cn.blockforge.generated.moderncompanionsexpand.util.CompanionDetector;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingHealEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幻影匕首“强制玩家 owner 分支”闸门（r29）。
 *
 * 源码结论（docs/ANALYSIS_ROUND29_PHANTOM_DAGGER_BRANCH.md）：
 * ThrownPhantomDaggerEntity.onHitEntity 里的分支判定就是编译进字节码的
 * {@code owner instanceof Player}，反射改不了判定本身；同伴（Mob）必然掉进非玩家分支——
 * ① getDamage() 返回原始 8 点（无乘区）；② 附加 target 最大生命 1% 伤害（MathUtils.toPercent）；
 * ③ hurt 成功后 EntityUtil.applyStackingEffect 施加 Soul Fracture（1 级、每 10 秒）；
 * ④ livingOwner.heal(3)（owner 回 3 点血）。
 *
 * 两个候选方案里选了【事件层拦截】而不是【给匕首挂假 Player owner】：
 * - 假 Player（FakePlayer）会把击杀归属、仇恨目标、掉落判定都引到一个不在世界里的玩家实体上，
 *   还会让 getDamage() 吃进传奇怪物自己的玩家乘区配置（SoulGreatSwordAbilityDamageMultiplier），
 *   “基础 8 点”就不再可控——稳定性差；
 * - 事件层零外来实体、金额完全可控，且与本项目既有的 AllyDamageGuard/影子闸门框架同构。
 *
 * 四件事各管各的（全部包 SafeGuard，判定出错按“放行/不动”兜底）：
 * 1. onIncomingDamage：认出“同伴名下匕首的 ghost 伤害”后，把结算金额压回基础 8 点
 *    （剥离 ①② 里的百分比附加，后续护甲/抗性/药水照常按 ghost 伤害类型正常结算）；
 *    soul_great_sword_dagger_monster_branch=true 时整段跳过，原样保留怪物分支。
 * 2. Soul Fracture 拦截（③）：NeoForge 1.21.1 的 MobEffectEvent.Added 不可取消（javap 核实），
 *    所以在入伤事件里先给目标拍一张“当前 Soul Fracture 快照”，等本 tick 结束（伤害管线与
 *    addEffect 都已跑完）再按快照回退——之前没有就移除，之前有就恢复原等级/剩余时长。
 * 3. onHeal：LivingHealEvent 可取消——同伴在匕首命中后 2 tick 内恰好回 3.0 点血即视为 ④，
 *    取消并日志（金额不对或窗口外的不动，绝不误伤其它治疗来源）。
 * 4. 日志口径（拍板要求）：每次匕首命中都打印“走的哪个分支 + 最终伤害 + 是否剥离了百分比附加”。
 *
 * 识别口径（r29 关键修正）：causeGhostlyDamage(owner, owner) 把 directEntity/causingEntity 都设成
 * owner 本人，getSource().getDirectEntity() 永远不是匕首本体——必须按
 * “伤害类型 = legendary_monsters:ghost 且 causing entity = 同伴”识别（isGhostlyDamageOf）。
 */
public final class SoulGreatSwordDaggerGate {

    private static final String SOUL_FRACTURE_EFFECT_ID = "legendary_monsters:soul_fracture";

    /** Soul Fracture 效果 Holder（注册表懒查询缓存；查不到 = 模组没装/改名，拦截整体跳过） */
    private static volatile Holder<MobEffect> soulFractureHolder;

    /** Soul Fracture 回退队列：victim UUID → 待回退记录（下一 tick 处理，防同 tick 竞态） */
    private static final Map<UUID, FracturePending> PENDING_FRACTURE = new ConcurrentHashMap<>();

    /** owner 回血拦截窗口：caster UUID → 匕首命中时的游戏 tick */
    private static final Map<UUID, Long> HEAL_PENDING = new ConcurrentHashMap<>();

    private SoulGreatSwordDaggerGate() {}

    /** Soul Fracture 快照（入伤事件时目标的已有状态，用于回退） */
    private record FractureSnapshot(boolean present, int amplifier, int duration) {}

    private record FracturePending(LivingEntity victim, FractureSnapshot snapshot, long hitTick) {}

    // ════════════════════════ 事件入口（GeneratedMod 注册） ════════════════════════

    /** 入伤事件：把同伴匕首的伤害压回基础 8 点（玩家分支），并布置 Soul Fracture 回退 */
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        SafeGuard.guard("Phantom Dagger forced player branch", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            DamageSource source = event.getSource();
            Mob caster = daggerOwnerCompanion(source);
            if (caster == null) {
                return; // 不是同伴名下匕首的 ghost 伤害：与本闸门无关
            }
            long now = victim.level().getGameTime();
            float original = event.getOriginalAmount();
            float base = SoulGreatSwordSkill.DAGGER_DAMAGE;

            if (MceConfig.soulGreatSwordDaggerMonsterBranch()) {
                SafeGuard.infoThrottled("mce_dag_branch_monster_" + caster.getUUID(), now,
                        "Phantom Dagger hit: branch = monster branch (soul_great_sword_dagger_monster_branch=true, kept as-is)"
                                + " | event-layer original=" + String.format("%.2f", original)
                                + " points (includes the 1% max-health bonus) | victim " + IncineratorSkill.describe(victim));
                return; // 怪物分支：金额/效果/回血全按原版
            }

            // —— 玩家分支（强制）：金额压回基础 8 点，剥离百分比附加（8 点之外的即目标最大生命 1%）——
            float extra = Math.max(0.0F, original - base);
            event.setAmount(base);
            SafeGuard.info("Phantom Dagger hit: branch = player branch (forced) | companion " + IncineratorSkill.describe(caster)
                    + " → " + IncineratorSkill.describe(victim)
                    + " | event-layer original=" + String.format("%.2f", original)
                    + " points | percentage bonus stripped=" + String.format("%.2f", extra)
                    + " points (1% of the target's max health" + (extra > 0.001F ? ", stripped" : ", no bonus on this hit")
                    + ") | resolved amount=" + String.format("%.2f", base)
                    + " points (armour/resistance then resolve normally as the ghost damage type)");

            // Soul Fracture 回退准备：addEffect 发生在 hurt() 返回之后（入伤事件之后），
            // 这里先拍快照，本 tick 的 ServerTickEvent.Post 再按快照回退
            Holder<MobEffect> holder = soulFractureHolder();
            if (holder != null) {
                MobEffectInstance existing = victim.getEffect(holder);
                PENDING_FRACTURE.putIfAbsent(victim.getUUID(), new FracturePending(victim,
                        new FractureSnapshot(existing != null, existing != null ? existing.getAmplifier() : 0,
                                existing != null ? existing.getDuration() : 0),
                        now));
            }
            HEAL_PENDING.put(caster.getUUID(), now);
        });
    }

    /** 伤害管线末尾（金额已定）：补一条最终生效日志，方便和入伤金额对账 */
    public static void onDamagePost(LivingDamageEvent.Post event) {
        SafeGuard.guard("Phantom Dagger final damage log", () -> {
            LivingEntity victim = event.getEntity();
            if (victim.level().isClientSide) {
                return;
            }
            DamageSource source = event.getSource();
            Mob caster = daggerOwnerCompanion(source);
            if (caster == null) {
                return;
            }
            boolean monster = MceConfig.soulGreatSwordDaggerMonsterBranch();
            SafeGuard.infoThrottled("mce_dag_final_" + caster.getUUID() + "_" + victim.getUUID(), victim.level().getGameTime(),
                    "Phantom Dagger final resolution: branch=" + (monster ? "monster branch (as-is)" : "player branch (forced)")
                            + " | finally applied=" + String.format("%.2f", event.getNewDamage())
                            + " points | victim " + IncineratorSkill.describe(victim)
                            + " | " + (monster ? "with the 1% max-health bonus (vanilla monster branch, untouched)" : "no percentage bonus"));
        });
    }

    /** owner 回血（heal(3)）拦截：同伴在匕首命中后 2 tick 内恰好回 3.0 点血才认定为该分支产物 */
    public static void onHeal(LivingHealEvent event) {
        SafeGuard.guard("Phantom Dagger owner heal interception", () -> {
            if (MceConfig.soulGreatSwordDaggerMonsterBranch()) {
                return; // 怪物分支：回血原样保留
            }
            LivingEntity healer = event.getEntity();
            if (!CompanionDetector.isCompanion(healer)) {
                return; // 只有同伴才会是我们匕首的 owner
            }
            if (Math.abs(event.getAmount() - 3.0F) > 0.001F) {
                return; // 金额对不上：不是 heal(3)，绝不误伤其它治疗
            }
            long now = healer.level().getGameTime();
            Long hitTick = HEAL_PENDING.get(healer.getUUID());
            if (hitTick == null || now - hitTick > 2L) {
                return; // 窗口外：与匕首无关
            }
            HEAL_PENDING.remove(healer.getUUID());
            event.setCanceled(true);
            SafeGuard.info("Phantom Dagger hit: the non-player branch heal was stripped | companion " + IncineratorSkill.describe(healer)
                    + " heal(3.0) cancelled (forced player branch: no heal for the owner)");
        });
    }

    /** 每 tick 收尾：处理 Soul Fracture 回退队列 + 清理过期窗口 */
    public static void onServerTickPost(ServerTickEvent.Post event) {
        SafeGuard.guard("Phantom Dagger Soul Fracture rollback", () -> {
            if (PENDING_FRACTURE.isEmpty() && HEAL_PENDING.isEmpty()) {
                return;
            }
            ServerLevel level = event.getServer().overworld();
            long now = level.getGameTime();
            if (!PENDING_FRACTURE.isEmpty()) {
                for (Map.Entry<UUID, FracturePending> entry : PENDING_FRACTURE.entrySet()) {
                    FracturePending pending = entry.getValue();
                    if (now <= pending.hitTick()) {
                        continue; // 留到下一 tick：确保 addEffect 已执行完
                    }
                    PENDING_FRACTURE.remove(entry.getKey());
                    if (MceConfig.soulGreatSwordDaggerMonsterBranch()) {
                        continue; // 中途切成怪物分支：不再回退
                    }
                    revertSoulFracture(pending, now);
                }
            }
            if (HEAL_PENDING.size() > 256) {
                HEAL_PENDING.clear(); // 防极端情况堆积；正常每发齐射只留 1 条
            }
        });
    }

    /** 按快照回退目标的 Soul Fracture（之前没有 → 移除；之前有 → 恢复原等级/剩余时长） */
    private static void revertSoulFracture(FracturePending pending, long now) {
        try {
            Holder<MobEffect> holder = soulFractureHolder();
            LivingEntity victim = pending.victim();
            if (holder == null || victim == null || victim.isRemoved() || victim.isDeadOrDying()) {
                return;
            }
            MobEffectInstance current = victim.getEffect(holder);
            if (current == null) {
                return; // 没新增（可能本来就没打上），无事可做
            }
            FractureSnapshot snap = pending.snapshot();
            if (!snap.present()) {
                victim.removeEffect(holder);
                SafeGuard.info("Phantom Dagger hit: Soul Fracture was stripped | victim " + IncineratorSkill.describe(victim)
                        + " the newly added max-health reduction was removed (forced player branch: no Soul Fracture applied)");
                return;
            }
            if (current.getAmplifier() != snap.amplifier() || current.getDuration() != snap.duration()) {
                victim.removeEffect(holder);
                victim.addEffect(new MobEffectInstance(holder, snap.duration(), snap.amplifier()));
                SafeGuard.info("Phantom Dagger hit: Soul Fracture was stripped | victim " + IncineratorSkill.describe(victim)
                        + " the original max-health reduction was restored (level " + snap.amplifier() + ", " + snap.duration()
                        + " ticks left; the new layer this dagger applied was rolled back)");
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_dag_fracture_err", now,
                    "Phantom Dagger: the Soul Fracture rollback threw (caught, the target is left as-is): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    // ════════════════════════ 内部工具 ════════════════════════

    /**
     * 这笔伤害是不是“现代同伴名下匕首”的 ghost 伤害？是则返回同伴（owner），否则 null。
     * 依据 r29 源码结论：ghost 伤害源的 causingEntity = owner 本人，同伴本人不天然造成 ghost 伤害，
     * 唯一来源就是它名下的幻影匕首（onHitEntity 只认 owner，不认 directEntity）。
     */
    private static Mob daggerOwnerCompanion(DamageSource source) {
        try {
            if (source == null || !source.is(LegendaryMonstersReflect.GHOST_DAMAGE_TYPE)) {
                return null;
            }
            if (!(source.getEntity() instanceof Mob mob)) {
                return null;
            }
            if (!CompanionDetector.isCompanion(mob)) {
                return null; // 传奇怪物自己的怪物（如碎裂使徒）名下的匕首：原版行为，不干预
            }
            if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
                return null;
            }
            return mob;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Soul Fracture 效果 Holder（懒查询 + 缓存；查不到返回 null，调用方整体跳过） */
    private static Holder<MobEffect> soulFractureHolder() {
        Holder<MobEffect> cached = soulFractureHolder;
        if (cached != null) {
            return cached;
        }
        try {
            Holder<MobEffect> found = BuiltInRegistries.MOB_EFFECT.getHolder(
                    ResourceKey.create(Registries.MOB_EFFECT,
                            ResourceLocation.parse(SOUL_FRACTURE_EFFECT_ID))).orElse(null);
            if (found != null) {
                soulFractureHolder = found;
            }
            return found;
        } catch (Throwable t) {
            return null;
        }
    }
}
