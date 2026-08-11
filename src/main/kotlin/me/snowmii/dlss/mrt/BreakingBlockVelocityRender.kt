package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.resources.Identifier
import java.util.Optional
import java.util.OptionalDouble

/**
 * The breaking-block crumbling velocity writer: the control seam, the shared VelocityConfig
 * payload write, and the writer twin's shader and layout surface.
 *
 * Minecraft renders the breaking-block overlay in `LevelRenderer.submitBlockDestroyAnimation`:
 * every breaking block with a model render shape is submitted into the `breakingOverlay` phase
 * with `ModelBakery.DESTROY_TYPES` - ten static stages of the `crumbling` render type, which
 * binds the mapped `pipeline/crumbling` pipeline (`core/rendertype_crumbling` vertex and
 * fragment shaders, BLOCK vertex format, the DST_COLOR/SRC_COLOR multiply blend, reversed-Z
 * depth test with no depth write) - and `RenderTypeFeatureRenderer.executeGroup` draws every
 * overlay through the same `PreparedRenderType.drawFromBuffer` seam the entity and moving-block
 * writers use. The prepared-draw dispatch asks this object whether the open world phase offers
 * the scene velocity view; when it does, the dispatch replaces only the owned main-target
 * crumbling draws with a two-attachment pass - the source scene color at index 0 unchanged, the
 * scene-sized RG16_FLOAT velocity view at index 1 - and binds the cached crumbling writer twin,
 * whose fragment shader ([FRAGMENT_SHADER], swapped in for the source's
 * core/rendertype_crumbling shader by [VelocityPipelineVariantKt.crumblingVelocityTwin])
 * reproduces the vanilla crumbling color output byte-identically (the alpha discard between the
 * vertex-color and ColorModulator multiplies included) and writes jitter-stripped NDC camera
 * motion into the velocity attachment.
 *
 * The payload is deliberately the terrain writer's existing [TerrainVelocityUniforms]
 * `VelocityConfig` block: [writeFrame] delegates to it on the draw's own command encoder, so
 * the crumbling overlay reads the same jitter-stripped camera reprojection and the same
 * invalid-sentinel-on-reset semantics every other scene writer does - the mapped breaking
 * overlay carries no stable block identity or history of its own, so the camera motion is the
 * whole payload, exactly like the weather and particle writers. The writer twin therefore
 * reuses [TerrainVelocityUniforms.LAYOUT] and [TerrainVelocityUniforms.UNIFORM_NAME] verbatim.
 *
 * Ineligible routes - a closed phase, a vanilla session, the latched camera-only route, a frame
 * whose scene target carries no velocity companion, a non-main output target, or an unsupported
 * pipeline - answer false from the gates and never reach the draw path: the prepared draw keeps
 * the exact vanilla behavior and cannot throw.
 */
object BreakingBlockVelocityRender {
	/** The shader path the crumbling twin swaps in for the source's core/rendertype_crumbling shader. */
	const val SHADER_PATH = "core/velocity_crumbling"

	/** The crumbling twin adds the existing terrain VelocityConfig layout, not a new one. */
	@JvmField
	val LAYOUT: BindGroupLayout = TerrainVelocityUniforms.LAYOUT

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME: String = TerrainVelocityUniforms.UNIFORM_NAME

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	private var uniformBuffer: GpuBuffer? = null

	/**
	 * Whether one pipeline is safe to replace with the crumbling writer.
	 *
	 * The crumbling render type binds the owned core/rendertype_crumbling shader family with
	 * the BLOCK vertex format: exactly the mapped `CRUMBLING` pipeline, the one pipeline the
	 * `ModelBakery.DESTROY_TYPES` stages bind. The same check never admits any terrain,
	 * entity, item, or block-shaped pipeline, whose shader paths differ.
	 */
	@JvmStatic
	fun isSupportedPipeline(pipeline: RenderPipeline): Boolean {
		val vertex = pipeline.vertexShader
		val fragment = pipeline.fragmentShader
		return vertex.path == "core/rendertype_crumbling" && fragment.path == "core/rendertype_crumbling" &&
			vertex.namespace in OWNED_SHADER_NAMESPACES && fragment.namespace in OWNED_SHADER_NAMESPACES &&
			pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.BLOCK
	}

