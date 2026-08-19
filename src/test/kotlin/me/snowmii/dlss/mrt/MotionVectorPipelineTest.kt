package me.snowmii.dlss.mrt

import com.mojang.blaze3d.pipeline.RenderPipeline
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssSessionState
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Known-world velocity pipeline surface: owned world pipelines stay on the velocity-MRT
 * route, and the first foreign shader latches camera-only exactly once without throwing and
 * without disabling the eligible DLSS world route or its camera motion.
 *
 * The known-world enumeration is the source of truth for what the terrain pass can bind:
 * the three [ChunkSectionLayer] pipelines (SOLID, CUTOUT, TRANSLUCENT) plus the WIREFRAME
 * debug override. The terrain pass does not bind velocity twins; camera motion belongs to
 * the fill on VELOCITY_MRT and the compute writer on CAMERA_ONLY. [velocityTwin] remains
 * for the object-motion writers (entity, moving block, cloud) and the stress pass.
 */
class MotionVectorPipelineTest {
	private val mainTarget = fakeMainTarget()


	@Test
	fun `known world descriptors classify owned and remain velocity-mrt eligible`() {
		val diagnostics = mutableListOf<String>()
		val compatibility = MotionVectorCompatibility(diagnostics::add)

		for (source in knownWorldPipelines()) {
			assertEquals(
				MotionVectorRoute.VELOCITY_MRT,
				compatibility.observe(pipelineObservation(source)),
			)
		}
		assertTrue(diagnostics.isEmpty(), "owned pipelines must never emit a compatibility diagnostic")
	}

	/**
	 * The first foreign shader flips the latch to camera-only exactly once: a second observe of
	 * the same pipeline and any later owned pipeline stay on the latched route, exactly one
	 * diagnostic names the incompatible pipeline and shader, the fallback pipeline is recorded,
	 * and a throwing diagnostic sink cannot turn the latch into a render throw.
	 */
	@Test
	fun `first foreign shader latches camera-only once without throwing`() {
		val diagnostics = mutableListOf<String>()
		val compatibility = MotionVectorCompatibility(diagnostics::add)
		val foreign = foreignTerrainPipeline()

		assertEquals(MotionVectorRoute.CAMERA_ONLY, compatibility.observe(foreign))
		assertEquals(MotionVectorRoute.CAMERA_ONLY, compatibility.observe(foreign))
		assertEquals(
			MotionVectorRoute.CAMERA_ONLY,
			compatibility.observe(pipelineObservation(RenderPipelines.SOLID_TERRAIN)),
		)

		assertEquals(1, diagnostics.size)
		val diagnostic = diagnostics.single()
		assertTrue(diagnostic.contains("example:pipeline/waving_terrain"))
		assertTrue(diagnostic.contains("example:core/waving_terrain"))
		assertTrue(diagnostic.contains("camera-only"))
		assertEquals(foreign, compatibility.firstForeignPipeline)

		val throwingSink = MotionVectorCompatibility { error("broken diagnostic sink") }
		assertDoesNotThrow { throwingSink.observe(foreignTerrainPipeline()) }
		assertEquals(MotionVectorRoute.CAMERA_ONLY, throwingSink.selectedRoute)
	}

	/**
	 * The camera-only latch leaves the eligible DLSS world route intact: frames still route to
	 * the low-resolution scene target at render dimensions, the session stays READY (frame
	 * generation remains eligible), the camera-motion chain keeps advancing, and the terrain
	 * passes read a null velocity view so they keep their exact vanilla one-attachment shape.
	 * Nothing here throws, and nothing compiles a pipeline on a device.
	 */
	@Test
	fun `camera-only fallback retains the eligible DLSS world route and camera motion`() {
		val runtime = velocityRuntime()
		val phase = WorldPhase(
			runtime = runtime,
			present = { _, _ -> },
			onWorldTargetChanged = {},
		)

		// The foreign shader latches the session route before the world renders, exactly as the
		// lazy-compile observation seam orders it.
		runtime.observeWorldPipeline(foreignTerrainPipeline())
		assertEquals(MotionVectorRoute.CAMERA_ONLY, runtime.motionVectorRoute)

		assertDoesNotThrow {
			val firstFrame = camera(0.0)
			phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = firstFrame)
			val resolved = phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)

			assertNotSame(mainTarget, resolved)
			assertSame(runtime.worldRenderTarget, resolved)
			assertEquals(DlssFrameRoute.DLSS, runtime.worldTargetRoute?.frame?.route)
			assertEquals(DlssSessionState.READY, runtime.sessionState)

			// The velocity attachment is gone, so terrain passes stay vanilla.
			val firstMotion = checkNotNull(runtime.activeMotion)
			assertTrue(firstMotion.reset, "the first frame has no predecessor")
			assertNull(phase.terrainVelocityView)

			phase.end()

			advanceClock()
			phase.prepare(
				normalInWorldFrame = true,
				mainTarget = mainTarget,
				camera = camera(2.0),
			)
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			val secondMotion = checkNotNull(runtime.activeMotion)
			assertFalse(secondMotion.reset)
			assertFalse(secondMotion.reprojection == Matrix4f(), "a moved camera must produce a non-identity reprojection")
			assertNull(phase.terrainVelocityView)
			phase.end()
		}
	}

	/**
	 * The full known-world enumeration: the three terrain pipelines [ChunkSectionLayer] can
	 * bind, plus the [RenderPipelines.WIREFRAME] debug override the terrain mixin selects in
	 * wireframe mode. This is the set `VulkanChunkSectionsToRenderMixin` classifies.
	 */

	private fun knownWorldPipelines(): List<RenderPipeline> =
		ChunkSectionLayer.entries.map { it.pipeline() } + RenderPipelines.WIREFRAME

	private fun pipelineObservation(pipeline: RenderPipeline) = MotionVectorPipeline(
		pipeline.location.toString(),
		listOf(
			MotionVectorShader(pipeline.vertexShader.toString(), pipeline.vertexShader.namespace),
			MotionVectorShader(pipeline.fragmentShader.toString(), pipeline.fragmentShader.namespace),
		),
	)

	private fun foreignTerrainPipeline() = MotionVectorPipeline(
		"example:pipeline/waving_terrain",
		listOf(MotionVectorShader("example:core/waving_terrain", "example")),
	)
	private var clockNanos = 0L

	private fun advanceClock() {
		clockNanos += 16_000_000L
	}

	private fun camera(x: Double) = DlssCameraSample(
		projection = Matrix4f(),
		viewRotation = Matrix4f(),
		cameraX = x,
		cameraY = 0.0,
		cameraZ = 0.0,
	)

}
