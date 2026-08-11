package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.Optional
import java.util.OptionalDouble
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.chunk.ChunkSectionLayer
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.lwjgl.system.MemoryUtil

/**
 * The contracts every velocity-MRT writer shares, proven once against the seam that owns them.
 *
 * A writer twin is a source pipeline's full descriptor plus one unblended RG16_FLOAT payload
 * target, the writer's velocity fragment shader, and the writer's payload layout. That shape is
 * one function - [writerTwin] - so it is proven here across every writer and every source
 * pipeline the writers bind, rather than re-proven inside each writer's own suite. The writer
 * suites keep only what is theirs: the payload they compute and the seam they route through.
 *
 * Two failure modes drive these tests. A descriptor field dropped in the clone silently changes
 * how a world pass renders - a lost blend function, a lost depth-write state, a lost vertex
 * binding. And an output declared in the wrong order in a velocity shader puts the near-black
 * motion payload on color attachment 0, because `IntermediaryShaderModule.createFromSpirv`
 * rewrites each output's Location to its index in the reflection list.
 */
class VelocityWriterContractTest {
	/** Every writer with the source pipelines it is bound for in production. */
	private val writers: Map<VelocityWriter, List<RenderPipeline>> = mapOf(
		VelocityWriter.TERRAIN to ChunkSectionLayer.entries.map { it.pipeline() } + RenderPipelines.WIREFRAME,
		VelocityWriter.ENTITY to listOf(
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
		),
		VelocityWriter.WEATHER to listOf(RenderPipelines.WEATHER_DEPTH_WRITE, RenderPipelines.WEATHER_NO_DEPTH_WRITE),
		VelocityWriter.PARTICLE to listOf(RenderPipelines.OPAQUE_PARTICLE, RenderPipelines.TRANSLUCENT_PARTICLE),
		VelocityWriter.MOVING_BLOCK to listOf(RenderPipelines.SOLID_BLOCK, RenderPipelines.CUTOUT_BLOCK),
		VelocityWriter.CRUMBLING to listOf(RenderPipelines.CRUMBLING),
		VelocityWriter.CLOUD to listOf(RenderPipelines.CLOUDS, RenderPipelines.FLAT_CLOUDS),
	)

	/**
	 * The clone contract, across every writer and source: every descriptor field the source
	 * carries survives, exactly one velocity target is added, and the writer's shader and layout
	 * are the only things swapped in. A field dropped here changes how the world pass renders.
	 */
	@TestFactory
	fun `a writer twin is the source descriptor plus the velocity target, shader, and layout`() =
		eachWriterPipeline { writer, source ->
			val twin = writerTwin(source, writer)

			assertSame(source.vertexShader, twin.vertexShader)
			assertEquals(source.shaderDefines, twin.shaderDefines)
			assertSame(source.depthStencilState, twin.depthStencilState)
			assertSame(source.polygonMode, twin.polygonMode)
			assertEquals(source.isCull, twin.isCull)
			assertSame(source.primitiveTopology, twin.primitiveTopology)

			// All sixteen binding slots map exactly, sparse nulls included: the cloud pipelines
			// bind none at all (their geometry comes from CloudFaces and gl_VertexID).
			assertEquals(source.getVertexFormatBindings().size, twin.getVertexFormatBindings().size)
			for (index in 0 until twin.getVertexFormatBindings().size) {
				assertSame(source.getVertexFormatBinding(index), twin.getVertexFormatBinding(index), "binding $index")
			}

			// Color target zero is the source's own, blend function intact; target one is the payload.
			val twinTargets = twin.colorTargetStates
			assertEquals(2, twinTargets.size, "a writer twin has exactly the source target plus the payload")
			assertSame(source.colorTargetStates[0], twinTargets[0])
			assertVelocityTarget(twinTargets[1]!!)

			// The source layouts stay in place - the source binds resolve by name exactly as
			// before - and exactly one payload layout is appended for the velocity shader's block.
			assertEquals(source.bindGroupLayouts.size + 1, twin.bindGroupLayouts.size)
			for (index in source.bindGroupLayouts.indices) {
				assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
			}
			assertSame(writer.layout, twin.bindGroupLayouts.last())
			assertSame(writer.fragmentShader, twin.fragmentShader)
		}

