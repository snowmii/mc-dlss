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
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble

/**
 * Per-entity motion uniform and draw binding contract for the velocity MRT.
 *
 * The entity twin keeps the source entity shader and all of its defines/layouts, then swaps only
 * the fragment shader and adds this block. A missing object predecessor, a reset camera/object
 * history, or a missing active world context sets [INVALID_VELOCITY] through [VelocityParams.x];
 * the fragment shader classifies every pixel before dividing its previous homogeneous coordinate.
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
 * Minecraft's model submits do not carry their originating EntityRenderState. The dispatcher
 * therefore brackets each entity renderer call, the submit-record constructor copies that id into
 * an identity map, and ModelFeatureRenderer.prepareModel installs it while its geometry is
 * staged. The draw and ExecuteInfo maps then preserve the one-to-one id through the batching and
 * upload boundary, where a per-draw uniform is finally available.
 */
object EntityVelocityWriterBindings {
	private val entityContext = ThreadLocal<Int?>()
	private val submitContext = ThreadLocal<Int?>()
	private val consolidationContext = ThreadLocal<Boolean>()
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
		val entityId = entityContext.get() ?: return
		submitIds[submit] = entityId
	}

	@JvmStatic
	fun submitEntityId(submit: Any): Int? = submitIds[submit]

	@JvmStatic
	fun beginSubmit(submit: Any) {
		val entityId = submitIds[submit]
		if (entityId == null) submitContext.remove() else submitContext.set(entityId)
	}

	@JvmStatic
	fun endSubmit() {
		submitContext.remove()
	}

	@JvmStatic
	fun beginDraw(pipeline: RenderPipeline): Boolean {
		val isolate = shouldIsolateDraw(pipeline)
		consolidationContext.set(isolate)
		return isolate
	}

	@JvmStatic
	fun endDraw() {
		consolidationContext.remove()
	}

	@JvmStatic
	fun suppressConsolidation(): Boolean = consolidationContext.get() == true

	private fun currentEntityId(): Int? = submitContext.get() ?: entityContext.get()

	/** Forces one staged draw per supported entity, defeating same-render-type consolidation. */
	@JvmStatic
	fun shouldIsolateDraw(pipeline: RenderPipeline): Boolean =
		currentEntityId() != null && EntityVelocityUniforms.activeVelocityPhase() != null &&
			EntityVelocityUniforms.isSupportedPipeline(pipeline)

	@JvmStatic
	fun bindDraw(draw: StagedVertexBuffer.Draw) {
		val entityId = currentEntityId()
		if (entityId != null && consolidationContext.get() == true && EntityVelocityUniforms.activeVelocityPhase() != null) {
			drawIds[draw] = entityId
		}
	}

	@JvmStatic
	fun bindExecuteInfo(draw: StagedVertexBuffer.Draw, info: StagedVertexBuffer.ExecuteInfo?) {
		if (info == null) return
		val entityId = drawIds[draw] ?: return
		executeInfoIds[info] = entityId
	}

	@JvmStatic
	fun executeInfoEntityId(info: StagedVertexBuffer.ExecuteInfo): Int? = executeInfoIds[info]

	@JvmStatic
	fun clearFrame() {
		submitIds.clear()
		drawIds.clear()
		executeInfoIds.clear()
		consolidationContext.remove()
		submitContext.remove()
		entityContext.remove()
	}
}

/**
 * The prepared entity draw replacement. Returning false leaves PreparedRenderType's exact
 * vanilla one-target implementation in control, which is what vanilla, CAMERA_ONLY, foreign,
 * and non-main-output draws require.
 */
object EntityVelocityRender {
	private var uniformBuffer: GpuBuffer? = null

	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean = runCatching {
			val entityId = EntityVelocityWriterBindings.executeInfoEntityId(info) ?: return@runCatching false
			val phase = EntityVelocityUniforms.activeVelocityPhase() ?: return@runCatching false
			if (!EntityVelocityUniforms.isSupportedPipeline(prepared.pipeline())) return@runCatching false
			if (prepared.outputTarget() !== OutputTarget.MAIN_TARGET) return@runCatching false

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
			EntityVelocityUniforms.writeFrame(encoder, buffer(), phase, entityId, velocityTexture)

			val descriptor = RenderPassDescriptor.create { "Entity velocity draw with ${prepared.pipeline()}" }
				.withColorAttachment(colorTexture, Optional.empty())
				.withColorAttachment(velocityTexture, Optional.empty())
			if (depthTexture != null) {
				descriptor.withDepthAttachment(depthTexture, OptionalDouble.empty())
			}
			descriptor.withRenderArea(RenderPass.RenderArea(0, 0, colorTexture.getWidth(0), colorTexture.getHeight(0)))

			encoder.createRenderPass(descriptor).use { pass ->
				pass.setPipeline(entityVelocityTwin(velocityTwin(prepared.pipeline())))
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
