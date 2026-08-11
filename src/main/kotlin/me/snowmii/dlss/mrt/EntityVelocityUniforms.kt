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
import net.minecraft.client.renderer.blockentity.CopperGolemStatueBlockRenderer
import net.minecraft.client.renderer.blockentity.LecternRenderer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.system.MemoryStack
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble

/**
 * Per-entity and static block-entity motion uniform and draw binding contract for the velocity MRT.
 *
 * The entity twin keeps the source entity shader and all of its defines/layouts, then swaps only
 * the fragment shader and adds this block. A missing object predecessor, a reset camera/object
 * history, or a missing active world context sets [INVALID_VELOCITY] through [VelocityParams.x];
 * the fragment shader classifies every pixel before dividing its previous homogeneous coordinate.
 * A static block-entity draw reuses the same writer with an exactly zero displacement, so its
 * reprojection is the frame's camera reprojection and its invalid classification is the same
 * one entity draws apply.
 */
object EntityVelocityUniforms {
	const val INVALID_VELOCITY = 10000.0f
	const val UNIFORM_NAME = "EntityVelocityConfig"
	const val SHADER_PATH = "core/velocity_entity"

	@JvmField
	val FRAGMENT_SHADER = net.minecraft.resources.Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

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

	/** Only the owned core/entity shader family is safe to replace with this writer. */
	@JvmStatic
	fun isSupportedPipeline(pipeline: RenderPipeline): Boolean {
		val vertex = pipeline.vertexShader
		val fragment = pipeline.fragmentShader
		return vertex.path == "core/entity" && fragment.path == "core/entity" &&
			vertex.namespace in OWNED_SHADER_NAMESPACES && fragment.namespace in OWNED_SHADER_NAMESPACES &&
			pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.ENTITY
	}

	/** The open scene phase on which entity geometry may carry the velocity attachment. */
	@JvmStatic
	fun activeVelocityPhase(): WorldPhase? = runCatching {
			ClientRuntime.active().activeWorldPhase()?.takeIf { it.entityVelocityActive }
		}.getOrNull()

	/** Writes this entity's object reprojection and reset classification on [encoder]. */
	@JvmStatic
	fun writeFrame(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		phase: WorldPhase,
		entityId: Int,
		view: GpuTextureView,
	) {
		val motion = phase.activeMotion
		val currentViewProjection = phase.currentViewProjection
		val jitter = phase.activeJitter
		val displacement = phase.objectMotionDisplacement(entityId)
		val invalid = motion == null || motion.reset || currentViewProjection == null || jitter == null || displacement == null
		val reprojection = if (invalid) IDENTITY else objectReprojection(
			requireNotNull(motion),
			requireNotNull(currentViewProjection),
			requireNotNull(jitter),
			requireNotNull(displacement),
		)
		writePayload(encoder, buffer, reprojection, invalid, view)
	}

	/**
	 * Classifies and computes the reprojection one static block-entity draw writes, or null when
	 * the frame's camera history is missing or reset.
	 *
	 * A static block has no displacement of its own, so its object reprojection is exactly the
	 * frame's camera reprojection - the camera-correct zero-displacement object motion the
	 * invariant names. The invalid classification is the same one entity draws apply: a missing
	 * published motion, a reset frame, a missing view-projection, or a missing jitter all mean
	 * the draw must write the invalid sentinel instead of a fabricated vector. Null is that
	 * sentinel signal; the writer writes [INVALID_VELOCITY] classification when this returns
	 * null.
	 */
	@JvmStatic
	fun blockEntityReprojection(
		motion: DlssFrameMotion?,
		currentViewProjection: Matrix4f?,
		jitter: DlssJitterOffset?,
	): Matrix4f? {
		if (motion == null || motion.reset || currentViewProjection == null || jitter == null) {
			return null
		}
		return objectReprojection(motion, currentViewProjection, jitter, ZERO_DISPLACEMENT)
	}

