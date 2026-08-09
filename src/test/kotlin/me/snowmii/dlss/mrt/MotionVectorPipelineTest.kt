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
 * the two-attachment render-pass shape the terrain seam builds, owned pipelines stay on the
 * velocity-MRT route, and the first foreign shader latches camera-only exactly once without
 * throwing and without disabling the eligible DLSS world route or its camera motion.
 *
 * The known-world enumeration is the actual source of truth for what the terrain pass binds:
 * the three [ChunkSectionLayer] pipelines (SOLID, CUTOUT, TRANSLUCENT) plus the WIREFRAME
 * debug override that `VulkanChunkSectionsToRenderMixin.mcDlssClassifyTerrainPipelines`
 * selects when the wireframe debug hotkey is on. Every one of them is a descriptor-level
 * proof: [velocityTwin] returns a [RenderPipeline] description and nothing here compiles a
 * pipeline on a device, which is exactly the lazy-compile risk this slice does not claim to
 * discharge — the twin's color-target shape is what a two-attachment pass would compile
 * against on its first `RenderPass.setPipeline`.
 *
 * Every surface is also bound to the production seams that consume it: the source-seam check
 * below fails when `VulkanChunkSectionsToRenderMixin` no longer classifies this exact
 * enumeration or no longer binds [velocityTwin] at its pipeline redirect, when
 * `VulkanPipelineCompatibilityMixin` no longer observes this exact pipeline shape at Vulkan's
 * lazy-compile seam, or when either mixin drops out of the registration file — so a removed or
 * broken production mixin cannot leave the descriptor and routing assertions above green.
 */
class MotionVectorPipelineTest {

	/**
	 * Every pipeline the world terrain pass can bind gets one cached twin: a different
	 * descriptor at a distinct mc-dlss location that preserves the source's shaders, defines,
	 * bind-group layouts, depth state, polygon mode, culling, all sixteen vertex bindings, and
	 * primitive topology, keeps target zero as the identical instance, and adds exactly one
	 * unblended RG16_FLOAT WRITE_ALL target at index 1.
	 */
	@Test
	fun `every enumerated known world pipeline has a two-target twin preserving target zero`() {
		val known = knownWorldPipelines()
		assertEquals(4, known.size, "the known world enumeration must stay in step with ChunkSectionLayer plus the wireframe debug override")
		val twins = known.map { velocityTwin(it) }

		for ((source, twin) in known.zip(twins)) {
			assertNotSame(source, twin)
			assertEquals(
				Identifier.fromNamespaceAndPath("mc-dlss", "velocity/${source.location.path}"),
				twin.location,
			)
			assertNotEquals(source.location, twin.location)

			// Descriptor fields carried over unchanged.
			assertSame(source.vertexShader, twin.vertexShader)
			assertSame(source.fragmentShader, twin.fragmentShader)
			assertEquals(source.shaderDefines, twin.shaderDefines)
			assertEquals(source.bindGroupLayouts.size, twin.bindGroupLayouts.size)
			for (index in source.bindGroupLayouts.indices) {
				assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
			}
			assertSame(source.depthStencilState, twin.depthStencilState)
			assertSame(source.polygonMode, twin.polygonMode)
			assertEquals(source.isCull, twin.isCull)
			assertSame(source.primitiveTopology, twin.primitiveTopology)
			for (index in 0 until 16) {
				assertSame(source.getVertexFormatBinding(index), twin.getVertexFormatBinding(index))
			}

			// The two-target color contract: target zero identical, target one the velocity payload.
			val sourceTargets = source.colorTargetStates
			val twinTargets = twin.colorTargetStates
			assertEquals(2, twinTargets.size)
			assertSame(sourceTargets[0], twinTargets[0])
			assertVelocityTarget(twinTargets[1]!!)

			// One cached twin per source pipeline, so Vulkan's identity-keyed lazy-compile
			// cache is hit on every bind after the first.
			assertSame(twin, velocityTwin(source))
		}

		// Each source gets its own twin: no two known pipelines share a velocity location.
		assertEquals(4, twins.distinct().size)
		assertEquals(4, twins.map { it.location }.distinct().size)
	}

