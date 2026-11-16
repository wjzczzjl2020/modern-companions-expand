package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.config.MceConfig;
import cn.blockforge.generated.moderncompanionsexpand.config.SkillPriorityConfig;
import cn.blockforge.generated.moderncompanionsexpand.util.AllyCheck;
import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * 怪怖之锚（legendary_monsters:monstrous_anchor，传奇怪物）同伴技能：震击晕眩（能力 1）。
 * 能力 2（攻击附带 AOE）是被动，在事件层 {@link cn.blockforge.generated.moderncompanionsexpand.event.MonstrousAnchorAoe}。
 *
 * 路线（r33 拍板，源码见 docs/ANALYSIS_ROUND33_ANCHOR_AXE.md、实现细节见 docs/IMPL_ROUND33_ANCHOR_AXE.md）：
 * - 瞬发：原版“按住右键 ≥15 tick（0.75 秒）+ 松手”绑死 Player（releaseUsing 第一行
 *   instanceof Player），同伴近身蓄力容易被控到死，拍板改为瞬发 + 长冷却；
 * - 震击：判定几何照抄原版 AABB——caster.getBoundingBox().inflate(3)（四周扩 3 格，
 *   原版就是 AABB 不是球形，配置项不另开，拍板照抄 3 格）；
 * - 效果：push(0, 0.6f, 0) 上抛 + STUN 晕眩 60 tick（legendary_monsters:stun，
 *   给移速/攻速各挂 -1 的 ADD_VALUE 修正 = 硬冻结移动和攻击，从原版药水效果注册表直接查，
 *   查不到只少晕眩不打断技能）+ 铁傀修复音效 + 震屏（CameraShakeEntity 反射，纯观感），
 *   无伤害（原版震击就没有伤害，拍板照抄）；
 * - 冷却（拍板 200 tick = 10 秒，原版 100 tick 对同伴太短会控到死）：
 *   配置项 monstrous_anchor_shockwave_cooldown_ticks，存 persistentData；
 *   原版不管震没震到人都进冷却（照抄），打空照样记冷却防刷屏；
 * - r37 触发距离门：monstrous_anchor_shockwave_trigger_distance（r38 拍板默认 1.0，
 *   贴脸释放——比 3 格震击 AABB 略小，确保敌人在实际打击范围内；默认值改动见 MceConfig）——
 *   以同伴为中心 inflate(本值) 扫描盒内一个敌对目标都没有就不释放、不进冷却
 *   （修复“离敌人很远也空放震击，打空后冲过去普攻”）；命令 force 不受此限；
 * - 攻击附带 AOE（能力 2）：被动，同伴近战命中后以【被击中目标】为中心 3 格 AABB
 *   结算 6 点 PLAYER_ATTACK 伤害，节流 10 tick（monstrous_anchor_aoe_min_interval_ticks，
 *   一刀多目标不再重复触发），见 MonstrousAnchorAoe；r37 起每次触发打印逐目标
 *   过滤/结算明细（发现 N 个实体 / 结算 M 个目标），实锤“只打到一个额外目标”的原因；
 * - 友军保护：震击逐个 AllyCheck 过滤（友军不上抛不晕眩）+ AllyDamageGuard 怪怖之锚窗口
 *   （固定 100 tick，拍板）兜底 AOE 伤害；原版过滤（自己/无敌玩家/自己驯服的动物）
 *   全部包含在 AllyCheck 口径内；
 * - 优先级：meat_shredder 附近（贴脸系，拍板），排绞肉锯之后、灵魂巨剑之前；
 * - 命令：/mce monstrous_anchor status|equip|clear|reset|force（force 带 shockwave|aoe）。
 */
public final class MonstrousAnchorSkill implements CompanionSkill {

    public static final MonstrousAnchorSkill INSTANCE = new MonstrousAnchorSkill();

    public static final String FORCE_MODE_SHOCKWAVE = "shockwave";
    public static final String FORCE_MODE_AOE = "aoe";