	/** Writes one static block-entity draw's zero-displacement motion on [encoder]. */
	@JvmStatic
	fun writeBlockEntityFrame(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		phase: WorldPhase,
		view: GpuTextureView,
	) {
		val reprojection = blockEntityReprojection(phase.activeMotion, phase.currentViewProjection, phase.activeJitter)
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

	private val ZERO_DISPLACEMENT = Vector3f(0f, 0f, 0f)

	private val IDENTITY = Matrix4f()
	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}

/**
 * Render-thread identity plumbing for CPU-baked entity and block-entity geometry.
 *
 * Minecraft's model submits do not carry their originating EntityRenderState. The entity
 * dispatcher therefore brackets each entity renderer call, the submit-record constructor copies
 * that id into an identity map, and ModelFeatureRenderer.prepareModel installs it while its
 * geometry is staged. The draw and ExecuteInfo maps then preserve the one-to-one id through the
 * batching and upload boundary, where a per-draw uniform is finally available.
 *
 * Ordinary static block-entity models ride the same batching and draw boundary but have no id
 * and no displacement of their own. Only the positive static renderer family opens the
 * block-entity bracket: [isStaticBlockEntityRenderer] admits the mapped renderers whose submit
 * geometry is time-invariant, and every other block-entity renderer keeps the exact source
 * route without a bracket, so an animated banner flag, chest lid, enchantment book, shulker
 * lid, bell, conduit, skull, spinning spawner display entity, or moving piston head can never
 * be misdrawn as static geometry. The bracket associates staged model geometry with
 * [BLOCK_ENTITY_TOKEN], which the id accessors hide: an adjacent entity's identity can never be
 * inherited, and the block-entity predicates let the shared writer draw them with the
 * zero-displacement camera reprojection. Any entity bracket - even an identity-less one - masks
 * the outer block context for its whole scope and restores it on exit, so a nested
 * EntityRenderDispatcher submit inside a block-entity renderer cannot inherit the block token.
 */
object EntityVelocityWriterBindings {
	/**
	 * The positive static block-entity renderer family.
	 *
	 * Read out of the mapped 26.2 `BlockEntityRenderers` registry: these are the renderers whose
	 * `submit` stages time-invariant model geometry through the shared core/entity model
	 * pipeline. Every other registered renderer is dynamic (banner flag wave, chest/shulker lid,
	 * enchantment book, bell ring, conduit spin/bob, skull animation, vault spin, campfire item
	 * rotation, beacon/end-gateway beam, decorated-pot wobble), stages non-model geometry
	 * (shelf and brushable-block items, end portal cube, piston moving-block, structure/test
	 * bounding box), or is explicitly out of scope (signs). Only the two classes below qualify.
	 */
	private val STATIC_BLOCK_ENTITY_RENDERERS: Set<Class<*>> = setOf(
		LecternRenderer::class.java,
		CopperGolemStatueBlockRenderer::class.java,
	)

	/**
	 * Whether one block-entity renderer class is in the positive static model family.
	 *
	 * Membership is exact positive allowlist matching: a subclass of a static renderer - even
	 * one that overrides `submit` with dynamic behavior - is never admitted, because the
	 * bracket's zero-displacement reprojection is only sound for the exact mapped classes whose
	 * submit geometry is time-invariant. The block-entity dispatcher consults this before
	 * installing the block-entity marker: dynamic-capable and subclass renderers get no bracket
	 * and no token, keeping their exact source route.
	 */
	@JvmStatic
	fun isStaticBlockEntityRenderer(rendererClass: Class<*>): Boolean =
		rendererClass in STATIC_BLOCK_ENTITY_RENDERERS

	/**
	 * The submit/draw marker for ordinary static block-entity geometry.
	 *
	 * Block-entity models are staged through the same [ModelFeatureRenderer] batching and
	 * [PreparedRenderType] draw seam as entity models, but carry no entity identity and no
	 * displacement of their own. The dispatcher bracket binds this sentinel instead of an id:
	 * it is never a real Minecraft entity id (those are small positive ints), the id accessors
	 * hide it ([submitEntityId] and [executeInfoEntityId] answer null for it), and only the
	 * block-entity predicates expose it, so an adjacent entity's identity can never be inherited
	 * and entity-id draws stay isolated.
	 */
	const val BLOCK_ENTITY_TOKEN = Int.MIN_VALUE