	/** The open scene phase on which crumbling overlay draws may carry the velocity attachment. */
	@JvmStatic
	fun activeVelocityPhase(): WorldPhase? = runCatching {
			ClientRuntime.active().activeWorldPhase()?.takeIf { it.terrainVelocityView != null }
		}.getOrNull()

	/**
	 * Control seam used by the draw callback and by headless evidence before any device
	 * operation. A true result means this prepared main-target crumbling draw has an open
	 * velocity-MRT phase offering the scene velocity view and an owned crumbling pipeline; the
	 * actual render pass still has its own safe gates.
	 */
	@JvmStatic
	fun canDraw(
		prepared: PreparedRenderType,
		info: StagedVertexBuffer.ExecuteInfo,
		phase: WorldPhase?,
	): Boolean = phase?.terrainVelocityView != null &&
		isSupportedPipeline(prepared.pipeline()) &&
		prepared.outputTarget() === OutputTarget.MAIN_TARGET

	/**
	 * Fills this frame's VelocityConfig payload on [encoder] for the crumbling overlay.
	 *
	 * Delegates to the terrain writer's existing block write: the frame's published camera
	 * reprojection (jitter-stripped current-to-previous, exactly what the DLSS evaluation
	 * receives) and its reset flag, which forces the invalid sentinel for a frame with no valid
	 * predecessor. [view] is the scene-sized velocity view the draw writes into; its size is
	 * what `gl_FragCoord` is sized to, and the shader inverts the viewport transform with it to
	 * recover NDC.
	 */
	@JvmStatic
	fun writeFrame(encoder: CommandEncoder, phase: WorldPhase, view: GpuTextureView) {
		TerrainVelocityUniforms.writeFrame(encoder, buffer(), phase.activeMotion, view)
	}