    /** 友军拦截窗口：震击瞬发 + AOE 兜底，固定 100 tick（拍板） */
    public static final long GUARD_BASE_TICKS = 100L;

    /** 震击半径（原版 getBoundingBox().inflate(3) 照抄，四周扩 3 格 AABB） */
    public static final double SHOCKWAVE_RADIUS = 3.0D;
    /** 震击上抛（原版 push(0, 0.6f, 0) 照抄） */
    private static final float PUSH_VERTICAL = 0.6F;
    /** 晕眩时长（原版 STUN 效果 60 tick = 3 秒 照抄） */
    public static final int STUN_TICKS = 60;

    /** persistentData 键：友军拦截窗口截止 tick / AOE 上次触发 tick / 最近一次模式（status 展示用） */
    private static final String KEY_GUARD_UNTIL = "mce_ma_guard_until";
    private static final String KEY_AOE_LAST = "mce_ma_aoe_last";
    private static final String KEY_LAST_MODE = "mce_ma_last";

    private MonstrousAnchorSkill() {}

    // ════════════════════════ CompanionSkill ════════════════════════

    @Override
    public String id() {
        return SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR;
    }

    @Override
    public net.minecraft.resources.ResourceLocation requiredItem() {
        return LegendaryMonstersReflect.MONSTROUS_ANCHOR_ITEM;
    }

    /** 怪怖之锚是剑类主手武器 */
    @Override
    public EquipmentSlot preferredSlot() {
        return EquipmentSlot.MAINHAND;
    }

    @Override
    public boolean enabled() {
        return MceConfig.monstrousAnchorEnabled();
    }

    /** 调度器冷却口径 = 震击冷却（拍板 200 tick）；AOE 是被动不占调度冷却 */
    @Override
    public int cooldownTicks() {
        return MceConfig.monstrousAnchorShockwaveCooldownTicks();
    }

    /** 瞬发：原版 15 tick 蓄力门槛绑 Player，同伴版不做蓄力站定（拍板） */
    @Override
    public int chargeTicks() {
        return 0;
    }

    @Override
    public boolean release(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        return tryCast(level, caster, target, now, ignoreCooldown);
    }

    /**
     * 震击纯释放（force 命令与调度器共用；目标可为空——震击以同伴自身为中心）。
     *
     * @return 是否释放成功（照抄原版“不管震没震到都进冷却”，只要走到这里就 true）
     */
    public static boolean tryCast(ServerLevel level, Mob caster, LivingEntity target, long now, boolean ignoreCooldown) {
        int cooldownTicks = MceConfig.monstrousAnchorShockwaveCooldownTicks();
        if (!ignoreCooldown && !SkillCooldownStore.isReady(caster, SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR, now, cooldownTicks)) {
            SafeGuard.infoThrottled("mce_ma_cooling_" + caster.getUUID(), now,
                    "Monstrous Anchor: " + IncineratorSkill.describe(caster) + " shockwave cooldown left "
                            + SkillCooldownStore.remainingTicks(caster, SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR, now, cooldownTicks)
                            + " ticks, skipped this time");
            return false;
        }
        if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
            SafeGuard.warnThrottled("mce_ma_nolm_" + level.dimension().location(), now,
                    "Monstrous Anchor: Legendary Monsters not detected (the STUN effect and screen shake depend on it), cast cancelled");
            return false;
        }
        EquipmentSlot slot = INSTANCE.heldSlot(caster);
        if (slot == null || caster.getItemBySlot(slot).isEmpty()) {
            SafeGuard.warn("Monstrous Anchor: " + IncineratorSkill.describe(caster) + " no longer holding the Monstrous Anchor, cast cancelled");
            return false;
        }

