package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.textures.GpuTextureView
import me.snowmii.dlss.render.DlssFrameMotion
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4f
import org.lwjgl.system.MemoryStack

/**
 * The terrain velocity writer's uniform contract: the bind-group layout, the sentinel, the
 * velocity fragment shader, and the per-frame block write.
 *
 * The terrain velocity twin - [writerTwin] for [VelocityWriter.TERRAIN] - swaps the
 * mc-dlss velocity fragment shader into the plain two-target velocity twin and adds this
 * object's [LAYOUT], so the terrain chunk passes can write jitter-stripped NDC camera motion
 * into the velocity attachment at color index 1. The shader derives the motion from this
 * frame's reprojection and the fragment's own reversed-Z depth:
 *
 * ```
 * ndc = gl_FragCoord.xy inverted through VelocityParams.yz (the velocity viewport size)
 * clip = vec4(ndc, gl_FragCoord.z, 1.0)
 * motion = (Reprojection * clip).xy / w - ndc
 * ```
 *
 * and classifies invalid pixels (reset frame, a previous w the previous camera cannot see, or
 * a non-finite/out-of-range result) to one representable sentinel, [INVALID_VELOCITY] - the
 * same 10000.0 payload value the mod-owned stress pass writes. The block layout here (one mat4,
 * one vec4) is the shader block's exact member layout, and the uniform name must match the
 * shader's block name, because Vulkan's lazy compile resolves every shader-declared uniform
 * against the pipeline's bind-group layouts by name.
 *
 * The per-frame write is driven by the terrain mixin's pass-creation redirect, which runs on
 * the same command encoder the terrain pass is created from: the copy lands in the same
 * submission as the draws that read it. A frame that publishes no motion - the camera seam
 * never ran - writes the identity reprojection and the reset flag, so every pixel is invalid
 * rather than reading the identity-derived zero.
 */
object TerrainVelocityUniforms {
	/** The one representable invalid payload value, mirrored from the shader's `INVALID_VELOCITY`. */
	const val INVALID_VELOCITY = 10000.0f

	/** The uniform name, which must match the shader block name and the layout entry exactly. */
	const val UNIFORM_NAME = "VelocityConfig"

	/** The shader path the terrain velocity twin swaps in for the source's terrain fragment shader. */
	const val SHADER_PATH = "core/velocity_terrain"

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	/** The clear color the opaque terrain group's pass clears the velocity attachment to. */
	@JvmField
	val SENTINEL: Vector4f = Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f)

	/**
	 * The one bind-group layout the terrain velocity twin adds: the [UNIFORM_NAME] uniform
	 * buffer the velocity shader's `VelocityConfig` block binds to.
	 */
	@JvmField
	val LAYOUT: BindGroupLayout = BindGroupLayout.builder()
		.withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)
		.build()

	/** `mat4 Reprojection` + `vec4 VelocityParams`, the shader block's exact member layout. */
	@JvmField
	val UBO_SIZE: Int = Std140SizeCalculator()
		.putMat4f()
		.putVec4()
		.get()

	/**
	 * Writes this frame's terrain velocity uniform block into [buffer] on [encoder].
	 *
	 * [motion] is the frame's published camera motion: its reprojection is the jitter-stripped
	 * current-to-previous clip mapping the shader derives vectors from, and its reset flag
	 * forces the invalid sentinel for a frame with no valid predecessor. A null motion (the
	 * camera seam never ran) writes the identity reprojection with the reset flag, so every
	 * pixel is invalid instead of reading the identity-derived zero. [view] is the scene-sized
	 * velocity view the passes write into; its size is what `gl_FragCoord` is sized to, and the
	 * shader inverts the viewport transform with it to recover NDC.
	 */
	@JvmStatic
	fun writeFrame(encoder: CommandEncoder, buffer: GpuBuffer, motion: DlssFrameMotion?, view: GpuTextureView) {
		val reprojection = motion?.reprojection ?: IDENTITY
		val reset = motion?.reset ?: true

		MemoryStack.stackPush().use { stack ->
			val data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(reprojection)
				.putVec4(
					if (reset) 1f else 0f,
					view.getWidth(0).toFloat(),
					view.getHeight(0).toFloat(),
					0f,
				)
				.get()
			encoder.writeToBuffer(buffer.slice(), data)
		}
	}

	/** Identity reprojection for frames that publish no motion; never mutated, only read into the block. */
	private val IDENTITY = Matrix4f()
}