	private val entityContext = ThreadLocal<Int?>()
	private val blockEntityContext = ThreadLocal<Boolean>()
	/** Depth of open entity renderer brackets; masks the outer block context even for null ids. */
	private val entityBracketDepth = ThreadLocal<Int>()
	private val submitContext = ThreadLocal<Int?>()
	private val consolidationContext = ThreadLocal<Boolean>()
	private val eligibleDrawContext = ThreadLocal<Boolean>()
	private val previousEligibleDraw = ThreadLocal<Boolean>()
	private val afterEligibleDraw = ThreadLocal<Boolean>()
	private val nonEntityDrawIndexes = IdentityHashMap<List<*>, MutableMap<Any, Int>>()
	private val submitIds = IdentityHashMap<Any, Int>()
	private val drawIds = IdentityHashMap<Any, Int>()
	private val executeInfoIds = IdentityHashMap<Any, Int>()

	@JvmStatic
	fun beginEntity(entityId: Int?) {
		if (entityId == null) entityContext.remove() else entityContext.set(entityId)
		// Any entity bracket - including an identity-less one - masks an outer block-entity
		// context: a nested EntityRenderDispatcher submit (e.g. a spawner display entity) must
		// never inherit the block token. The outer context is untouched and resumes on endEntity.
		entityBracketDepth.set((entityBracketDepth.get() ?: 0) + 1)
	}

	@JvmStatic
	fun endEntity() {
		entityContext.remove()
		submitContext.remove()
		val depth = (entityBracketDepth.get() ?: 1) - 1
		if (depth <= 0) entityBracketDepth.remove() else entityBracketDepth.set(depth)
	}

	/**
	 * Opens the block-entity bracket: clears any adjacent entity identity and marks the context
	 * so [bindSubmit] associates staged model geometry with the block-entity marker instead.
	 */
	@JvmStatic
	fun beginBlockEntity() {
		entityContext.remove()
		blockEntityContext.set(true)
	}

	@JvmStatic
	fun endBlockEntity() {
		blockEntityContext.remove()
		submitContext.remove()
	}

	@JvmStatic
	fun bindSubmit(submit: Any) {
		val entityId = entityContext.get()
		if (entityId != null) {
			submitIds[submit] = entityId
		} else if (entityBracketDepth.get() == null && blockEntityContext.get() == true) {
			// Only an identity-less submit outside any entity bracket may take the block token;
			// a null-id entity inside a block bracket stays identity-less and keeps vanilla.
			submitIds[submit] = BLOCK_ENTITY_TOKEN
		}
	}

	/** The entity id one submit record belongs to, or null for block-entity and identity-less submits. */
	@JvmStatic
	fun submitEntityId(submit: Any): Int? = submitIds[submit]?.takeIf { it != BLOCK_ENTITY_TOKEN }

	/** Whether one submit record belongs to ordinary static block-entity geometry. */
	@JvmStatic
	fun submitIsBlockEntity(submit: Any): Boolean = submitIds[submit] == BLOCK_ENTITY_TOKEN

	@JvmStatic
	fun beginSubmit(submit: Any) {
		val identity = submitIds[submit]
		if (identity == null) {
			submitContext.remove()
		} else {
			submitContext.set(identity)
		}
	}

	@JvmStatic
	fun endSubmit() {
		submitContext.remove()
	}

	@JvmStatic
	fun beginDraw(renderType: RenderType): Boolean =
		beginDraw(renderType.pipeline(), isEligibleRenderType(renderType, shouldIsolateDraw(renderType.pipeline())))

	@JvmStatic
	fun beginDraw(pipeline: RenderPipeline): Boolean = beginDraw(pipeline, shouldIsolateDraw(pipeline))

