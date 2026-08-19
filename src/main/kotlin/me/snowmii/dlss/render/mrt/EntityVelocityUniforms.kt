package me.snowmii.dlss.render.mrt

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
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import java.util.HashMap
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble

/**
 * Per-entity motion uniform and draw binding contract for the velocity MRT.
 *
 * The entity twin keeps the source entity shader and all of its defines/layouts, then swaps only
 * the fragment shader and adds this block. A missing object predecessor, a reset camera/object
 * history, or a missing active world context sets [INVALID_VELOCITY] through `VelocityParams.x`;
 * the fragment shader classifies every pixel before dividing its previous homogeneous coordinate.
 */
object EntityVelocityUniforms {
	const val INVALID_VELOCITY = 10000.0f
	const val UNIFORM_NAME = "EntityVelocityConfig"
	const val SHADER_PATH = "core/velocity_entity"

	@JvmField
	val FRAGMENT_SHADER = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

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
			motion,
			currentViewProjection,
			jitter,
			displacement,
		)
		writePayload(encoder, buffer, reprojection, invalid, view)
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

	private val IDENTITY = Matrix4f()
	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}

/**
 * Render-thread identity plumbing for CPU-baked entity geometry.
 *
 * Minecraft's model submits do not carry their originating EntityRenderState. The entity
 * dispatcher therefore brackets each entity renderer call, the submit-record constructor copies
 * that id into an identity map, and ModelFeatureRenderer.prepareModel installs it while its
 * geometry is staged. The draw and ExecuteInfo maps then preserve the one-to-one id through the
 * batching and upload boundary, where a per-draw uniform is finally available.
 *
 * An identity-less submit (a block entity, or an entity renderer invoked with no id) can
 * never inherit an adjacent entity's identity, because every entity bracket removes the
 * context on exit. Block-entity renderers have no bracket and no token.
 */
object EntityVelocityWriterBindings {
	private val entityContext = ThreadLocal<Int?>()
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
	}

	@JvmStatic
	fun endEntity() {
		entityContext.remove()
		submitContext.remove()
	}

	@JvmStatic
	fun bindSubmit(submit: Any) {
		val entityId = entityContext.get()
		if (entityId != null) {
			submitIds[submit] = entityId
		}
	}

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

	/** The entity id one prepared draw belongs to, or null for identity-less draws. */
	@JvmStatic
	fun executeInfoEntityId(info: StagedVertexBuffer.ExecuteInfo): Int? = executeInfoIds[info]

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
	}
}

/**
 * The prepared entity draw replacement. Returning false leaves PreparedRenderType's
 * exact vanilla one-target implementation in control, which is what vanilla, CAMERA_ONLY,
 * foreign, block-entity, and non-main-output draws require.
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
	): Boolean = EntityVelocityWriterBindings.executeInfoEntityId(info) != null &&
		phase?.entityVelocityActive == true &&
		EntityVelocityUniforms.isSupportedPipeline(prepared.pipeline()) &&
		prepared.outputTarget() === OutputTarget.MAIN_TARGET

	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean = runCatching {
		val phase = EntityVelocityUniforms.activeVelocityPhase() ?: return@runCatching false
		if (!canDraw(prepared, info, phase)) return@runCatching false
		val entityId = EntityVelocityWriterBindings.executeInfoEntityId(info) ?: return@runCatching false
		val attachments = attachments(prepared, phase) ?: return@runCatching false

		val encoder = RenderSystem.getDevice().createCommandEncoder()
		EntityVelocityUniforms.writeFrame(encoder, buffer(), phase, entityId, attachments.velocity)
		encoder.createRenderPass(descriptor(prepared, attachments)).use { pass ->
			record(pass, prepared, info)
		}
		true
	}.getOrDefault(false)

	/** The three views one entity draw writes through, resolved together or not at all. */
	private class Attachments(val color: GpuTextureView, val velocity: GpuTextureView, val depth: GpuTextureView?)

	/**
	 * Resolves the draw's attachments, or null when this draw must stay vanilla: no scene
	 * override, no scene-sized velocity companion, or a colour view that is not the held scene
	 * target's - the identity check that keeps a foreign target off the velocity route.
	 */
	private fun attachments(prepared: PreparedRenderType, phase: WorldPhase): Attachments? {
		val scene = phase.worldTargetOverride ?: return null
		val renderTarget = prepared.outputTarget().getRenderTarget()
		val colorTexture = RenderSystem.outputColorTextureOverride ?: renderTarget.colorTextureView
		val depthTexture = if (renderTarget.useDepth) {
			RenderSystem.outputDepthTextureOverride ?: renderTarget.depthTextureView
		} else {
			null
		}
		val velocityTexture = phase.terrainVelocityView ?: return null
		if (colorTexture == null || colorTexture !== scene.colorTextureView) return null
		return Attachments(colorTexture, velocityTexture, depthTexture)
	}

	private fun descriptor(prepared: PreparedRenderType, attachments: Attachments): RenderPassDescriptor {
		val descriptor = RenderPassDescriptor.create { "Entity velocity draw with ${prepared.pipeline()}" }
			.withColorAttachment(attachments.color, Optional.empty())
			.withColorAttachment(attachments.velocity, Optional.empty())
		if (attachments.depth != null) {
			descriptor.withDepthAttachment(attachments.depth, OptionalDouble.empty())
		}
		val color = attachments.color
		return descriptor.withRenderArea(RenderPass.RenderArea(0, 0, color.getWidth(0), color.getHeight(0)))
	}

	private fun record(pass: RenderPass, prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo) {
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

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS entity velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			EntityVelocityUniforms.UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}
}
