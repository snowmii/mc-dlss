package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble

/**
 * The piston moving-block velocity writer: the block identity and batching isolation plumbing,
 * the piston render-state capture seam, the shared BlockVelocityConfig payload write, and the
 * writer twin's shader and layout surface.
 *
 * Minecraft renders a moving piston block through `PistonHeadRenderer`, which submits one or
 * two `MovingBlockRenderState`s into `MovingBlockFeatureRenderer`; that feature renderer
 * tesselates each submit's block model into the `solidMovingBlock` / `cutoutMovingBlock` /
 * `translucentMovingBlock` render types - the block-shaped `SOLID_BLOCK` / `CUTOUT_BLOCK` /
 * `TRANSLUCENT_BLOCK` pipelines - and the draws ride the same `PreparedRenderType.drawFromBuffer`
 * seam the entity writer uses. The capture seam binds each submitted `MovingBlockRenderState`
 * object to the collision-free packed long identity of its baked block position and records
 * its absolute render position
 * (the baked block position plus the piston's current interpolated offset) into the shared
 * [WorldPhase] object-motion history, so the draw path reads the block's piston offset-delta
 * displacement through the same frame boundary the entity history uses. The draw seam then
 * selects the cached two-target moving-block twin only for owned main-target solid/cutout
 * draws that carry that identity; the translucent layer's `ITEM_ENTITY_TARGET` draws and every
 * identity-less moving-block draw (falling blocks share the feature renderer) keep the exact
 * vanilla route.
 *
 * The payload is the same shape the entity writer uses - one `mat4 ObjectReprojection` plus one
 * `vec4 VelocityParams` (reset flag, velocity viewport size) - but under its own
 * `BlockVelocityConfig` block and layout, so the writer's twin and payload are distinct from
 * every other writer's. The reprojection is [objectReprojection] with the block's offset-delta
 * displacement; the exact reset/unknown-history sentinel classification is the entity writer's:
 * a missing published motion, a reset frame, a missing view-projection or jitter, or a missing
 * displacement (first observation, evicted id, reset history) all force the invalid sentinel.
 *
 * Ineligible routes - a closed phase, a vanilla session, the latched camera-only route, a frame
 * without a velocity companion, a non-main output target, an unsupported pipeline, or a draw
 * without a bound block identity - answer false from the gates and never reach the draw path:
 * the prepared draw and the batch state machine keep the exact vanilla behavior and cannot
 * throw.
 */
