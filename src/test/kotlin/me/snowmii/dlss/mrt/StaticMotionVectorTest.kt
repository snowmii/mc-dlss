package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraMotion
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Proves the terrain velocity writer of M-5 (AC-3): while an open VELOCITY_MRT world phase
 * offers its RG16_FLOAT velocity view, the known terrain chunk pipelines write jitter-stripped
 * NDC camera motion into color target 1 through the terrain writer twin - the plain two-target
 * velocity twin with the mc-dlss terrain velocity fragment shader and its VelocityConfig
 * uniform layout layered on - deriving the vectors from the frame's reprojection and the
 * fragment's own reversed-Z depth; invalid and reset pixels write the one representable
 * sentinel 10000.0; the velocity attachment clears to that sentinel before the opaque writer
 * and stays loaded through the translucent group; and the vanilla and camera-only routes keep
 * the exact one-target pass shape and cannot throw.
 *
 * The pipeline- and shader-level claims are descriptor and source proofs, exactly like the
 * rest of the MRT suite: nothing here compiles a pipeline on a device. The still-camera math
 * is exercised through the real [DlssCameraMotion] with the shader's own per-pixel formula,
 * and the sentinel choice is pinned both as shader source and as the classification behavior.
 * The mixin seams are pinned by source checks that fail when the clear lifecycle, the twin
 * selection, or the uniform write drop out of the production path.
 */
class StaticMotionVectorTest {
	private val output = DlssDimensions(2560, 1440)
	private val render = DlssDimensions(1280, 720)

	/**
	 * The terrain writer twin is the plain two-target velocity twin plus the terrain velocity
	 * shader and its uniform layout: the fragment shader swaps to mc-dlss:core/velocity_terrain,
	 * the layout list grows by exactly the VelocityConfig layout, the location moves to
	 * `velocity/terrain/<name>`, and every other descriptor field - vertex shader, defines,
	 * depth state, polygon mode, culling, all sixteen vertex bindings, primitive topology, and
	 * both color targets - is the plain twin's. The plain twin itself is untouched, so the M-4
	 * descriptor contract (source fragment shader preserved, one twin per source pipeline) still
	 * holds for the same call the M-4 tests exercise.
	 */
	@Test
	fun `terrain writer twin layers the velocity shader and uniform layout onto the plain twin`() {
		for (source in knownWorldPipelines()) {
			val plain = velocityTwin(source)
			val twin = terrainVelocityTwin(plain)

			assertNotSame(plain, twin)
			assertEquals(
				Identifier.fromNamespaceAndPath(
					"mc-dlss",
					"velocity/terrain/" + source.location.path.removePrefix("pipeline/"),
				),
				twin.location,
			)
			assertNotEquals(plain.location, twin.location)

			// The velocity shader replaces the source fragment shader; the vertex shader is the
			// plain twin's (which is the source's), and the plain twin itself still keeps the
			// source fragment shader - the M-4 descriptor contract.
			assertEquals(TerrainVelocityUniforms.FRAGMENT_SHADER, twin.fragmentShader)
			assertSame(source.fragmentShader, plain.fragmentShader, "the plain twin still keeps the source fragment shader")
			assertSame(plain.vertexShader, twin.vertexShader)

			// Defines, depth state, polygon mode, culling, topology, and all sixteen bindings
			// carry over from the plain twin.
			assertEquals(plain.shaderDefines, twin.shaderDefines)
			assertSame(plain.depthStencilState, twin.depthStencilState)
			assertSame(plain.polygonMode, twin.polygonMode)
			assertEquals(plain.isCull, twin.isCull)
			assertSame(plain.primitiveTopology, twin.primitiveTopology)
			for (index in 0 until 16) {
				assertSame(plain.getVertexFormatBinding(index), twin.getVertexFormatBinding(index))
			}

			// Exactly one extra layout: the VelocityConfig uniform buffer the shader block
			// binds. The first layouts are the plain twin's identical instances.
			val plainLayouts = plain.bindGroupLayouts
			val twinLayouts = twin.bindGroupLayouts
			assertEquals(plainLayouts.size + 1, twinLayouts.size)
			for (index in plainLayouts.indices) {
				assertSame(plainLayouts[index], twinLayouts[index])
			}
			assertSame(TerrainVelocityUniforms.LAYOUT, twinLayouts.last())

			// The two-target color contract is the plain twin's: target zero identical, target
			// one the unblended RG16_FLOAT velocity payload.
			assertEquals(2, twin.colorTargetStates.size)
			assertSame(plain.colorTargetStates[0], twin.colorTargetStates[0])
			assertVelocityTarget(twin.colorTargetStates[1]!!)

			// Cached per plain twin, so the first velocity frame pays the compile once.
			assertSame(twin, terrainVelocityTwin(plain))
		}

		// Every known pipeline gets its own terrain twin at a distinct location.
		val terrainTwins = knownWorldPipelines().map { terrainVelocityTwin(velocityTwin(it)) }
		assertEquals(4, terrainTwins.distinct().size)
		assertEquals(4, terrainTwins.map { it.location }.distinct().size)
	}

