package me.snowmii.dlss.mrt

import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.mixin.LevelExtractorCaptureMixin
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import net.minecraft.client.renderer.entity.state.EntityRenderState
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.world.entity.Entity
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import org.spongepowered.asm.mixin.injection.callback.LocalCapture

/**
 * M-6's capture seam: the visible-entity extraction pass feeds each entity's interpolated render
 * position into the frame-boundary object-motion history the dynamic velocity writers will read.
 *
 * Minecraft 26.2 extracts entities in `GameRenderer.extract` -> `LevelExtractor.extract` ->
 * `extractVisibleEntities`, which runs *before* `LevelRenderer.render` HEAD opens the DLSS world
 * phase. At that add point the loop holds both halves of the capture: the live `Entity`
 * (`entity.getId()`, the stable key) and the `EntityRenderState` it just extracted (whose
 * `x/y/z` doubles are the partial-tick interpolated pose the geometry will be drawn at). The
 * capture seam must therefore land while the phase is *closed* - unlike pipeline observation,
 * which is open-phase-only - and only a world-phase completion whose DLSS evaluation/composition
 * succeeds publishes exactly once. False, skipped, or throwing evaluation, vanilla frames,
 * abandoned phases, world changes, releases, and close reset without retaining captures.
 *
 * The first eligible frame of a session can capture nothing: extraction precedes the render loop
 * building the phase, so the first frame's extraction finds no phase yet. That frame must publish
 * an empty boundary and stay reset - a first observation, never stale history.
 */
class MotionVectorCaptureSeamTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1707, 960)
	private val mainTarget = FakeTarget(output.width, output.height)

	@Test
	fun `the extraction capture lands before the phase opens and publishes the exact interpolated pose under the entity id`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)
		assertFalse(phase.isOpen, "extraction runs before the world phase opens")

		// The extraction pass fires while the phase is closed, so the seam must not gate on the
		// open phase the way pipeline observation does - the captures have to land before open.
		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.captureEntity(9, -3.5, 72.25, 11.125)
		assertNull(runtime.objectMotion.previous(7), "nothing is a predecessor before the frame boundary")

		renderDlssFrame(phase)

		// The exact doubles the extraction produced, keyed by each entity's own stable id.
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))
		assertEquals(position(-3.5, 72.25, 11.125), runtime.objectMotion.previous(9))
	}

	@Test
	fun `a successful DLSS frame publishes its captures exactly once at the frame boundary`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))

		// Second frame: the published frame is the predecessor the draw path composes from, and
		// it stays readable while the phase is open - between capture and publish.
		phase.captureEntity(7, 10.5, 64.0, 5.0)
		assertEquals(vec(0.5f, 0f, 0f), runtime.objectMotion.displacement(7))
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertEquals(
			vec(0.5f, 0f, 0f),
			runtime.objectMotion.displacement(7),
			"the draw path reads this frame's displacement while the phase is open",
		)
		phase.end()
		assertEquals(position(10.5, 64.0, 5.0), runtime.objectMotion.previous(7))
		assertNull(runtime.objectMotion.displacement(7), "the in-flight set is empty between frames")

		// A re-entrant end is a no-op, so the frame cannot publish twice.
		phase.end()
		assertEquals(position(10.5, 64.0, 5.0), runtime.objectMotion.previous(7))
	}

	@Test
	fun `a false evaluation resets captures without publishing them`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime) { false }

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)

		assertNull(runtime.objectMotion.previous(7), "an uncomposed frame cannot become a predecessor")
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `a skipped evaluation resets captures without publishing them`() {
		val runtime = dlssRuntime()
		var evaluations = 0
		val phase = phase(runtime) {
			evaluations++
			true
		}

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		// No camera sample means WorldPhase.evaluate skips its callback: no DLSS composition
		// happened even though the frame routed through the scene target.
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()

		assertEquals(0, evaluations)
		assertNull(runtime.objectMotion.previous(7), "a skipped evaluation cannot publish captures")
	}

	@Test
	fun `a throwing evaluation resets captures before propagating the failure`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime) { throw IllegalStateException("evaluation failed") }

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		assertThrows(IllegalStateException::class.java) { phase.end() }

		assertFalse(phase.isOpen)
		assertNull(runtime.objectMotion.previous(7), "a throwing evaluation cannot publish captures")
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `a vanilla frame resets the object history without retaining captures`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))

		// A vanilla frame (unsupported frame, panorama) breaks the accumulated history exactly
		// where the camera sequences break, so nothing survives into the next DLSS frame.
		phase.captureEntity(7, 11.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = false, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = false, mainTarget = mainTarget)
		phase.end()

		assertNull(runtime.objectMotion.previous(7), "a vanilla frame must not retain the object history")
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `an abandoned phase resets the object history`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		// LevelRenderer.render throws: the tail never closes the phase, and the next frame's
		// prepare drops the abandoned phase and breaks the history it would otherwise leave.
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())

		assertNull(runtime.objectMotion.previous(7))
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `a world change resets the object history`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))

		// setLevel / clearClientLevel: the scene is replaced, so the accumulated poses describe
		// a world that is gone and a reused entity id must not reproject against it.
		phase.resetHistory()

		assertNull(runtime.objectMotion.previous(7))
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `releasing the frame state resets the object history`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))

		// Switching DLSS off releases the held targets and breaks the history: the frames that
		// come back are not continuous with the ones that stopped.
		runtime.setEnabled(false)

		assertNull(runtime.objectMotion.previous(7), "a released runtime must not retain the object history")
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `closing the runtime resets the object history`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		phase.captureEntity(7, 10.0, 64.0, 5.0)
		renderDlssFrame(phase)
		assertEquals(position(10.0, 64.0, 5.0), runtime.objectMotion.previous(7))

		phase.close()

		assertNull(runtime.objectMotion.previous(7), "a closed runtime must not retain the object history")
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `the first eligible frame captures nothing and stays reset, never stale`() {
		// The first DLSS frame's extraction runs before the render loop has built the phase, so
		// no capture lands; the frame must publish an empty boundary rather than carry history
		// from a session that never rendered.
		val runtime = dlssRuntime()
		val phase = phase(runtime)

		renderDlssFrame(phase)

		assertNull(runtime.objectMotion.previous(7))
		assertNull(runtime.objectMotion.displacement(7))
	}

	@Test
	fun `the compiled injection matches the mapped extraction helper without local capture`() {
		// Resolve both compiled descriptors, not source spellings. Minecraft 26.2's private helper
		// is the only extractVisibleEntities call that pairs one live entity with its returned
		// render state; injecting at RETURN exposes target args + return value and no caller LVT.
		val target = LevelExtractor::class.java.getDeclaredMethod(
			"extractEntity",
			Entity::class.java,
			Float::class.javaPrimitiveType,
		)
		assertEquals(EntityRenderState::class.java, target.returnType)

		val handler = LevelExtractorCaptureMixin::class.java.getDeclaredMethod(
			"mcDlssCaptureVisibleEntity",
			Entity::class.java,
			Float::class.javaPrimitiveType,
			CallbackInfoReturnable::class.java,
		)
		val injection = requireNotNull(handler.getAnnotation(Inject::class.java))
		assertTrue(injection.method.contentEquals(arrayOf("extractEntity")))
		assertEquals(1, injection.at.size)
		assertEquals("RETURN", injection.at.single().value)
		assertEquals(
			LocalCapture.NO_CAPTURE,
			injection.locals,
			"the injection must not depend on extractVisibleEntities' caller-local layout",
		)
	}

	@Test
	fun `the extraction mixin pairs entity id with returned render pose and delegates read-only`() {
		val repository = Path.of("").toAbsolutePath()
		val mixin = repository
			.resolve("src/main/java/me/snowmii/dlss/mixin/LevelExtractorCaptureMixin.java")
			.readText()
		val mixins = repository.resolve("src/main/resources/mc-dlss.mixins.json").readText()

		assertTrue(mixins.contains("LevelExtractorCaptureMixin"))
		assertTrue(mixin.contains("@Mixin(LevelExtractor.class)"))
		assertTrue(mixin.contains("method = \"extractEntity\""))
		assertTrue(mixin.contains("@At(\"RETURN\")"))
		assertFalse(mixin.contains("LocalCapture"))
		assertTrue(mixin.contains("entity.getId()"))
		assertTrue(mixin.contains("info.getReturnValue()"))
		assertTrue(mixin.contains("state.x"))
		assertTrue(mixin.contains("state.y"))
		assertTrue(mixin.contains("state.z"))
		// Read-only delegation through active view, never runtime creation.
		assertTrue(mixin.contains("ClientRuntime.active().activeWorldPhase()"))
		assertTrue(mixin.contains("phase.captureEntity("))
	}

	private fun dlssRuntime(): RenderRuntime {
		val session = DlssSession(config()).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
			),
			startup = { render },
		)
	}

	private fun phase(runtime: RenderRuntime, evaluate: () -> Boolean = { true }) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = { _, _, _, _, _, _ -> evaluate() },
	)

	private fun renderDlssFrame(phase: WorldPhase) {
		phase.prepare(normalInWorldFrame = true, mainTarget = mainTarget, camera = camera())
		phase.begin(normalInWorldFrame = true, mainTarget = mainTarget)
		phase.end()
	}

	private fun camera() = DlssCameraSample(
		projection = Matrix4f().setPerspective(
			Math.toRadians(70.0).toFloat(),
			output.width.toFloat() / output.height,
			1000f,
			0.05f,
			true,
		),
		viewRotation = Matrix4f(),
		cameraX = 0.0,
		cameraY = 64.0,
		cameraZ = 0.0,
	)

	private fun config() = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = output,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	private fun position(x: Double, y: Double, z: Double) = ObjectPosition(x, y, z)

	private fun vec(x: Float, y: Float, z: Float) = Vector3f(x, y, z)

	/** Render target with a fake view over a fake texture, so the frame lifecycle is testable off the render thread. */
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
