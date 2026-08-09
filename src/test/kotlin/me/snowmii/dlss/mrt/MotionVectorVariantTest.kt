package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.PolygonMode
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import java.util.Optional
import net.minecraft.client.renderer.BindGroupLayouts
import net.minecraft.resources.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Velocity twin construction preserves the full source descriptor: shaders, defines, bind-group
 * layouts, depth state, polygon mode, culling, all sixteen vertex binding slots, primitive
 * topology, and the first color target, while adding exactly one unblended RG16_FLOAT target at
 * index 1 under a distinct mc-dlss location.
 */
class MotionVectorVariantTest {
	@Test
	fun `terrain twin keeps every descriptor field and adds one unblended RG16 velocity target`() {
		val source = terrainPipeline()
		val twin = velocityTwin(source)

		assertNotSame(source, twin)
		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/solid_terrain"), twin.location)
		assertSame(source.vertexShader, twin.vertexShader)
		assertSame(source.fragmentShader, twin.fragmentShader)
		assertEquals(source.shaderDefines, twin.shaderDefines)
		assertTrue(twin.shaderDefines.values.containsKey("ALPHA_CUTOUT"))
		assertEquals(source.bindGroupLayouts.size, twin.bindGroupLayouts.size)
		for (index in source.bindGroupLayouts.indices) {
			assertSame(source.bindGroupLayouts[index], twin.bindGroupLayouts[index])
		}
		assertSame(source.depthStencilState, twin.depthStencilState)
		assertSame(source.polygonMode, twin.polygonMode)
		assertEquals(source.isCull, twin.isCull)
		assertSame(source.primitiveTopology, twin.primitiveTopology)

		assertAllSixteenBindingsPreserved(source, twin)

		val sourceTargets = source.colorTargetStates
		val twinTargets = twin.colorTargetStates
		assertEquals(2, twinTargets.size)
		assertSame(sourceTargets[0], twinTargets[0])
		assertVelocityTarget(twinTargets[1]!!)
	}

	@Test
	fun `blended first target and non-default polygon and cull survive the twin unmodified`() {
		val source = translucentWireframePipeline()
		val twin = velocityTwin(source)

		assertEquals(Optional.of(BlendFunction.TRANSLUCENT), twin.colorTargetStates[0]!!.blendFunction())
		assertSame(PolygonMode.WIREFRAME, twin.polygonMode)
		assertFalse(twin.isCull)
		assertNull(twin.depthStencilState)
		assertVelocityTarget(twin.colorTargetStates[1]!!)
	}

	@Test
	fun `all sixteen vertex binding slots map exactly with sparse nulls preserved`() {
		val source = sparseBindingPipeline()
		val twin = velocityTwin(source)

		assertEquals(16, twin.vertexFormatBindings.size)
		for (index in 0 until 16) {
			assertSame(source.getVertexFormatBinding(index), twin.getVertexFormatBinding(index))
		}
		assertSame(DefaultVertexFormat.POSITION_TEX, twin.getVertexFormatBinding(0))
		assertSame(DefaultVertexFormat.POSITION, twin.getVertexFormatBinding(7))
	}

	@Test
	fun `twin of an mc-dlss source still gets a distinct location`() {
		val source = modOwnedPipeline()
		val twin = velocityTwin(source)

		assertEquals(Identifier.fromNamespaceAndPath("mc-dlss", "velocity/pipeline/dlss_stress"), twin.location)
		assertFalse(source.location == twin.location)
		assertSame(source.vertexShader, twin.vertexShader)
		assertSame(source.fragmentShader, twin.fragmentShader)
	}

	private fun assertAllSixteenBindingsPreserved(source: RenderPipeline, twin: RenderPipeline) {
		assertEquals(16, twin.vertexFormatBindings.size)
		for (index in 0 until 16) {
			assertSame(source.getVertexFormatBinding(index), twin.getVertexFormatBinding(index))
		}
	}

	private fun assertVelocityTarget(target: ColorTargetState) {
		assertTrue(target.blendFunction().isEmpty())
		assertEquals(GpuFormat.RG16_FLOAT, target.format())
		assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
	}

	/** Mirrors RenderPipelines.TERRAIN_SNIPPET plus the ALPHA_CUTOUT define of CUTOUT_TERRAIN. */
	private fun terrainPipeline(): RenderPipeline {
		val terrainSnippet = RenderPipeline.builder()
			.withBindGroupLayout(BindGroupLayouts.GLOBALS)
			.withBindGroupLayout(BindGroupLayouts.FOG)
			.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER2)
			.withBindGroupLayout(BindGroupLayouts.PROJECTION)
			.withBindGroupLayout(BindGroupLayouts.CHUNK_SECTION)
			.withVertexBinding(0, DefaultVertexFormat.BLOCK)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withDepthStencilState(DepthStencilState.DEFAULT)
			.buildSnippet()
		return RenderPipeline.builder(terrainSnippet)
			.withLocation("pipeline/solid_terrain")
			.withVertexShader("core/terrain")
			.withFragmentShader("core/terrain")
			.withShaderDefine("ALPHA_CUTOUT", 0.5f)
			.build()
	}

	/** Translucent world pipeline: blended first target, wireframe, no cull, no depth state. */
	private fun translucentWireframePipeline(): RenderPipeline {
		return RenderPipeline.builder()
			.withLocation("pipeline/translucent_terrain")
			.withVertexShader("core/terrain")
			.withFragmentShader("core/terrain")
			.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
			.withPolygonMode(PolygonMode.WIREFRAME)
			.withCull(false)
			.withVertexBinding(0, DefaultVertexFormat.BLOCK)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.build()
	}

	/** Sparse multi-binding pipeline: binding 0 and 7 occupied, remaining fourteen null. */
	private fun sparseBindingPipeline(): RenderPipeline {
		return RenderPipeline.builder()
			.withLocation("pipeline/custom_multibinding")
			.withVertexShader("core/terrain")
			.withFragmentShader("core/terrain")
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withVertexBinding(7, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()
	}

	/** Mod-owned pipeline: already under mc-dlss, twin location must stay distinct. */
	private fun modOwnedPipeline(): RenderPipeline {
		return RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("mc-dlss", "pipeline/dlss_stress"))
			.withVertexShader(Identifier.fromNamespaceAndPath("mc-dlss", "core/screenquad"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("mc-dlss", "post/dlss_stress"))
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.build()
	}
}
