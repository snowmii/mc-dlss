package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.resources.Identifier

/**
 * The weather pass velocity writer: the control seam, the shared VelocityConfig payload write,
 * and the writer twin's shader and layout surface.
 *
 * `WeatherEffectRenderer.render` is the smallest remaining bespoke world pass: it creates one
 * pass over the weather target with `WEATHER_DEPTH_WRITE` or `WEATHER_NO_DEPTH_WRITE` and draws
 * the CPU-baked rain and snow columns. The pass-creation redirect asks this object whether the
 * open world phase offers the scene velocity view; when it does, the redirect builds a
 * two-attachment pass - the source weather target at color index 0 unchanged, the scene-sized
 * RG16_FLOAT velocity view at index 1 - and the pipeline-boundary seam binds the cached weather
 * writer twin, whose fragment shader ([FRAGMENT_SHADER], swapped in for the source's
 * core/particle shader by [writerTwin] for [VelocityWriter.WEATHER]) reproduces the
 * vanilla particle color output and writes jitter-stripped NDC camera motion into the velocity
 * attachment.
 *
 * The payload is deliberately the terrain writer's existing [TerrainVelocityUniforms]
 * `VelocityConfig` block: [writeFrame] delegates to it on the same command encoder the pass is
 * created from, so the weather pass reads the same jitter-stripped camera reprojection and the
 * same invalid-sentinel-on-reset semantics every other scene writer does, without a new uniform
 * design. The writer twin therefore reuses [TerrainVelocityUniforms.LAYOUT] and
 * [TerrainVelocityUniforms.UNIFORM_NAME] verbatim.
 *
 * Ineligible routes - a closed phase, a vanilla session, the latched camera-only route, or a
 * frame whose scene target carries no velocity companion - leave `WorldPhase.terrainVelocityView`
 * null, the one gate every writer reads: the pass-creation redirect falls through to the exact vanilla
 * one-attachment creation and the source weather pipeline binds unchanged. Every read here is a
 * plain field or enum read, so the fallback path cannot throw.
 */
object WeatherVelocityRender {
	/** The shader path the weather velocity twin swaps in for the source's core/particle shader. */
	const val SHADER_PATH = "core/velocity_weather"

	/** The weather twin adds the existing terrain VelocityConfig layout, not a new one. */
	@JvmField
	val LAYOUT: BindGroupLayout = TerrainVelocityUniforms.LAYOUT

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME: String = TerrainVelocityUniforms.UNIFORM_NAME

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	private var uniformBuffer: GpuBuffer? = null



	/**
	 * Fills this frame's VelocityConfig payload on [encoder] for the weather pass.
	 *
	 * Delegates to the terrain writer's existing block write: the frame's published camera
	 * reprojection (jitter-stripped current-to-previous, exactly what the DLSS evaluation
	 * receives) and its reset flag, which forces the invalid sentinel for a frame with no valid
	 * predecessor. [view] is the scene-sized velocity view the pass writes into; its size is
	 * what `gl_FragCoord` is sized to, and the shader inverts the viewport transform with it to
	 * recover NDC.
	 */
	@JvmStatic
	fun writeFrame(encoder: CommandEncoder, phase: WorldPhase, view: GpuTextureView) {
		TerrainVelocityUniforms.writeFrame(encoder, buffer(), phase.activeMotion, view)
	}

	/**
	 * The shared payload buffer slice the weather pass binds as `VelocityConfig`.
	 *
	 * The pass-creation redirect writes this frame's payload into [buffer] on the pass's
	 * encoder; the pipeline-boundary seam binds the same buffer's slice into the weather twin,
	 * so both weather draws read the block the redirect just wrote in the same submission.
	 */
	@JvmStatic
	fun uniformSlice(): GpuBufferSlice = buffer().slice()

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS weather velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			TerrainVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}
}