	/** The blended translucent first target and the cutout threshold defines survive the twin. */
	@Test
	fun `translucent and cutout terrain twins preserve their blended target and defines`() {
		val translucent = velocityTwin(RenderPipelines.TRANSLUCENT_TERRAIN)
		assertEquals(Optional.of(BlendFunction.TRANSLUCENT), translucent.colorTargetStates[0]!!.blendFunction())
		assertVelocityTarget(translucent.colorTargetStates[1]!!)
		assertEquals("0.1", translucent.shaderDefines.values["ALPHA_CUTOUT"])

		val cutout = velocityTwin(RenderPipelines.CUTOUT_TERRAIN)
		assertEquals("0.5", cutout.shaderDefines.values["ALPHA_CUTOUT"])
		assertVelocityTarget(cutout.colorTargetStates[1]!!)
	}

	/**
	 * The two-attachment render-pass descriptor the terrain seam builds — scene colour at
	 * index 0, the RG16_FLOAT velocity view at index 1, neither cleared — carries exactly the
	 * color-target count and format the twins declare, which is the agreement
	 * `RenderPass.setPipeline` validates on first bind.
	 */
	@Test
	fun `two-attachment pass descriptor agrees with every twin color-target shape`() {
		val sceneView = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocityView = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		val descriptor = RenderPassDescriptor.create({ "terrain velocity" })
			.withColorAttachment(sceneView, Optional.empty())
			.withColorAttachment(velocityView, Optional.empty())

		val attachments = descriptor.colorAttachments()
		assertEquals(2, attachments.size)
		assertSame(sceneView, attachments[0]!!.textureView())
		assertSame(velocityView, attachments[1]!!.textureView())
		assertEquals(Optional.empty<Vector4fc>(), attachments[1]!!.clearValue())
		assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().getFormat())

