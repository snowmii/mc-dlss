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

/**
 * Cached entity-model writer twin: the plain two-target velocity twin with the mc-dlss entity
 * velocity fragment shader and its per-object reprojection layout layered on top. The source
 * entity shader's defines and all source layouts remain intact, so `EMISSIVE`, `DISSOLVE`, face
 * lighting, overlay, and light-map behavior continue to compile and render exactly as before.
 */
fun entityVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	entityVelocityTwins.computeIfAbsent(plainTwin, ::buildEntityVelocityTwin)

/**
 * Cached weather writer twin: the plain two-target velocity twin with the mc-dlss weather
 * velocity fragment shader and the existing VelocityConfig uniform layout layered on top.
 *
 * The weather pass is the one remaining bespoke world pass: `WeatherEffectRenderer.render`
 * creates a single pass over the weather target with `WEATHER_DEPTH_WRITE` or
 * `WEATHER_NO_DEPTH_WRITE` and draws the CPU-baked rain and snow columns. The pass-creation
 * redirect binds this twin only while an open VELOCITY_MRT phase offers the scene velocity
 * view: the two-target shape comes from the plain twin, the velocity shader reproduces the
 * source core/particle color output byte-identically and writes jitter-stripped NDC camera
 * motion into the payload at color target 1, and the added layout is the terrain writer's own
 * `VelocityConfig` layout, because the weather writer fills that existing payload block
 * rather than introducing a new uniform design. Everything else - vertex shader, defines,
 * the other bind-group layouts, depth state (the depth-write variant's write state included),
 * polygon mode, culling, all sixteen vertex bindings, primitive topology, and the first
 * color target (the weather target's translucent blend) - is the plain twin's, so the pass's
 * attachment count and format agree with the pipeline exactly as they do for the plain twin.
 *
 * Keyed by the plain twin (which is itself cached per source pipeline), so every weather pass
 * bind after the first hits the lazy-compile cache. The twin lives at a distinct mc-dlss
 * location - `velocity/weather/<name>` - so it never collides with the plain twin's
 * `velocity/pipeline/<name>` location or the terrain/entity writer twins.
 */
fun weatherVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	weatherVelocityTwins.computeIfAbsent(plainTwin, ::buildWeatherVelocityTwin)

/**
 * Cached moving-block writer twin: the plain two-target velocity twin with the mc-dlss moving-
 * block velocity shader and the existing moving-block payload layout layered on top.
 *
 * The piston moving-block render types bind the owned core/block pipeline family (`SOLID_BLOCK`
 * and `CUTOUT_BLOCK` on the main target), and the prepared-draw redirect binds this twin only
 * for those owned main-target draws that carry a bound moving-block identity. The two-target
 * shape comes from the plain twin, the swapped-in fragment shader is the moving-block writer's
 * own `core/velocity_block` - which is the vanilla `core/block` fragment body verbatim plus
 * the velocity-MRT payload write, so block color output stays byte-identical - and the added
 * layout is the writer's `BlockVelocityConfig` layout, the existing payload design the writer
 * fills on the draw encoder. Everything else - vertex shader, defines, the other bind-group
 * layouts, depth state, polygon mode, culling, all sixteen vertex bindings, primitive topology,
 * and the first color target - is the plain twin's, so the pass's attachment count and format
 * agree with the pipeline exactly as they do for the plain twin.
 *
 * Keyed by the plain twin (which is itself cached per source pipeline), so every moving-block
 * draw bind after the first hits the lazy-compile cache. The twin lives at a distinct mc-dlss
 * location - `velocity/movingblock/<name>` - so it never collides with the plain twin's
 * `velocity/pipeline/<name>` location or the terrain/entity/weather/particle writer twins.
 */
fun movingBlockVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	movingBlockVelocityTwins.computeIfAbsent(plainTwin, ::buildMovingBlockVelocityTwin)

/**
 * Cached breaking-block crumbling writer twin: the plain two-target velocity twin with the
 * mc-dlss crumbling velocity shader and the existing terrain VelocityConfig uniform layout
 * layered on top.
 *
 * The crumbling overlay - `ModelBakery.DESTROY_TYPES`, ten static stages of the mapped
 * `CRUMBLING` pipeline - draws through the same `PreparedRenderType.drawFromBuffer` seam the
 * entity and moving-block writers use, and the prepared-draw dispatch binds this twin only for
 * owned main-target crumbling draws while an open VELOCITY_MRT phase offers the scene velocity
 * view. The two-target shape comes from the plain twin, the swapped-in fragment shader is the
 * crumbling writer's own `core/velocity_crumbling` - which is the vanilla
 * `core/rendertype_crumbling` fragment body verbatim (the alpha discard between the
 * vertex-color and ColorModulator multiplies included) plus the velocity-MRT payload write, so
 * overlay color output stays byte-identical - and the added layout is the terrain writer's own
 * `VelocityConfig` layout, because the crumbling overlay carries no block identity or history
 * of its own and the writer fills that existing payload block rather than introducing a new
 * uniform design. Everything else - vertex shader, defines, the other bind-group layouts,
 * depth state, polygon mode, culling, all sixteen vertex bindings, primitive topology, and the
 * first color target (the overlay's DST_COLOR/SRC_COLOR multiply blend) - is the plain twin's,
 * so the pass's attachment count and format agree with the pipeline exactly as they do for the
 * plain twin.
 *
 * Keyed by the plain twin (which is itself cached per source pipeline), so every crumbling
 * draw bind after the first hits the lazy-compile cache. The twin lives at a distinct mc-dlss
 * location - `velocity/crumbling/<name>` - so it never collides with the plain twin's
 * `velocity/pipeline/<name>` location or the terrain/entity/weather/particle/moving-block
 * writer twins.
 */