object MovingBlockVelocityRender {
	/** The shader path the moving-block twin swaps in for the source's core/block shader. */
	const val SHADER_PATH = "core/velocity_block"

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME = "BlockVelocityConfig"

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	@JvmField
	val LAYOUT: BindGroupLayout = BindGroupLayout.builder()
		.withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)
		.build()

	/** `mat4 ObjectReprojection` + `vec4 VelocityParams`, both std140-aligned. */
	@JvmField
	val UBO_SIZE: Int = Std140SizeCalculator()
		.putMat4f()
		.putVec4()
		.get()

	private var uniformBuffer: GpuBuffer? = null

	/**
	 * Whether one pipeline is safe to replace with the moving-block writer.
	 *
	 * The moving-block render types bind the owned core/block shader family with the BLOCK
	 * vertex format: exactly the `SOLID_BLOCK` and `CUTOUT_BLOCK` pipelines the solid and
	 * cutout moving-block layers draw with. The same check also admits `TRANSLUCENT_BLOCK`,
	 * whose render type is separately excluded by the main-target eligibility gate, so the
	 * pipeline gate alone never misroutes a translucent moving-block draw.
	 */
	@JvmStatic
	fun isSupportedPipeline(pipeline: RenderPipeline): Boolean {
		val vertex = pipeline.vertexShader
		val fragment = pipeline.fragmentShader
		return vertex.path == "core/block" && fragment.path == "core/block" &&
			vertex.namespace in OWNED_SHADER_NAMESPACES && fragment.namespace in OWNED_SHADER_NAMESPACES &&
			pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.BLOCK
	}

	/**
	 * Whether one block-entity renderer class is the mapped piston moving-block renderer.
	 *
	 * Exact positive membership, like the static block-entity family: a subclass of
	 * `PistonHeadRenderer` - even one that stages different geometry - never opens the capture
	 * seam, because the offset-delta reprojection is only sound for the exact mapped class.
	 */
	@JvmStatic
	fun isPistonHeadRenderer(rendererClass: Class<*>): Boolean =
		rendererClass == PistonHeadRenderer::class.java

	/** The open scene phase on which piston moving-block draws may carry the velocity attachment. */
	@JvmStatic
	fun activeVelocityPhase(): WorldPhase? = runCatching {
			ClientRuntime.active().activeWorldPhase()?.takeIf { it.terrainVelocityView != null }
		}.getOrNull()

	/**
	 * The piston render-state capture seam, called from the block-entity dispatcher before the
	 * piston renderer's submit runs.
	 *
	 * Each non-null `MovingBlockRenderState` of the render state - the moving block, and the
	 * retracting piston base - is bound to the collision-free packed long identity of its baked
	 * block position and captured into the moving-block domain of the frame's object-motion
	 * history. The moving block draws at
	 * its baked position plus the piston's current interpolated offset (`xOffset/yOffset/zOffset`
	 * from the render state), so its capture position is `blockPos + offset`; the retracting
	 * base draws at its baked position without any offset translate, so its capture position is
	 * `basePos` and its displacement is exactly zero - the camera reprojection - while the head
	 * moving beside it carries the offset delta. Both share the frame boundary and reset
	 * lifecycle of the entity history, so a first observation, an evicted id, or a reset frame
	 * all read the unknown-history sentinel. With no eligible phase (vanilla, camera-only, or a
	 * closed phase) nothing is bound or captured and the submit keeps its exact source route.
	 */
	@JvmStatic
	fun capturePiston(state: PistonHeadRenderState) {
		val phase = activeVelocityPhase() ?: return
		val xOffset = state.xOffset.toDouble()
		val yOffset = state.yOffset.toDouble()
		val zOffset = state.zOffset.toDouble()
		state.block?.let { block ->
			val pos = block.blockPos
			val id = MovingBlockVelocityWriterBindings.blockId(pos.x, pos.y, pos.z)
			MovingBlockVelocityWriterBindings.bindMovingBlock(block, id)
			phase.captureBlock(id, pos.x + xOffset, pos.y + yOffset, pos.z + zOffset)
		}
		state.base?.let { base ->
			val pos = base.blockPos
			val id = MovingBlockVelocityWriterBindings.blockId(pos.x, pos.y, pos.z)
			MovingBlockVelocityWriterBindings.bindMovingBlock(base, id)
			phase.captureBlock(id, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())
		}
	}

	/**
	 * Control seam used by the draw callback and by headless evidence before any device
	 * operation. A true result means this prepared main-target draw has a bound moving-block
	 * identity, an open velocity-MRT phase, and an owned block-shaped pipeline; the actual
	 * render pass still has its own safe gates.
	 */
	@JvmStatic
	fun canDraw(
		prepared: PreparedRenderType,
		info: StagedVertexBuffer.ExecuteInfo,
		phase: WorldPhase?,
	): Boolean = MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info) != null &&
		phase?.terrainVelocityView != null &&
		isSupportedPipeline(prepared.pipeline()) &&
		prepared.outputTarget() === OutputTarget.MAIN_TARGET

	/**
	 * Classifies and computes the reprojection one moving-block draw writes, or null when the
	 * draw must write the invalid sentinel.
	 *
	 * The displacement is the block's piston offset-delta: this frame's captured position minus
	 * the last published one, read from the shared object-motion history. A missing published
	 * motion, a reset frame, a missing view-projection or jitter, or a missing displacement (a
	 * first observation, an id evicted at the frame boundary, or a reset history) all mean the
	 * draw must write the invalid sentinel instead of a fabricated vector. Null is that
	 * sentinel signal; the writer writes the invalid classification when this returns null.
	 */
	@JvmStatic
	fun movingBlockReprojection(
		motion: DlssFrameMotion?,
		currentViewProjection: Matrix4f?,
		jitter: DlssJitterOffset?,
		displacement: Vector3f?,
	): Matrix4f? {
		if (motion == null || motion.reset || currentViewProjection == null || jitter == null || displacement == null) {
			return null
		}
		return objectReprojection(motion, currentViewProjection, jitter, displacement)
	}

	/** Writes one moving-block draw's piston offset-delta reprojection on [encoder]. */
	@JvmStatic
	fun writeFrame(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		phase: WorldPhase,
		blockId: Long,
		view: GpuTextureView,
	) {
		val reprojection = movingBlockReprojection(
			phase.activeMotion,
			phase.currentViewProjection,
			phase.activeJitter,
			phase.blockMotionDisplacement(blockId),
		)
		writePayload(encoder, buffer, reprojection ?: IDENTITY, reprojection == null, view)
	}

	private fun writePayload(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		reprojection: Matrix4f,
		invalid: Boolean,
		view: GpuTextureView,
	) {
		MemoryStack.stackPush().use { stack ->
			val data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(reprojection)
				.putVec4(
					if (invalid) 1f else 0f,
					view.getWidth(0).toFloat(),
					view.getHeight(0).toFloat(),
					0f,
				)
				.get()
			encoder.writeToBuffer(buffer.slice(), data)
		}
	}

	/**
	 * The prepared moving-block draw replacement. Returning false leaves
	 * `PreparedRenderType.drawFromBuffer`'s exact vanilla one-target implementation in control,
	 * which is what vanilla, CAMERA_ONLY, foreign, identity-less (falling block), non-main, and
	 * unsupported draws require.
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
		val blockId = MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info) ?: return false

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
		val twin = writerTwin(prepared.pipeline(), VelocityWriter.MOVING_BLOCK)
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
			writeFrame(encoder, payload, phase, blockId, velocityTexture)

			val descriptor = RenderPassDescriptor.create { "Moving block velocity draw with ${prepared.pipeline()}" }
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
	 * draw is not replayed, because replaying it over partial writer work would draw the block
	 * twice into a torn pass. The phase and session expose no per-draw failure seam to signal
	 * mid-frame; the established failure handling is the phase's end-of-frame disposition,
	 * where a frame whose evaluation does not complete resets the object history. That runs
	 * regardless of this draw's outcome, so nothing needs to be retained here. Never throws.
	 */
	@Suppress("UNUSED_PARAMETER")
	private fun committedDrawFailure(failure: Throwable) {
	}

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS moving block velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}

	private val IDENTITY = Matrix4f()
	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}