		// The pass's two attachments and every twin's two targets declare the same shape: two
		// color targets with an unblended RG16_FLOAT payload at index 1.
		for (source in knownWorldPipelines()) {
			val twin = velocityTwin(source)
			val targets = twin.colorTargetStates
			assertEquals(2, targets.size)
			assertEquals(attachments.size, targets.size)
			assertEquals(GpuFormat.RG16_FLOAT, targets[1]!!.format())
			assertEquals(attachments[1]!!.textureView().texture().getFormat(), targets[1]!!.format())
		}
	}

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

		// The foreign shader latches before pass creation, exactly as the renderGroup HEAD
		// inject orders it.
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
	/**
	 * Binds every descriptor and routing assertion above to the production seams that consume them.
	 *
	 * The twin assertions exercise [velocityTwin]; this check proves that exact function is what
	 * the terrain mixin's pipeline-boundary redirect binds. The known-world enumeration is the
	 * exact selection the mixin's `renderGroup` HEAD classification makes — each layer's own
	 * pipeline, or the wireframe debug override for every layer — and the two-attachment
	 * descriptor agreement is the exact descriptor the pass-creation redirect constructs. The
	 * route assertions feed [MotionVectorCompatibility] one pipeline shape; this check proves
	 * both mixins build that exact shape (location id plus vertex/fragment shader id and owner
	 * namespace) and hand it to the phase that forwards into the runtime seam the fallback test
	 * drives. Removing or breaking any of those production seams fails here even though the
	 * pure descriptor and routing assertions stay green.
	 */
	@Test
	fun `production mixin seams select the exact twin and observation surface under test`() {
		val repository = Path.of("").toAbsolutePath()
		val mixins = repository.resolve("src/main/resources/mc-dlss.mixins.json").readText()
		val terrainMixin = repository
			.resolve("src/main/java/me/snowmii/dlss/mixin/VulkanChunkSectionsToRenderMixin.java")
			.readText()
		val compileMixin = repository
			.resolve("src/main/java/me/snowmii/dlss/mixin/VulkanPipelineCompatibilityMixin.java")
			.readText()
		val worldPhase = repository
			.resolve("src/main/kotlin/me/snowmii/dlss/render/WorldPhase.kt")
			.readText()

		// Both mixins stay registered, or the loader never applies the seams under test.
		assertTrue(mixins.contains("VulkanChunkSectionsToRenderMixin"))
		assertTrue(mixins.contains("VulkanPipelineCompatibilityMixin"))

		// The twin under test is the exact pipeline bound at the terrain setPipeline redirect,
		// and only for the pass that carries the velocity attachment.
		assertTrue(terrainMixin.contains("@Mixin(ChunkSectionsToRender.class)"))
		assertTrue(terrainMixin.contains("RenderPass;setPipeline("))
		assertTrue(terrainMixin.contains("velocityTwin(pipeline)"))
		assertTrue(terrainMixin.contains("VELOCITY_PASS.get() == pass"))

		// The known-world enumeration is the exact selection the renderGroup HEAD classification
		// makes: one pass over the group's layers, each layer's own pipeline unless the wireframe
		// debug override replaces every layer's pipeline.
		assertTrue(terrainMixin.contains("@Inject(method = \"renderGroup\", at = @At(\"HEAD\"))"))
		assertTrue(terrainMixin.contains("for (ChunkSectionLayer layer : group.layers())"))
		assertTrue(terrainMixin.contains("wireframe ? RenderPipelines.WIREFRAME : layer.pipeline()"))

		// The two-attachment descriptor agreement is the exact descriptor the pass-creation
		// redirect builds when the open velocity-MRT phase offers its RG16_FLOAT view.
		assertTrue(terrainMixin.contains("getTerrainVelocityView()"))
		assertTrue(terrainMixin.contains("withColorAttachment(velocity, Optional.empty())"))

		// The classification and the lazy-compile backstop both observe through the phase, and
		// both build the exact MotionVectorPipeline shape the route assertions feed the
		// compatibility latch: location id, then vertex and fragment shader id plus owner
		// namespace.
		val observationShape = listOf(
			"pipeline.getLocation().toString()",
			"pipeline.getVertexShader().toString()",
			"pipeline.getVertexShader().getNamespace()",
			"pipeline.getFragmentShader().toString()",
			"pipeline.getFragmentShader().getNamespace()",
		)
		for (fragment in observationShape) {
			assertTrue(terrainMixin.contains(fragment), "terrain classification must observe the shape via $fragment")
			assertTrue(compileMixin.contains(fragment), "lazy-compile observation must observe the shape via $fragment")
		}
		assertTrue(terrainMixin.contains("phase.observePipeline(new MotionVectorPipeline("))
		assertTrue(compileMixin.contains("phase.observePipeline(new MotionVectorPipeline("))

		// The lazy-compile backstop observes at the Vulkan pipeline-compile seam while the world
		// phase is open, before compilation can bind an incompatible attachment shape.
		assertTrue(compileMixin.contains("@Mixin(VulkanDevice.class)"))
		assertTrue(compileMixin.contains("method = \"getOrCompilePipeline\""))
		assertTrue(compileMixin.contains("at = @At(\"HEAD\")"))
		assertTrue(compileMixin.contains("activeWorldPhase()"))

		// The phase forwards mixin observations into the runtime seam the camera-only fallback
		// test drives, and the terrain mixin reads the velocity view through that same phase.
		assertTrue(worldPhase.contains("fun observePipeline(pipeline: MotionVectorPipeline)"))
		assertTrue(worldPhase.contains("runtime.observeWorldPipeline(pipeline)"))
	}

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

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
		assertTrue(target.writeRed())
		assertTrue(target.writeGreen())
		assertTrue(target.writeBlue())
		assertTrue(target.writeAlpha())
	}

	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)
	private val mainTarget = FakeTarget(output.width, output.height)
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

	private fun velocityRuntime(): RenderRuntime {
		val session = session(enabled = true).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = { width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) },
			),
			startup = { render },
			clock = { clockNanos },
		)
	}

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

	/** Render target with a fake view over a fake texture, so the seams are verifiable off the render thread. */
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

	private class FakeTexture(format: GpuFormat, width: Int = 16, height: Int = 16) :
		GpuTexture(GpuTexture.USAGE_RENDER_ATTACHMENT, "fake", format, width, height, 1, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}

	private class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
		override fun close() = Unit
		override fun isClosed() = false
	}
}
