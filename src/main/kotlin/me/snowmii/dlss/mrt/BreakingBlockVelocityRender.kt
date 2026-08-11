package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.GpuDevice
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
import org.slf4j.LoggerFactory
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
 * core/rendertype_crumbling shader by [writerTwin] for [VelocityWriter.CRUMBLING])
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

	private val LOGGER = LoggerFactory.getLogger("me.snowmii.dlss.mrt.BreakingBlockVelocityRender")

	private var uniformBuffer: GpuBuffer? = null

	/**
	 * Headless test seam: the open phase `draw` gates on when no live `ClientRuntime` phase
	 * exists. Production never sets this; the default reads the render loop's phase exactly as
	 * before. The same eligibility filter (an open velocity-MRT phase offering the scene
	 * velocity view) applies either way.
	 */
	@JvmStatic
	internal var activePhaseOverride: WorldPhase? = null

	/**
	 * The device the writer draws through: the encoder for the owned two-attachment pass and the
	 * payload buffer's first allocation. Production resolves the live Blaze3D device exactly as
	 * before; the headless evidence swaps in a recording fake command backend to execute the
	 * eligible production draw without a device.
	 */
	@JvmStatic
	internal var deviceProvider: () -> GpuDevice = { RenderSystem.getDevice() }

	/**
	 * Resolves the prepared draw's output target, mirroring exactly what vanilla
	 * `PreparedRenderType.drawFromBuffer` reads. Production resolves through the target's own
	 * supplier; the headless evidence points it at the fake scene target so the eligible
	 * production draw can run without a Minecraft instance.
	 */
	@JvmStatic
	internal var outputTargetResolver: (OutputTarget) -> RenderTarget = { it.getRenderTarget() }

	/**
	 * Headless test seam: drops the writer's cached payload allocation so the next draw forces
	 * a fresh [deviceProvider] allocation. Production never calls this.
	 */
	@JvmStatic
	internal fun resetPayloadBuffer() {
		uniformBuffer = null
	}

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
			(activePhaseOverride ?: ClientRuntime.active().activeWorldPhase())
				?.takeIf { it.terrainVelocityView != null }
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
	 * draws require - and what every failure of an eligible draw requires too, so the crumbling
	 * overlay is drawn instead of silently dropped.
	 *
	 * The false path is a no-throw passthrough and the only path that can answer false; the
	 * true path is answered only when the two-attachment replacement fully recorded (pipeline,
	 * uniforms, binds, draw, pass close all succeeded), so the dispatch's cancellation always
	 * follows a successfully replaced draw. Everything the owned pass
	 * will touch is preflighted before the writer takes ownership: the eligibility gates, the
	 * target textures, the twin pipeline (a pure cache lookup), the payload buffer (the
	 * writer's own cached allocation), and every descriptor input - the scissor rect, the
	 * dynamic transforms, the bound textures, and the execute-info geometry. None of that
	 * mutates encoder or pass state, so a failure there still leaves the exact source draw
	 * safe to replay; the payload buffer's first allocation is a device call, so it is guarded
	 * like every other device call and a device failure degrades to passthrough instead of
	 * throwing.
	 *
	 * The whole draw runs under one catch: a failure at any point - an eligibility read, a
	 * preflight read, the payload write, pass creation, a pipeline compile failure on first
	 * bind, a uniform or geometry bind, the draw submission, or the pass close - is logged and
	 * answered false. The writer's own encoder and pass are the only state it ever touches, and
	 * the pass is always closed through the use block (submitting whatever partial work it
	 * recorded), so `PreparedRenderType.drawFromBuffer` replays the exact source draw on its
	 * own fresh encoder and pass with nothing torn or doubled; a draw the writer failed is
	 * never consumed. Never throws.
	 */
	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean {
		return try {
			// Eligibility gates: plain getter reads on objects already known to be live, so an
			// ineligible draw answers false without throwing and keeps the exact vanilla route.
			val phase = activeVelocityPhase() ?: return false
			if (!canDraw(prepared, info, phase)) return false

			// Target preflight: the scene, its textures, and the velocity companion. Still pure
			// reads; a missing or foreign target is the ineligible passthrough, not a failure.
			val scene = phase.worldTargetOverride ?: return false
			val renderTarget = outputTargetResolver(prepared.outputTarget())
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
			val twin = writerTwin(prepared.pipeline(), VelocityWriter.CRUMBLING)
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

			// Ownership: the device encoder. The writer's encoder and pass are self-contained - the
			// pass is closed through the use block even on failure - so a failure here answers
			// false and the source draw replays on its own fresh encoder; the dispatch cancels
			// only a successfully recorded replacement.
			val encoder = deviceProvider().createCommandEncoder()
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
			// Never throws, never consumes: the dispatch does not cancel, and the exact vanilla
			// one-target draw replays. The warning keeps a repeated failure visible in the log
			// instead of a silently dropped overlay.
			LOGGER.warn("Breaking-block crumbling velocity draw failed; the vanilla draw replays", failure)
			false
		}
	}

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: deviceProvider().createBuffer(
			{ "DLSS crumbling velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			TerrainVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}

	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}