	/**
	 * The velocity shader declares exactly the uniform block the pipeline's added layout
	 * carries - Vulkan's lazy compile resolves every shader-declared uniform against the
	 * pipeline's layouts by name, so a mismatch would fail at the first bind, lazily,
	 * mid-render - and the block's member layout is exactly the JVM-side write: one mat4, one
	 * vec4, 80 bytes.
	 */
	@Test
	fun `the velocity uniform contract matches between shader and JVM write`() {
		val shader = terrainVelocityShader()

		// The shader block name is the layout's uniform name, and its members are the JVM
		// block's members.
		assertTrue(shader.contains("layout(std140) uniform ${TerrainVelocityUniforms.UNIFORM_NAME} {"))
		assertTrue(shader.contains("mat4 Reprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertEquals(80, TerrainVelocityUniforms.UBO_SIZE, "mat4 + vec4 in std140")

		// The write fills the block in member order: reprojection, then the reset flag and the
		// velocity viewport size in pixels.
		val uniforms = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/kotlin/me/snowmii/dlss/mrt/TerrainVelocityUniforms.kt")
			.readText()
		assertTrue(uniforms.contains("withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)"))
		assertTrue(uniforms.contains("putMat4f(reprojection)"))
		assertTrue(uniforms.contains("putVec4("))
		assertTrue(uniforms.contains("motion?.reset ?: true"), "a frame without published motion is a reset frame")
		assertTrue(uniforms.contains("view.getWidth(0).toFloat()"))
		assertTrue(uniforms.contains("writeToBuffer(buffer.slice(), data)"))
		assertTrue(uniforms.contains("INVALID_VELOCITY, INVALID_VELOCITY, 0f, 0f"), "the clear color is the sentinel")
	}

	/**
	 * The shader derives previous NDC from the reprojection and the fragment's own reversed-Z
	 * depth, and subtracts the current NDC: exactly the formula the reprojection was composed
	 * to serve. The clip position is reconstructed from gl_FragCoord by inverting the
	 * backend's fixed viewport transform - gl_FragCoord.z is the reversed-Z depth (1.0 near,
	 * 0.0 far), which the backend maps to clip.z / clip.w directly, so it goes into clip.z
	 * unmodified, the same convention the stress shader feeds its sampled depth with.
	 */
	@Test
	fun `the terrain shader derives previous NDC from the reprojection and reversed-Z depth`() {
		val shader = terrainVelocityShader()

		assertTrue(shader.contains("out vec4 velocityColor;"), "the velocity output is the pipeline's second color target")
		assertTrue(shader.contains("gl_FragCoord.x / VelocityParams.y * 2.0 - 1.0"))
		assertTrue(shader.contains("gl_FragCoord.y / VelocityParams.z * 2.0 - 1.0"))
		assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"))
		assertTrue(shader.contains("vec4 previous = Reprojection * clip;"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))

		// The color output is the vanilla terrain shader's, verbatim: the twin swaps only the
		// velocity write in, so the terrain renders byte-identically on the velocity route.
		assertTrue(shader.contains("sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)"))
		assertTrue(shader.contains("apply_fog("))
		assertTrue(shader.contains("#ifdef ALPHA_CUTOUT"))
	}

	/**
	 * A continuous still camera produces zero NDC motion at every depth through the shader's
	 * own formula, whatever the jitter moved: the reprojection collapses to the identity, so
	 * `ndc(Reprojection * clip) - ndc(clip)` is zero and the jitter never leaks into the
	 * vector. This is the "static motion vectors" the milestone names: terrain is static
	 * geometry, so its correct velocity is exactly the camera's, and a still camera must read
	 * zero everywhere.
	 */
	@Test
	fun `a continuous still camera produces zero NDC motion at every depth`() {
		val motion = DlssCameraMotion(render)
		val camera = sample()
		motion.advance(camera, jitter(0, -0.44f, 0.31f), 0L)
		val frame = motion.advance(camera, jitter(1, 0.37f, -0.21f), 16_000_000L)

		assertFalse(frame.reset, "a continuous still camera is not a reset frame")
		for (probe in probes) {
			val motionVector = motionOf(frame, probe)
			assertEquals(0f, motionVector.x, TOLERANCE, "x motion at $probe")
			assertEquals(0f, motionVector.y, TOLERANCE, "y motion at $probe")
		}
	}

	/**
	 * Invalid and no-predecessor pixels write one representable sentinel instead of the
	 * identity-derived zero. A reset frame's reprojection is the identity, which would read as
	 * "the camera stood still" - the sentinel is the only thing that tells DLSS the pixel has
	 * no predecessor at all. One value, representable in the RG16_FLOAT payload, far outside
	 * the NDC range a real vector can reach - and the same value the velocity attachment is
	 * cleared to before the opaque writer, so pixels the terrain never writes stay invalid.
	 */
	@Test
	fun `invalid and no-predecessor pixels write one representable sentinel`() {
		val shader = terrainVelocityShader()
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))
		assertTrue(shader.contains("VelocityParams.x > 0.5"), "the reset flag drives the per-pixel classification")
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"), "every invalid path writes the one sentinel")

		val motion = DlssCameraMotion(render)
		val first = motion.advance(sample(), jitter(0, 0.0f, 0.0f), 0L)
		assertTrue(first.reset)
		assertEquals(Matrix4f(), first.reprojection)
		for (probe in probes) {
			val result = classify(Matrix4f(), probe, reset = true)
			assertEquals(INVALID_VELOCITY, result.x, "reset forces the sentinel at $probe")
			assertEquals(INVALID_VELOCITY, result.y, "reset forces the sentinel at $probe")
		}
	}

