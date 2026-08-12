package me.snowmii.dlss.mrt

import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.textures.GpuTextureView
import org.joml.Vector4f
import org.joml.Vector4fc

/**
 * The terrain chunk passes' velocity-companion handling: the pre-object-write SENTINEL clear.
 *
 * The terrain writers are retired: the terrain chunk passes never carry the velocity
 * attachment and never bind a velocity twin, so their color output is byte-identical to
 * vanilla. What remains is the companion's clear lifecycle: on an open VELOCITY_MRT phase the
 * scene velocity companion must read the invalid sentinel before any retained object writer
 * draws, so every pixel no object writer covers - sky, discarded cutout texels, the cleared
 * far plane, and every camera-only surface like terrain, weather, particles, static block
 * entities, and the breaking overlay - is classified as "no object motion" by the post-scene
 * fill, which reconstructs camera motion for it. [createPass] emits that clear (an encoder
 * command, never a pass clear) exactly once, before the opaque terrain group's pass exists,
 * and then delegates pass creation to [create] unchanged.
 *
 * [clearBeforeObjectWrites] is true only for the opaque group, which is the first terrain
 * group of the frame: the translucent group loads the companion instead of clearing it, so
 * the opaque-written state survives through its work. A null [velocity] (a closed phase, a
 * vanilla session, the latched camera-only route, or a frame whose scene target carries no
 * companion) skips the clear.
 *
 * The helper never owns pass creation: [create] runs through the caller, and the
 * `@WrapOperation` handler passes a lambda over `Operation.call`, so the MixinExtras chain
 * around `CommandEncoder.createRenderPass` keeps working for other mods and the original is
 * invoked exactly once with the original arguments.
 */
object TerrainVelocityPass {
	/**
	 * The one representable invalid payload value, mirrored from every retained velocity
	 * shader's `INVALID_VELOCITY` and from the fill's sentinel classification.
	 */
	const val INVALID_VELOCITY = 10000.0f

	/** The clear color the opaque terrain group clears the velocity companion to. */
	@JvmField
	val SENTINEL: Vector4f = Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f)

	/**
	 * Emits the companion's sentinel clear before the pass exists when [velocity] is offered
	 * and [clearBeforeObjectWrites] is set, then creates the pass through [create]. The pass
	 * keeps the exact shape the caller asks for - vanilla `renderGroup` asks for the exact
	 * vanilla one-attachment pass, and [create] adds nothing to it.
	 */
	@JvmStatic
	fun createPass(
		encoder: CommandEncoder,
		velocity: GpuTextureView?,
		clearBeforeObjectWrites: Boolean,
		create: () -> RenderPass,
	): RenderPass {
		if (velocity != null && clearBeforeObjectWrites) {
			encoder.clearColorTexture(velocity.texture(), SENTINEL)
		}
		return create()
	}
}
