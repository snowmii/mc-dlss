package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
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
 *
 * The twin is cached per source pipeline: Vulkan's lazy-compile cache is keyed by pipeline
 * identity (`IdentityHashMap`), so a twin rebuilt on every bind would be recompiled on every
 * bind. Reusing the one twin per source pipeline keeps the compile at the first bind, exactly
 * like the source pipeline's own cache entry. Terrain layers are static pipelines, so the same
 * twin instance is what every frame's terrain pass binds.
 */
fun velocityTwin(source: RenderPipeline): RenderPipeline =
	velocityTwins.computeIfAbsent(source, ::buildVelocityTwin)

private val velocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()

/**
 * Cached terrain writer twin: the plain two-target velocity twin with the mc-dlss terrain
 * velocity fragment shader swapped in for the source's fragment shader and the
 * [TerrainVelocityUniforms.LAYOUT] uniform layout added.
 *
 * The plain [velocityTwin] keeps the source fragment shader - that is the M-4 descriptor
 * contract - so it cannot write the velocity payload itself. The terrain pass binds this twin
 * instead: the two-target shape comes from the plain twin, the velocity shader writes
 * jitter-stripped NDC camera motion into the payload at color target 1, and the added layout
 * carries the `VelocityConfig` uniform block the shader declares, which Vulkan's lazy compile
 * resolves by name against the pipeline's layouts. Everything else - vertex shader, defines,
 * depth state, polygon mode, culling, all sixteen vertex bindings, primitive topology, and the
 * first color target - is the plain twin's, so the pass's attachment count and format agree
 * with the pipeline exactly as they do for the plain twin.
 *
 * Keyed by the plain twin (which is itself cached per source pipeline), so every terrain pass
 * bind after the first hits the lazy-compile cache. The twin lives at a distinct mc-dlss
 * location - `velocity/terrain/<name>` - so it never collides with the plain twin's
 * `velocity/pipeline/<name>` location.
 */
fun terrainVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	terrainVelocityTwins.computeIfAbsent(plainTwin, ::buildTerrainVelocityTwin)

private val terrainVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()

private fun buildVelocityTwin(source: RenderPipeline): RenderPipeline {
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

private fun buildTerrainVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
	val snippet = RenderPipeline.Snippet(
		Optional.of(plainTwin.vertexShader),
		Optional.of(plainTwin.fragmentShader),
		Optional.of(plainTwin.shaderDefines),
		Optional.of(plainTwin.bindGroupLayouts),
		plainTwin.colorTargetStates,
		plainTwin.colorTargetStates?.size ?: 0,
		Optional.ofNullable(plainTwin.depthStencilState),
		Optional.of(plainTwin.polygonMode),
		Optional.of(plainTwin.isCull),
		plainTwin.vertexFormatBindings,
		Optional.of(plainTwin.primitiveTopology),
	)

	return RenderPipeline.builder(snippet)
		.withLocation(terrainVelocityLocation(plainTwin.location))
		.withFragmentShader(TerrainVelocityUniforms.FRAGMENT_SHADER)
		.withBindGroupLayout(TerrainVelocityUniforms.LAYOUT)
		.build()
}

private fun velocityLocation(source: Identifier): Identifier =
	Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, "$VELOCITY_PATH_PREFIX${source.path}")

/**
 * The terrain twin's location: the plain twin's `velocity/pipeline/<name>` path with the
 * `pipeline` segment replaced by `terrain`, so the two never share a location. A plain twin
 * always sits under `velocity/pipeline/`; anything else keeps the full path rather than
 * guessing at a source name it does not carry.
 */
private fun terrainVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val terrainPath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}terrain/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}terrain/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, terrainPath)
}

private const val VELOCITY_NAMESPACE = "mc-dlss"
private const val VELOCITY_PATH_PREFIX = "velocity/"

/** Unblended RG16_FLOAT with every channel writable: the DLSS motion-vector payload format. */
private val VELOCITY_COLOR_TARGET = ColorTargetState(
	Optional.empty(),
	GpuFormat.RG16_FLOAT,
	ColorTargetState.WRITE_ALL,
)
