package me.snowmii.dlss.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import me.snowmii.dlss.mrt.HandSlot;
import me.snowmii.dlss.mrt.HandVelocityRender;
import me.snowmii.dlss.mrt.HandVelocityWriterBindings;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Brackets each rendered hand and captures its interpolated render pose for the hand velocity
 * writer.
 *
 * {@code submitArmWithItem} runs once per rendered hand, in submission order, and is the only
 * path that reaches {@code renderItem} - the moment the item's final pose is on the
 * {@code PoseStack}. The bracket opens the hand slot for everything the branch submits (the
 * item model, the item quads, and the bare player arm), so the batching seam can attach the
 * slot to the staged draws. The capture reads the pose at {@code renderItem} HEAD, before the
 * submission bakes it, and composes it with the frame's HUD projection (captured at the
 * {@code renderItemInHand} seam) and the model-view stack into the clip matrix the writer's
 * history keeps.
 *
 * Scoping, spectator mode, a sleeping or HUD-hidden camera, and a render selection that drops
 * the hand never reach {@code renderItem}: the slot is simply not captured, and the frame
 * boundary classifies it as a disappeared hand. The bracket is per-hand and removed on exit,
 * so a hand's submissions can never leak into the other hand's slot.
 */
@Mixin(net.minecraft.client.renderer.ItemInHandRenderer.class)
public class ItemInHandRendererMotionMixin {
	@Inject(
		method = "submitArmWithItem",
		at = @At("HEAD")
	)
	private void mcDlssBeginHand(
		final AbstractClientPlayer player,
		final float frameInterp,
		final float xRot,
		final InteractionHand hand,
		final float attack,
		final ItemStack itemStack,
		final float inverseArmHeight,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final CallbackInfo info
	) {
		HandVelocityWriterBindings.beginHand(
			hand == InteractionHand.MAIN_HAND ? HandSlot.MAIN_HAND : HandSlot.OFF_HAND
		);
	}

	@Inject(
		method = "submitArmWithItem",
		at = @At("RETURN")
	)
	private void mcDlssEndHand(
		final AbstractClientPlayer player,
		final float frameInterp,
		final float xRot,
		final InteractionHand hand,
		final float attack,
		final ItemStack itemStack,
		final float inverseArmHeight,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final CallbackInfo info
	) {
		HandVelocityWriterBindings.endHand();
		// Each bracket end re-records the frame's render selection, so the value is complete by
		// the time the last bracket closes - ahead of the staged hand draws, which run after
		// submitHandsWithItems returns. The reset classification is decided at draw time, so it
		// can never be cleared out from under the draws by an earlier frame boundary.
		HandVelocityRender.noteHandSelection();
	}

	/**
	 * Captures the hand's interpolated render pose at the submission seam.
	 *
	 * The pose stack carries the frame's final hand pose - arm height, bob, attack, use, and
	 * view-rotation-difference transforms all lerped with the partial tick. The model-view
	 * stack holds the camera rotation the geometry draws under, and the frame projection is
	 * the unjittered HUD projection captured at the {@code renderItemInHand} seam. Any null
	 * input records a failed observation, which the writer classifies as a reset.
	 */
	@Inject(
		method = "renderItem",
		at = @At("HEAD")
	)
	private void mcDlssCaptureHandPose(
		final LivingEntity mob,
		final ItemStack itemStack,
		final ItemDisplayContext type,
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final CallbackInfo info
	) {
		HandVelocityRender.captureHandPose(
			HandVelocityWriterBindings.currentHand(),
			itemStack,
			HandVelocityRender.frameProjection(),
			RenderSystem.getModelViewMatrixCopy(),
			poseStack.last().pose()
		);
	}

	/**
	 * Captures the map branch's interpolated render pose at the {@code renderMap} seam - the
	 * one branch that bypasses {@code renderItem}. The pose is read before {@code renderMap}'s
	 * own fixed transforms, which cancel in the reprojection, so the one observation serves
	 * the background, the map texture, the decorations, and the labels; the map's
	 * {@code ItemStack} is the identity that resets on a visible map swap.
	 */
	@Inject(
		method = "renderMap",
		at = @At("HEAD")
	)
	private void mcDlssCaptureMapPose(
		final PoseStack poseStack,
		final SubmitNodeCollector submitNodeCollector,
		final int lightCoords,
		final ItemStack itemStack,
		final CallbackInfo info
	) {
		HandVelocityRender.captureMapHandPose(
			HandVelocityWriterBindings.currentHand(),
			itemStack,
			HandVelocityRender.frameProjection(),
			RenderSystem.getModelViewMatrixCopy(),
			poseStack.last().pose()
		);
	}
}
