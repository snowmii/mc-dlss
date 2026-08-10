package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.dlss.bridge.DlssDimensions
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

/**
 * Entity-model vertical proof for M-6's first dynamic writer.
 *
 * Descriptor tests exercise every supported core/entity pipeline family. Source/seam tests pin the
 * CPU identity path and its anti-consolidation boundary; the phase test proves the returned
 * EntityRenderState identity and object predecessor are available while an active VELOCITY_MRT
 * world is executing. GPU draw execution remains a client/device concern, but its production
 * PreparedRenderType seam is source-bound and has an explicit safe vanilla fallback.
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
		assertTrue(batching.contains("suppressConsolidation"))
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

	private class FakeTexture(format: GpuFormat, width: Int, height: Int) :
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}
}
