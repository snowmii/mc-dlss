package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.Optional
import java.util.OptionalDouble
import kotlin.io.path.readText
import kotlin.math.abs
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.SharedConstants
import net.minecraft.WorldVersion
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.blockentity.state.PistonHeadRenderState
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.world.level.storage.DataVersion
import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource
import com.google.gson.JsonParser

/**
 * Piston moving-block vertical proof for M-6's velocity writer.
 *
 * `PistonHeadRenderer` renders a moving piston block by submitting one or two
 * `MovingBlockRenderState`s (the moving block and the retracting piston base) into
 * `MovingBlockFeatureRenderer`, which tesselates each submit's block model into the
 * `solidMovingBlock` / `cutoutMovingBlock` / `translucentMovingBlock` render types - the
 * block-shaped `SOLID_BLOCK` / `CUTOUT_BLOCK` / `TRANSLUCENT_BLOCK` pipelines - and draws them
 * through the same `PreparedRenderType.drawFromBuffer` seam the entity writer uses. This slice
 * binds each submitted render state to the collision-free packed long identity of its baked
 * block position at the block-entity dispatcher seam, captures its absolute render position (baked position plus the
 * piston's current interpolated offset) into the shared object-motion history, isolates one
 * staged draw per moving block at the feature-renderer boundary, and replaces the owned
 * main-target solid/cutout draws with a cached two-target twin whose fragment shader
 * reproduces the vanilla core/block color output byte-identically and writes the piston
 * offset-delta object reprojection into the RG16_FLOAT velocity attachment - with the exact
 * reset/unknown-history sentinel. Translucent `ITEM_ENTITY_TARGET` draws, identity-less
 * moving geometry (falling blocks ride the same feature renderer), vanilla, and CAMERA_ONLY
 * keep their exact source routes without throwing.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D device, so this suite makes
 * no live transformed/GPU draw claim: descriptors are proven against the mapped 26.2 classes,
 * the control seams are driven at the same seams the mixins use, and passthrough is proven by
 * the control seams answering false. The moving-block shader compiles through the same LWJGL
 * Shaderc + spirv-cross path `GlslCompiler` and `IntermediaryShaderModule` use - it inlines the
 * two vanilla includes it needs, so it is self-contained - and the reflected output order is
 * pinned to fragColor-then-velocityColor, the order Minecraft's location rewrite turns into
 * color attachments 0 and 1.
 */
class MotionVectorMovingBlockTest {
	private val repository = Path.of("").toAbsolutePath()

	companion object {
		/**
		 * Constructing real mapped moving-block render states touches the Blocks/AIR registry;
		 * the headless test JVM needs the vanilla registry bootstrap first (with a synthetic
		 * world version, since no game entrypoint runs in tests). Idempotent.
		 */
		@JvmStatic
		@org.junit.jupiter.api.BeforeAll
		fun bootstrapVanillaRegistries() {
			SharedConstants.setVersion(
				WorldVersion.Simple(
					"26.2",
					"26.2",
					DataVersion(9999, "main"),
					1,
					PackFormat(1, 1),
					PackFormat(1, 1),
					java.util.Date(),
					true,
				),
			)
			Bootstrap.bootStrap()
		}

		/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and this suite reads back. */
		const val LOCATION_DECORATION = 30

		/** The shared payload's sentinel, mirrored so the JVM classification asserts the same value. */
		const val INVALID_VELOCITY = 10000f
	}

	@Test
	fun `moving block twin preserves every source descriptor and adds the velocity target`() {
		for (source in listOf(RenderPipelines.SOLID_BLOCK, RenderPipelines.CUTOUT_BLOCK)) {
			val plain = velocityTwin(source)
			val twin = movingBlockVelocityTwin(plain)

			// The writer twin preserves every source descriptor field through the plain twin, with
			// exactly the writer's velocity shader swapped in for the source fragment shader.
			assertSame(source.vertexShader, twin.vertexShader)
			assertEquals(MovingBlockVelocityRender.FRAGMENT_SHADER, twin.fragmentShader)
			assertEquals(source.shaderDefines, twin.shaderDefines)
			assertSame(source.depthStencilState, twin.depthStencilState)
			assertSame(source.polygonMode, twin.polygonMode)
			assertEquals(source.isCull, twin.isCull)
			assertSame(source.primitiveTopology, twin.primitiveTopology)
			assertSame(source.getVertexFormatBinding(0), twin.getVertexFormatBinding(0))
			assertSame(DefaultVertexFormat.BLOCK, twin.getVertexFormatBinding(0))

			// The source block layouts stay, exactly one BlockVelocityConfig layout is added.
			assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
			for (index in source.bindGroupLayouts.indices) {
				assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
			}
			assertSame(MovingBlockVelocityRender.LAYOUT, twin.bindGroupLayouts.last())

			// Target zero is the source block target - the cutout variant's alpha threshold
			// define and unblended state intact - and target one is exactly the unblended
			// RG16_FLOAT velocity payload.
			val sourceTargets = source.colorTargetStates
			val twinTargets = twin.colorTargetStates
			assertEquals(2, twinTargets.size)
			assertSame(sourceTargets[0], twinTargets[0])
			assertVelocityTarget(twinTargets[1]!!)
		}
	}

	@Test
	fun `moving block twin is cached per source and distinct from every other writer twin`() {
		val source = RenderPipelines.SOLID_BLOCK
		val plain = velocityTwin(source)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/solid_block"), plain.location)
		assertSame(plain, velocityTwin(source), "the plain twin is cached per source pipeline")

		val twin = movingBlockVelocityTwin(plain)
		assertSame(twin, movingBlockVelocityTwin(plain), "the writer twin is cached per plain twin")
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/movingblock/solid_block"), twin.location)

		// The moving-block twin lives at its own location: no collision with the plain twin's
		// velocity/pipeline path or the terrain/entity/weather/particle writer twins.
		assertNotEquals(plain.location, twin.location)
		assertNotEquals(terrainVelocityTwin(plain).location, twin.location)
		assertNotEquals(entityVelocityTwin(plain).location, twin.location)
		assertNotEquals(weatherVelocityTwin(plain).location, twin.location)
		assertNotEquals(particleVelocityTwin(plain).location, twin.location)
	}