	/**
	 * Vulkan's lazy-compile cache is an `IdentityHashMap` keyed by pipeline, so a twin rebuilt per
	 * bind would recompile per bind. Every twin is therefore one instance per source, and every
	 * twin lives at its own location so it never collides with the plain twin or another writer's.
	 */
	@TestFactory
	fun `twins are cached per source and every twin owns a distinct location`() =
		eachWriterPipeline { writer, source ->
			val twin = writerTwin(source, writer)
			assertSame(twin, writerTwin(source, writer), "the writer twin is cached per source pipeline")
			assertSame(velocityTwin(source), velocityTwin(source), "the plain twin is cached per source pipeline")

			val name = source.location.path.removePrefix("pipeline/")
			assertEquals(
				Identifier.fromNamespaceAndPath("mc-dlss", "velocity/${writer.segment}/$name"),
				twin.location,
			)
			assertNotEquals(velocityTwin(source).location, twin.location)
			for (other in VelocityWriter.entries - writer) {
				assertNotEquals(writerTwin(source, other).location, twin.location, "collides with ${other.name}")
			}
		}

	/**
	 * The plain twin is the M-4 descriptor contract the writer twins are built on: the source
	 * descriptor with the payload target added and the *source* fragment shader kept, at its own
	 * `velocity/pipeline/` location - which stays distinct even for an mc-dlss source.
	 */
	@Test
	fun `the plain twin keeps the source shader and takes its own location`() {
		val source = RenderPipelines.WEATHER_DEPTH_WRITE
		val plain = velocityTwin(source)

		assertSame(source.fragmentShader, plain.fragmentShader)
		assertEquals(source.bindGroupLayouts, plain.bindGroupLayouts)
		assertEquals(2, plain.colorTargetStates.size)
		assertVelocityTarget(plain.colorTargetStates[1]!!)
		assertEquals(
			Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/weather_depth_write"),
			plain.location,
		)
		assertNotEquals(source.location, velocityTwin(plain).location, "an mc-dlss source still gets a distinct location")
	}

	/**
	 * The pass a writer's redirect builds must agree with the twin it binds: `setPipeline` checks
	 * the attachment count and formats against the pipeline's color targets on first bind, so a
	 * two-target twin needs a two-attachment pass whose second attachment is the RG16_FLOAT
	 * payload, never cleared.
	 */
	@Test
	fun `the two-attachment velocity pass agrees with the twin's color targets`() {
		val scene = FakeView(FakeTexture(GpuFormat.RGBA8_UNORM))
		val velocity = FakeView(FakeTexture(GpuFormat.RG16_FLOAT))
		val depth = FakeView(FakeTexture(GpuFormat.D32_FLOAT))

		val descriptor = com.mojang.blaze3d.systems.RenderPassDescriptor.create({ "velocity" })
			.withColorAttachment(scene)
			.withColorAttachment(velocity, Optional.empty())
			.withDepthAttachment(depth, OptionalDouble.empty())

		val attachments = descriptor.colorAttachments()
		assertEquals(2, attachments.size)
		assertSame(velocity, attachments[1]!!.textureView())
		assertTrue(attachments[1]!!.clearValue().isEmpty(), "the velocity attachment is never cleared mid-frame")

		for ((writer, sources) in writers) {
			for (source in sources) {
				val twin = writerTwin(source, writer)
				assertEquals(attachments.size, twin.colorTargetStates.size, "${writer.name} on ${source.location}")
				assertEquals(twin.colorTargetStates[1]!!.format(), attachments[1]!!.textureView().texture().getFormat())
			}
		}
	}

	/**
	 * The compiled seam. `IntermediaryShaderModule.createFromSpirv` rewrites each stage output's
	 * Location decoration to that output's index in the spirv-cross reflection list, and glslang
	 * emits that list in first-assignment order inside `main()`. So a velocity shader that writes
	 * its payload before its final scene color puts the near-black motion vector on color
	 * attachment 0 and the scene color on the velocity attachment.
	 *
	 * Compiling each shader through the same Shaderc + spirv-cross path the game uses catches
	 * that, and catches a shader that no longer compiles at all - both without a live device.
	 */
	@TestFactory
	fun `every velocity shader compiles and reflects fragColor before velocityColor`(): List<DynamicTest> =
		VelocityWriter.entries
			.map { it.fragmentShader.path.removePrefix("core/") }
			.distinct()
			.map { name ->
				DynamicTest.dynamicTest(name) {
					val spirv = compileFragmentShader(velocityShaderSource(name), "$name.fsh")
					try {
						val outputs = reflectOutputs(spirv)
						assertEquals(
							listOf("fragColor", "velocityColor"),
							outputs.map { it.name },
							"the reflection list order IS the attachment binding: createFromSpirv rewrites " +
								"each output's Location to its index in this list",
						)

						// Apply that rewrite and read the decorations back, exactly as the game does.
						val intSpirv = spirv.asIntBuffer()
						outputs.forEachIndexed { index, OUTPUT_DIMENSIONS -> intSpirv.put(OUTPUT_DIMENSIONS.locationOffset, index) }
						assertEquals(
							mapOf("fragColor" to 0, "velocityColor" to 1),
							reflectOutputs(spirv).associate { it.name to it.location },
							"scene color must land on attachment 0 and the payload on attachment 1",
						)
					} finally {
						MemoryUtil.memFree(spirv)
					}
				}
			}

