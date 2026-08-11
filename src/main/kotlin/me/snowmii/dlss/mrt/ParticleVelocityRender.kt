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
 * The particle pass velocity writer: the control seam, the shared VelocityConfig payload write,
 * and the writer twin's shader and layout surface.
 *
 * `QuadParticleFeatureRenderer.executeGroup` is the one method that draws both particle
 * families - the solid group into the scene (main) target, the translucent group into the
 * particles target - and the static `drawLayers` binds each layer's `OPAQUE_PARTICLE` /
 * `TRANSLUCENT_PARTICLE` pipeline. Both targets are scene-sized on a DLSS frame (the particle
 * target is created from `mainRenderTarget()`'s size, which the world-target redirect answers
 * as the low-resolution scene target while the phase is open), so the same scene-sized
 * RG16_FLOAT velocity view fits both passes at color index 1. The pass-creation redirect asks
 * this object whether the open world phase offers the scene velocity view; when it does, the
 * redirect builds a two-attachment pass - the source solid/translucent particle target at
 * color index 0 unchanged, the scene-sized velocity view at index 1 - and the pipeline-boundary
 * seam binds the cached particle writer twin, whose fragment shader ([FRAGMENT_SHADER], swapped
 * in for the source's core/particle shader by [writerTwin] for [VelocityWriter.PARTICLE])
 * reproduces the vanilla particle color output and writes jitter-stripped NDC camera motion
 * into the velocity attachment.
 *
 * The mapped particle render state carries no stable previous identity, so this writer reuses
 * the proven camera semantics: the payload is deliberately the terrain writer's existing
 * [TerrainVelocityUniforms] `VelocityConfig` block, and [writeFrame] delegates to it on the
 * same command encoder the pass is created from, so the particle passes read the same
 * jitter-stripped camera reprojection and the same invalid-sentinel-on-reset semantics every
 * other scene writer does, without a new uniform design. The writer twin therefore reuses
 * [TerrainVelocityUniforms.LAYOUT] and [TerrainVelocityUniforms.UNIFORM_NAME] verbatim.
 *
 * The twin's shader is the weather writer's [WeatherVelocityRender.FRAGMENT_SHADER]: that
 * shader IS the vanilla `core/particle` fragment body verbatim plus the velocity-MRT payload
 * write, and the particle pipelines bind `core/particle`, so the same shader reproduces
 * particle color byte-identically and the slice needs no new shader resource.
 *
 * Ineligible routes - a closed phase, a vanilla session, the latched camera-only route, or a
 * frame whose scene target carries no velocity companion - leave `WorldPhase.terrainVelocityView`
 * null, the one gate every writer reads: the pass-creation redirect falls through to the exact vanilla
 * one-attachment creation and the source particle pipeline binds unchanged. Every read here is
 * a plain field or enum read, so the fallback path cannot throw.
 */
object ParticleVelocityRender {
	/**
	 * The shader path the particle twin swaps in for the source's core/particle shader: the
	 * existing particle-body velocity shader, which the weather writer also binds because the
	 * weather pipelines bind the same vanilla core/particle fragment shader.
	 */
	const val SHADER_PATH: String = WeatherVelocityRender.SHADER_PATH

	/** The particle twin adds the existing terrain VelocityConfig layout, not a new one. */
	@JvmField
	val LAYOUT: BindGroupLayout = TerrainVelocityUniforms.LAYOUT

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME: String = TerrainVelocityUniforms.UNIFORM_NAME

	@JvmField
	val FRAGMENT_SHADER: Identifier = WeatherVelocityRender.FRAGMENT_SHADER

	private var uniformBuffer: GpuBuffer? = null



	/**
	 * Fills this frame's VelocityConfig payload on [encoder] for the particle passes.
	 *
	 * Delegates to the terrain writer's existing block write: the frame's published camera
	 * reprojection (jitter-stripped current-to-previous, exactly what the DLSS evaluation
	 * receives) and its reset flag, which forces the invalid sentinel for a frame with no valid
	 * predecessor. [view] is the scene-sized velocity view the passes write into; its size is
	 * what `gl_FragCoord` is sized to, and the shader inverts the viewport transform with it to
	 * recover NDC.
	 */
	@JvmStatic
	fun writeFrame(encoder: CommandEncoder, phase: WorldPhase, view: GpuTextureView) {
		TerrainVelocityUniforms.writeFrame(encoder, buffer(), phase.activeMotion, view)
	}

	/**
	 * The shared payload buffer slice the particle passes bind as `VelocityConfig`.
	 *
	 * The pass-creation redirect writes this frame's payload into [buffer] on the pass's
	 * encoder; the pipeline-boundary seam binds the same buffer's slice into the particle twin,
	 * so both particle draws read the block the redirect just wrote in the same submission.
	 */
	@JvmStatic
	fun uniformSlice(): GpuBufferSlice = buffer().slice()

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS particle velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			TerrainVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}
}