	/**
	 * Per-pixel invalid reprojections write the same sentinel the reset flag forces, never
	 * Inf/NaN or a mirrored finite motion, and the classification is per-pixel: one pixel whose
	 * previous w the previous camera cannot see (zero or negative) or that is non-finite, or
	 * whose reprojection produced a non-finite or out-of-range vector, does not drag the rest
	 * of the frame with it. The classification is deliberately the stress pass's, kept in
	 * lockstep between the two writers of the same payload.
	 */
	@Test
	fun `per-pixel invalid reprojections write the sentinel instead of Inf or NaN`() {
		val shader = terrainVelocityShader()
		assertTrue(shader.contains("previous.w <= 0.0"), "a point on or behind the previous eye plane is invalid")
		assertTrue(shader.indexOf("previous.w <= 0.0") < shader.indexOf("/ previous.w"), "classification precedes the divide")
		assertTrue(shader.contains("previous.w != previous.w"), "a non-finite previous w is invalid")
		assertTrue(shader.contains("motion.x != motion.x"), "NaN collapses to invalid")
		assertTrue(shader.contains("abs(motion.x) >= INVALID_VELOCITY"), "out-of-range collapses to invalid")

		// Per-pixel, not per-frame: a reprojection that puts one depth on the previous eye
		// plane (previous w exactly zero) invalidates only that pixel; a shallower surface that
		// the previous camera still sees stays a valid finite vector. JOML names elements mRC
		// as column R, row C, so the bottom row is (m03, m13, m23, m33) and w' = z - 0.6·w here.
		val onEyePlane = Matrix4f().m23(1f).m33(-0.6f)
		assertEquals(INVALID_VELOCITY, classify(onEyePlane, Vector4f(0f, 0f, 0.6f, 1f)).x, "w == 0 writes the sentinel")
		val visible = classify(onEyePlane, Vector4f(0f, 0f, 0.95f, 1f))
		assertNotEquals(INVALID_VELOCITY, visible.x, 0f)
		assertFiniteMotion(visible)

		// A NaN reprojection entry propagates through the multiply into the vector and
		// collapses to invalid rather than writing NaN.
		val nanReprojection = Matrix4f().set(
			Float.NaN, 0f, 0f, 0f,
			0f, 1f, 0f, 0f,
			0f, 0f, 1f, 0f,
			0f, 0f, 0f, 1f,
		)
		assertEquals(INVALID_VELOCITY, classify(nanReprojection, Vector4f(0f, 0f, 0.95f, 1f)).x)

		// An out-of-range result - magnitude at or beyond the sentinel itself - collapses to
		// invalid instead of overflowing the half-float payload or colliding with the sentinel.
		val outOfRange = Matrix4f().set(
			20000f, 0f, 0f, 0f,
			0f, 1f, 0f, 0f,
			0f, 0f, 1f, 0f,
			0f, 0f, 0f, 1f,
		)
		assertEquals(INVALID_VELOCITY, classify(outOfRange, Vector4f(0.95f, 0f, 0.6f, 1f)).x)

		// A previous w behind the camera is a point the previous camera cannot see: the divide
		// would mirror it into a plausible-looking but wrong NDC, so it writes the sentinel
		// rather than a finite motion. Same bottom-row construction as above: w' = z - 2·w puts
		// the probe behind the previous eye plane.
		val behindCamera = Matrix4f().m23(1f).m33(-2f)
		val offCenter = Vector4f(0.4f, 0.3f, 0.95f, 1f)
		assertEquals(INVALID_VELOCITY, classify(behindCamera, offCenter).x, "w < 0 (behind the previous camera) writes the sentinel")
		assertEquals(INVALID_VELOCITY, classify(behindCamera, offCenter).y, "a sentinel carries both components")

		// A non-finite previous w (an Inf reprojection entry reaching the bottom row) is
		// invalid before the divide: Inf/Inf collapses to a finite-looking value and would
		// smuggle a bogus vector past the result guard.
		val infiniteW = Matrix4f().m23(Float.POSITIVE_INFINITY)
		assertEquals(INVALID_VELOCITY, classify(infiniteW, offCenter).x, "a non-finite previous w writes the sentinel")

		// Sweep: whatever the reprojection, the classification is either the sentinel (both
		// components) or a finite vector strictly below the sentinel magnitude. Inf/NaN never
		// reaches the payload.
		for (probe in probes) {
			for (reprojection in listOf(onEyePlane, behindCamera, infiniteW, nanReprojection, outOfRange, Matrix4f())) {
				val result = classify(reprojection, probe)
				if (result.x == INVALID_VELOCITY) {
					assertEquals(INVALID_VELOCITY, result.y, "a sentinel carries both components at $probe")
				} else {
					assertFiniteMotion(result)
				}
			}
		}
	}