/**
 * Render-thread identity plumbing for piston moving-block geometry.
 *
 * Minecraft submits a moving piston block as one or two `MovingBlockRenderState` objects inside
 * `PistonHeadRenderer.submit`; `MovingBlockFeatureRenderer.buildGroup` later tesselates each
 * submit's model into the moving-block render types. The capture seam ([MovingBlockVelocityRender.capturePiston],
 * called from the block-entity dispatcher) binds each submitted render state object to its
 * block-position id; the tesselation redirect then installs that id on the thread while one
 * submit's quads are staged, so the shared `RenderTypeFeatureRenderer.Group` draw boundary
 * isolates one draw per moving block and records the draw -> id -> ExecuteInfo chain. Falling
 * blocks ride the same feature renderer but never go through the capture seam, so their render
 * states carry no id and their draws keep the exact vanilla batching.
 *
 * The batching isolation state machine mirrors the entity writer's: an eligible moving-block
 * draw forces a fresh staged draw (clearing `Group.lastDraw`/`lastRenderType` and suppressing
 * the reorder lookup), and the draw after an eligible run must also start fresh so identity-less
 * geometry (a falling block after a piston block, same render type) can never consolidate into
 * the moving block's draw. Consecutive ineligible draws regain vanilla reorder/consolidation
 * after that one boundary. The machine runs in parallel with the entity machine; exactly one of
 * them is ever active in one group, because groups are per feature renderer.
 */
