package me.snowmii.dlss.mrt

import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.readText
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the terrain pass seam of the velocity MRT: while an eligible DLSS world phase is open,
 * `ChunkSectionsToRender.renderGroup` passes for the opaque and translucent terrain groups carry
 * one scene-sized RG16_FLOAT velocity attachment at color index 1 and bind the cached velocity
 * twin of the known Minecraft terrain pipelines; on the camera-only route or outside the
 * eligible phase, pass creation and source-pipeline binding stay exactly vanilla and never throw.
 */
class MotionVectorTerrainPassTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)
	private val mainTarget = FakeTarget(output.width, output.height)

	@Test
	fun `an eligible phase offers one scene-sized RG16 velocity view for the terrain pass`() {
		val phase = phase(velocityRuntime())

		assertNull(phase.terrainVelocityView)
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		val view = checkNotNull(phase.terrainVelocityView)
		assertEquals(GpuFormat.RG16_FLOAT, view.texture().getFormat())
		assertEquals(render.width, view.getWidth(0))
		assertEquals(render.height, view.getHeight(0))

		phase.end()
		assertNull(phase.terrainVelocityView)
	}

	@Test
	fun `camera-only fallback keeps terrain pass creation and binding vanilla and cannot throw`() {
		val runtime = velocityRuntime()
		runtime.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, runtime.motionVectorRoute)
		val phase = phase(runtime)

		assertDoesNotThrow {
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			phase.end()
		}
		assertNull(phase.terrainVelocityView)
	}

	@Test
	fun `a session without DLSS keeps terrain passes vanilla`() {
		val session = session(enabled = false)
		val phase = phase(runtime(session) { null })

		assertDoesNotThrow {
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			phase.end()
		}
		assertNull(phase.terrainVelocityView)
	}

	@Test
	fun `a phase without a velocity companion keeps terrain passes vanilla`() {
		val phase = phase(runtime(velocityRuntimeSession(), velocityCompanion = false) { render })

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertNull(phase.terrainVelocityView)
		phase.end()
	}

	@Test
	fun `known terrain pipelines bind one cached velocity twin with one unblended RG16 target`() {
		val solid = velocityTwin(RenderPipelines.SOLID_TERRAIN)

		// One twin per source pipeline, so Vulkan's identity-keyed lazy-compile cache is hit on
		// every later frame instead of recompiling the twin per bind.
		assertSame(solid, velocityTwin(RenderPipelines.SOLID_TERRAIN))
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/solid_terrain"), solid.location)
		val solidTargets = solid.colorTargetStates
		assertEquals(2, solidTargets.size)
		assertVelocityTarget(solidTargets[1]!!)

		val translucent = velocityTwin(RenderPipelines.TRANSLUCENT_TERRAIN)
		val translucentTargets = translucent.colorTargetStates
		assertEquals(2, translucentTargets.size)
		// The twin preserves the source's blended first target and adds an unblended velocity one.
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT), translucentTargets[0]!!.blendFunction())
		assertVelocityTarget(translucentTargets[1]!!)

		val cutout = velocityTwin(RenderPipelines.CUTOUT_TERRAIN)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/cutout_terrain"), cutout.location)
		assertEquals(2, cutout.colorTargetStates.size)
	}

	@Test
	fun `first foreign terrain pipeline classified before pass creation keeps that pass exactly vanilla`() {
		val runtime = velocityRuntime()
		val phase = phase(runtime)
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		// The renderGroup HEAD inject classifies every source pipeline the group will bind before
		// the pass descriptor exists. An owned layer keeps the velocity shape; the first foreign
		// layer then latches camera-only, and the pass-shape read that follows answers vanilla:
		// one attachment, and the foreign source pipeline binds unchanged instead of a twin.
		phase.observePipeline(terrainPipeline(RenderPipelines.SOLID_TERRAIN))
		assertNotNull(phase.terrainVelocityView)
		phase.observePipeline(foreignTerrainPipeline())

		assertEquals(MotionVectorRoute.CAMERA_ONLY, runtime.motionVectorRoute)
		assertNull(phase.terrainVelocityView)

		phase.end()
	}

	@Test
	fun `terrain mixin gates the velocity attachment and twin selection on the open velocity-mrt phase`() {
		val repository = Path.of("").toAbsolutePath()
		val mixin = repository
			.resolve("src/main/java/me/snowmii/dlss/mixin/VulkanChunkSectionsToRenderMixin.java")
			.readText()
		val mixins = repository.resolve("src/main/resources/mc-dlss.mixins.json").readText()

		assertTrue(mixins.contains("VulkanChunkSectionsToRenderMixin"))
		assertTrue(mixin.contains("@Mixin(ChunkSectionsToRender.class)"))
		assertTrue(mixin.contains("method = \"renderGroup\""))
		// The pass-creation redirect must add the velocity attachment at color index 1.
		assertTrue(mixin.contains("CommandEncoder;createRenderPass("))
		assertTrue(mixin.contains("withColorAttachment(velocity, Optional.empty())"))
		// The pipeline-boundary redirect must swap in the twin only for the pass that carries it.
		assertTrue(mixin.contains("RenderPass;setPipeline("))
		assertTrue(mixin.contains("velocityTwin(pipeline)"))
		// The eligible-phase gate: null phase or null velocity view keeps the exact vanilla calls.
		assertTrue(mixin.contains("getTerrainVelocityView()"))
		assertTrue(mixin.contains("createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)"))
		// First-encounter foreign ordering: a HEAD inject on renderGroup classifies the group's
		// source pipelines before the pass-creation redirect chooses the attachment shape, so the
		// first foreign pipeline never sees a two-attachment pass or a twin.
		assertTrue(mixin.contains("@Inject(method = \"renderGroup\", at = @At(\"HEAD\"))"))
		assertTrue(mixin.contains("ChunkSectionLayerGroup group"))
		assertTrue(mixin.contains("layer.pipeline()"))
		assertTrue(mixin.contains("phase.observePipeline(new MotionVectorPipeline("))
		assertTrue(mixin.indexOf("mcDlssClassifyTerrainPipelines") >= 0)
		assertTrue(mixin.indexOf("mcDlssChunkRenderPass") >= 0)
		assertTrue(mixin.indexOf("mcDlssClassifyTerrainPipelines") < mixin.indexOf("mcDlssChunkRenderPass"))
	}

	private fun terrainPipeline(pipeline: RenderPipeline) = MotionVectorPipeline(
		pipeline.getLocation().toString(),
		listOf(
			MotionVectorShader(pipeline.getVertexShader().toString(), pipeline.getVertexShader().getNamespace()),
			MotionVectorShader(pipeline.getFragmentShader().toString(), pipeline.getFragmentShader().getNamespace()),
		),
	)

	private fun foreignTerrainPipeline() = MotionVectorPipeline(
		"example:pipeline/waving_terrain",
		listOf(MotionVectorShader("example:core/waving_terrain", "example")),
	)

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	private fun phase(runtime: RenderRuntime) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
	)

	private fun velocityRuntime(): RenderRuntime {
		val session = session(enabled = true)
		return runtime(session, velocityCompanion = true) {
			check(session.markReadyAfterNativeStartup())
			render
		}
	}

	private fun velocityRuntimeSession() = session(enabled = true).also { it.markReadyAfterNativeStartup() }

	private fun runtime(
		session: DlssSession,
		velocityCompanion: Boolean = true,
		startup: () -> DlssDimensions?,
	) = RenderRuntime(
		session = session,
		sceneTarget = SceneTarget(
			allocate = { width, height -> FakeTarget(width, height) },
			release = { (it as FakeTarget).releases++ },
			allocateVelocity = { width, height ->
				if (velocityCompanion) FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) else null
			},
		),
		startup = startup,
	)

	private fun session(enabled: Boolean) = DlssSession(
		DlssStartupConfig(
			enabled = enabled,
			qualityMode = SRMode.QUALITY,
			outputDimensions = output,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	/** Render target with a fake view over a fake texture, so the velocity seam is testable off the render thread. */
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
			if (withView) {
				colorTextureView = FakeView(texture)
			}
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