	/**
	 * Opens one Group.getVertexBuilder boundary with an explicit eligibility result. The overload
	 * is also the JVM-test seam for the batch state machine: a supported entity draw must be
	 * followed by a fresh draw when eligibility ends, while consecutive ineligible submits regain
	 * vanilla reorder/consolidation behavior after that one boundary.
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
		// This flag controls both Group.lastDraw and getOrAddDraw's reorder lookup. A transition
		// must suppress both reuse paths or a no-id block/entity submit can still find the entity's
		// PreparedRenderType in drawRenderTypes after lastDraw has been cleared.
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

	/**
	 * Chooses the index for RenderTypeFeatureRenderer.Group's reorder lookup.
	 *
	 * Before any eligible entity draw, this is exactly vanilla's indexOf. Once an entity draw has
	 * ended, eligible draws are never looked up, and each non-entity render type gets a private
	 * latest index. The first non-entity occurrence reserves a new draw; later occurrences reuse
	 * that safe non-entity draw rather than indexOf's earlier entity duplicate.
	 */
	@JvmStatic
	fun consolidationIndex(renderTypes: List<*>, preparedRenderType: Any): Int {
		if (eligibleDrawContext.get() == true) {
			return -1
		}
		if (afterEligibleDraw.get() != true) {
			return if (suppressConsolidation()) -1 else renderTypes.indexOf(preparedRenderType)
		}

		val indexes = nonEntityDrawIndexes.getOrPut(renderTypes) { HashMap() }
		val existingIndex = indexes[preparedRenderType]
		if (!suppressConsolidation() && existingIndex != null &&
			existingIndex < renderTypes.size && renderTypes[existingIndex] == preparedRenderType
		) {
			return existingIndex
		}

		// getOrAddDraw appends this prepared type immediately after the redirect returns -1.
		// Reserve that append position so subsequent non-entity submits can consolidate safely.
		indexes[preparedRenderType] = renderTypes.size
		return -1
	}

	private fun currentEntityId(): Int? = submitContext.get() ?: entityContext.get()

	/**
	 * Applies the actual RenderType output target to pipeline eligibility at the batching seam.
	 * Non-main targets must retain vanilla consolidation even when their pipeline is entity-shaped.
	 */
	@JvmStatic
	internal fun isEligibleRenderType(renderType: RenderType, pipelineEligible: Boolean): Boolean =
		pipelineEligible && renderType.outputTarget() === OutputTarget.MAIN_TARGET

	/** Forces one staged draw per supported main-target entity or static block-entity draw, defeating same-render-type consolidation. */
	@JvmStatic
	fun shouldIsolateDraw(renderType: RenderType): Boolean =
		isEligibleRenderType(renderType, shouldIsolateDraw(renderType.pipeline()))

	/** Forces one staged draw per supported entity or static block-entity draw, defeating same-render-type consolidation. */
	@JvmStatic
	fun shouldIsolateDraw(pipeline: RenderPipeline): Boolean =
		currentEntityId() != null && EntityVelocityUniforms.activeVelocityPhase() != null &&
			EntityVelocityUniforms.isSupportedPipeline(pipeline)

	@JvmStatic
	fun bindDraw(draw: StagedVertexBuffer.Draw) {
		val entityId = currentEntityId()
		if (entityId != null && eligibleDrawContext.get() == true) {
			drawIds[draw] = entityId
		}
	}

	@JvmStatic
	fun bindExecuteInfo(draw: StagedVertexBuffer.Draw, info: StagedVertexBuffer.ExecuteInfo?) {
		if (info == null) return
		val entityId = drawIds[draw] ?: return
		executeInfoIds[info] = entityId
	}

	/** The entity id one prepared draw belongs to, or null for block-entity and identity-less draws. */
	@JvmStatic
	fun executeInfoEntityId(info: StagedVertexBuffer.ExecuteInfo): Int? = executeInfoIds[info]?.takeIf { it != BLOCK_ENTITY_TOKEN }

	/** Whether one prepared draw belongs to ordinary static block-entity geometry. */
	@JvmStatic
	fun executeInfoIsBlockEntity(info: StagedVertexBuffer.ExecuteInfo): Boolean = executeInfoIds[info] == BLOCK_ENTITY_TOKEN