	@Test
	fun `only the owned core block pipeline family is eligible for the moving block writer`() {
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_BLOCK))
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.CUTOUT_BLOCK))
		// TRANSLUCENT_BLOCK is block-shaped, but its render type writes the item/entity target,
		// which the render-type eligibility gate separately excludes.
		assertTrue(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.TRANSLUCENT_BLOCK))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.SOLID_TERRAIN))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ENTITY_SOLID))
		assertFalse(MovingBlockVelocityRender.isSupportedPipeline(RenderPipelines.ITEM_CUTOUT))

		// The mapped moving-block render types use exactly the two eligible pipelines on the
		// main target, and the translucent one writes the item/entity target.
		assertSame(RenderPipelines.SOLID_BLOCK, RenderTypes.solidMovingBlock().pipeline())
		assertSame(RenderPipelines.CUTOUT_BLOCK, RenderTypes.cutoutMovingBlock().pipeline())
		assertSame(RenderPipelines.TRANSLUCENT_BLOCK, RenderTypes.translucentMovingBlock().pipeline())
		assertSame(OutputTarget.MAIN_TARGET, RenderTypes.solidMovingBlock().outputTarget())
		assertSame(OutputTarget.MAIN_TARGET, RenderTypes.cutoutMovingBlock().outputTarget())
		assertSame(OutputTarget.ITEM_ENTITY_TARGET, RenderTypes.translucentMovingBlock().outputTarget())

		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/MovingBlockVelocityRender.kt")
		assertTrue(writer.contains("outputTarget() === OutputTarget.MAIN_TARGET"), "the render-type gate must keep non-main targets vanilla")
	}

	@Test
	fun `moving block pass attachments agree with the twin on both routes`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))

		// The vanilla route: the one-attachment draw binds the source block pipeline.
		val oneTarget = com.mojang.blaze3d.systems.RenderPassDescriptor.create({ "Moving block" })
			.withColorAttachment(scene)
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())
		assertEquals(1, oneTarget.colorAttachments().size)
		assertEquals(RenderPipelines.SOLID_BLOCK.colorTargetStates.size, oneTarget.colorAttachments().size)

		// The VELOCITY_MRT route: the two-attachment pass must agree with the two-target twin -
		// exactly the count/format check RenderPass.setPipeline performs on first bind.
		val twin = movingBlockVelocityTwin(velocityTwin(RenderPipelines.SOLID_BLOCK))
		val twoTarget = com.mojang.blaze3d.systems.RenderPassDescriptor.create({ "Moving block velocity" })
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())
			.withDepthAttachment(FakeView(FakeTexture(GpuFormat.D32_FLOAT)), OptionalDouble.empty())

		val attachments = twoTarget.colorAttachments()
		assertEquals(2, attachments.size)
		assertEquals(twin.colorTargetStates.size, attachments.size)
		assertSame(scene, attachments[0]!!.textureView())
		assertSame(velocity, attachments[1]!!.textureView())
		assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared")
		assertEquals(GpuFormat.RG16_FLOAT, attachments[1]!!.textureView().texture().getFormat())
		assertEquals(twin.colorTargetStates[1]!!.format(), attachments[1]!!.textureView().texture().getFormat())
	}

	@Test
	fun `moving block shader preserves the block color output and writes velocity after the final color`() {
		val shader = movingBlockShader()

		// The vanilla core/block fragment body the moving-block pipelines bind, verbatim.
		assertTrue(shader.contains("texture(Sampler0, texCoord0) * vertexColor * ColorModulator"))
		assertTrue(shader.contains("color.a < ALPHA_CUTOUT"))
		assertTrue(shader.contains("discard"))
		assertTrue(shader.contains("fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor)"))

		// The inlined vanilla includes it needs: fog and dynamic transforms (ColorModulator).
		assertTrue(shader.contains("layout(std140) uniform Fog {"))
		assertTrue(shader.contains("layout(std140) uniform DynamicTransforms {"))
		assertTrue(shader.contains("vec4 ColorModulator;"))
		assertTrue(shader.contains("uniform sampler2D Sampler0;"))

		// The payload: the moving-block writer's own BlockVelocityConfig block and the velocity
		// output, with the exact sentinel the entity writer uses.
		assertTrue(shader.contains("layout(std140) uniform BlockVelocityConfig {"))
		assertTrue(shader.contains("mat4 ObjectReprojection;"))
		assertTrue(shader.contains("vec4 VelocityParams;"))
		assertTrue(shader.contains("out vec4 velocityColor;"))
		assertTrue(shader.contains("const float INVALID_VELOCITY = 10000.0;"))

		// Assignment order: glslang emits fragment outputs in first-assignment order and
		// Minecraft rewrites locations by that reflection order, so the final fragColor write
		// must precede the velocityColor write or the near-black payload lands on attachment 0.
		val fragWrite = shader.indexOf("fragColor = apply_fog")
		val velocityWrite = shader.indexOf("velocityColor = invalidPixel")
		assertTrue(fragWrite >= 0, "the final fragColor write must exist")
		assertTrue(velocityWrite >= 0, "the velocityColor write must exist")
		assertTrue(velocityWrite > fragWrite, "velocityColor must be assigned after the final fragColor")
	}

	/**
	 * The compiled seam, exercising the true mechanism: the moving-block shader is self-contained
	 * (it inlines the two vanilla includes instead of #moj_import, which needs Minecraft's
	 * resource preprocessor), so it can be compiled through the same LWJGL Shaderc + spirv-cross
	 * path `GlslCompiler.createIntermediary` and `IntermediaryShaderModule.createFromSpirv` use.
	 * The stage-output reflection list must come back fragColor-first (that list's index is what
	 * the location rewrite writes), and applying the rewrite must leave fragColor on Location 0
	 * (the scene color) and velocityColor on Location 1 (the velocity attachment).
	 */
	@Test
	fun `moving block shader reflects outputs in fragColor-then-velocityColor order through Minecraft's compile path`() {
		val spirv = compileFragmentShader(minecraftFragmentSource(movingBlockShader()))
		try {
			val outputs = reflectOutputs(spirv)
			assertEquals(
				listOf("fragColor", "velocityColor"),
				outputs.map { it.name },
				"the stage-output reflection list must be fragColor first: createFromSpirv rewrites each " +
					"output's Location to its index in this list, so the list order IS the attachment binding",
			)

			val intSpirv = spirv.asIntBuffer()
			outputs.forEachIndexed { index, output -> intSpirv.put(output.locationOffset, index) }
			val rewritten = reflectOutputs(spirv)
			assertEquals(
				mapOf("fragColor" to 0, "velocityColor" to 1),
				rewritten.associate { it.name to it.location },
				"after the createFromSpirv rewrite fragColor must sit on attachment 0 (scene color) and " +
					"velocityColor on attachment 1 (velocity)",
			)
		} finally {
			MemoryUtil.memFree(spirv)
		}
	}

	/**
	 * The moving-block shader derives the previous NDC from the object reprojection - the camera's
	 * jitter-stripped reprojection with the block's piston offset-delta conjugated into it - and
	 * the shared classification collapses reset frames, missing predecessors, and invalid
	 * reprojections to the one representable sentinel instead of the identity-derived zero.
	 */
	@Test
	fun `moving block shader writes object reprojection with the exact sentinel on reset and unknown history`() {
		val shader = movingBlockShader()
		assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"))
		assertTrue(shader.contains("vec4 previous = ObjectReprojection * clip;"))
		assertTrue(shader.contains("previous.xy / previous.w - ndc"))
		assertTrue(shader.contains("VelocityParams.x > 0.5"), "the reset flag drives the per-pixel classification")
		assertTrue(shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"), "every invalid path writes the one sentinel")

		// The classification behavior: a reset frame (identity reprojection) must force the
		// sentinel at every probe - the identity would otherwise read as a still block - and a
		// valid continuous reprojection produces a finite vector strictly below the sentinel.
		for (probe in probes) {
			val reset = classify(Matrix4f(), probe, reset = true)
			assertEquals(INVALID_VELOCITY, reset.x, "reset forces the sentinel at $probe")
			assertEquals(INVALID_VELOCITY, reset.y, "reset forces the sentinel at $probe")
		}

		val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		for (probe in probes) {
			val result = classify(camera.reprojection, probe)
			if (result.x == INVALID_VELOCITY) {
				assertEquals(INVALID_VELOCITY, result.y, "a sentinel carries both components at $probe")
			} else {
				assertTrue(result.x == result.x && result.y == result.y, "motion must not be NaN: $result")
				assertTrue(
					abs(result.x) < INVALID_VELOCITY && abs(result.y) < INVALID_VELOCITY,
					"a valid vector stays below the sentinel: $result",
				)
			}
		}
	}

	@Test
	fun `block ids are collision-free packed positions in their own domain`() {
		// The identity is the game's own packed block position: a pure function of the
		// coordinates, so the same block reuses the same history slot across frames.
		val id = MovingBlockVelocityWriterBindings.blockId(100, 64, -200)
		assertEquals(id, MovingBlockVelocityWriterBindings.blockId(100, 64, -200), "the id is a pure function of the position")
		assertEquals(BlockPos.asLong(100, 64, -200), id, "the id is exactly the game's packed block position")

		// Collision resistance over a volume: every distinct valid position keeps its own slot.
		val volume = HashSet<Long>()
		for (x in -24 until 24) {
			for (y in -8 until 8) {
				for (z in -24 until 24) {
					volume += MovingBlockVelocityWriterBindings.blockId(x, y, z)
				}
			}
		}
		assertEquals(48 * 16 * 48, volume.size, "every position in a volume keeps a distinct id")

		// The extremes of the packed valid-world range - the full 26-bit horizontal and 12-bit
		// vertical ranges across all four horizontal quadrants - stay distinct: a collision here
		// would smear two far-apart blocks' histories through one slot.
		val extremes = listOf(
			BlockPos(0, 0, 0),
			BlockPos(33554431, 2047, 33554431),
			BlockPos(-33554432, -2048, -33554432),
			BlockPos(33554431, -2048, -33554432),
			BlockPos(-33554432, 2047, 33554431),
			BlockPos(-33554432, -2048, 33554431),
			BlockPos(33554431, 2047, -33554432),
		)
		val extremeIds = extremes.map { MovingBlockVelocityWriterBindings.blockId(it.x, it.y, it.z) }
		assertEquals(extremes.size, extremeIds.toSet().size, "valid-world extremes keep distinct ids")
	}

	@Test
	fun `entity and moving-block domains stay disjoint even when ids share a numeric value`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)
		// The block at (0, 7, 0) packs to exactly 7 - the same numeric value as entity id 7 -
		// so any shared-slot implementation would smear the two histories into one.
		val collidingBlockId = MovingBlockVelocityWriterBindings.blockId(0, 7, 0)
		assertEquals(7L, collidingBlockId, "the block domain can carry an entity id's numeric value")

		// Frame one: both domains observe an object carrying the value 7.
		renderFrame(phase)
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureEntity(7, 1.0, 2.0, 3.0)
		phase.captureBlock(collidingBlockId, 4.0, 5.0, 6.0)
		phase.end()

		// Frame two: each domain moves its own object; the displacements stay independent.
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureEntity(7, 1.5, 2.0, 3.0)
		phase.captureBlock(collidingBlockId, 4.5, 5.0, 6.0)
		assertEquals(
			Vector3f(0.5f, 0f, 0f),
			phase.objectMotionDisplacement(7),
			"the entity id keeps its own displacement",
		)
		assertEquals(
			Vector3f(0.5f, 0f, 0f),
			phase.blockMotionDisplacement(collidingBlockId),
			"the block id with the same numeric value keeps its own displacement",
		)
		phase.end()
	}

	@Test
	fun `identity plumbing carries the block id from render state through draw to execute info`() {
		val staged = StagedVertexBuffer({ "moving-block-identity-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()

			val state = MovingBlockRenderState()
			state.blockPos = BlockPos(10, 64, -5)
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)
			MovingBlockVelocityWriterBindings.bindMovingBlock(state, id)
			assertEquals(id, MovingBlockVelocityWriterBindings.movingBlockId(state))

			// The tesselation redirect installs the id on the thread while the block's quads
			// are staged; the draw boundary then binds it onto the staged draw.
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(RenderTypes.solidMovingBlock().pipeline(), true))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			assertEquals(id, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info))
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// The entity predicates never see the moving-block identity: the maps are separate.
			assertNull(EntityVelocityWriterBindings.executeInfoEntityId(info), "a moving-block draw must never read as an entity draw")
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(info), "a moving-block draw must never read as static block geometry")

			// An unbound render state (a falling block) carries no identity at all.
			val falling = MovingBlockRenderState()
			falling.blockPos = BlockPos(10, 64, -5)
			assertNull(MovingBlockVelocityWriterBindings.movingBlockId(falling))
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `moving block then identity-less moving geometry gets fresh draw and keeps batching isolation`() {
		val pipeline = RenderTypes.solidMovingBlock().pipeline()
		val preparedType = PreparedRenderType(
			pipeline,
			OutputTarget.MAIN_TARGET,
			FakeBuffer().slice(),
			ScissorState(),
			emptyList(),
		)
		val drawRenderTypes = mutableListOf<Any>()
		val staged = StagedVertexBuffer({ "moving-block-boundary-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)

			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val firstDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val firstInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(firstDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(firstDraw, firstInfo)
			assertEquals(id, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(firstInfo))
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// An identity-less moving submit (a falling block after the piston block, same render
			// type) must clear both Group.lastDraw and the reorder lookup before staging, so it
			// can never consolidate into the moving block's draw or inherit its id.
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, false))
			assertTrue(MovingBlockVelocityWriterBindings.suppressConsolidation(), "transition draw must defeat reorder reuse")
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			drawRenderTypes += preparedType
			val blockDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(blockDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertNull(MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(blockInfo), "identity-less moving geometry must not inherit the piston block id")
			MovingBlockVelocityWriterBindings.endDraw()

			// After the transition, unrelated ineligible geometry gets vanilla consolidation
			// again - its own draw, not the moving block's.
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(pipeline, false))
			assertFalse(MovingBlockVelocityWriterBindings.suppressConsolidation())
			assertEquals(1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			MovingBlockVelocityWriterBindings.endDraw()

			// A later piston block starts yet another fresh draw with its own id.
			val secondId = MovingBlockVelocityWriterBindings.blockId(11, 64, -5)
			MovingBlockVelocityWriterBindings.beginMovingBlock(secondId)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(pipeline, true))
			assertEquals(-1, MovingBlockVelocityWriterBindings.consolidationIndex(drawRenderTypes, preparedType))
			val secondDraw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val secondInfo = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(secondDraw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(secondDraw, secondInfo)
			assertEquals(secondId, MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(secondInfo))
			assertNotEquals(firstDraw, secondDraw, "each eligible moving block starts a distinct staged draw")
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `ineligible moving block paths keep vanilla batching and bind no identity`() {
		val staged = StagedVertexBuffer({ "moving-block-ineligible-test" }, 256)
		try {
			MovingBlockVelocityWriterBindings.clearFrame()
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)

			// Ineligible draw with a context installed (the translucent layer or a closed phase):
			// no fresh boundary, no identity bound, vanilla consolidation stays intact.
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderTypes.translucentMovingBlock().pipeline(), false))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			assertNull(MovingBlockVelocityWriterBindings.executeInfoMovingBlockId(info), "an ineligible draw binds no identity")
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			// Consecutive ineligible draws regain vanilla reorder consolidation.
			val renderTypes = mutableListOf<Any>()
			val prepared = Any()
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, false))
			renderTypes += prepared
			MovingBlockVelocityWriterBindings.endDraw()
			assertFalse(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, false))
			assertEquals(0, MovingBlockVelocityWriterBindings.consolidationIndex(renderTypes, prepared))
			MovingBlockVelocityWriterBindings.endDraw()
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `governing marker hands the reorder decision to the machine that owns the group`() {
		MovingBlockVelocityWriterBindings.clearFrame()
		try {
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "a fresh frame has no governing machine")

			// The mixin's rule, driven with its boundary flags: the moving-block machine governs
			// from its own fresh boundary (an eligible draw or the eligibility-ending transition)
			// until the entity machine takes a fresh boundary of its own.
			var movingBoundary = true
			var entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "a moving-block fresh boundary takes the decision")

			// An entity fresh boundary outranks a stale moving-block history.
			movingBoundary = false
			entityBoundary = true
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "an entity fresh boundary outranks the stale moving-block history")

			// A moving-block fresh boundary takes the decision back...
			movingBoundary = true
			entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "a moving-block fresh boundary takes the decision back")

			// ...and consecutive ineligible draws stay with the governing machine (the
			// moving-block post-eligible bookkeeping owns them).
			movingBoundary = false
			entityBoundary = false
			MovingBlockVelocityWriterBindings.setGoverning(
				movingBoundary || (MovingBlockVelocityWriterBindings.isGoverning() && !entityBoundary),
			)
			assertTrue(MovingBlockVelocityWriterBindings.isGoverning(), "consecutive ineligible draws stay with the governing machine")
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			assertFalse(MovingBlockVelocityWriterBindings.isGoverning(), "clearFrame drops the marker with the frame")
		}
	}

	@Test
	fun `moving block reprojection composes the offset delta and invalidates without a predecessor`() {
		val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		val viewProjection = Matrix4f()
		val jitter = DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720))

		val delta = Vector3f(0.25f, 0f, -0.5f)
		assertEquals(
			objectReprojection(camera, viewProjection, jitter, delta),
			MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, delta),
			"the writer's reprojection is exactly the object reprojection with the offset delta",
		)

		// The exact reset/unknown-history classification: a missing or reset frame, a missing
		// view-projection or jitter, or a missing displacement (first observation, eviction,
		// reset history) all mean the invalid sentinel.
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(null, viewProjection, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera.copy(reset = true), viewProjection, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, null, jitter, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, null, delta))
		assertNull(MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, null))

		// A piston at rest (zero offset delta) collapses to the camera reprojection itself.
		assertEquals(
			camera.reprojection,
			MovingBlockVelocityRender.movingBlockReprojection(camera, viewProjection, jitter, Vector3f(0f, 0f, 0f)),
		)
	}

	@Test
	fun `capture seam records block position plus piston offset and the retracting base stands still`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)
		val blockPos = BlockPos(100, 64, -200)
		val basePos = BlockPos(99, 64, -200)
		val blockId = MovingBlockVelocityWriterBindings.blockId(blockPos.x, blockPos.y, blockPos.z)
		val baseId = MovingBlockVelocityWriterBindings.blockId(basePos.x, basePos.y, basePos.z)

		// The capture seam's math: the moving block draws at its baked position plus the
		// piston's current interpolated offset; the retracting base draws at its baked position
		// without any offset translate.
		val capture = source("src/main/kotlin/me/snowmii/dlss/mrt/MovingBlockVelocityRender.kt")
		assertTrue(capture.contains("pos.x + xOffset"))
		assertTrue(capture.contains("pos.x.toDouble()"), "the retracting base captures its baked position with no offset")

		// Frame one: first observation - the draw path reads no displacement and must write the
		// unknown-history sentinel.
		renderFrame(phase)
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.5, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.captureBlock(baseId, basePos.x.toDouble(), basePos.y.toDouble(), basePos.z.toDouble())
		assertNull(phase.blockMotionDisplacement(blockId), "a first observation has no predecessor: the sentinel")
		assertNull(phase.blockMotionDisplacement(baseId), "a first observation has no predecessor: the sentinel")
		phase.end()

		// Frame two: the piston moved on by a quarter block - the displacement is exactly the
		// offset delta, while the base keeps zero displacement.
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.captureBlock(baseId, basePos.x.toDouble(), basePos.y.toDouble(), basePos.z.toDouble())
		val delta = checkNotNull(phase.blockMotionDisplacement(blockId))
		assertEquals(0.25f, delta.x, 1e-6f, "the offset delta is the displacement")
		assertEquals(0f, delta.y, 1e-6f)
		assertEquals(0f, delta.z, 1e-6f)
		val baseDelta = checkNotNull(phase.blockMotionDisplacement(baseId))
		assertEquals(0f, baseDelta.x, 1e-6f, "the retracting base has zero displacement")
		assertEquals(0f, baseDelta.y, 1e-6f)
		assertEquals(0f, baseDelta.z, 1e-6f)

		// The block and entity id spaces never mix: a positive entity id keeps its own history.
		phase.captureEntity(7, 1.0, 2.0, 3.0)
		assertNull(phase.objectMotionDisplacement(7), "an entity id is a separate history slot")
		assertEquals(0.25f, checkNotNull(phase.blockMotionDisplacement(blockId)).x, 1e-6f, "the block history is untouched by the entity capture")
		phase.end()

		// A frame that never completed a DLSS evaluation breaks the chain: the next observation
		// is an unknown history again.
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		phase.end()
		val abandoned = phase(runtime, evaluate = false)
		renderFrame(abandoned)
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.captureBlock(blockId, blockPos.x + 0.75, blockPos.y.toDouble(), blockPos.z.toDouble())
		assertNull(
			phase.blockMotionDisplacement(blockId),
			"a vanilla/abandoned frame in between resets the history, so the next observation writes the sentinel",
		)
		phase.end()

		// The writer reads the same history the capture seam fills: the reset makes the first
		// observation classify invalid through the writer's own classification seam.
		assertNull(
			MovingBlockVelocityRender.movingBlockReprojection(
				phase.activeMotion,
				phase.currentViewProjection,
				phase.activeJitter,
				phase.blockMotionDisplacement(blockId),
			),
			"after the reset, the first observation still classifies invalid",
		)
		phase.end()
	}

	@Test
	fun `eligible open velocity-mrt phase admits the writer control seam and ineligible routes fall through`() {
		val runtime = dlssRuntime()
		val phase = phase(runtime)
		val staged = StagedVertexBuffer({ "moving-block-control-test" }, 256)
		try {
			val id = MovingBlockVelocityWriterBindings.blockId(10, 64, -5)
			renderFrame(phase)
			phase.prepare(true, mainTarget, camera())
			phase.begin(true, mainTarget)

			// A bound identity on an owned main-target solid/cutout pipeline is eligible.
			MovingBlockVelocityWriterBindings.clearFrame()
			val state = MovingBlockRenderState()
			state.blockPos = BlockPos(10, 64, -5)
			MovingBlockVelocityWriterBindings.bindMovingBlock(state, id)
			MovingBlockVelocityWriterBindings.beginMovingBlock(id)
			assertTrue(MovingBlockVelocityWriterBindings.beginDraw(RenderPipelines.SOLID_BLOCK, true))
			val draw = staged.appendDraw(DefaultVertexFormat.BLOCK, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			MovingBlockVelocityWriterBindings.bindDraw(draw)
			MovingBlockVelocityWriterBindings.bindExecuteInfo(draw, info)
			MovingBlockVelocityWriterBindings.endDraw()
			MovingBlockVelocityWriterBindings.endMovingBlock()

			val solid = PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(MovingBlockVelocityRender.canDraw(solid, info, phase))
			val cutout = PreparedRenderType(RenderPipelines.CUTOUT_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertTrue(MovingBlockVelocityRender.canDraw(cutout, info, phase))

			// The translucent layer writes the item/entity target: never a velocity route.
			val translucent = PreparedRenderType(RenderPipelines.TRANSLUCENT_BLOCK, OutputTarget.ITEM_ENTITY_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(MovingBlockVelocityRender.canDraw(translucent, info, phase))

			// Unsupported pipelines fall through to their exact source draw.
			val terrain = PreparedRenderType(RenderPipelines.SOLID_TERRAIN, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList())
			assertFalse(MovingBlockVelocityRender.canDraw(terrain, info, phase))

			// A draw without a bound identity (a falling block) falls through even on the
			// eligible pipeline.
			val fallingInfo = emptyExecuteInfo()
			assertFalse(MovingBlockVelocityRender.canDraw(solid, fallingInfo, phase))

			// The draw replacement falls through without a live ClientRuntime phase: the phase
			// gate answers false before anything can touch a device, so the draw never throws.
			assertFalse(
				MovingBlockVelocityRender.draw(solid, info),
				"headless: the draw must answer false at the phase gate, never throw",
			)
		} finally {
			MovingBlockVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `vanilla camera-only and non-open phases keep the moving block route unchanged`() {
		// Camera-only: the first foreign pipeline latches the fallback route, so the open phase
		// offers no velocity view and the writer answers false - the exact source draw survives.
		val cameraOnly = dlssRuntime()
		cameraOnly.observeWorldPipeline(
			MotionVectorPipeline(
				"example:pipeline/waving_terrain",
				listOf(MotionVectorShader("example:core/waving_terrain", "example")),
			),
		)
		assertEquals(MotionVectorRoute.CAMERA_ONLY, cameraOnly.motionVectorRoute)
		val cameraOnlyPhase = phase(cameraOnly)
		val info = emptyExecuteInfo()
		assertFalse(
			MovingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				cameraOnlyPhase,
			),
			"a camera-only phase offers no velocity view",
		)
		assertFalse(MovingBlockVelocityRender.draw(PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()), info))

		// Vanilla: a session without DLSS keeps the moving-block draw on its exact source route.
		val vanillaSession = DlssSession(
			DlssStartupConfig(
				enabled = false,
				qualityMode = SRMode.QUALITY,
				outputDimensions = output,
				sdkPath = null,
				nativeLibraryPath = null,
				dataPath = null,
				warnings = emptyList(),
			),
		)
		val vanillaPhase = phase(
			RenderRuntime(
				session = vanillaSession,
				sceneTarget = SceneTarget(
					allocate = { width, height -> FakeTarget(width, height) },
					release = { (it as FakeTarget).releases++ },
					allocateVelocity = { _, _ -> null },
				),
				startup = { null },
			),
		)
		assertFalse(
			MovingBlockVelocityRender.canDraw(
				PreparedRenderType(RenderPipelines.SOLID_BLOCK, OutputTarget.MAIN_TARGET, FakeBuffer().slice(), ScissorState(), emptyList()),
				info,
				vanillaPhase,
			),
		)
	}

	@Test
	fun `piston renderer classification is exact and the dispatcher handler passes the mapped piston submit through`() {
		// Exact membership: the mapped renderer is the piston family; a subclass is not.
		assertTrue(MovingBlockVelocityRender.isPistonHeadRenderer(PistonHeadRenderer::class.java))
		assertFalse(MovingBlockVelocityRender.isPistonHeadRenderer(DynamicPistonSubclass::class.java))

		// The dispatcher handler itself: with no eligible phase the capture seam binds nothing
		// and the piston submit runs exactly once, like vanilla.
		val renderer = PistonHeadRenderer()
		val state = PistonHeadRenderState()
		state.xOffset = 0.25f
		state.yOffset = 0f
		state.zOffset = 0f
		val block = MovingBlockRenderState()
		block.blockPos = BlockPos(10, 64, -5)
		state.block = block

		var submitCalls = 0
		val collector = Proxy.newProxyInstance(
			SubmitNodeCollector::class.java.classLoader,
			arrayOf(SubmitNodeCollector::class.java),
			java.lang.reflect.InvocationHandler { _, method, _ ->
				if (method.name == "submitMovingBlock") submitCalls++
				null
			},
		) as SubmitNodeCollector

		dispatcherHandler().invoke(mixinInstance(), renderer, state, PoseStack(), collector, null)
		assertEquals(1, submitCalls, "the piston submit runs exactly once")
		assertNull(
			MovingBlockVelocityWriterBindings.movingBlockId(block),
			"without an eligible velocity phase nothing is bound and the source route is untouched",
		)
	}

	@Test
	fun `moving block mixin seams map the mapped tesselate and draw descriptors and are registered`() {
		val mixin = source("src/main/java/me/snowmii/dlss/mixin/MovingBlockFeatureRendererMotionMixin.java")
		val mixins = source("src/main/resources/mc-dlss.mixins.json")

		// The tesselation redirect matches the mapped ModelBlockRenderer.tesselateBlock call
		// inside MovingBlockFeatureRenderer.buildGroup and brackets it with the block id.
		assertTrue(mixin.contains("@Mixin(MovingBlockFeatureRenderer.class)"))
		assertTrue(mixin.contains("method = \"buildGroup\""))
		assertTrue(mixin.contains("beginMovingBlock(id)"))
		assertTrue(mixin.contains("endMovingBlock()"))
		assertTrue(mixin.contains("movingBlockId(moving)"), "the id comes from the bound render state, never from a thread context")

		val mixinClass = Class.forName("me.snowmii.dlss.mixin.MovingBlockFeatureRendererMotionMixin")
		val handler = mixinClass.getDeclaredMethod(
			"mcDlssBeginMovingBlockDraw",
			net.minecraft.client.renderer.block.ModelBlockRenderer::class.java,
			net.minecraft.client.renderer.block.BlockQuadOutput::class.java,
			Float::class.javaPrimitiveType,
			Float::class.javaPrimitiveType,
			Float::class.javaPrimitiveType,
			net.minecraft.client.renderer.block.BlockAndTintGetter::class.java,
			BlockPos::class.java,
			net.minecraft.world.level.block.state.BlockState::class.java,
			net.minecraft.client.renderer.block.dispatch.BlockStateModel::class.java,
			Long::class.javaPrimitiveType,
		)
		val redirect = requireNotNull(handler.getAnnotation(org.spongepowered.asm.mixin.injection.Redirect::class.java))
		assertTrue(redirect.method.contentEquals(arrayOf("buildGroup")))
		assertEquals("INVOKE", redirect.at.value)
		assertEquals(
			"Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(" +
				"Lnet/minecraft/client/renderer/block/BlockQuadOutput;" +
				"FFF" +
				"Lnet/minecraft/client/renderer/block/BlockAndTintGetter;" +
				"Lnet/minecraft/core/BlockPos;" +
				"Lnet/minecraft/world/level/block/state/BlockState;" +
				"Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;" +
				"J)V",
			redirect.at.target,
		)

		// The other mixin seams call the moving-block writer and bindings, and the config
		// registers the new mixin class.
		val prepared = source("src/main/java/me/snowmii/dlss/mixin/PreparedRenderTypeMotionMixin.java")
		assertTrue(prepared.contains("MovingBlockVelocityRender.draw"), "the prepared draw dispatch consults the moving-block writer")
		val staged = source("src/main/java/me/snowmii/dlss/mixin/StagedVertexBufferMotionMixin.java")
		assertTrue(staged.contains("MovingBlockVelocityWriterBindings.bindDraw(draw)"))
		assertTrue(staged.contains("MovingBlockVelocityWriterBindings.bindExecuteInfo"))
		assertTrue(staged.contains("MovingBlockVelocityWriterBindings.clearFrame()"))
		val feature = source("src/main/java/me/snowmii/dlss/mixin/RenderTypeFeatureRendererMotionMixin.java")
		assertTrue(feature.contains("MovingBlockVelocityWriterBindings.beginDraw(renderType)"))
		assertTrue(feature.contains("MovingBlockVelocityWriterBindings.endDraw()"))
		assertTrue(feature.contains("setGoverning("), "the mixin records which batching machine governs the reorder decision")
		assertTrue(feature.contains("isGoverning()"), "the reorder redirect consults the governing machine")
		val dispatcher = source("src/main/java/me/snowmii/dlss/mixin/BlockEntityRenderDispatcherMotionMixin.java")
		assertTrue(dispatcher.contains("isPistonHeadRenderer(renderer.getClass())"))
		assertTrue(dispatcher.contains("capturePiston(pistonState)"))

		val registered = JsonParser.parseString(mixins).asJsonObject.getAsJsonArray("client").map { it.asString }
		assertTrue("MovingBlockFeatureRendererMotionMixin" in registered)
	}

	@Test
	fun `moving block writer is registered and reachable from the mixin through the variant surface`() {
		val variant = source("src/main/kotlin/me/snowmii/dlss/mrt/VelocityPipelineVariant.kt")
		assertTrue(variant.contains("fun movingBlockVelocityTwin(plainTwin: RenderPipeline)"))
		assertTrue(variant.contains("movingBlockVelocityTwins.computeIfAbsent"))
		assertTrue(variant.contains("withFragmentShader(MovingBlockVelocityRender.FRAGMENT_SHADER)"))
		assertTrue(variant.contains("withBindGroupLayout(MovingBlockVelocityRender.LAYOUT)"))

		val writer = source("src/main/kotlin/me/snowmii/dlss/mrt/MovingBlockVelocityRender.kt")
		assertTrue(writer.contains("BlockVelocityConfig"), "the payload block, uniform name, and layout are the writer's own")
		assertTrue(writer.contains("movingBlockVelocityTwin(velocityTwin(prepared.pipeline()))"))
		assertTrue(writer.contains("blockMotionDisplacement(blockId)"), "the writer reads the shared object history by the block id")
		assertTrue(writer.contains("movingBlockReprojection"), "the classification seam feeds the payload")

		// The draw failure decision: every gate, resource, twin, and descriptor input is
		// preflighted before the writer takes ownership (the command encoder), and a failure
		// after ownership commits the draw instead of falling through to vanilla - there is no
		// getOrDefault(false) fallback left anywhere in the owned region.
		val draw = writer.substring(writer.indexOf("fun draw("))
		assertTrue(draw.contains("createCommandEncoder()"), "the ownership boundary is the device encoder")
		assertTrue(
			draw.indexOf("createCommandEncoder()") > draw.indexOf("movingBlockVelocityTwin(velocityTwin(prepared.pipeline()))"),
			"the twin is preflighted before ownership",
		)
		assertTrue(
			draw.indexOf("createCommandEncoder()") > draw.indexOf("runCatching { buffer() }"),
			"the payload buffer is preflighted before ownership",
		)
		assertTrue(draw.contains("catch"), "a failure after ownership is caught")
		assertTrue(draw.contains("committedDrawFailure(failure)"), "the committed failure is the documented no-replay disposition")
		assertTrue(
			draw.contains("committedDrawFailure(failure)") && draw.substring(draw.indexOf("committedDrawFailure(failure)")).contains("\n\t\t\ttrue"),
			"the committed failure answers true so the source draw is never replayed",
		)
		assertFalse(draw.contains("getOrDefault(false)"), "the owned draw never falls back to vanilla after partial writer work")

		// The writer's payload shape is the same std140 mat4 + vec4 the entity writer uses.
		assertEquals("BlockVelocityConfig", MovingBlockVelocityRender.UNIFORM_NAME)
		assertEquals("core/velocity_block", MovingBlockVelocityRender.SHADER_PATH)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "core/velocity_block"), MovingBlockVelocityRender.FRAGMENT_SHADER)
		assertEquals(EntityVelocityUniforms.UBO_SIZE, MovingBlockVelocityRender.UBO_SIZE)
	}

	private fun source(path: String) = repository.resolve(path).readText()

	private fun movingBlockShader(): String = repository
		.resolve("src/main/resources/assets/mc-dlss/shaders/core/velocity_block.fsh")
		.readText()

	/**
	 * Compiles a fragment shader exactly the way `GlslCompiler.createIntermediary` does: the
	 * global defines injected after the `#version` line, then shaderc with the Vulkan 1.2 target
	 * and automatic location/uniform mapping. Returns a copy of the SPIR-V bytes so the caller
	 * owns the buffer.
	 */
	private fun compileFragmentShader(source: String): ByteBuffer {
		val compiler = Shaderc.shaderc_compiler_initialize()
		val options = Shaderc.shaderc_compile_options_initialize()
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
			Shaderc.shaderc_compile_options_set_generate_debug_info(options)
			Shaderc.shaderc_compile_options_set_optimization_level(options, 0)

			MemoryStack.stackPush().use {
				val sourceBuffer = MemoryUtil.memUTF8(source, false)
				val filenameBuffer = MemoryUtil.memUTF8("velocity_block.fsh")
				val entrypointBuffer = MemoryUtil.memUTF8("main")
				try {
					val result = Shaderc.shaderc_compile_into_spv(
						compiler, sourceBuffer, Shaderc.shaderc_fragment_shader, filenameBuffer, entrypointBuffer, options,
					)
					try {
						val status = Shaderc.shaderc_result_get_compilation_status(result)
						check(status == 0) { "shaderc failed (status $status): ${Shaderc.shaderc_result_get_error_message(result)}" }
						val compiled = checkNotNull(Shaderc.shaderc_result_get_bytes(result)) { "shaderc returned no SPIR-V bytes" }
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

	/**
	 * The exact preprocessed source `compileShader` hands `createIntermediary`: the global
	 * defines injected right after the `#version` line. They alias vertex-only builtins and are
	 * inert for fragment output emission, but keeping them makes the compiled module match the
	 * game's byte-for-byte.
	 */
	private fun minecraftFragmentSource(source: String): String {
		val versionLineEnd = source.indexOf('\n')
		check(versionLineEnd >= 0) { "shader source must start with a #version line" }
		return source.substring(0, versionLineEnd + 1) +
			"#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n#line 1 0\n" +
			source.substring(versionLineEnd + 1)
	}

	/** A stage output as spirv-cross reflects it, plus the byte offset of its Location decoration. */
	private class OutputReflection(val name: String, val locationOffset: Int, val location: Int)

	/**
	 * Reflects the stage outputs of a compiled module the way `createFromSpirv` does: parse the
	 * SPIR-V, list STAGE_OUTPUT resources, and read each output's Location decoration value and
	 * the binary word offset where that decoration lives. The list comes back in module
	 * declaration order, which is glslang's first-assignment order inside `main()`.
	 */
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
				spvcCheck(Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPointer), "spvc_compiler_create_shader_resources")
				val listPointer = stack.callocPointer(1)
				val countPointer = stack.callocPointer(1)
				spvcCheck(
					Spvc.spvc_resources_get_resource_list_for_type(
						resourcesPointer.get(0), Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, listPointer, countPointer,
					),
					"spvc_resources_get_resource_list_for_type",
				)
				val resources = SpvcReflectedResource.create(listPointer.get(0), countPointer.get(0).toInt())
				val offsetBuffer = stack.callocInt(1)
				val outputs = ArrayList<OutputReflection>(resources.capacity())
				for (index in 0 until resources.capacity()) {
					val resource = resources.get(index)
					val name = resource.nameString()
					check(Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), LOCATION_DECORATION, offsetBuffer)) {
						"no Location decoration on $name"
					}
					outputs.add(
						OutputReflection(
							name = name,
							locationOffset = offsetBuffer.get(0),
							location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), LOCATION_DECORATION),
						),
					)
				}
				return outputs
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

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	/** The mixin redirect handler itself: executable proof of the piston branch without a live Mixin transform. */
	private fun dispatcherHandler(): Method {
		val mixinClass = Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin")
		val handler = mixinClass.getDeclaredMethod(
			"mcDlssSubmitBlockEntity",
			BlockEntityRenderer::class.java,
			BlockEntityRenderState::class.java,
			PoseStack::class.java,
			SubmitNodeCollector::class.java,
			CameraRenderState::class.java,
		)
		handler.isAccessible = true
		return handler
	}

	/** The untransformed mixin class is a plain object at test runtime: the receiver for the handler. */
	private fun mixinInstance(): Any =
		Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin").getDeclaredConstructor().newInstance()

	private fun renderFrame(phase: WorldPhase) {
		phase.prepare(true, mainTarget, camera())
		phase.begin(true, mainTarget)
		phase.end()
	}

	private fun phase(runtime: RenderRuntime, evaluate: Boolean = true) = WorldPhase(
		runtime = runtime,
		present = { _, _ -> },
		onWorldTargetChanged = {},
		evaluateFrame = { _, _, _, _, _, _ -> evaluate },
	)

	private fun dlssRuntime(): RenderRuntime {
		val session = DlssSession(config()).also { check(it.markReadyAfterNativeStartup()) }
		return RenderRuntime(
			session = session,
			sceneTarget = SceneTarget(
				allocate = { width, height -> FakeTarget(width, height) },
				release = { (it as FakeTarget).releases++ },
				allocateVelocity = { width, height -> FakeTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) },
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

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	private val output = DlssDimensions(2560, 1440)
	private val mainTarget = FakeTarget(2560, 1440)

	/** Sample points spread across the frustum, from near the eye to the far plane. */
	private val probes = listOf(
		Vector4f(0f, 0f, 0.95f, 1f),
		Vector4f(0.4f, 0.3f, 0.6f, 1f),
		Vector4f(-0.5f, 0.2f, 0.25f, 1f),
		Vector4f(0.1f, -0.4f, 0.05f, 1f),
	)

	/** A foreign piston renderer subclass: exact membership keeps it on the vanilla route. */
	private class DynamicPistonSubclass : PistonHeadRenderer()

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
