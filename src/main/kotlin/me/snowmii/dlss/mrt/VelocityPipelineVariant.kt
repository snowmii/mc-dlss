package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.Optional
import net.minecraft.resources.Identifier

/**
 * Clones a source pipeline description into a velocity-MRT twin.
 *
 * Every descriptor field the source carries — shaders, defines, bind-group layouts, depth state,
 * polygon mode, culling, all sixteen vertex binding slots, primitive topology, and the first
 * color target — is carried over unchanged, then exactly one unblended RG16_FLOAT color target is
 * added at index 1. The twin lives at a distinct mc-dlss location so it can be compiled and bound
 * without colliding with the source pipeline's lazy-compile cache entry.
 *
 * The clone goes through [RenderPipeline.Snippet], the public descriptor carrier, so no private
 * RenderPipelines internals and no reflection are involved: the source's getters feed the snippet
 * verbatim, and the snippet seeds the builder.
 */
fun velocityTwin(source: RenderPipeline): RenderPipeline {
	val snippet = RenderPipeline.Snippet(
		Optional.of(source.vertexShader),
		Optional.of(source.fragmentShader),
		Optional.of(source.shaderDefines),
		Optional.of(source.bindGroupLayouts),
		source.colorTargetStates,
		source.colorTargetStates?.size ?: 0,
		Optional.ofNullable(source.depthStencilState),
		Optional.of(source.polygonMode),
		Optional.of(source.isCull),
		source.vertexFormatBindings,
		Optional.of(source.primitiveTopology),
	)

	return RenderPipeline.builder(snippet)
		.withLocation(velocityLocation(source.location))
		.withColorTargetState(1, VELOCITY_COLOR_TARGET)
		.build()
}

private fun velocityLocation(source: Identifier): Identifier =
	Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, "$VELOCITY_PATH_PREFIX${source.path}")

private const val VELOCITY_NAMESPACE = "mc-dlss"
private const val VELOCITY_PATH_PREFIX = "velocity/"

/** Unblended RG16_FLOAT with every channel writable: the DLSS motion-vector payload format. */
private val VELOCITY_COLOR_TARGET = ColorTargetState(
	Optional.empty(),
	GpuFormat.RG16_FLOAT,
	ColorTargetState.WRITE_ALL,
)