        // —— r37 触发距离门（拍板：距离内没有敌人不释放；force 不受限，走到这里 ignoreCooldown=false）——
        // 震击本体以同伴为中心 3 格 AABB，原版要近身蓄力才放得出；同伴版瞬发后曾出现
        // 离敌人很远也空放（打空后冲过去普攻）。这里以同伴为中心 inflate(触发距离) 扫描盒，
        // 一个敌对目标都没有就不释放——markCast 在后面才执行，所以空放不消耗冷却。
        if (!ignoreCooldown) {
            LivingEntity nearbyEnemy = AllyCheck.findEnemyNear(caster, MceConfig.monstrousAnchorShockwaveTriggerDistance());
            if (nearbyEnemy == null) {
                SafeGuard.infoThrottled("mce_ma_far_" + caster.getUUID(), now,
                        "Monstrous Anchor: " + IncineratorSkill.describe(caster) + " trigger distance "
                                + MceConfig.monstrousAnchorShockwaveTriggerDistance()
                                + " blocks (monstrous_anchor_shockwave_trigger_distance) contains no enemy"
                                + "(the shockwave itself only covers a 3-block AABB), not cast this time and no cooldown");
                return false;
            }
        }

        // —— 朝向：有目标就面向目标（震击本身以自身为中心，朝向纯视觉）——
        if (target != null) {
            float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
            caster.setYRot(yawDegrees);
            caster.setXRot(0.0F);
            caster.setYHeadRot(yawDegrees);
        }

        int stunned = performShockwave(level, caster, now);