object MovingBlockVelocityWriterBindings {
	private val movingBlockIds = IdentityHashMap<Any, Long>()
	private val drawIds = IdentityHashMap<Any, Long>()
	private val executeInfoIds = IdentityHashMap<Any, Long>()
	private val movingBlockContext = ThreadLocal<Long?>()
	private val eligibleDrawContext = ThreadLocal<Boolean>()
	private val previousEligibleDraw = ThreadLocal<Boolean>()
	private val afterEligibleDraw = ThreadLocal<Boolean>()
	private val consolidationContext = ThreadLocal<Boolean>()
	private val governingContext = ThreadLocal<Boolean>()
	private val nonMovingDrawIndexes = IdentityHashMap<List<*>, MutableMap<Any, Int>>()

	/**
	 * The collision-free identity of one baked block position: the packed long the game itself
	 * assigns to block positions.
	 *
	 * The identity is exact over every valid world position - `BlockPos.asLong` packs the full
	 * 26-bit horizontal and 12-bit vertical coordinate ranges without compression, so two
	 * distinct valid positions can never share a history slot, no matter how far apart they sit
	 * or which quadrant they are in. It is a pure function of the coordinates, so the same
	 * block reuses the same history slot across frames without any per-frame map.
	 *
	 * The key is a long in the moving-block domain. The shared object-motion history resolves
	 * long keys in their own partition, and entity ids (positive ints) and the static
	 * block-entity token (`Int.MIN_VALUE`) live in the int domain, so the three domains can
	 * never collide: a block can never read an entity's predecessor and an entity can never
	 * read a block's.
	 */
	@JvmStatic
	fun blockId(x: Int, y: Int, z: Int): Long = BlockPos.asLong(x, y, z)

	/** Associates one submitted moving-block render state with its block-position id. */
	@JvmStatic
	fun bindMovingBlock(state: Any, id: Long) {
		movingBlockIds[state] = id
	}

	/** The id one moving-block render state carries, or null for identity-less moving geometry. */
	@JvmStatic
	fun movingBlockId(state: Any): Long? = movingBlockIds[state]

	/** Installs one moving block's id on the thread while its quads are staged. */
	@JvmStatic
	fun beginMovingBlock(id: Long) {
		movingBlockContext.set(id)
	}

	@JvmStatic
	fun endMovingBlock() {
		movingBlockContext.remove()
	}

	@JvmStatic
	fun beginDraw(renderType: RenderType): Boolean =
		beginDraw(renderType.pipeline(), isEligibleRenderType(renderType, shouldIsolateDraw(renderType.pipeline())))

	@JvmStatic
	fun beginDraw(pipeline: RenderPipeline): Boolean = beginDraw(pipeline, shouldIsolateDraw(pipeline))

	/**
	 * Opens one Group.getVertexBuilder boundary with an explicit eligibility result. The overload
	 * is also the JVM-test seam for the batch state machine: a supported moving-block draw must
	 * be followed by a fresh draw when eligibility ends, while consecutive ineligible submits
	 * regain vanilla reorder/consolidation behavior after that one boundary.
	 */
	@Suppress("UNUSED_PARAMETER")
	@JvmStatic
	internal fun beginDraw(pipeline: RenderPipeline, eligible: Boolean): Boolean {
		val endedEligibleDraw = previousEligibleDraw.get() == true && !eligible
		if (endedEligibleDraw) {
			afterEligibleDraw.set(true)
		}
		previousEligibleDraw.set(eligible)
		eligibleDrawContext.set(eligible)
		val freshBoundary = eligible || endedEligibleDraw
		consolidationContext.set(freshBoundary)
		return freshBoundary
	}

	@JvmStatic
	fun endDraw() {
		consolidationContext.remove()
		eligibleDrawContext.remove()
	}

