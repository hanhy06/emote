package io.github.hanhy06.emote.mixin;

import io.github.hanhy06.emote.EmoteClient;
import net.minecraft.client.renderer.entity.DisplayRenderer;
import net.minecraft.client.renderer.entity.state.ItemDisplayEntityRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DisplayRenderer.ItemDisplayRenderer.class)
public class ItemDisplayRendererMixin {
	@Shadow
	@Final
	private ItemModelResolver itemModelResolver;

	@Inject(
			method = "extractRenderState(Lnet/minecraft/world/entity/Display$ItemDisplay;Lnet/minecraft/client/renderer/entity/state/ItemDisplayEntityRenderState;F)V",
			at = @At("TAIL")
	)
	private void emote$applySkinOverride(Display.ItemDisplay entity, ItemDisplayEntityRenderState state, float partialTicks, CallbackInfo callbackInfo) {
		ItemStack overrideItemStack = EmoteClient.SKIN_OVERRIDE_CONTROLLER.createOverrideItemStack(entity);
		if (overrideItemStack == null) {
			return;
		}

		Display.ItemDisplay.ItemRenderState itemRenderState = entity.itemRenderState();
		this.itemModelResolver.updateForNonLiving(
				state.item,
				overrideItemStack,
				itemRenderState.itemTransform(),
				entity
		);
	}
}