fun crumblingVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	crumblingVelocityTwins.computeIfAbsent(plainTwin, ::buildCrumblingVelocityTwin)

private val terrainVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
private val entityVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
private val weatherVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
private val particleVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
private val movingBlockVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
private val crumblingVelocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()

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

private fun buildEntityVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
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
		.withLocation(entityVelocityLocation(plainTwin.location))
		.withFragmentShader(EntityVelocityUniforms.FRAGMENT_SHADER)
		.withBindGroupLayout(EntityVelocityUniforms.LAYOUT)
		.build()
}

/**
 * Cached particle writer twin: the plain two-target velocity twin with the existing
 * particle-body velocity shader and the existing VelocityConfig uniform layout layered on
 * top.
 *
 * `QuadParticleFeatureRenderer.executeGroup` is the one method that draws both particle
 * families - the solid group into the scene (main) target, the translucent group into the
 * particles target - and the static `drawLayers` binds each layer's `OPAQUE_PARTICLE` /
 * `TRANSLUCENT_PARTICLE` pipeline. The pass-creation redirect binds this twin only while an
 * open VELOCITY_MRT phase offers the scene velocity view: the two-target shape comes from
 * the plain twin, the swapped-in fragment shader is the weather writer's shader - which IS
 * the vanilla `core/particle` fragment body verbatim plus the velocity-MRT payload write,
 * and the particle pipelines bind `core/particle`, so particle color output stays
 * byte-identical - and the added layout is the terrain writer's own `VelocityConfig` layout,
 * because the particle writer fills that existing payload block rather than introducing a
 * new uniform design. The mapped particle render state carries no stable previous identity,
 * so the shader writes jitter-stripped NDC camera motion into the payload at color target 1
 * exactly like the weather writer, with no particle history of its own. Everything else -
 * vertex shader, defines, the other bind-group layouts, depth state, polygon mode, culling,
 * all sixteen vertex bindings, primitive topology, and the first color target (the solid
 * variant's unblended state or the translucent variant's blend) - is the plain twin's, so
 * the pass's attachment count and format agree with the pipeline exactly as they do for the
 * plain twin.
 *
 * Keyed by the plain twin (which is itself cached per source pipeline), so every particle
 * pass bind after the first hits the lazy-compile cache. The twin lives at a distinct
 * mc-dlss location - `velocity/particle/<name>` - so it never collides with the plain
 * twin's `velocity/pipeline/<name>` location or the terrain/entity/weather writer twins.
 */
fun particleVelocityTwin(plainTwin: RenderPipeline): RenderPipeline =
	particleVelocityTwins.computeIfAbsent(plainTwin, ::buildParticleVelocityTwin)

private fun buildParticleVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
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
		.withLocation(particleVelocityLocation(plainTwin.location))
		.withFragmentShader(ParticleVelocityRender.FRAGMENT_SHADER)
		.withBindGroupLayout(ParticleVelocityRender.LAYOUT)
		.build()
}

private fun buildWeatherVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
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
		.withLocation(weatherVelocityLocation(plainTwin.location))
		.withFragmentShader(WeatherVelocityRender.FRAGMENT_SHADER)
		.withBindGroupLayout(WeatherVelocityRender.LAYOUT)
		.build()
}

private fun buildMovingBlockVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
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
		.withLocation(movingBlockVelocityLocation(plainTwin.location))
		.withFragmentShader(MovingBlockVelocityRender.FRAGMENT_SHADER)
		.withBindGroupLayout(MovingBlockVelocityRender.LAYOUT)
		.build()
}

private fun buildCrumblingVelocityTwin(plainTwin: RenderPipeline): RenderPipeline {
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
		.withLocation(crumblingVelocityLocation(plainTwin.location))
		.withFragmentShader(BreakingBlockVelocityRender.FRAGMENT_SHADER)
		.withBindGroupLayout(BreakingBlockVelocityRender.LAYOUT)
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

private fun entityVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val entityPath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}entity/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}entity/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, entityPath)
}

private fun weatherVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val weatherPath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}weather/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}weather/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, weatherPath)
}

private fun particleVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val particlePath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}particle/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}particle/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, particlePath)
}

private fun crumblingVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val crumblingPath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}crumbling/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}crumbling/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, crumblingPath)
}

private fun movingBlockVelocityLocation(plainTwinLocation: Identifier): Identifier {
	val path = plainTwinLocation.path
	val movingBlockPath = if (path.startsWith("${VELOCITY_PATH_PREFIX}pipeline/")) {
		"${VELOCITY_PATH_PREFIX}movingblock/" + path.removePrefix("${VELOCITY_PATH_PREFIX}pipeline/")
	} else {
		"${VELOCITY_PATH_PREFIX}movingblock/$path"
	}
	return Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, movingBlockPath)
}

private const val VELOCITY_NAMESPACE = "mc-dlss"
private const val VELOCITY_PATH_PREFIX = "velocity/"

/** Unblended RG16_FLOAT with every channel writable: the DLSS motion-vector payload format. */
private val VELOCITY_COLOR_TARGET = ColorTargetState(
	Optional.empty(),
	GpuFormat.RG16_FLOAT,
	ColorTargetState.WRITE_ALL,
)
