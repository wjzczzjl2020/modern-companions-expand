package cn.blockforge.generated.moderncompanionsexpand.mixin;

import cn.blockforge.generated.moderncompanionsexpand.util.ShieldPassiveSuppression;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求②注入点 3/3：尖刺之盾格挡被动（传奇怪物 ForgeEvents#spikyShieldEvent）。
 *
 * ForgeEvents 是传奇怪物的杂项事件大杂烩类——本注入只点名 spikyShieldEvent
 * 这一个方法（按完整 JVM 描述符锁定），该类其余处理器（坠落、骑乘、boss 伤害统计等）
 * 一概不受影响。
 *
 * 为什么只能 Mixin 不能事件层：荆棘反击在内层 sourceEntity.hurt(...) 同步结算完，
 * 任何外层事件监听看到的结果都是"已经扣完血"，事后撤不掉（见第 39 轮分析）。
 * 容错：config json 的 "required": false + @Inject require = 0（sponge-mixin 0.15.x
 * 的 @Mixin 注解没有 require 成员），传奇怪物没装/改签名 = WARN 跳过，不崩。
 */
@Mixin(targets = "net.miauczel.legendary_monsters.event.ForgeEvents")
public class LMForgeEventsSpikyShieldPassiveMixin {

    @Inject(method = "spikyShieldEvent(Lnet/neoforged/neoforge/event/entity/living/"
            + "LivingIncomingDamageEvent;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private static void mce$suppressForCompanion(LivingIncomingDamageEvent event, CallbackInfo ci) {
        if (ShieldPassiveSuppression.shouldSuppress("Spiky Shield",
                "about 50% chance on block to deal thorns-style retaliation damage to the attacker (attack x25% + 5 points)", event.getEntity())) {
            ci.cancel();
        }
    }
}
