package com.alonediamond.playercontrolpp.mixin.client;

import org.spongepowered.asm.mixin.Mixin;

/**
 * Removed - input is now handled via key simulation in ClientEventHandler.
 * This empty mixin is retained to avoid changing the mixin config.
 */
@Mixin(net.minecraft.client.player.KeyboardInput.class)
public abstract class MixinKeyboardInput {
}