	@JvmStatic
	fun suppressConsolidation(): Boolean = consolidationContext.get() == true

	/** Whether an eligible moving-block draw is currently governing this draw boundary. */
	@JvmStatic
	fun isGoverning(): Boolean = governingContext.get() == true

	/**
	 * Records which batching machine governs the current draw boundary.
	 *
	 * Called by the shared feature-renderer mixin at every Group.getVertexBuilder boundary: the
	 * moving-block machine governs from its own fresh boundary (an eligible draw or the
	 * eligibility-ending transition) until the entity machine takes a fresh boundary of its own,
	 * so a moving-block history can never steer an entity group's reorder decision and an entity
	 * history can never steer a moving-block group's. With neither machine ever fresh, the marker
	 * stays false and the reorder decision is exactly vanilla's.
	 */
	@JvmStatic
	fun setGoverning(governing: Boolean) {
		governingContext.set(governing)
	}

	/**
	 * Chooses the index for RenderTypeFeatureRenderer.Group's reorder lookup.
	 *
	 * Before any eligible moving-block draw, this is exactly vanilla's indexOf. Once an eligible
	 * moving-block draw has ended, eligible draws are never looked up, and each identity-less
	 * render type gets a private latest index: the first identity-less occurrence (a falling
	 * block after a piston block, same render type) reserves a new draw, and later occurrences
	 * reuse that safe draw rather than indexOf's earlier moving-block duplicate.
	 */
	@JvmStatic
	fun consolidationIndex(renderTypes: List<*>, preparedRenderType: Any): Int {
		if (eligibleDrawContext.get() == true) {
			return -1
		}
		if (afterEligibleDraw.get() != true) {
			return if (suppressConsolidation()) -1 else renderTypes.indexOf(preparedRenderType)
		}

		val indexes = nonMovingDrawIndexes.getOrPut(renderTypes) { HashMap() }
		val existingIndex = indexes[preparedRenderType]
		if (!suppressConsolidation() && existingIndex != null &&
			existingIndex < renderTypes.size && renderTypes[existingIndex] == preparedRenderType
		) {
			return existingIndex
		}

		indexes[preparedRenderType] = renderTypes.size
		return -1
	}

	private fun isEligibleRenderType(renderType: RenderType, pipelineEligible: Boolean): Boolean =
		pipelineEligible && renderType.outputTarget() === OutputTarget.MAIN_TARGET

	/** Forces one staged draw per supported moving-block draw, defeating same-render-type consolidation. */
	@JvmStatic
	fun shouldIsolateDraw(pipeline: RenderPipeline): Boolean =
		movingBlockContext.get() != null && MovingBlockVelocityRender.activeVelocityPhase() != null &&
			MovingBlockVelocityRender.isSupportedPipeline(pipeline)

	@JvmStatic
	fun bindDraw(draw: StagedVertexBuffer.Draw) {
		val id = movingBlockContext.get()
		if (id != null && eligibleDrawContext.get() == true) {
			drawIds[draw] = id
		}
	}

	@JvmStatic
	fun bindExecuteInfo(draw: StagedVertexBuffer.Draw, info: StagedVertexBuffer.ExecuteInfo?) {
		if (info == null) return
		val id = drawIds[draw] ?: return
		executeInfoIds[info] = id
	}

	/** The block id one prepared moving-block draw belongs to, or null for identity-less draws. */
	@JvmStatic
	fun executeInfoMovingBlockId(info: StagedVertexBuffer.ExecuteInfo): Long? = executeInfoIds[info]

	@JvmStatic
	fun clearFrame() {
		movingBlockIds.clear()
		drawIds.clear()
		executeInfoIds.clear()
		nonMovingDrawIndexes.clear()
		movingBlockContext.remove()
		eligibleDrawContext.remove()
		previousEligibleDraw.remove()
		afterEligibleDraw.remove()
		consolidationContext.remove()
		governingContext.remove()
	}
}
