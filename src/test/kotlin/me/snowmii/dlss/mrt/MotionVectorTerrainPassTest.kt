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
	private val mainTarget = fakeMainTarget()


	@Test
	fun `an eligible phase offers one scene-sized RG16 velocity view for the terrain pass`() {
		val phase = worldPhase(velocityRuntime())

		assertNull(phase.terrainVelocityView)
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

		val view = checkNotNull(phase.terrainVelocityView)
		assertEquals(GpuFormat.RG16_FLOAT, view.texture().getFormat())
		assertEquals(RENDER_DIMENSIONS.width, view.getWidth(0))
		assertEquals(RENDER_DIMENSIONS.height, view.getHeight(0))

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
		val phase = worldPhase(runtime)

		assertDoesNotThrow {
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			phase.end()
		}
		assertNull(phase.terrainVelocityView)
	}

	@Test
	fun `a session without DLSS keeps terrain passes vanilla`() {
		val session = session(enabled = false)
		val phase = worldPhase(runtime(session) { null })

		assertDoesNotThrow {
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			phase.end()
		}
		assertNull(phase.terrainVelocityView)
	}

	@Test
	fun `a phase without a velocity companion keeps terrain passes vanilla`() {
		val phase = worldPhase(runtime(velocityRuntimeSession(), velocityCompanion = false) { RENDER_DIMENSIONS })

		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertNull(phase.terrainVelocityView)
		phase.end()
	}

	@Test
	fun `first foreign terrain pipeline classified before pass creation keeps that pass exactly vanilla`() {
		val runtime = velocityRuntime()
		val phase = worldPhase(runtime)
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
			outputDimensions = OUTPUT_DIMENSIONS,
			sdkPath = null,
			nativeLibraryPath = null,
			dataPath = null,
			warnings = emptyList(),
		),
	)

	/** Render target with a fake view over a fake texture, so the velocity seam is testable off the render thread. */
}
