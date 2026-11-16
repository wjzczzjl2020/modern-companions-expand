package cn.blockforge.generated.moderncompanionsexpand.skill;

import cn.blockforge.generated.moderncompanionsexpand.util.SafeGuard;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传奇怪物（Legendary Monsters，modid 传奇模组源码里就是 legendary_monsters）反射代理层——
 * 本模组与传奇怪物相关的类名字符串只允许出现在这里。
 *
 * 编译期零依赖：本工程不链接传奇怪物的任何类，模组没装也只是“技能放不出来 + 一条日志”。
 *
 * 复用目标（来自用户上传的传奇怪物源码，详见 docs/ANALYSIS_ROUND28_SOUL_GREAT_SWORD.md）：
 * - 幻影匕首实体 net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile
 *   .ThrownPhantomDaggerEntity（继承原版 AbstractArrow）：
 *   公开构造器 (EntityType&lt;?&gt;, Level)——注意与灾变系的 (Level, LivingEntity) 不同，
 *   owner 要构造后用原版 Projectile.setOwner() 补设（r28 分析结论，顺序反不了也不需要反）；
 * - 注册名 legendary_monsters:thrown_phantom_dagger（ModEntities THROWN_PHANTOM_DAGGER，
 *   .build(MOD_ID + ":thrown_phantom_dagger")）→ 直接查原版实体注册表拿 EntityType，不反射注册类；
 * - 追踪三件套都是实体自己的公开方法，反射逐个调：
 *   setReturnEntity(LivingEntity)（锁定目标）、setDamage(float)（接触伤害 8）、
 *   setReturnTick(int)（发射后 10 tick 才开始追踪，追踪由实体内部自驱，同伴版零追踪代码）；
 * - 格挡成功音效 legendary_monsters:block / legendary_monsters:soul_fly
 *   （ModSounds.registerSoundEvent("block") / ("soul_fly")）→ 查原版音效注册表，查不到只少音效。
 */
public final class LegendaryMonstersReflect {

    public static final String LEGENDARY_MONSTERS_MOD_ID = "legendary_monsters";