        // 冷却照原版：不管震没震到人都进冷却（拍板保留，防打空时逐 tick 刷）
        SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR, now);
        setLastMode(caster, FORCE_MODE_SHOCKWAVE);
        markGuardWindow(caster, now + GUARD_BASE_TICKS);
        caster.swing(slot == EquipmentSlot.OFFHAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, true);
        ChargeStateStore.markSilence(caster, now + SkillPriorityConfig.silenceTicks());
        SafeGuard.info("Monstrous Anchor: " + IncineratorSkill.describe(caster) + " slammed the ground, "
                + SHOCKWAVE_RADIUS + " stunned " + stunned + " targets inside the AABB (launch upward 0.6 + STUN "
                + STUN_TICKS / 20 + " seconds + the iron golem repair sound + screen shake, no damage as in vanilla), "
                + "cooldown " + cooldownTicks + " tick" + (ignoreCooldown ? " (command forced)" : "")
                + ", ally interception window " + GUARD_BASE_TICKS + " tick");
        return true;
    }

    // ════════════════════════ 震击（自研复刻，几何照抄原版 AABB） ════════════════════════

    /**
     * 震击本体：caster.getBoundingBox().inflate(3) 内所有生物逐个过滤后
     * push(0, 0.6, 0) + STUN 60 tick。
     *
     * @return 晕眩的目标数（友军/自己/无敌单位不算）
     */
    public static int performShockwave(ServerLevel level, Mob caster, long now) {
        int stunned = 0;
        Holder<MobEffect> stun = null;
        try {
            stun = LegendaryMonstersReflect.stunEffectHolder();
            if (stun == null) {
                SafeGuard.warnThrottled("mce_ma_nostun_" + level.dimension().location(), now,
                        "Monstrous Anchor: the stun effect legendary_monsters:stun was not found (Legendary Monsters missing / effect renamed), "
                                + "this time it only launches without stunning (skill as usual)");
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ma_stunlookup", now,
                    "Monstrous Anchor: querying the stun effect threw (caught, launch only, no stun): " + t.getClass().getSimpleName());
        }
        try {
            AABB area = caster.getBoundingBox().inflate(SHOCKWAVE_RADIUS); // 原版 inflate(3) 照抄
            List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, area);
            for (LivingEntity victim : entities) {
                try {
                    if (victim == caster || !victim.isAlive() || victim.isRemoved() || victim.isInvulnerable()) {
                        continue; // 原版跳过自己/无敌单位照抄（无敌玩家的 abilities.invulnerable ⊆ isInvulnerable 口径）
                    }
                    if (AllyCheck.isAlly(caster, victim)) {
                        continue; // 友军保护：不上抛、不晕眩（原版“自己驯服的动物”过滤的超集）
                    }
                    victim.push(0.0D, PUSH_VERTICAL, 0.0D); // 原版 push(0, 0.6f, 0) 照抄
                    if (stun != null) {
                        victim.addEffect(new MobEffectInstance(stun, STUN_TICKS, 0)); // 原版 STUN 60,0 照抄
                    }
                    stunned++;
                } catch (Throwable per) {
                    SafeGuard.warnThrottled("mce_ma_per_" + caster.getUUID(), now,
                            "Monstrous Anchor: single-target handling threw (caught, that target skipped): "
                                    + per.getClass().getSimpleName() + " / " + per.getMessage());
                }
            }
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ma_wave_err", now,
                    "Monstrous Anchor: the shockwave resolution threw (caught, this round skipped): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        playShockwaveVisual(level, caster, now);
        return stunned;
    }

    /** 观感：铁傀修复音效（原版照抄）+ 震屏（反射）+ 服务端复刻原版烟圈粒子（模组粒子反射不划算） */
    private static void playShockwaveVisual(ServerLevel level, Mob caster, long now) {
        try {
            level.playSound(null, caster.getX(), caster.getY(), caster.getZ(),
                    SoundEvents.IRON_GOLEM_REPAIR, SoundSource.PLAYERS, 1.0F, 1.0F);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ma_snd", now,
                    "Monstrous Anchor: playing the iron golem repair sound failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
        try {
            LegendaryMonstersReflect.cameraShake(level, caster.position(), 10.0F, 0.3F, 0, 15, now);
        } catch (Throwable ignored) {
        }
        try {
            // 原版客户端粒子：Circle 环（自定义数据粒子，反射不划算）+ 180 点 LM_COSY_SMOKE。
            // 服务端用原版 CLOUD/LARGE_SMOKE 补同观感：地面一圈向外的烟。
            double y = caster.getY() + 0.2D;
            for (int i = 0; i < 24; i++) {
                double a = Math.PI * 2 * i / 24.0D;
                level.sendParticles(ParticleTypes.CLOUD,
                        caster.getX() + Math.cos(a) * 2.0D, y, caster.getZ() + Math.sin(a) * 2.0D,
                        1, 0.05D, 0.05D, 0.05D, 0.08D);
            }
            level.sendParticles(ParticleTypes.LARGE_SMOKE,
                    caster.getX(), y + 0.5D, caster.getZ(), 12, 1.5D, 0.3D, 1.5D, 0.02D);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ma_vis", now,
                    "Monstrous Anchor: shockwave particles failed (caught, skill unaffected): " + t.getClass().getSimpleName());
        }
    }

    // ════════════════════════ AOE（能力 2，被动；事件层调用） ════════════════════════

    /**
     * AOE 节流判定：距上次触发 ≥ monstrous_anchor_aoe_min_interval_ticks（拍板 10）才放行。
     * 存 persistentData（读档不重置）；interval ≤ 0 视为关闭节流。
     */
    public static boolean aoeIntervalReady(Entity owner, long now) {
        try {
            int interval = MceConfig.monstrousAnchorAoeMinIntervalTicks();
            if (interval <= 0) {
                return true;
            }
            long last = owner.getPersistentData().getLong(KEY_AOE_LAST);
            return now - last >= interval;
        } catch (Throwable t) {
            return false;
        }
    }

    /** AOE 触发后记节流时间戳 */
    public static void markAoeLast(Entity owner, long now) {
        try {
            owner.getPersistentData().putLong(KEY_AOE_LAST, now);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 攻击附带 AOE 结算（照抄原版 hurtEnemy：以【被击中目标】为中心 3 格 AABB、
     * 6 点 PLAYER_ATTACK 伤害；同伴版加 AllyCheck 过滤 + 节流，节流由事件层负责）。
     *
     * r37 排查补充（用户反馈“AOE 只打到一个额外目标”）：逐个候选目标打印
     * 距离 + 过滤/结算原因（施法者本人 / 已死 / 无敌单位 / 友军 / hurt() 被原版无敌帧
     * 挡下 / 正常结算），一次 AOE 一条汇总——AABB 范围内发现 N 个实体，最终结算 M 个目标。
     *
     * @return AOE 命中的敌人数（友军/自己/无敌单位不算）
     */
    public static int performAoe(ServerLevel level, Mob caster, LivingEntity center, long now) {
        int hits = 0;
        try {
            AABB area = center.getBoundingBox().inflate(MceConfig.monstrousAnchorAoeRadius());
            // 伤害口径照抄原版：DamageTypes.PLAYER_ATTACK + 攻击者（与原版 playerAttack 同类，不穿甲）
            net.minecraft.world.damagesource.DamageSource source = new net.minecraft.world.damagesource.DamageSource(
                    level.registryAccess()
                            .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                            .getHolderOrThrow(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK),
                    caster);
            List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, area);
            StringBuilder detail = new StringBuilder();
            int filtered = 0;
            for (LivingEntity victim : entities) {
                String name;
                double distance;
                try {
                    name = IncineratorSkill.describe(victim);
                    distance = center.distanceTo(victim);
                } catch (Throwable nt) {
                    name = victim == null ? "null" : victim.getClass().getSimpleName();
                    distance = -1.0D;
                }
                try {
                    if (victim == caster) {
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> filtered: the caster itself; ");
                        filtered++;
                        continue;
                    }
                    if (!victim.isAlive() || victim.isRemoved()) {
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> filtered: dead/removed; ");
                        filtered++;
                        continue;
                    }
                    if (victim.isInvulnerable()) {
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> filtered: invulnerable unit; ");
                        filtered++;
                        continue;
                    }
                    if (AllyCheck.isAlly(caster, victim)) {
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> filtered: ally protection; ");
                        filtered++;
                        continue;
                    }
                    // 原版无敌帧诊断：结算前快照，被挡下时能看出“是不是刚被普攻/AOE 上一轮打过”
                    int invulnBefore = victim.invulnerableTime;
                    float amount = (float) MceConfig.monstrousAnchorAoeDamage();
                    boolean landed = victim.hurt(source, amount);
                    if (landed) {
                        hits++;
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> OK resolved ")
                                .append(String.format("%.1f", amount)).append(" points; ");
                    } else {
                        detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> X blocked by hurt() ")
                                .append(" (vanilla invulnerability: invulnerableTime=").append(invulnBefore)
                                .append(" before resolution, a fresh window only opens at <=10; or this damage type is immune); ");
                        filtered++;
                    }
                } catch (Throwable per) {
                    SafeGuard.warnThrottled("mce_ma_aoe_per_" + caster.getUUID(), now,
                            "Monstrous Anchor: AOE single-target resolution threw (caught, that target skipped): "
                                    + per.getClass().getSimpleName() + " / " + per.getMessage());
                    detail.append(name).append('(').append(fmt(distance)).append(" blocks) -> X exception caught; ");
                    filtered++;
                }
            }
            SafeGuard.info("Monstrous Anchor AOE details (r37 troubleshooting): centred on " + IncineratorSkill.describe(center)
                    + " centred on " + MceConfig.monstrousAnchorAoeRadius() + " found "
                    + entities.size() + " entities inside the AABB, finally resolved " + hits + " targets, filtered/blocked "
                    + filtered + " of them" + (entities.isEmpty() ? " (not a single entity in range)" : "")
                    + "; one by one: " + detail);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_ma_aoe_err", now,
                    "Monstrous Anchor: the AOE resolution threw (caught, this round skipped): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        return hits;
    }

    /** 距离格式化（排查日志用） */
    private static String fmt(double value) {
        return String.format("%.1f", value);
    }

    // ════════════════════════ force / 状态键 ════════════════════════

    /**
     * force 命令入口（跳过冷却与触发条件）：
     * - shockwave：走 tryCast（震击以同伴为中心，目标可空，只做朝向）；
     * - aoe：以【指定目标】为中心直接结算一轮 AOE（照抄“以被击中目标为中心”的几何），
     *   需要目标；节流照常记一次（防连点 force 刷屏）。
     */
    public static boolean forceCast(ServerLevel level, Mob caster, LivingEntity target,
                                    String mode, long now) {
        try {
            if (FORCE_MODE_AOE.equals(mode)) {
                if (target == null) {
                    SafeGuard.warn("Monstrous Anchor: the aoe mode needs an attack target (the AOE is centred on the victim) -- "
                            + "get it into combat first (for example hit it once) then run /mce monstrous_anchor force @e[...] aoe");
                    return false;
                }
                if (!LegendaryMonstersReflect.isLegendaryMonstersPresent()) {
                    SafeGuard.warn("Monstrous Anchor: Legendary Monsters not detected, forced AOE cancelled");
                    return false;
                }
                EquipmentSlot slot = INSTANCE.heldSlot(caster);
                if (slot == null) {
                    SafeGuard.warn("Monstrous Anchor: " + IncineratorSkill.describe(caster)
                            + " not holding the Monstrous Anchor (force does not require holding it but the intent must be clear, cancelled)");
                    return false;
                }
                float yawDegrees = IncineratorSkill.yawToDegrees(caster, target);
                caster.setYRot(yawDegrees);
                caster.setYHeadRot(yawDegrees);
                markAoeLast(caster, now);
                int hits = performAoe(level, caster, target, now);
                setLastMode(caster, FORCE_MODE_AOE);
                markGuardWindow(caster, now + GUARD_BASE_TICKS);
                SkillCooldownStore.markCast(caster, SkillPriorityConfig.SKILL_MONSTROUS_ANCHOR, now);
                SafeGuard.info("Monstrous Anchor: force aoe -- around " + IncineratorSkill.describe(target)
                        + " centred on " + MceConfig.monstrousAnchorAoeRadius() + " blocks AABB resolution "
                        + MceConfig.monstrousAnchorAoeDamage() + " points of PLAYER_ATTACK damage, hit " + hits
                        + " enemies (the throttle was recorded, window " + GUARD_BASE_TICKS + " tick)");
                return true;
            }
            // 默认（不带模式 / shockwave）：震击
            return tryCast(level, caster, target, now, true);
        } catch (Throwable t) {
            SafeGuard.error("Monstrous Anchor: forceCast threw (caught): ", t);
            return false;
        }
    }

    /** 友军拦截窗口（AllyDamageGuard 读） */
    public static void markGuardWindow(Entity owner, long untilTick) {
        try {
            owner.getPersistentData().putLong(KEY_GUARD_UNTIL, untilTick);
        } catch (Throwable ignored) {
        }
    }

    public static boolean guardWindowActive(Entity owner, long now) {
        try {
            return owner.getPersistentData().getLong(KEY_GUARD_UNTIL) > now;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一次模式（status 展示用） */
    private static void setLastMode(Entity owner, String mode) {
        try {
            owner.getPersistentData().putString(KEY_LAST_MODE, mode);
        } catch (Throwable ignored) {
        }
    }

    public static String getLastMode(Entity owner) {
        try {
            String mode = owner.getPersistentData().getString(KEY_LAST_MODE);
            return mode == null || mode.isEmpty() ? "none" : mode;
        } catch (Throwable t) {
            return "none";
        }
    }

    /** /mce monstrous_anchor reset 时一并清掉本技能的易变状态（拦截窗口/AOE 节流/结果标记） */
    public static void clearVolatileState(Entity owner) {
        try {
            CompoundTag data = owner.getPersistentData();
            data.remove(KEY_GUARD_UNTIL);
            data.remove(KEY_AOE_LAST);
            data.remove(KEY_LAST_MODE);
        } catch (Throwable ignored) {
        }
    }
}