	@JvmStatic
	fun clearFrame() {
		submitIds.clear()
		drawIds.clear()
		executeInfoIds.clear()
		consolidationContext.remove()
		eligibleDrawContext.remove()
		previousEligibleDraw.remove()
		afterEligibleDraw.remove()
		nonEntityDrawIndexes.clear()
		submitContext.remove()
		entityContext.remove()
		blockEntityContext.remove()
		entityBracketDepth.remove()
	}
}

/**
 * The prepared entity/block-entity draw replacement. Returning false leaves PreparedRenderType's
 * exact vanilla one-target implementation in control, which is what vanilla, CAMERA_ONLY, foreign,
 * and non-main-output draws require.
 */
object EntityVelocityRender {
	private var uniformBuffer: GpuBuffer? = null

	/**
	 * Control seam used by the callback and by headless evidence before any device operation. A
	 * true result means this prepared main-target draw has an entity identity or a block-entity
	 * marker, an open velocity-MRT phase, and an owned core/entity pipeline; the actual render
	 * pass still has its own safe gates.
	 */
	@JvmStatic
	fun canDraw(
		prepared: PreparedRenderType,
		info: StagedVertexBuffer.ExecuteInfo,
		phase: WorldPhase?,
	): Boolean = (EntityVelocityWriterBindings.executeInfoEntityId(info) != null ||
		EntityVelocityWriterBindings.executeInfoIsBlockEntity(info)) &&
		phase?.entityVelocityActive == true &&
		EntityVelocityUniforms.isSupportedPipeline(prepared.pipeline()) &&
		prepared.outputTarget() === OutputTarget.MAIN_TARGET

	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean = runCatching {
			val phase = EntityVelocityUniforms.activeVelocityPhase() ?: return@runCatching false
			if (!canDraw(prepared, info, phase)) return@runCatching false
			val entityId = EntityVelocityWriterBindings.executeInfoEntityId(info)
			val blockEntity = EntityVelocityWriterBindings.executeInfoIsBlockEntity(info)
			if (entityId == null && !blockEntity) return@runCatching false

			val scene = phase.worldTargetOverride ?: return@runCatching false
			val renderTarget = prepared.outputTarget().getRenderTarget()
			val colorTexture = RenderSystem.outputColorTextureOverride ?: renderTarget.colorTextureView
			val depthTexture = if (renderTarget.useDepth) {
				RenderSystem.outputDepthTextureOverride ?: renderTarget.depthTextureView
			} else {
				null
			}
			val velocityTexture = phase.terrainVelocityView ?: return@runCatching false
			if (colorTexture == null || colorTexture !== scene.colorTextureView) return@runCatching false

			val encoder = RenderSystem.getDevice().createCommandEncoder()
			if (blockEntity) {
				EntityVelocityUniforms.writeBlockEntityFrame(encoder, buffer(), phase, velocityTexture)
			} else {
				EntityVelocityUniforms.writeFrame(encoder, buffer(), phase, checkNotNull(entityId), velocityTexture)
			}

			val descriptor = RenderPassDescriptor.create { "Entity velocity draw with ${prepared.pipeline()}" }
				.withColorAttachment(colorTexture, Optional.empty())
				.withColorAttachment(velocityTexture, Optional.empty())
			if (depthTexture != null) {
				descriptor.withDepthAttachment(depthTexture, OptionalDouble.empty())
			}
			descriptor.withRenderArea(RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)))

			encoder.createRenderPass(descriptor).use { pass ->
				pass.setPipeline(writerTwin(prepared.pipeline(), VelocityWriter.ENTITY))
				if (prepared.scissorState().enabled()) {
					val scissor = prepared.scissorState()
					pass.enableScissor(scissor.x(), scissor.y(), scissor.width(), scissor.height())
				}
				RenderSystem.bindDefaultUniforms(pass)
				pass.setUniform("DynamicTransforms", prepared.dynamicTransforms())
				pass.setUniform(EntityVelocityUniforms.UNIFORM_NAME, buffer().slice())
				pass.setVertexBuffer(0, info.vertexBuffer().slice())
				for (texture in prepared.textures()) {
					pass.bindTexture(texture.name(), texture.textureView(), texture.sampler())
				}
				pass.setIndexBuffer(info.indexBuffer(), info.indexType())
				pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0)
			}
			true
		}.getOrDefault(false)

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS entity velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			EntityVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}
}
