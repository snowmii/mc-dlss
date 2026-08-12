package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.file.Path
import java.util.Optional
import kotlin.io.path.readText
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssFrameRoute
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssSessionState
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4fc
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the known-world velocity pipeline surface of M-4: every world pipeline the terrain
 * pass can bind forms a two-target velocity twin through [velocityTwin], that twin agrees with
 * the two-attachment render-pass shape, owned pipelines stay on the velocity-MRT route, and
 * the first foreign shader latches camera-only exactly once without throwing and without
 * disabling the eligible DLSS world route or its camera motion.
 *
 * The known-world enumeration is the actual source of truth for what the terrain pass binds:
 * the three [ChunkSectionLayer] pipelines (SOLID, CUTOUT, TRANSLUCENT) plus the WIREFRAME
 * debug override. Every one of them is a descriptor-level proof: [velocityTwin] returns a
 * [RenderPipeline] description and nothing here compiles a pipeline on a device, which is
 * exactly the lazy-compile risk this slice does not claim to discharge — the twin's
 * color-target shape is what a two-attachment pass would compile against on its first
 * `RenderPass.setPipeline`.
 *
 * The terrain camera-motion writers are retired, so the terrain pass itself no longer binds
 * these twins; the twin surface survives for the retained object-motion writers (entity,
 * moving block, cloud) and the stress pass, which is where [velocityTwin] still lives.
 */
class MotionVectorPipelineTest {
	private val mainTarget = fakeMainTarget()


	/** The blended translucent first target and the cutout threshold defines survive the twin. */

	/** The real known world descriptors classify owned: Minecraft shaders stay velocity-MRT eligible. */
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
		assertEquals(foreign, compatibility.fallbackPipeline)

		val throwingSink = MotionVectorCompatibility { error("broken diagnostic sink") }
		assertDoesNotThrow { throwingSink.observe(foreignTerrainPipeline()) }
		assertEquals(MotionVectorRoute.CAMERA_ONLY, throwingSink.route)
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

			// The route is still DLSS: the world renders into the low-resolution scene target,
			// not the vanilla main target, and the session stays READY.
			assertNotSame(mainTarget, resolved)
			assertSame(runtime.activeWorldTarget, resolved)
			assertEquals(DlssFrameRoute.DLSS, runtime.activeRoute?.frame?.route)
			assertEquals(DlssSessionState.READY, runtime.sessionState)

			// Camera motion is retained on the camera-only route: this frame publishes motion
			// from the camera sample. The velocity attachment is gone, so terrain passes stay
			// vanilla.
			val firstMotion = checkNotNull(runtime.activeMotion)
			assertTrue(firstMotion.reset, "the first frame has no predecessor")
			assertNull(phase.terrainVelocityView)

			phase.end()

			// The next frame keeps advancing the camera-motion chain: a small camera move is a
			// continuous continuation, not a reset, so the fallback writer still produces
			// usable per-frame motion for DLSS.
			advanceClock()
			phase.prepare(
				normalInWorldFrame = true,
				mainTarget = mainTarget,
				camera = camera(2.0),
			)
			phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
			val secondMotion = checkNotNull(runtime.activeMotion)
			assertFalse(secondMotion.reset)
			assertFalse(secondMotion.reprojection.equals(Matrix4f()), "a moved camera must produce a non-identity reprojection")
			assertNull(phase.terrainVelocityView)
			phase.end()
		}
	}

	/**
	 * The full known-world enumeration: the three terrain pipelines [ChunkSectionLayer] can
	 * bind, plus the [RenderPipelines.WIREFRAME] debug override the terrain mixin selects in
	 * wireframe mode. This is the exact set `VulkanChunkSectionsToRenderMixin` classifies and
	 * twins, so it is the enumeration a twin-construction defect would surface in.
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

	/** Render target with a fake view over a fake texture, so the seams are verifiable off the render thread. */
}
