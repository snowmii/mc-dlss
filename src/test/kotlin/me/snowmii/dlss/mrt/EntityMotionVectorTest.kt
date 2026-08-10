package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexConsumer
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.readText
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.mixin.PreparedRenderTypeMotionMixin
import me.snowmii.dlss.mixin.RenderTypeFeatureRendererMotionMixin
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.Redirect
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import com.google.gson.JsonParser

/**
 * Entity-model vertical proof for M-6's first dynamic writer.
 *
 * Descriptor tests exercise every supported core/entity pipeline family. Behavior tests drive the
 * CPU identity/batch state and safe no-identity draw fallback directly; reflection checks bind the
 * mapped 26.2 methods, callback descriptors, and JSON registration; Shaderc/SPIR-V-Cross compiles
 * the actual velocity_entity.fsh and verifies output locations. The test JVM does not apply Fabric
 * mixins or own a live Blaze3D RenderPass, so this suite makes no live transformed/GPU draw claim.
 */
class EntityMotionVectorTest {
	private val repository = Path.of("").toAbsolutePath()

	@Test
	fun `every supported entity model pipeline gets a cached two target writer twin`() {
		for (source in supportedEntityPipelines()) {
			assertTrue(EntityVelocityUniforms.isSupportedPipeline(source), "${source.location} must be in entity family")
			val plain = velocityTwin(source)
			val twin = entityVelocityTwin(plain)

			assertEquals(
				Identifier.fromNamespaceAndPath(
					"mc-dlss",
					"velocity/entity/" + source.location.path.removePrefix("pipeline/"),
				),
				twin.location,
			)
			assertSame(plain.vertexShader, twin.vertexShader)
			assertEquals(EntityVelocityUniforms.FRAGMENT_SHADER, twin.fragmentShader)
			assertEquals(plain.shaderDefines, twin.shaderDefines)
			assertSame(plain.depthStencilState, twin.depthStencilState)
			assertSame(plain.polygonMode, twin.polygonMode)
			assertEquals(plain.isCull, twin.isCull)
			assertSame(plain.primitiveTopology, twin.primitiveTopology)
			for (index in 0 until 16) {
				assertSame(plain.getVertexFormatBinding(index), twin.getVertexFormatBinding(index))
			}

			assertEquals(plain.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
			for (index in plain.bindGroupLayouts.indices) {
				assertSame(plain.bindGroupLayouts[index], twin.bindGroupLayouts[index])
			}
			assertSame(EntityVelocityUniforms.LAYOUT, twin.bindGroupLayouts.last())

			assertEquals(2, twin.colorTargetStates.size)
			assertSame(plain.colorTargetStates[0], twin.colorTargetStates[0])
			assertVelocityTarget(twin.colorTargetStates[1]!!)
			assertSame(twin, entityVelocityTwin(plain))
		}
	}

	@Test
	fun `entity writer descriptor matches its two-attachment render pass`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM, 16, 16))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT, 16, 16))
		val plain = velocityTwin(RenderPipelines.ENTITY_SOLID)
		val twin = entityVelocityTwin(plain)
		val descriptor = RenderPassDescriptor.create({ "entity velocity" })
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())

		assertEquals(2, descriptor.colorAttachments().size)
		assertEquals(twin.colorTargetStates.size, descriptor.colorAttachments().size)
		assertSame(scene, descriptor.colorAttachments()[0]!!.textureView())
		assertSame(velocity, descriptor.colorAttachments()[1]!!.textureView())
		assertTrue(descriptor.colorAttachments()[1]!!.clearValue().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, twin.colorTargetStates[1]!!.format())
		assertEquals(GpuFormat.RG16_FLOAT, velocity.texture().getFormat())
	}

	@Test
	fun `entity shader preserves scene output before exact sentinel motion output`() {
		val shader = shader()
		val fragOutput = shader.indexOf("out vec4 fragColor;")
		val velocityOutput = shader.indexOf("out vec4 velocityColor;")
		assertTrue(fragOutput >= 0 && fragOutput < velocityOutput)
		assertTrue(shader.indexOf("fragColor = apply_fog(") < shader.indexOf("velocityColor ="))
		assertTrue(shader.contains("layout(std140) uniform EntityVelocityConfig {"))
		assertTrue(shader.contains("mat4 ObjectReprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertTrue(shader.contains("gl_FragCoord.z"), "entity writer must retain reversed-Z depth")
		assertTrue(shader.contains("previous.w <= 0.0"))
		assertTrue(shader.contains("previous.w != previous.w"))
		assertTrue(shader.contains("isinf(previous.w)"))
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"))
		assertEquals(80, EntityVelocityUniforms.UBO_SIZE)
	}

	@Test
	fun `compiled entity shader reflects scene output before velocity output`() {
		val spirv = compileFragmentShader(minecraftFragmentSource(shader()))
		try {
			val outputs = reflectOutputs(spirv)
			assertEquals(
				listOf("fragColor", "velocityColor"),
				outputs.map { it.name },
				"compiled output order is the attachment order consumed by Minecraft's shader module",
			)

			val intSpirv = spirv.asIntBuffer()
			outputs.forEachIndexed { index, output -> intSpirv.put(output.locationOffset, index) }
			val rewritten = reflectOutputs(spirv)
			assertEquals(
				mapOf("fragColor" to 0, "velocityColor" to 1),
				rewritten.associate { it.name to it.location },
				"Minecraft's location rewrite must keep scene color at target 0 and velocity at target 1",
			)
		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	@Test
	fun `object uniform consumes displacement and invalidates reset or missing predecessors`() {
		val uniforms = repository.resolve("src/main/kotlin/me/snowmii/dlss/mrt/EntityVelocityUniforms.kt").readText()
		assertTrue(uniforms.contains("phase.objectMotionDisplacement(entityId)"))
		assertTrue(uniforms.contains("objectReprojection("))
		assertTrue(uniforms.contains("motion.reset"))
		assertTrue(uniforms.contains("displacement == null"))
		assertTrue(uniforms.contains("if (invalid) 1f else 0f"))
		assertTrue(uniforms.contains("view.getWidth(0).toFloat()"))
		assertTrue(uniforms.contains("writeToBuffer(buffer.slice(), data)"))
		assertEquals(EntityVelocityUniforms.INVALID_VELOCITY, 10000.0f)

		val camera = me.snowmii.dlss.render.DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		val reprojection = objectReprojection(
			camera,
			Matrix4f(),
			DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720)),
			org.joml.Vector3f(0.25f, 0f, 0f),
		)
		assertNotEquals(Matrix4f(), reprojection, "nonzero object displacement must alter target-1 reprojection")
	}

	@Test
	fun `returned render state keeps stable id and displacement through active velocity phase`() {
		val runtime = dlssRuntime(withVelocity = true)
		val phase = phase(runtime)
		val first = EntityRenderState()
		phase.captureEntity(first, 42, 10.0, 64.0, 5.0)
		renderFrame(phase)

		val second = EntityRenderState()
		phase.captureEntity(second, 42, 10.5, 64.0, 5.0)
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		assertTrue(phase.entityVelocityActive)
		assertEquals(42, phase.entityId(second))
		assertEquals(org.joml.Vector3f(0.5f, 0f, 0f), runtime.objectMotion.displacement(42))
		assertNotNull(phase.activeJitter)
		assertNotNull(phase.currentViewProjection)
		phase.end()
		assertFalse(phase.isOpen)
	}

	@Test
	fun `entity identity is not assigned by position and missing state falls back to sentinel`() {
		val runtime = dlssRuntime(withVelocity = true)
		val phase = phase(runtime)
		val state = EntityRenderState()
		phase.captureEntity(state, 7, 10.0, 64.0, 5.0)
		renderFrame(phase)

		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		assertEquals(null, phase.entityId(EntityRenderState()), "unpaired state cannot borrow another entity id")
		assertEquals(null, runtime.objectMotion.displacement(7), "first predecessor is invalid")
		phase.end()
	}

	@Test
	fun `entity writer seams isolate draws bind execute identity and preserve passthrough`() {
		val dispatcher = source("src/main/java/me/snowmii/dlss/mixin/EntityRenderDispatcherMotionMixin.java")
		val submit = source("src/main/java/me/snowmii/dlss/mixin/ModelFeatureSubmitMotionMixin.java")
		val renderer = source("src/main/java/me/snowmii/dlss/mixin/ModelFeatureRendererMotionMixin.java")
		val batching = source("src/main/java/me/snowmii/dlss/mixin/RenderTypeFeatureRendererMotionMixin.java")
		val staged = source("src/main/java/me/snowmii/dlss/mixin/StagedVertexBufferMotionMixin.java")
		val prepared = source("src/main/java/me/snowmii/dlss/mixin/PreparedRenderTypeMotionMixin.java")
		val bindings = source("src/main/kotlin/me/snowmii/dlss/mrt/EntityVelocityUniforms.kt")
		val mixins = source("src/main/resources/mc-dlss.mixins.json")

		assertTrue(dispatcher.contains("EntityRenderDispatcher.class"))
		assertTrue(dispatcher.contains("try {"))
		assertTrue(dispatcher.contains("finally {"))
		assertTrue(dispatcher.contains("phase.entityId(state)"))
		assertTrue(submit.contains("<init>"))
		assertTrue(submit.contains("bindSubmit(this)"))
		assertTrue(renderer.contains("prepareModel"))
		assertTrue(renderer.contains("beginSubmit(submit)"))
		assertTrue(batching.contains("RenderTypeFeatureRenderer\$Group"))
		assertTrue(batching.contains("lastDraw = null"))
		assertTrue(batching.contains("consolidationIndex"))
		assertTrue(staged.contains("appendDraw"))
		assertTrue(staged.contains("getExecuteInfo"))
		assertTrue(staged.contains("bindExecuteInfo"))
		assertTrue(prepared.contains("EntityVelocityRender.draw"))
		assertTrue(prepared.contains("callback.cancel()"))
		assertTrue(bindings.contains("activeVelocityPhase()"))
		assertTrue(bindings.contains("OutputTarget.MAIN_TARGET"))
		assertTrue(bindings.contains("getOrDefault(false)"))
		for (mixin in listOf(
			"EntityRenderDispatcherMotionMixin",
			"ModelFeatureSubmitMotionMixin",
			"ModelFeatureRendererMotionMixin",
			"RenderTypeFeatureRendererMotionMixin",
			"StagedVertexBufferMotionMixin",
			"PreparedRenderTypeMotionMixin",
		)) {
			assertTrue(mixins.contains(mixin), "registered $mixin")
		}
	}

	@Test
	fun `mapped batching and draw callbacks retain exact descriptors and registration`() {
		val group = Class.forName("net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer\$Group")
		val groupBuilder = group.getDeclaredMethod("getVertexBuilder", RenderType::class.java)
		assertEquals(VertexConsumer::class.java, groupBuilder.returnType)
		val getOrAddDraw = group.getDeclaredMethod("getOrAddDraw", RenderType::class.java)
		assertEquals(StagedVertexBuffer.Draw::class.java, getOrAddDraw.returnType)

		val drawTarget = PreparedRenderType::class.java.getDeclaredMethod(
			"drawFromBuffer",
			StagedVertexBuffer.ExecuteInfo::class.java,
		)
		assertEquals(Void.TYPE, drawTarget.returnType)

		val batchingHandler = RenderTypeFeatureRendererMotionMixin::class.java.getDeclaredMethod(
			"mcDlssBeginEntityDraw",
			RenderType::class.java,
			CallbackInfoReturnable::class.java,
		)
		val batchingInjection = requireNotNull(batchingHandler.getAnnotation(Inject::class.java))
		assertTrue(batchingInjection.method.contentEquals(arrayOf("getVertexBuilder")))
		assertEquals("HEAD", batchingInjection.at.single().value)

		val reorderHandler = RenderTypeFeatureRendererMotionMixin::class.java.getDeclaredMethod(
			"mcDlssDisableEntityReorder",
			List::class.java,
			Any::class.java,
		)
		val reorder = requireNotNull(reorderHandler.getAnnotation(Redirect::class.java))
		assertTrue(reorder.method.contentEquals(arrayOf("getOrAddDraw")))
		assertEquals("Ljava/util/List;indexOf(Ljava/lang/Object;)I", reorder.at.target)

		val drawHandler = PreparedRenderTypeMotionMixin::class.java.getDeclaredMethod(
			"mcDlssDrawEntityVelocity",
			StagedVertexBuffer.ExecuteInfo::class.java,
			CallbackInfo::class.java,
		)
		val drawInjection = requireNotNull(drawHandler.getAnnotation(Inject::class.java))
		assertTrue(
			drawInjection.method.contentEquals(
				arrayOf("drawFromBuffer(Lnet/minecraft/client/renderer/StagedVertexBuffer\$ExecuteInfo;)V"),
			),
		)
		assertEquals("HEAD", drawInjection.at.single().value)
		assertTrue(drawInjection.cancellable)

		val registered = JsonParser.parseString(source("src/main/resources/mc-dlss.mixins.json"))
			.asJsonObject
			.getAsJsonArray("client")
			.map { it.asString }
		val requiredMixins = listOf(
			"EntityRenderDispatcherMotionMixin",
			"ModelFeatureSubmitMotionMixin",
			"ModelFeatureRendererMotionMixin",
			"RenderTypeFeatureRendererMotionMixin",
			"StagedVertexBufferMotionMixin",
			"PreparedRenderTypeMotionMixin",
		)
		assertTrue(requiredMixins.all { it in registered }, "all entity writer mixins must be registered")
	}

	@Test
	fun `prepared draw control falls through safely without an entity identity`() {
		EntityVelocityWriterBindings.clearFrame()
		val prepared = PreparedRenderType(
			RenderPipelines.ENTITY_SOLID,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		assertFalse(
			EntityVelocityRender.draw(prepared, emptyExecuteInfo()),
			"without a bound ExecuteInfo identity callback must not cancel vanilla draw",
		)
	}

	@Test
	fun `draw control accepts a mapped entity identity before any GPU operation`() {
		val runtime = dlssRuntime(withVelocity = true)
		val phase = phase(runtime)
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		val staged = StagedVertexBuffer({ "entity-control-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginEntity(303)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(draw)
			EntityVelocityWriterBindings.bindExecuteInfo(draw, info)

			val prepared = PreparedRenderType(
				RenderPipelines.ENTITY_SOLID,
				OutputTarget.MAIN_TARGET,
				FakeBuffer().slice(),
				ScissorState(),
				emptyList(),
			)
			assertTrue(EntityVelocityRender.canDraw(prepared, info, phase))
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `entity then no-id same render type gets fresh draw and next entity keeps own id`() {
		val renderType = RenderTypes.entitySolid(
			Identifier.fromNamespaceAndPath("minecraft", "textures/entity/entity-boundary.png"),
		)
		val pipeline = renderType.pipeline()
		val preparedType = PreparedRenderType(
			pipeline,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		val drawRenderTypes = mutableListOf<Any>()
		val staged = StagedVertexBuffer({ "entity-boundary-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()

			EntityVelocityWriterBindings.beginEntity(101)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val firstDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val firstInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(firstDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(firstDraw, firstInfo)
			assertEquals(101, EntityVelocityWriterBindings.executeInfoEntityId(firstInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// A block/entity-model submit has no dispatcher identity. It uses the same RenderType,
			// so this call must clear both Group.lastDraw and the reorder lookup before staging.
			EntityVelocityWriterBindings.beginEntity(null)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, false))
			assertTrue(EntityVelocityWriterBindings.suppressConsolidation(), "transition draw must defeat reorder reuse")
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val blockDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(blockDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertEquals(null, EntityVelocityWriterBindings.executeInfoEntityId(blockInfo), "block geometry must not inherit entity 101")
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// After the transition, unrelated no-id geometry gets vanilla consolidation again.
			assertFalse(EntityVelocityWriterBindings.beginDraw(pipeline, false))
			assertFalse(EntityVelocityWriterBindings.suppressConsolidation())
			assertEquals(1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			EntityVelocityWriterBindings.endDraw()

			EntityVelocityWriterBindings.beginEntity(202)
			assertTrue(EntityVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, EntityVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			val secondDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val secondInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(secondDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(secondDraw, secondInfo)
			assertEquals(202, EntityVelocityWriterBindings.executeInfoEntityId(secondInfo))
			assertNotEquals(firstDraw, secondDraw, "each eligible entity starts a distinct staged draw")
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `ineligible route keeps vanilla reorder consolidation when no entity preceded it`() {
		EntityVelocityWriterBindings.clearFrame()
		val renderTypes = mutableListOf<Any>()
		val prepared = Any()
		assertFalse(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ITEM_CUTOUT, false))
		renderTypes += prepared
		EntityVelocityWriterBindings.endDraw()
		assertFalse(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ITEM_CUTOUT, false))
		assertEquals(0, EntityVelocityWriterBindings.consolidationIndex(renderTypes, prepared))
		EntityVelocityWriterBindings.endDraw()
		EntityVelocityWriterBindings.clearFrame()
	}

	@Test
	fun `only core entity shader family is eligible and camera-only keeps source path`() {
		assertTrue(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ENTITY_SOLID))
		assertTrue(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE))
		assertFalse(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.ITEM_CUTOUT))
		assertFalse(EntityVelocityUniforms.isSupportedPipeline(RenderPipelines.SOLID_TERRAIN))

		val prepared = source("src/main/kotlin/me/snowmii/dlss/mrt/EntityVelocityUniforms.kt")
		assertTrue(prepared.contains("return@runCatching false"), "unsupported/inactive draw must fall through")
		val variant = source("src/main/kotlin/me/snowmii/dlss/mrt/VelocityPipelineVariant.kt")
		assertTrue(variant.contains("entityVelocityTwin"))
		assertTrue(variant.contains("withColorTargetState(1, VELOCITY_COLOR_TARGET)"))
	}

	private fun supportedEntityPipelines() = listOf(
		RenderPipelines.ARMOR_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_DECAL_CUTOUT_NO_CULL,
		RenderPipelines.ARMOR_TRANSLUCENT,
		RenderPipelines.ENTITY_SOLID,
		RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
		RenderPipelines.ENTITY_CUTOUT_CULL,
		RenderPipelines.ENTITY_CUTOUT,
		RenderPipelines.ENTITY_CUTOUT_Z_OFFSET,
		RenderPipelines.ENTITY_CUTOUT_DISSOLVE,
		RenderPipelines.ENTITY_TRANSLUCENT,
		RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
		RenderPipelines.ENTITY_TRANSLUCENT_CULL,
		RenderPipelines.END_CRYSTAL_BEAM,
		RenderPipelines.BANNER_PATTERN,
		RenderPipelines.BREEZE_WIND,
		RenderPipelines.ENERGY_SWIRL,
		RenderPipelines.EYES,
	)

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	private fun shader() = repository.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_entity.fsh").readText()

	private fun source(path: String) = repository.resolve(path).readText()

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	/** Compiles the actual entity shader after resolving its two canonical 26.2 imports. */
	private fun compileFragmentShader(source: String): ByteBuffer {
		val compiler = Shaderc.shaderc_compiler_initialize()
		val options = Shaderc.shaderc_compile_options_initialize()
		try {
			Shaderc.shaderc_compile_options_set_target_env(
				options,
				Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_2,
			)
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
			Shaderc.shaderc_compile_options_set_generate_debug_info(options)
			Shaderc.shaderc_compile_options_set_optimization_level(options, 0)

			MemoryStack.stackPush().use {
				val sourceBuffer = MemoryUtil.memUTF8(source, false)
				val filenameBuffer = MemoryUtil.memUTF8("velocity_entity.fsh")
				val entrypointBuffer = MemoryUtil.memUTF8("main")
				try {
					val result = Shaderc.shaderc_compile_into_spv(
						compiler,
						sourceBuffer,
						Shaderc.shaderc_fragment_shader,
						filenameBuffer,
						entrypointBuffer,
						options,
					)
					try {
						val status = Shaderc.shaderc_result_get_compilation_status(result)
						check(status == 0) {
							"shaderc failed (status $status): ${Shaderc.shaderc_result_get_error_message(result)}"
						}
						val compiled = checkNotNull(Shaderc.shaderc_result_get_bytes(result)) {
							"shaderc returned no SPIR-V bytes"
						}
						val copy = MemoryUtil.memCalloc(compiled.remaining())
						MemoryUtil.memCopy(compiled, copy)
						return copy
					} finally {
						Shaderc.shaderc_result_release(result)
					}
				} finally {
					MemoryUtil.memFree(entrypointBuffer)
					MemoryUtil.memFree(filenameBuffer)
					MemoryUtil.memFree(sourceBuffer)
				}
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options)
			Shaderc.shaderc_compiler_release(compiler)
		}
	}

	private fun minecraftFragmentSource(source: String): String {
		val resolved = source
			.replace("#moj_import <minecraft:fog.glsl>", FOG_INCLUDE)
			.replace("#moj_import <minecraft:dynamictransforms.glsl>", DYNAMIC_TRANSFORMS_INCLUDE)
		val versionLineEnd = resolved.indexOf('\n')
		check(versionLineEnd >= 0) { "shader source must start with a #version line" }
		return resolved.substring(0, versionLineEnd + 1) +
			"#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n#line 1 0\n" +
			resolved.substring(versionLineEnd + 1)
	}

	private class OutputReflection(val name: String, val locationOffset: Int, val location: Int)

	private fun reflectOutputs(spirv: ByteBuffer): List<OutputReflection> {
		MemoryStack.stackPush().use { stack ->
			val contextPointer = stack.callocPointer(1)
			spvcCheck(Spvc.spvc_context_create(contextPointer), "spvc_context_create")
			val context = contextPointer.get(0)
			try {
				val intSpirv = spirv.asIntBuffer()
				val irPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_context_parse_spirv(context, intSpirv, intSpirv.remaining().toLong(), irPointer),
					"spvc_context_parse_spirv",
				)
				val compilerPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_context_create_compiler(context, 0, irPointer.get(0), 1, compilerPointer),
					"spvc_context_create_compiler",
				)
				val compiler = compilerPointer.get(0)
				val resourcesPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPointer),
					"spvc_compiler_create_shader_resources",
				)
				val listPointer = stack.callocPointer(1)
				val countPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_resources_get_resource_list_for_type(
						resourcesPointer.get(0),
						Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT,
						listPointer,
						countPointer,
					),
					"spvc_resources_get_resource_list_for_type",
				)
				val resources = SpvcReflectedResource.create(listPointer.get(0), countPointer.get(0).toInt())
				val offsetBuffer = stack.callocInt(1)
				return buildList(resources.capacity()) {
					for (index in 0 until resources.capacity()) {
						val resource = resources.get(index)
						val name = resource.nameString()
						check(
							Spvc.spvc_compiler_get_binary_offset_for_decoration(
								compiler,
								resource.id(),
								LOCATION_DECORATION,
								offsetBuffer,
							),
						) { "no Location decoration on $name" }
						add(
							OutputReflection(
								name,
								offsetBuffer.get(0),
								Spvc.spvc_compiler_get_decoration(compiler, resource.id(), LOCATION_DECORATION),
							),
						)
					}
				}
			} finally {
				Spvc.spvc_context_destroy(context)
			}
		}
	}

	private fun spvcCheck(result: Int, step: String) {
		check(result == Spvc.SPVC_SUCCESS) {
			val name = when (result) {
				Spvc.SPVC_ERROR_INVALID_ARGUMENT -> "SPVC_ERROR_INVALID_ARGUMENT"
				Spvc.SPVC_ERROR_OUT_OF_MEMORY -> "SPVC_ERROR_OUT_OF_MEMORY"
				Spvc.SPVC_ERROR_UNSUPPORTED_SPIRV -> "SPVC_ERROR_UNSUPPORTED_SPIRV"
				Spvc.SPVC_ERROR_INVALID_SPIRV -> "SPVC_ERROR_INVALID_SPIRV"
				else -> result.toString()
			}
			"$step failed ($name)"
		}
	}

	private fun renderFrame(phase: WorldPhase) {
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.end()
	}

	private fun phase(runtime: RenderRuntime) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = { _, _, _, _, _, _ -> true },
	)

	private fun dlssRuntime(withVelocity: Boolean): RenderRuntime {
		val session = DlssSession(config()).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = if (withVelocity) {
					{ width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) }
				} else {
					{ _, _ -> null }
				},
			),
			startup = { DlssDimensions(1280, 720) },
		)
	}

	private fun config() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = DlssDimensions(2560, 1440),
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	private fun camera() = DlssCameraSample(
		projection = Matrix4f().setPerspective(
			Math.toRadians(70.0).toFloat(),
			2560f / 1440f,
			1000f,
			0.05f,
			true,
		),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 64.0,
		cameraZ = 0.0,
	)

	private val mainTarget = FakeTarget(2560, 1440)

	private class FakeTarget(
		width: Int,
		height: Int,
		format: GpuFormat = GpuFormat.RGBA8_UNORM,
		withView: Boolean = false,
	) : RenderTarget("fake", true, format) {
		var releases = 0
		private val texture = FakeTexture(format, width, height)

		init {
			this.width = width
			this.height = height
			if (withView) colorTextureView = FakeView(texture)
		}

		override fun createBuffers(width: Int, height: Int) {
			this.width = width
			this.height = height
		}

		override fun destroyBuffers() {
			releases++
		}
	}

	private class FakeBuffer : GpuBuffer(GpuBuffer.USAGE_VERTEX, 0) {
		override fun isClosed() = false
		override fun close() = Unit
		override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
			throw UnsupportedOperationException("test buffer is never mapped")
	}

	private class FakeTexture(format: GpuFormat, width: Int, height: Int) :
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private companion object {
		const val LOCATION_DECORATION = 30

		// Exact bodies of Minecraft 26.2's imported fog.glsl and dynamictransforms.glsl, without
		// their duplicate #version lines. The shader under test remains the repository asset itself.
		val FOG_INCLUDE = """
			layout(std140) uniform Fog {
			    vec4 FogColor;
			    float FogEnvironmentalStart;
			    float FogEnvironmentalEnd;
			    float FogRenderDistanceStart;
			    float FogRenderDistanceEnd;
			    float FogSkyEnd;
			    float FogCloudsEnd;
			};

			float linear_fog_value(float vertexDistance, float fogStart, float fogEnd) {
			    if (vertexDistance <= fogStart) {
			        return 0.0;
			    } else if (vertexDistance >= fogEnd) {
			        return 1.0;
			    }

			    return (vertexDistance - fogStart) / (fogEnd - fogStart);
			}

			float total_fog_value(float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd) {
			    return max(linear_fog_value(sphericalVertexDistance, environmentalStart, environmantalEnd), linear_fog_value(cylindricalVertexDistance, renderDistanceStart, renderDistanceEnd));
			}

			vec4 apply_fog(vec4 inColor, float sphericalVertexDistance, float cylindricalVertexDistance, float environmentalStart, float environmantalEnd, float renderDistanceStart, float renderDistanceEnd, vec4 fogColor) {
			    float fogValue = total_fog_value(sphericalVertexDistance, cylindricalVertexDistance, environmentalStart, environmantalEnd, renderDistanceStart, renderDistanceEnd);
			    return vec4(mix(inColor.rgb, fogColor.rgb, fogValue * fogColor.a), inColor.a);
			}
		""".trimIndent()

		val DYNAMIC_TRANSFORMS_INCLUDE = """
			layout(std140) uniform DynamicTransforms {
			    mat4 ModelViewMat;
			    vec4 ColorModulator;
			    vec3 ModelOffset;
			    mat4 TextureMat;
			};
		""".trimIndent()
	}
}