	/**
	 * The payload formula and its per-pixel classification, held identical across every velocity
	 * shader. GLSL cannot be executed here - there is no device - and the shader asset is the only
	 * boundary this behavior has, so these are assertions on the asset itself, kept to the few
	 * lines that carry the contract rather than to the shader's shape.
	 *
	 * Two failure modes: a pixel with no usable predecessor that writes an identity-derived zero
	 * reads to DLSS as "this surface stood still", which ghosts; and a classification that runs
	 * *after* the perspective divide lets a point behind the previous eye plane mirror into a
	 * plausible-looking but wrong vector, or lets a NaN reach the payload.
	 */
	@TestFactory
	fun `every velocity shader derives motion from the reprojection and classifies before the divide`(): List<DynamicTest> =
		VelocityWriter.entries
			.map { it.fragmentShader.path.removePrefix("core/") }
			.distinct()
			.map { name ->
				DynamicTest.dynamicTest(name) {
					val shader = velocityShaderSource(name)

					// Previous NDC comes from this frame's reprojection applied to the fragment's
					// own reconstructed clip position, and the motion is the NDC difference.
					assertTrue(shader.contains("vec4 clip = vec4(ndc, gl_FragCoord.z, 1.0);"), "$name reconstructs clip")
					assertTrue(shader.contains(" * clip;"), "$name reprojects that clip position")
					assertTrue(shader.contains("previous.xy / previous.w - ndc"), "$name subtracts current NDC")

					// The classification: one representable sentinel, decided before the divide.
					assertTrue(
						shader.contains("const float INVALID_VELOCITY = ${TerrainVelocityUniforms.INVALID_VELOCITY};"),
						"$name must declare the same sentinel the JVM payload writer mirrors",
					)
					assertTrue(
						shader.contains("vec4(INVALID_VELOCITY, INVALID_VELOCITY, 0.0, 0.0)"),
						"$name must write the sentinel in both payload components",
					)
					assertTrue(
						shader.indexOf("previous.w <= 0.0") in 0 until shader.indexOf("/ previous.w"),
						"$name must reject an unseeable previous position before dividing by it",
					)
					assertTrue(shader.contains("previous.w != previous.w"), "$name rejects a non-finite previous w")
					assertTrue(shader.contains("motion.x != motion.x"), "$name collapses NaN to the sentinel")
					assertTrue(shader.contains("abs(motion.x) >= INVALID_VELOCITY"), "$name collapses out-of-range to the sentinel")
				}
			}

	/**
	 * Several writers deliberately fill the terrain writer's existing `VelocityConfig` block
	 * instead of introducing a payload design of their own, and the particle writer reuses the
	 * weather writer's shader outright. That sharing is the reason those writers have no payload
	 * behavior of their own to prove.
	 */
	@Test
	fun `the writers that share a payload design share the exact layout and shader`() {
		assertSame(TerrainVelocityUniforms.LAYOUT, WeatherVelocityRender.LAYOUT)
		assertSame(TerrainVelocityUniforms.LAYOUT, ParticleVelocityRender.LAYOUT)
		assertSame(TerrainVelocityUniforms.LAYOUT, BreakingBlockVelocityRender.LAYOUT)
		assertSame(WeatherVelocityRender.FRAGMENT_SHADER, ParticleVelocityRender.FRAGMENT_SHADER)

		// The block name the shader declares must match the layout entry, because Vulkan's lazy
		// compile resolves shader-declared uniforms against the pipeline's layouts by name.
		assertEquals("VelocityConfig", TerrainVelocityUniforms.UNIFORM_NAME)
		for (name in listOf("velocity_terrain", "velocity_weather", "velocity_crumbling")) {
			assertTrue(
				velocityShaderSource(name).contains("uniform ${TerrainVelocityUniforms.UNIFORM_NAME} {"),
				"$name declares the shared payload block",
			)
		}
	}

	private fun eachWriterPipeline(check: (VelocityWriter, RenderPipeline) -> Unit): List<DynamicTest> =
		writers.flatMap { (writer, sources) ->
			sources.map { source ->
				DynamicTest.dynamicTest("${writer.name} / ${source.location.path}") { check(writer, source) }
			}
		}
}