    /** 灵魂巨剑物品（同伴版持握判定只做注册表 ID 比对，零依赖） */
    public static final ResourceLocation SOUL_GREAT_SWORD_ITEM =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":soul_great_sword");

    /** 超立方体物品（TheTesseractItem，ModItems.TESSERACT → legendary_monsters:the_tesseract，r30） */
    public static final ResourceLocation TESSERACT_ITEM =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":the_tesseract");

    /** 湮灭圆盾物品（BucklerOfAnnihilationItem，ModItems.BUCKLER_OF_ANNIHILATION → legendary_monsters:buckler_of_annihilation，r32） */
    public static final ResourceLocation BUCKLER_ITEM =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":buckler_of_annihilation");

    /** 怪怖之锚物品（MonstrousAnchorItem，ModItems.MONSTROUS_ANCHOR → legendary_monsters:monstrous_anchor，r33） */
    public static final ResourceLocation MONSTROUS_ANCHOR_ITEM =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":monstrous_anchor");

    /** 雷霆之钺物品（Axe_Of_LightningItem，ModItems.AXE_OF_LIGHTNING → legendary_monsters:axe_of_lightning，r33） */
    public static final ResourceLocation AXE_OF_LIGHTNING_ITEM =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":axe_of_lightning");

    /** 晕眩效果（ModEffects.STUN → legendary_monsters:stun；给移速/攻速各挂 -1 的 ADD_VALUE 修正 = 硬冻结） */
    public static final ResourceLocation STUN_EFFECT_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":stun");

    /** 电爆冲击实体注册名（ModEntities.ELectr → legendary_monsters:electric_burst，r33） */
    public static final ResourceLocation ELECTRIC_BURST_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":electric_burst");

    /** 雷击波柱实体注册名（ModEntities.L → legendary_monsters:lightning_strike，r33） */
    public static final ResourceLocation LIGHTNING_STRIKE_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":lightning_strike");

    /** 幻影匕首实体注册名（ModEntities.THROWN_PHANTOM_DAGGER） */
    public static final ResourceLocation THROWN_PHANTOM_DAGGER_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":thrown_phantom_dagger");

    /** 小型湮灭爆弹实体注册名（ModEntities.SMALL_ANNIHILATION_BOMB_ENTITY，r32） */
    public static final ResourceLocation SMALL_DIMENSIONAL_BOMB_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":small_dimensional_bomb");

    /** 湮灭传送门实体注册名（ModEntities.ANNIHILATION_PORTAL，r30） */
    public static final ResourceLocation ANNIHILATION_PORTAL_ID =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":annihilation_portal");

    /** 格挡成功音效（ForgeEvents.onLivingHurt 播的 ModSounds.BLOCK / SOUL_FLY） */
    public static final ResourceLocation PARRY_BLOCK_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":block");
    public static final ResourceLocation PARRY_SOUL_FLY_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":soul_fly");

    private static final String DAGGER_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.ThrownPhantomDaggerEntity";

    /** r32 小型湮灭爆弹实体类（ThrowableProjectile 雪球系投掷物） */
    private static final String BOMB_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.SmallAnnihilationBombEntity";

    /** r33 电爆冲击实体类（ElectricityEntity，雷霆之钺 useOn 的 5 道扇形电爆） */
    private static final String ELECTRICITY_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.ElectricityEntity";

    /** r33 雷击波柱实体类（LightningBoltEntity，雷霆之钺 use 的 12 段沿线雷击） */
    private static final String LIGHTNING_BOLT_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.LightningBoltEntity";

    /** r33 震屏实体类（CameraShakeEntity，怪怖之锚 releaseUsing 的 cameraShake 静态方法） */
    private static final String CAMERA_SHAKE_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Effect.CameraShakeEntity";

    /** r32 圆盾释放音效（releaseUsing 的 ModSounds.DIMENSIONAL_BOMB_EXPLODE_SMALL → 注册名 dimensional_bomb_explosion2） */
    public static final ResourceLocation BUCKLER_RELEASE_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":dimensional_bomb_explosion2");
    /** r32 圆盾挥臂音效（releaseUsing 的 ModSounds.GENERIC_ARM_SWING → 注册名 posessed_paladin_swing） */
    public static final ResourceLocation BUCKLER_SWING_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":posessed_paladin_swing");
    /** r32 撞击命中音效（每个被撞者播的 ModSounds.THE_OBLITERATOR_STUN → 注册名 the_warped_one_stun） */
    public static final ResourceLocation BUCKLER_HIT_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":the_warped_one_stun");
    /** r32 挥击观感粒子（原版客户端 ANNIHILATION_EXPLOSION 自定义粒子 → 注册名 annihilation_explosion；查不到只少粒子） */
    public static final ResourceLocation ANNIHILATION_EXPLOSION_PARTICLE =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":annihilation_explosion");

    /**
     * r30 传奇怪物“未来拦截集合”备注（拍板：防其他怪物误用，本类名/口径只在这里记录）：
     * - AnnihilationPortalEntity（湮灭传送门，TheObliterator Boss 与超立方体共用）；
     * - AnnihilationGroundNukeStrikeEntity（湮灭地面核爆冲击，传送门 scale=4 的 43~52 tick 冲击波、
     *   Boss 技能都用它）。
     * 这两类实体的伤害源全部是伤害类型 legendary_monsters:void（ModDamageTypes.ANNIHILATION）
     * 且 causing entity = caster——所以“void 类型 + 施法者是同伴”一个口径就能把同伴名下
     * 的全部传奇怪物湮灭伤害认出来（isAnnihilationDamageOf），同伴版超立方体的传送门
     * dangerous=false + 自研伤害循环，原版残留的湮灭伤害（到期收尾/冲击波）由 AllyDamageGuard
     * 按这个口径整体接管取消。
     */
    public static final String[] AREA_DAMAGE_CLASS_NOTE = {
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.AnnihilationPortalEntity",
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.AnnihilationGroundNukeStrikeEntity",
    };

    /**
     * 湮灭伤害类型（ModDamageTypes.ANNIHILATION，注册名 legendary_monsters:void）。
     * causeAnnihilationDamage(caster, caster) 把 directEntity 与 causingEntity 都设成 caster，
     * 所以同伴名下的传送门/核爆伤害一律“void 类型 + causing entity = 同伴”。
     */
    public static final ResourceKey<net.minecraft.world.damagesource.DamageType> ANNIHILATION_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":void"));

    /**
     * 幻影匕首的伤害类型（ModDamageTypes.GHOST，注册名 legendary_monsters:ghost）。
     * r29 关键结论：causeGhostlyDamage(owner, owner) 把 directEntity 和 causingEntity 都设成 owner，
     * 所以事件里 getSource().getDirectEntity() 是【同伴本人】而不是匕首本体——
     * 要认出“这笔伤害来自该同伴的幻影匕首”，必须靠伤害类型 + causing entity 双重比对。
     */
    public static final ResourceKey<net.minecraft.world.damagesource.DamageType> GHOST_DAMAGE_TYPE =
            ResourceKey.create(Registries.DAMAGE_TYPE,
                    ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":ghost"));

    private static volatile boolean presentProbed = false;
    private static volatile Class<?> daggerClass = null;
    private static volatile Class<?> bombClass = null;
    private static volatile Class<?> electricityClass = null;
    private static volatile Class<?> lightningBoltClass = null;
    private static volatile boolean cameraShakeWarned = false;

    private LegendaryMonstersReflect() {}

    /** 传奇怪物是否装了（= 四个实体类任一能加载；各技能再各自核对自己用的实体） */
    public static boolean isLegendaryMonstersPresent() {
        probePresent();
        return daggerClass != null || bombClass != null
                || electricityClass != null || lightningBoltClass != null;
    }

    private static void probePresent() {
        if (presentProbed) {
            return;
        }
        presentProbed = true;
        boolean any = false;
        try {
            daggerClass = Class.forName(DAGGER_CLASS_NAME);
            any = true;
            SafeGuard.info("Legendary Monsters reflection layer: ThrownPhantomDaggerEntity found (Phantom Dagger can be created by reflection)");
        } catch (Throwable t) {
            daggerClass = null;
            SafeGuard.warn("Legendary Monsters reflection layer: ThrownPhantomDaggerEntity not found (Legendary Monsters missing / class renamed), "
                    + "the whole Phantom Dagger skill is skipped: " + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        try {
            bombClass = Class.forName(BOMB_CLASS_NAME);
            any = true;
            SafeGuard.info("Legendary Monsters reflection layer: SmallAnnihilationBombEntity found (annihilation bomb can be created by reflection, r32)");
        } catch (Throwable t) {
            bombClass = null;
            SafeGuard.warn("Legendary Monsters reflection layer: SmallAnnihilationBombEntity not found (Legendary Monsters missing / class renamed), "
                    + "the bomb branch of Buckler of Annihilation is skipped: " + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        try {
            electricityClass = Class.forName(ELECTRICITY_CLASS_NAME);
            any = true;
            SafeGuard.info("Legendary Monsters reflection layer: ElectricityEntity found (electric burst shockwave can be created by reflection, r33)");
        } catch (Throwable t) {
            electricityClass = null;
            SafeGuard.warn("Legendary Monsters reflection layer: ElectricityEntity not found (Legendary Monsters missing / class renamed), "
                    + "the electric burst shockwave branch of Axe of Lightning is skipped: " + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        try {
            lightningBoltClass = Class.forName(LIGHTNING_BOLT_CLASS_NAME);
            any = true;
            SafeGuard.info("Legendary Monsters reflection layer: LightningBoltEntity found (lightning bolt pillar can be created by reflection, r33)");
        } catch (Throwable t) {
            lightningBoltClass = null;
            SafeGuard.warn("Legendary Monsters reflection layer: LightningBoltEntity not found (Legendary Monsters missing / class renamed), "
                    + "the lightning bolt branch of Axe of Lightning is skipped: " + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
        if (!any) {
            SafeGuard.warn("Legendary Monsters reflection layer: no entity class found at all, all Legendary Monsters skills (Soul Great Sword / The Tesseract / Buckler of Annihilation / Monstrous Anchor / Axe of Lightning) are skipped");
        }
    }

    /** 幻影匕首实体类是否加载（/mce soul_great_sword status 自检用） */
    public static boolean isDaggerClassResolved() {
        probePresent();
        return daggerClass != null;
    }

    /** 幻影匕首的 EntityType（查原版实体注册表；查不到返回 null） */
    public static EntityType<?> daggerEntityType() {
        try {
            Optional<EntityType<?>> found = BuiltInRegistries.ENTITY_TYPE.getOptional(THROWN_PHANTOM_DAGGER_ID);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 格挡成功音效（查不到返回 null = 少音效，不影响技能） */
    public static SoundEvent getParryBlockSound() {
        return lookupSound(PARRY_BLOCK_SOUND);
    }

    public static SoundEvent getParrySoulFlySound() {
        return lookupSound(PARRY_SOUL_FLY_SOUND);
    }

    // ══════════════════ r30 超立方体：湮灭传送门反射 ══════════════════

    /** 超立方体释放音效（releaseUsing 的 ModSounds.FLAME_BURST） */
    public static final ResourceLocation TESSERACT_FLAME_BURST_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":flame_burst");

    /** 超立方体蓄满音效（onUseTick 第 30 tick 的 ModSounds.ULTIMATE_FLAME_IMPACT） */
    public static final ResourceLocation TESSERACT_CHARGE_SOUND =
            ResourceLocation.parse(LEGENDARY_MONSTERS_MOD_ID + ":ultimate_flame_impact");

    /** 湮灭传送门的 EntityType（查原版实体注册表；查不到返回 null） */
    public static EntityType<?> annihilationPortalEntityType() {
        try {
            Optional<EntityType<?>> found = BuiltInRegistries.ENTITY_TYPE.getOptional(ANNIHILATION_PORTAL_ID);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static SoundEvent getTesseractFlameBurstSound() {
        return lookupSound(TESSERACT_FLAME_BURST_SOUND);
    }

    public static SoundEvent getTesseractChargeSound() {
        return lookupSound(TESSERACT_CHARGE_SOUND);
    }

    /**
     * 反射创建一个湮灭传送门并加入世界（同伴版：dangerous=false = 纯外观）。
     *
     * 构造器照抄原版全参公开构造器（AnnihilationPortalEntity，r30 源码核对）：
     * (Level, double x, double y, double z, float yRot, int warmup, LivingEntity caster,
     *  int lifeTicks, float damage, boolean dangerous, float scale)
     *
     * @param damage 传 0——同伴版 dangerous=false，实体自身的周期/收尾伤害一律不需要，
     *               数值伤害由同伴版自研循环结算（拍板“伤害自研”）；万无一漏的残留
     *               由 AllyDamageGuard 的湮灭接管闸门取消
     * @return 是否成功加入世界；任何失败（未装/注册名变了/构造器对不上）只写日志返回 false
     */
    public static boolean spawnAnnihilationPortal(ServerLevel level, LivingEntity caster,
                                                  double x, double y, double z,
                                                  int warmup, int lifeTicks, float scale, long nowTick) {
        probePresent();
        EntityType<?> type = annihilationPortalEntityType();
        if (type == null) {
            SafeGuard.warn("Legendary Monsters reflection layer: entity " + ANNIHILATION_PORTAL_ID
                    + " is not in the registry (mod version may have changed), annihilation portal cancelled");
            return false;
        }
        try {
            Class<?> portalClass = Class.forName(PORTAL_CLASS_NAME);
            Constructor<?> ctor = portalClass.getConstructor(
                    Level.class, double.class, double.class, double.class,
                    float.class, int.class, LivingEntity.class,
                    int.class, float.class, boolean.class, float.class);
            Object obj = ctor.newInstance(level, x, y, z, 0.0F, warmup, caster,
                    lifeTicks, 0.0F, false, scale);
            if (obj instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Legendary Monsters reflection layer: the reflected portal is not an Entity, aborted: " + obj.getClass().getName());
            return false;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : t;
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> portalClass = Class.forName(PORTAL_CLASS_NAME);
                for (Constructor<?> c : portalClass.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warn("Legendary Monsters reflection layer: annihilation portal creation failed (caught, that spot skipped). Public constructors available when the constructor signature mismatches:"
                    + sb + " (exception: " + cause.getClass().getSimpleName() + " / " + cause.getMessage() + ")");
            return false;
        }
    }

    private static final String PORTAL_CLASS_NAME =
            "net.miauczel.legendary_monsters.entity.AnimatedMonster.Projectile.AnnihilationPortalEntity";

    /** 传送门实体类是否解析（/mce tesseract status 自检用） */
    public static boolean isPortalClassResolved() {
        try {
            return Class.forName(PORTAL_CLASS_NAME) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 该伤害源是否为“该同伴名下的传奇怪物湮灭伤害”（r30 接管闸门的识别口径）。
     *
     * 源码核对：AnnihilationPortalEntity.damage 与 AnnihilationGroundNukeStrikeEntity.damage
     * 都用 ModDamageTypes.ANNIHILATION（注册名 legendary_monsters:void），
     * 且 directEntity/causingEntity 都是 caster 本身 → 按“void 类型 + causing entity = 同伴”判定。
     *
     * r32 注意：小型湮灭爆弹（SmallAnnihilationBombEntity.onHitEntity）用的是【同一个】
     * causeAnnihilationDamage(owner, owner) 口径——所以接管闸门必须给“圆盾拦截窗口内”的
     * 该口径让路（那是同伴版圆盾的正常爆弹伤害，不是传送门残留），见 AllyDamageGuard 的判定顺序。
     */
    public static boolean isAnnihilationDamageOf(DamageSource source, Entity owner) {
        if (source == null || owner == null) {
            return false;
        }
        try {
            return source.is(ANNIHILATION_DAMAGE_TYPE) && source.getEntity() == owner;
        } catch (Throwable t) {
            return false;
        }
    }

    // ══════════════════ r32 湮灭圆盾：小型湮灭爆弹反射 ══════════════════

    /** 爆弹实体类是否解析（/mce buckler status 自检用） */
    public static boolean isBombClassResolved() {
        probePresent();
        return bombClass != null;
    }

    /** 小型湮灭爆弹的 EntityType（查原版实体注册表；查不到返回 null） */
    public static EntityType<?> bombEntityType() {
        try {
            Optional<EntityType<?>> found = BuiltInRegistries.ENTITY_TYPE.getOptional(SMALL_DIMENSIONAL_BOMB_ID);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static SoundEvent getBucklerReleaseSound() {
        return lookupSound(BUCKLER_RELEASE_SOUND);
    }

    public static SoundEvent getBucklerSwingSound() {
        return lookupSound(BUCKLER_SWING_SOUND);
    }

    public static SoundEvent getBucklerHitSound() {
        return lookupSound(BUCKLER_HIT_SOUND);
    }

    /** 挥击观感粒子（原版客户端自定义粒子；查不到或不是 SimpleParticleType 返回 null = 只少粒子，不影响技能。
     *  注意 1.21.1 的 ParticleType 不再实现 ParticleOptions，只有 SimpleParticleType（原版就是用它注册的）
     *  能直接作为 ParticleOptions 传给 ServerLevel.sendParticles） */
    public static net.minecraft.core.particles.ParticleOptions annihilationExplosionParticle() {
        try {
            Optional<?> found = BuiltInRegistries.PARTICLE_TYPE.getOptional(ANNIHILATION_EXPLOSION_PARTICLE);
            if (found.isPresent() && found.get() instanceof net.minecraft.core.particles.ParticleOptions options) {
                return options;
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 反射发射一枚小型湮灭爆弹并加入世界（r32 湮灭圆盾专属）。
     *
     * 构造器照抄原版全参公开构造器（SmallAnnihilationBombEntity，r31/r32 源码核对）：
     * (EntityType&lt;SmallAnnihilationBombEntity&gt;, Level, LivingEntity thrower, float damage)
     * ——第三个参数就是 LivingEntity，同伴直接当 owner（构造器内部 setOwner，我们再补一次与原版
     * shootAnnihilationBomb 的显式 setOwner 对齐，重复调用无害）。
     *
     * 发射步骤逐行对齐原版 BucklerOfAnnihilationItem.shootAnnihilationBomb：
     *   construct → shootFromRotation(thrower, xRot, yRot, 0, velocity, inaccuracy) → 定位到 (x, y, z)
     *   → setOwner → addFreshEntity（原版用 setPosRaw，同伴版用公开的 moveTo 定位，等价）。
     *
     * @return 是否成功加入世界；任何失败（未装/注册名变了/构造器对不上）只写日志返回 false，绝不抛出
     */
    public static boolean spawnSmallAnnihilationBomb(ServerLevel level, LivingEntity thrower,
                                                     double x, double y, double z,
                                                     float xRot, float yRot,
                                                     float velocity, int inaccuracy, float damage, long nowTick) {
        probePresent();
        if (bombClass == null) {
            return false;
        }
        EntityType<?> type = bombEntityType();
        if (type == null) {
            SafeGuard.warn("Legendary Monsters reflection layer: entity " + SMALL_DIMENSIONAL_BOMB_ID
                    + " is not in the registry (mod version may have changed), annihilation bomb cancelled");
            return false;
        }
        try {
            Constructor<?> ctor = bombClass.getConstructor(
                    EntityType.class, Level.class, LivingEntity.class, float.class);
            Object obj = ctor.newInstance(type, level, thrower, damage);
            if (!(obj instanceof Entity entity)) {
                SafeGuard.warn("Legendary Monsters reflection layer: the reflected bomb is not an Entity, aborted: " + obj.getClass().getName());
                return false;
            }
            if (!(obj instanceof Projectile projectile)) {
                SafeGuard.warn("Legendary Monsters reflection layer: the reflected bomb is not a projectile, aborted: " + obj.getClass().getName());
                return false;
            }
            projectile.setOwner(thrower); // 与原版 shootAnnihilationBomb 的显式 setOwner 对齐
            entity.moveTo(x, y, z, yRot, xRot); // 原版 setPosRaw(x, y+1, z) 的定位步骤
            projectile.shootFromRotation(thrower, xRot, yRot, 0.0F, velocity, inaccuracy);
            return level.addFreshEntity(entity);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : t;
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> cls = Class.forName(BOMB_CLASS_NAME);
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warnThrottled("mce_bk_bomb_spawn_" + thrower.getUUID(), nowTick,
                    "Legendary Monsters reflection layer: annihilation bomb creation failed (caught, that bomb skipped). Public constructors available when the constructor signature mismatches:"
                            + sb + " (exception: " + cause.getClass().getSimpleName() + " / " + cause.getMessage() + ")");
            return false;
        }
    }

    private static SoundEvent lookupSound(ResourceLocation id) {
        try {
            return BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════ r33 怪怖之锚 / 雷霆之钺 ══════════════════

    /**
     * 晕眩效果 Holder（ModEffects.STUN，注册名 legendary_monsters:stun）。
     * 从原版药水效果注册表直接查——查不到返回 null（传奇怪物没装/效果改名），调用方跳过施加。
     */
    public static Holder<MobEffect> stunEffectHolder() {
        try {
            return BuiltInRegistries.MOB_EFFECT.getHolder(
                    ResourceKey.create(Registries.MOB_EFFECT, STUN_EFFECT_ID)).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 电爆冲击实体的 EntityType（雷霆之钺冲击波分支自检/创建用；查不到返回 null） */
    public static EntityType<?> electricBurstEntityType() {
        try {
            Optional<EntityType<?>> found = BuiltInRegistries.ENTITY_TYPE.getOptional(ELECTRIC_BURST_ID);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 雷击波柱实体的 EntityType（雷霆之钺雷击分支自检/创建用；查不到返回 null） */
    public static EntityType<?> lightningStrikeEntityType() {
        try {
            Optional<EntityType<?>> found = BuiltInRegistries.ENTITY_TYPE.getOptional(LIGHTNING_STRIKE_ID);
            return found.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean isElectricityClassResolved() {
        probePresent();
        return electricityClass != null;
    }

    public static boolean isLightningBoltClassResolved() {
        probePresent();
        return lightningBoltClass != null;
    }

    /**
     * 反射创建一道电爆冲击（ElectricityEntity）并加入世界（r33 雷霆之钺冲击波分支）。
     *
     * 构造器照抄原版全参公开构造器（ElectricityEntity，r33 源码核对）：
     * (LivingEntity owner, double dx, double dy, double dz, Level level, float damage, float yRot, float life)
     * ——首参就是 LivingEntity，同伴直接当 owner；实体内部自己的周期伤害（每 5 tick、
     * 盒 inflate(0.5, 4, 0.75)、INDIRECT_MAGIC + 原版 isAlliedTo 过滤）自驱，同伴版零自研伤害代码。
     * 注意：该构造器把实体定位在 owner 身上（getX/getY/getZ），原版 useOn 随后 setPos 到
     * (player.getX()+vecX, floor(y), player.getZ()+vecZ)——这里照抄这一步。
     *
     * @return 是否成功加入世界；任何失败只写日志返回 false，绝不抛出
     */
    public static boolean spawnElectricityBurst(ServerLevel level, LivingEntity owner,
                                                double dirX, double dirY, double dirZ,
                                                float damage, float yRotDegrees, float life,
                                                double spawnX, double spawnY, double spawnZ,
                                                long nowTick) {
        probePresent();
        if (electricityClass == null) {
            return false;
        }
        EntityType<?> type = electricBurstEntityType();
        if (type == null) {
            SafeGuard.warn("Legendary Monsters reflection layer: entity " + ELECTRIC_BURST_ID
                    + " is not in the registry (mod version may have changed), electric burst cancelled");
            return false;
        }
        try {
            Constructor<?> ctor = electricityClass.getConstructor(
                    LivingEntity.class, double.class, double.class, double.class,
                    Level.class, float.class, float.class, float.class);
            Object obj = ctor.newInstance(owner, dirX, dirY, dirZ, level, damage, yRotDegrees, life);
            if (!(obj instanceof Entity entity)) {
                SafeGuard.warn("Legendary Monsters reflection layer: the reflected electric burst is not an Entity, aborted: "
                        + obj.getClass().getName());
                return false;
            }
            entity.setPos(spawnX, spawnY, spawnZ); // 原版 useOn 的 setPos 照抄（构造器只定位到 owner 身上）
            return level.addFreshEntity(entity);
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : t;
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> cls = Class.forName(ELECTRICITY_CLASS_NAME);
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warnThrottled("mce_ax_burst_" + owner.getUUID(), nowTick,
                    "Legendary Monsters reflection layer: electric burst creation failed (caught, that burst skipped). Public constructors available when the constructor signature mismatches:"
                            + sb + " (exception: " + cause.getClass().getSimpleName() + " / " + cause.getMessage() + ")");
            return false;
        }
    }

    /**
     * 反射创建一根雷击波柱（LightningBoltEntity）并加入世界（r33 雷霆之钺雷击分支）。
     *
     * 构造器照抄原版全参公开构造器（LightningBoltEntity，r33 源码核对）：
     * (Level worldIn, double x, double y, double z, float yRotRad, int warmup,
     *  LivingEntity casterIn, int life, float setDamage)
     * ——caster 参数就是 LivingEntity，同伴直接当施法者；柱内伤害（每 5 tick、
     * 盒 inflate(0.2, 6, 0.2)、INDIRECT_MAGIC + 原版 isAlliedTo 过滤、warmup 后自驱）全部内置。
     * 注意 yRot 传【弧度】（构造器内部 ×180/π 转角度），与原版 spawnFangs 一致。
     *
     * @return 是否成功加入世界；任何失败只写日志返回 false，绝不抛出
     */
    public static boolean spawnLightningStrike(ServerLevel level, LivingEntity caster,
                                               double x, double y, double z,
                                               float yRotRadians, int warmup, int life, float damage,
                                               long nowTick) {
        probePresent();
        if (lightningBoltClass == null) {
            return false;
        }
        EntityType<?> type = lightningStrikeEntityType();
        if (type == null) {
            SafeGuard.warn("Legendary Monsters reflection layer: entity " + LIGHTNING_STRIKE_ID
                    + " is not in the registry (mod version may have changed), lightning bolt pillar cancelled");
            return false;
        }
        try {
            Constructor<?> ctor = lightningBoltClass.getConstructor(
                    Level.class, double.class, double.class, double.class,
                    float.class, int.class, LivingEntity.class, int.class, float.class);
            Object obj = ctor.newInstance(level, x, y, z, yRotRadians, warmup, caster, life, damage);
            if (obj instanceof Entity entity) {
                return level.addFreshEntity(entity);
            }
            SafeGuard.warn("Legendary Monsters reflection layer: the reflected lightning bolt pillar is not an Entity, aborted: " + obj.getClass().getName());
            return false;
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ite
                    && ite.getCause() != null ? ite.getCause() : t;
            StringBuilder sb = new StringBuilder();
            try {
                Class<?> cls = Class.forName(LIGHTNING_BOLT_CLASS_NAME);
                for (Constructor<?> c : cls.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warnThrottled("mce_ax_bolt_" + caster.getUUID(), nowTick,
                    "Legendary Monsters reflection layer: lightning bolt pillar creation failed (caught, that pillar skipped). Public constructors available when the constructor signature mismatches:"
                            + sb + " (exception: " + cause.getClass().getSimpleName() + " / " + cause.getMessage() + ")");
            return false;
        }
    }

    /**
     * 震屏（CameraShakeEntity.cameraShake 静态方法反射，r33 怪怖之锚 releaseUsing 观感）。
     * 原版调用：cameraShake(level, player.position(), 10.0F, 0.3F, 0, 15)。
     * 纯客户端观感，反射对不上只少震屏，不影响技能。
     */
    public static void cameraShake(ServerLevel level, Vec3 position,
                                   float radius, float magnitude, int duration, int fadeDuration,
                                   long nowTick) {
        try {
            Class<?> cls = Class.forName(CAMERA_SHAKE_CLASS_NAME);
            Method shake = cls.getMethod("cameraShake",
                    Level.class, Vec3.class, float.class, float.class, int.class, int.class);
            shake.invoke(null, level, position, radius, magnitude, duration, fadeDuration);
        } catch (Throwable t) {
            if (!cameraShakeWarned) {
                cameraShakeWarned = true;
                SafeGuard.infoThrottled("mce_camshake", nowTick,
                        "Legendary Monsters reflection layer: screen shake CameraShakeEntity.cameraShake does not match by reflection (caught, "
                                + "only the shake feel is missing, skill unaffected): "
                                + t.getClass().getSimpleName() + " / " + t.getMessage());
            }
        }
    }

    // ══════════════════ 幻影匕首反射 setter（按名缓存，失败只记日志不抛出） ══════════════════

    private static final ConcurrentHashMap<String, Method> SETTERS = new ConcurrentHashMap<>();

    private static Method setter(Class<?> cls, String name, Class<?> param) {
        try {
            return SETTERS.computeIfAbsent(cls.getName() + "#" + name + "#" + param.getSimpleName(), k -> {
                try {
                    return cls.getMethod(name, param);
                } catch (Throwable inner) {
                    return null;
                }
            });
        } catch (Throwable t) {
            return null;
        }
    }

    private static void invokeSetter(Entity dagger, String name, Class<?> param, Object value, String label) {
        try {
            Method m = setter(dagger.getClass(), name, param);
            if (m == null) {
                SafeGuard.warnThrottled("mce_sgs_setter_" + name, gameTime(dagger),
                        label + ": " + name + " not found by reflection (Legendary Monsters version may have changed), this parameter degrades to the entity default");
                return;
            }
            m.invoke(dagger, value);
        } catch (Throwable t) {
            SafeGuard.warnThrottled("mce_sgs_setter_" + name, gameTime(dagger),
                    label + ": " + name + " reflection call failed (caught, this parameter degrades to the entity default): "
                            + t.getClass().getSimpleName() + " / " + t.getMessage());
        }
    }

    // ══════════════════ 发射 6 支幻影匕首（照抄 SoulGreatSwordItem.spreadDaggers 几何） ══════════════════

    /**
     * 反射发射 count 支幻影匕首并加入世界。
     *
     * 与原版 spreadDaggers 的差异（拍板）：锁定不再沿视线 15 格射线，而是直接锁定
     * “同伴当前攻击目标”（target 参数）——每支都 setReturnEntity(target)，
     * setReturnTick(10) 照抄（发射后 10 tick 实体内部开始自驱追踪）。
     * 出膛几何（全圆环散布、出膛高度 0.2×身高、弹速 0.7、inaccuracy 1.0、yRot i×11.25°）
     * 逐行对齐原版 spreadDaggers。
     *
     * @return 成功加入世界的数量；传奇怪物未装/构造器对不上返回 0，绝不抛出
     */
    public static int spawnDaggerVolley(ServerLevel level, LivingEntity owner, LivingEntity target,
                                        int count, float damage, long nowTick) {
        probePresent();
        if (daggerClass == null) {
            return 0;
        }
        EntityType<?> type = daggerEntityType();
        if (type == null) {
            SafeGuard.warn("Legendary Monsters reflection layer: entity " + THROWN_PHANTOM_DAGGER_ID
                    + " is not in the registry (mod version may have changed), Phantom Dagger launch cancelled");
            return 0;
        }
        Constructor<?> ctor;
        try {
            // 原版公开构造器 (EntityType<? extends ThrownPhantomDaggerEntity>, Level)，擦除后即这两个类
            ctor = daggerClass.getConstructor(EntityType.class, Level.class);
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            try {
                for (Constructor<?> c : daggerClass.getConstructors()) {
                    sb.append("\n  - ").append(c);
                }
            } catch (Throwable ignored) {
            }
            SafeGuard.warn("Legendary Monsters reflection layer: Phantom Dagger constructor (EntityType, Level) does not match. Public constructors available:" + sb
                    + " (exception: " + t.getClass().getSimpleName() + " / " + t.getMessage() + ")");
            return 0;
        }

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            try {
                // —— 照抄 spreadDaggers 的环形出膛几何 ——
                float throwAngle = i * Mth.PI / (count / 2);
                double sx = owner.getX() + (Mth.cos(throwAngle) * 1);
                double sy = owner.getY() + (owner.getBbHeight() * 0.2D);
                double sz = owner.getZ() + (Mth.sin(throwAngle) * 1);
                double vx = Mth.cos(throwAngle);
                double vz = Mth.sin(throwAngle);

                Object obj = ctor.newInstance(type, level);
                if (!(obj instanceof Entity entity) || !(obj instanceof Projectile projectile)) {
                    SafeGuard.warn("Legendary Monsters: the reflected object is not a projectile, launch aborted: "
                            + obj.getClass().getName());
                    continue;
                }
                // 锁定（拍板改动）：不再沿视线射线，直接指认同伴当前攻击目标
                if (target != null) {
                    invokeSetter(entity, "setReturnEntity", LivingEntity.class, target, "Phantom Dagger");
                }
                // owner：原版用 Projectile.setOwner 补设（构造器不带 owner）
                projectile.setOwner(owner);
                invokeSetter(entity, "setDamage", float.class, damage, "Phantom Dagger");
                invokeSetter(entity, "setReturnTick", int.class, 10, "Phantom Dagger");
                entity.moveTo(sx, sy, sz, i * 11.25F, owner.getXRot());
                float speed = 0.7F;
                projectile.shoot(vx, 0, vz, speed, 1.0F);
                if (level.addFreshEntity(entity)) {
                    spawned++;
                }
            } catch (Throwable perDagger) {
                Throwable cause = perDagger instanceof java.lang.reflect.InvocationTargetException ite
                        && ite.getCause() != null ? ite.getCause() : perDagger;
                SafeGuard.warnThrottled("mce_sgs_dagger_" + i, nowTick,
                        "Legendary Monsters: dagger " + (i + 1) + " failed to launch (that dagger skipped): "
                                + cause.getClass().getSimpleName() + " / " + cause.getMessage());
            }
        }
        return spawned;
    }

    /**
     * 该伤害源是否为“该 owner 射出的幻影匕首”的 ghost 伤害（r29 事件层识别，取代按 directEntity 认实体的旧法）。
     *
     * 源码核对：onHitEntity 里 target.hurt(ModDamageTypes.causeGhostlyDamage(livingOwner, livingOwner), ...)
     * → new DamageSource(GHOST, caster, caster)，directEntity 与 causingEntity 都是 owner 本人，
     * 所以 getSource().getDirectEntity() 永远不是匕首本体；按“伤害类型 = legendary_monsters:ghost
     * 且 causing entity = 该 owner”判定才靠得住（同伴本人不天然造成 ghost 伤害，唯一来源就是它名下的匕首）。
     */
    public static boolean isGhostlyDamageOf(DamageSource source, Entity owner) {
        if (source == null || owner == null) {
            return false;
        }
        try {
            return source.is(GHOST_DAMAGE_TYPE) && source.getEntity() == owner;
        } catch (Throwable t) {
            return false;
        }
    }

    private static long gameTime(Entity entity) {
        try {
            return entity.level().getGameTime();
        } catch (Throwable t) {
            return -1L;
        }
    }
}