	/**
	 * The clear lifecycle is wired at the pass-creation redirect: the velocity attachment is
	 * cleared to the sentinel through an encoder command before the opaque group's pass is
	 * created - never through the pass descriptor, whose velocity attachment stays
	 * Optional.empty() - and the translucent group skips the clear so the opaque-written
	 * velocity remains loaded through its work. The vanilla and camera-only routes keep the
	 * exact one-attachment call, which cannot throw because none of the new code runs before
	 * the velocity view is known to be non-null.
	 */
	@Test
	fun `velocity clears to the sentinel before the opaque writer and loads through translucent work`() {
		val mixin = terrainMixin()

		// The redirect captures the enclosing renderGroup argument, so the group decides the
		// clear lifecycle.
		assertTrue(mixin.contains("ChunkSectionLayerGroup group"))
		assertTrue(mixin.contains("group == ChunkSectionLayerGroup.OPAQUE"))

		// The opaque writer clears through the encoder command, before the pass descriptor is
		// built; the descriptor's velocity attachment stays Optional.empty() (load) on both
		// groups, exactly as the attachment contract pins it.
		assertTrue(mixin.contains("clearColorTexture(velocity.texture(), TerrainVelocityUniforms.SENTINEL)"))
		assertTrue(mixin.contains("withColorAttachment(velocity, Optional.empty())"))
		assertTrue(
			mixin.indexOf("clearColorTexture") < mixin.indexOf("withColorAttachment(velocity, Optional.empty())"),
			"the clear precedes the pass descriptor",
		)

		// The vanilla route is the exact original call, untouched, before any velocity work:
		// a null velocity view means the pass is one-attachment and every new call below is
		// skipped, so that route cannot throw.
		assertTrue(mixin.contains("createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)"))
		assertTrue(mixin.indexOf("createRenderPass(label, colorTexture, clearColor, depthTexture, clearDepth)") < mixin.indexOf("clearColorTexture"))
	}

