package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.pipeline.BindGroupLayout
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
 *
 * This plain twin keeps the *source* fragment shader, so it cannot write the velocity payload
 * itself. A pass that has to write the payload binds a [writerTwin] instead.
 */
fun velocityTwin(source: RenderPipeline): RenderPipeline =
	velocityTwins.computeIfAbsent(source) { plainTwin(it) }

/**
 * The velocity payload writers, one per retained object-motion world pass family.
 *
 * Each entry names the fragment shader swapped in for the source's and the one bind-group layout
 * added for the payload block that shader declares — Vulkan's lazy compile resolves the block by
 * name against the pipeline's layouts.
 *
 * [segment] is the twin's location segment, so a writer twin can never collide with the plain
 * twin's `velocity/pipeline/<name>` location or with another writer's.
 *
 * The camera-motion-only writer families (terrain, weather, particle, breaking block) are
 * retired: those passes keep the exact vanilla one-attachment route and their pixels stay
 * sentinel for the post-scene fill.
 */
enum class VelocityWriter(
	val segment: String,
	val fragmentShader: Identifier,
	val layout: BindGroupLayout,
) {
	ENTITY("entity", EntityVelocityUniforms.FRAGMENT_SHADER, EntityVelocityUniforms.LAYOUT),
	MOVING_BLOCK("movingblock", MovingBlockVelocityRender.FRAGMENT_SHADER, MovingBlockVelocityRender.LAYOUT),
	CLOUD("cloud", CloudVelocityRender.FRAGMENT_SHADER, CloudVelocityRender.LAYOUT),
	;

	internal val cache = ConcurrentHashMap<RenderPipeline, RenderPipeline>()
}

/**
 * The cached [writer] twin of [source]: the plain two-target [velocityTwin] with the writer's
 * velocity fragment shader swapped in and its payload layout added.
 *
 * The two-target shape, and with it the pass's attachment count and formats, comes from the plain
 * twin; everything else the source carries — vertex shader, defines, the source bind-group
 * layouts, depth state, polygon mode, culling, all sixteen vertex bindings, primitive topology,
 * and the first color target with its blend — survives untouched, so the pass's color output
 * stays byte-identical to vanilla and only the payload at color target 1 is new.
 *
 * Keyed by the source pipeline, so every bind after the first hits Vulkan's lazy-compile cache.
 */
fun writerTwin(
	source: RenderPipeline,
	writer: VelocityWriter,
): RenderPipeline =
	writer.cache.computeIfAbsent(source) {
		build(velocityTwin(it), writerLocation(it, writer)) {
			withFragmentShader(writer.fragmentShader)
			withBindGroupLayout(writer.layout)
		}
	}

private val velocityTwins = ConcurrentHashMap<RenderPipeline, RenderPipeline>()

private fun plainTwin(source: RenderPipeline): RenderPipeline =
	build(source, Identifier.fromNamespaceAndPath(VELOCITY_NAMESPACE, "$VELOCITY_PATH_PREFIX${source.path}")) {
		withColorTargetState(1, VELOCITY_COLOR_TARGET)
	}

/**
 * Rebuilds [source]'s full descriptor at [location] through the public [RenderPipeline.Snippet]
 * carrier, then applies [edits]. Every field the snippet carries round-trips verbatim.
 */
private fun build(
	source: RenderPipeline,
	location: Identifier,
	edits: RenderPipeline.Builder.() -> Unit,
): RenderPipeline {
	val snippet = RenderPipeline.Snippet(
		Optional.of(source.vertexShader),
		Optional.of(source.fragmentShader),
		Optional.of(source.shaderDefines),
		Optional.of(source.bindGroupLayouts),
		source.colorTargetStates,
		source.colorTargetStates.size,
		Optional.ofNullable(source.depthStencilState),
		Optional.of(source.polygonMode),
		Optional.of(source.isCull),
		source.vertexFormatBindings,
		Optional.of(source.primitiveTopology),
	)
	return RenderPipeline.builder(snippet).withLocation(location).apply(edits).build()
}

/**
 * `velocity/<segment>/<name>` for a source at `pipeline/<name>`: distinct from the plain twin's
 * `velocity/pipeline/<name>` and from every other writer's segment. A source that is not under
 * `pipeline/` keeps its full path rather than guessing at a name it does not carry.
 */
private fun writerLocation(source: RenderPipeline, writer: VelocityWriter): Identifier =
	Identifier.fromNamespaceAndPath(
		VELOCITY_NAMESPACE,
		"$VELOCITY_PATH_PREFIX${writer.segment}/${source.path.removePrefix("pipeline/")}",
	)

private val RenderPipeline.path: String
	get() = location.path

private const val VELOCITY_NAMESPACE = "mc-dlss"
private const val VELOCITY_PATH_PREFIX = "velocity/"

/** Unblended RG16_FLOAT with every channel writable: the DLSS motion-vector payload format. */
private val VELOCITY_COLOR_TARGET = ColorTargetState(
	Optional.empty(),
	GpuFormat.RG16_FLOAT,
	ColorTargetState.WRITE_ALL,
)