	/**
	 * The prepared crumbling draw replacement. Returning false leaves
	 * `PreparedRenderType.drawFromBuffer`'s exact vanilla one-target implementation in control,
	 * which is what vanilla, CAMERA_ONLY, closed-phase, foreign, non-main, and unsupported
	 * draws require.
	 *
	 * The false path is a no-throw passthrough, and it is the only path that can answer false.
	 * Everything the owned pass will touch is preflighted before the writer takes ownership: the
	 * eligibility gates, the target textures, the twin pipeline (a pure cache lookup), the
	 * payload buffer (the writer's own cached allocation), and every descriptor input - the
	 * scissor rect, the dynamic transforms, the bound textures, and the execute-info geometry.
	 * None of that mutates encoder or pass state, so a failure there still leaves the exact
	 * source draw safe to replay; the one device call in that region, the payload buffer's
	 * first allocation, is guarded so even a device failure degrades to passthrough instead of
	 * throwing.
	 *
	 * Ownership begins at the command encoder. From that point the writer owns the draw: a
	 * failure inside the device work - a lost device, a pipeline compile failure, a pass
	 * reject - commits the draw instead of falling through, so the source draw is never
	 * replayed over partial writer work (an encoder or pass that already carries the writer's
	 * state) and the batch never sees a torn two-attachment pass. See [committedDrawFailure].
	 */
	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean {
		// Eligibility gates: plain getter reads on objects already known to be live, so an
		// ineligible draw answers false without throwing and keeps the exact vanilla route.
		val phase = activeVelocityPhase() ?: return false
		if (!canDraw(prepared, info, phase)) return false

		// Target preflight: the scene, its textures, and the velocity companion. Still pure
		// reads; a missing or foreign target is the ineligible passthrough, not a failure.
		val scene = phase.worldTargetOverride ?: return false
		val renderTarget = prepared.outputTarget().getRenderTarget()
		val colorTexture = RenderSystem.outputColorTextureOverride ?: renderTarget.colorTextureView
		val depthTexture = if (renderTarget.useDepth) {
			RenderSystem.outputDepthTextureOverride ?: renderTarget.depthTextureView
		} else {
			null
		}
		val velocityTexture = phase.terrainVelocityView ?: return false
		if (colorTexture == null || colorTexture !== scene.colorTextureView) return false

		// Owned-work preflight: the twin, the payload buffer, and every descriptor input the
		// pass will consume. Nothing here mutates encoder or pass state - the twin is a pure
		// cache lookup, the buffer is the writer's own cached allocation, and the rest are
		// plain reads - so a failure here still leaves the exact source draw safe to replay.
		// The buffer's first allocation is a device call, so it is guarded: a device failure
		// degrades to the passthrough rather than throwing out of the writer.
		val twin = crumblingVelocityTwin(velocityTwin(prepared.pipeline()))
		val payload = runCatching { buffer() }.getOrNull() ?: return false
		val scissor = prepared.scissorState()
		val scissorEnabled = scissor.enabled()
		val scissorX = scissor.x()
		val scissorY = scissor.y()
		val scissorWidth = scissor.width()
		val scissorHeight = scissor.height()
		val dynamicTransforms = prepared.dynamicTransforms()
		val textures = prepared.textures()
		val vertexBuffer = info.vertexBuffer()
		val indexBuffer = info.indexBuffer()
		val indexType = info.indexType()
		val indexCount = info.indexCount()
		val firstIndex = info.firstIndex()
		val baseVertex = info.baseVertex()
		val renderWidth = colorTexture.getWidth(0)
		val renderHeight = colorTexture.getHeight(0)

		// Ownership: the device encoder. From here the writer owns the draw, so any failure
		// commits it - the source draw is never replayed over partial writer work - and the
		// writer answers true either way.
		return try {
			val encoder = RenderSystem.getDevice().createCommandEncoder()
			writeFrame(encoder, phase, velocityTexture)

			val descriptor = RenderPassDescriptor.create { "Crumbling velocity draw with ${prepared.pipeline()}" }
				.withColorAttachment(colorTexture, Optional.empty())
				.withColorAttachment(velocityTexture, Optional.empty())
			if (depthTexture != null) {
				descriptor.withDepthAttachment(depthTexture, OptionalDouble.empty())
			}
			descriptor.withRenderArea(RenderPass.RenderArea(0, 0, renderWidth, renderHeight))

			encoder.createRenderPass(descriptor).use { pass ->
				pass.setPipeline(twin)
				if (scissorEnabled) {
					pass.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight)
				}
				RenderSystem.bindDefaultUniforms(pass)
				pass.setUniform("DynamicTransforms", dynamicTransforms)
				pass.setUniform(UNIFORM_NAME, payload.slice())
				pass.setVertexBuffer(0, vertexBuffer.slice())
				for (texture in textures) {
					pass.bindTexture(texture.name(), texture.textureView(), texture.sampler())
				}
				pass.setIndexBuffer(indexBuffer, indexType)
				pass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)
			}
			true
		} catch (failure: Throwable) {
			committedDrawFailure(failure)
			true
		}
	}

	/**
	 * Disposes a draw the writer owned but could not complete: the device work threw after the
	 * command encoder existed, so the encoder or pass may already carry partial writer state.
	 *
	 * The failure is committed rather than reported: the caller answers true and the source
	 * draw is not replayed, because replaying it over partial writer work would draw the
	 * crumbling overlay twice into a torn pass. The phase and session expose no per-draw
	 * failure seam to signal mid-frame; the established failure handling is the phase's
	 * end-of-frame disposition, where a frame whose evaluation does not complete resets the
	 * object history. That runs regardless of this draw's outcome, so nothing needs to be
	 * retained here. Never throws.
	 */
	@Suppress("UNUSED_PARAMETER")
	private fun committedDrawFailure(failure: Throwable) {
	}

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS crumbling velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			TerrainVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}

	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}