	/**
	 * Every production seam is bound to the assertions above: the pipeline redirect binds the
	 * terrain writer twin - built from the plain twin the M-4 tests pin - and this frame's
	 * velocity uniform block, whose data comes from the open phase's published motion through
	 * the same gating the velocity view uses. A removed or broken seam fails here even when
	 * the descriptor and shader assertions stay green.
	 */
	@Test
	fun `the terrain mixin binds the velocity uniform and writer twin on the velocity route`() {
		val mixin = terrainMixin()
		val worldPhase = Path.of("")
			.toAbsolutePath()
			.resolve("src/main/kotlin/me/snowmii/dlss/render/WorldPhase.kt")
			.readText()

		// The exact twin bound on the velocity route: the terrain writer twin derived from the
		// plain velocity twin, and only for the pass that carries the velocity attachment.
		assertTrue(mixin.contains("terrainVelocityTwin(velocityTwin(pipeline))"))
		assertTrue(mixin.contains("VELOCITY_PASS.get() == pass"))
		assertTrue(mixin.contains("pass.setPipeline(pipeline)"), "the non-velocity route binds the source pipeline unchanged")

		// The uniform block the twin's shader reads is bound on the same redirect, from the
		// shared buffer the pass-creation redirect wrote this frame.
		assertTrue(mixin.contains("setUniform(TerrainVelocityUniforms.UNIFORM_NAME, VELOCITY_UNIFORM_BUFFER.slice())"))
		assertTrue(mixin.contains("TerrainVelocityUniforms.writeFrame("))
		assertTrue(mixin.contains("velocityUniformBuffer()"))
		assertTrue(mixin.contains("GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST"))

		// The write is fed from the open phase's published motion - the same WorldPhase seam
		// that gates the velocity view - so the terrain velocity and the DLSS evaluation
		// describe the same camera motion.
		assertTrue(mixin.contains("mcDlssActiveMotion()"))
		assertTrue(mixin.contains("getActiveMotion()"))
		assertTrue(worldPhase.contains("val activeMotion: DlssFrameMotion?"))
		assertTrue(worldPhase.contains("if (isOpen) runtime.activeMotion else null"))
	}

	private fun terrainMixin(): String = Path.of("")
		.toAbsolutePath()
		.resolve("src/main/java/me/snowmii/dlss/mixin/VulkanChunkSectionsToRenderMixin.java")
		.readText()

	private fun terrainVelocityShader(): String = Path.of("")
		.toAbsolutePath()
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_terrain.fsh")
		.readText()

	private fun knownWorldPipelines() = ChunkSectionLayer.entries.map { it.pipeline() } + RenderPipelines.WIREFRAME

	private fun sample() = DlssCameraSample(projection, Matrix4f(), 0.0, 64.0, 0.0)

	private fun jitter(index: Int, pixelX: Float, pixelY: Float) =
		DlssJitterOffset(index, pixelX, pixelY, render)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

	/**
	 * The shader's own per-pixel formula: `ndc` is this fragment's normalized device
	 * coordinates recovered from gl_FragCoord, `clip = vec4(ndc, gl_FragCoord.z, 1.0)`,
	 * `previous = Reprojection * clip`, `motion = previous.xy / previous.w - ndc`.
	 */
	private fun motionOf(frame: DlssFrameMotion, clip: Vector4f): Vector4f {
		val reprojected = frame.reprojection.transform(Vector4f(clip))
		return Vector4f(
			reprojected.x / reprojected.w - clip.x / clip.w,
			reprojected.y / reprojected.w - clip.y / clip.w,
			0f,
			1f,
		)
	}

	/**
	 * The shader's full per-pixel classification, mirrored exactly: the reset flag, a previous
	 * w the previous camera cannot see (zero or negative), or a non-finite previous w (NaN/Inf)
	 * is invalid before the divide, and a non-finite or out-of-range result (magnitude at or
	 * beyond the sentinel) collapses to invalid after it. Invalid pixels write the sentinel in
	 * both components; valid pixels write the finite formula value.
	 */
	private fun classify(reprojection: Matrix4f, clip: Vector4f, reset: Boolean = false): Vector4f {
		if (reset) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		val previous = reprojection.transform(Vector4f(clip))
		if (previous.w <= 0.0f || previous.w.isNaN() || previous.w.isInfinite()) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		val motion = Vector4f(
			previous.x / previous.w - clip.x / clip.w,
			previous.y / previous.w - clip.y / clip.w,
			0f,
			1f,
		)
		if (motion.x != motion.x || motion.y != motion.y ||
			abs(motion.x) >= INVALID_VELOCITY || abs(motion.y) >= INVALID_VELOCITY
		) {
			return Vector4f(INVALID_VELOCITY, INVALID_VELOCITY, 0f, 1f)
		}
		return motion
	}

	/** The payload invariant: a valid vector is finite and strictly below the sentinel magnitude. */
	private fun assertFiniteMotion(motion: Vector4f) {
		assertTrue(motion.x == motion.x && motion.y == motion.y, "motion must not be NaN: $motion")
		assertTrue(abs(motion.x) < INVALID_VELOCITY && abs(motion.y) < INVALID_VELOCITY, "motion must stay below the sentinel: $motion")
	}

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	private val projection: Matrix4f = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		render.width.toFloat() / render.height,
		1000f,
		0.05f,
		true,
	)

	private companion object {
		const val TOLERANCE = 1e-3f

		/** The shader's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}
}
