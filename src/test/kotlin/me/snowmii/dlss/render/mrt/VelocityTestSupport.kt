package me.snowmii.dlss.render.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.io.path.readText
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.SRMode
import org.joml.Matrix4f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.shaderc.Shaderc
import org.lwjgl.util.spvc.Spvc
import org.lwjgl.util.spvc.SpvcReflectedResource

/**
 * Shared fixtures for the velocity-MRT suites: the headless Blaze3D doubles, the world-phase
 * builders every writer drives its seams through, and the Shaderc + spirv-cross path Minecraft
 * itself compiles fragment shaders with.
 *
 * The test JVM applies no Fabric transformation and owns no live device, so a writer suite
 * reaches descriptors against the mapped 26.2 classes, seams at the same entry points the
 * mixin calls, and shaders compiled through the real toolchain.
 */

internal val repositoryRoot: Path = Path.of("").toAbsolutePath()

internal fun repositorySource(path: String): String = repositoryRoot.resolve(path).readText()

/** A velocity fragment shader asset, by its `core/velocity_*` name. */
internal fun velocityShaderSource(name: String): String =
	repositorySource("src/main/resources/assets/mc-dlss/shaders/core/$name.fsh")

internal val OUTPUT_DIMENSIONS = Dimensions(2560, 1440)
internal val RENDER_DIMENSIONS = Dimensions(1707, 960)

internal fun fakeMainTarget(): HeadlessRenderTarget = HeadlessRenderTarget(OUTPUT_DIMENSIONS.width, OUTPUT_DIMENSIONS.height)

internal fun startupConfig(enabled: Boolean = true) = DlssStartupConfig(
	enabled = enabled,
	qualityMode = SRMode.QUALITY,
	outputDimensions = OUTPUT_DIMENSIONS,
	sdkPath = null,
	nativeLibraryPath = null,
	dataPath = null,
	warnings = emptyList(),
)

internal fun cameraSample() = DlssCameraSample(
	projection = Matrix4f().setPerspective(
		Math.toRadians(70.0).toFloat(),
		OUTPUT_DIMENSIONS.width.toFloat() / OUTPUT_DIMENSIONS.height,
		1000f,
		0.05f,
		true,
	),
	viewRotation = Matrix4f(),
	cameraX = 0.0,
	cameraY = 64.0,
	cameraZ = 0.0,
)

/**
 * A DLSS runtime whose scene target carries the RG16_FLOAT velocity companion, so an open phase
 * latches the velocity-MRT route and offers `terrainVelocityView`. Without the companion
 * ([withVelocity] false) the same runtime stays on the vanilla one-attachment route.
 */
internal fun velocityRuntime(withVelocity: Boolean = true, enabled: Boolean = true): RenderRuntime {
	val session = DlssSession(startupConfig(enabled))
		.also { if (enabled) check(it.markReadyAfterNativeStartup()) }
	return RenderRuntime(
		session = session,
		sceneTarget = SceneTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height, withDepthView = true) },
			release = { (it as HeadlessRenderTarget).releases++ },
			allocateVelocity = if (withVelocity) {
				{ width, height -> HeadlessRenderTarget(width, height, GpuFormat.RG16_FLOAT, withView = true) }
			} else {
				{ _, _ -> null }
			},
		),
		startup = { if (enabled) RENDER_DIMENSIONS else null },
	)
}

internal fun velocityWorldPhase(runtime: RenderRuntime, evaluate: Boolean = true) = WorldPhase(
	runtime = runtime,
	present = { _, _ -> },
	onWorldTargetChanged = {},
	evaluateFrame = { _, _, _, _, _, _, _ -> evaluate },
)

/** Runs one complete phase so the frame boundary advances: prepare, begin, end. */
internal fun renderFrame(phase: WorldPhase, target: RenderTarget) {
	phase.prepare(true, target, cameraSample())
	phase.begin(true, target)
	phase.end()
}

internal fun assertVelocityTarget(target: ColorTargetState) {
	assertTrue(target.blendFunction().isEmpty, "the velocity payload is never blended")
	assertEquals(GpuFormat.RG16_FLOAT, target.format())
	assertEquals(ColorTargetState.WRITE_ALL, target.writeMask())
}

internal class HeadlessRenderTarget(
	width: Int,
	height: Int,
	format: GpuFormat = GpuFormat.RGBA8_UNORM,
	withView: Boolean = false,
	withDepthView: Boolean = false,
) : RenderTarget("fake", true, format) {
	var releases = 0
	private val texture = FakeTexture(format, width, height)
	private val extraDepthView: GpuTextureView? =
		if (withDepthView) FakeView(FakeTexture(GpuFormat.D32_FLOAT, width, height)) else null

	init {
		this.width = width
		this.height = height
		if (withView) colorTextureView = FakeView(texture)
		if (extraDepthView != null) depthTextureView = extraDepthView
	}

	override fun getDepthTextureView(): GpuTextureView? = extraDepthView ?: super.getDepthTextureView()

	override fun createBuffers(width: Int, height: Int) {
		this.width = width
		this.height = height
	}

	override fun destroyBuffers() {
		releases++
	}
}

internal class FakeTexture(format: GpuFormat, width: Int = 16, height: Int = 16) :
	GpuTexture(USAGE_RENDER_ATTACHMENT, "fake", format, width, height, 1, 1) {
	override fun close() = Unit
	override fun isClosed() = false
}

internal class FakeView(texture: GpuTexture) : GpuTextureView(texture, 0, 1) {
	override fun close() = Unit
	override fun isClosed() = false
}

internal class FakeBuffer : GpuBuffer(USAGE_VERTEX, 0) {
	override fun isClosed() = false
	override fun close() = Unit
	override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
		throw UnsupportedOperationException("test buffer is never mapped")
}

/**
 * Compiles a fragment shader exactly the way `GlslCompiler.createIntermediary` does: the global
 * defines injected after the `#version` line, then shaderc with the Vulkan 1.2 target and
 * automatic location/uniform mapping. Returns a copy of the SPIR-V bytes, which the caller owns
 * and must [MemoryUtil.memFree].
 */
internal fun compileFragmentShader(source: String, filename: String = "velocity.fsh"): ByteBuffer {
	val compiler = Shaderc.shaderc_compiler_initialize()
	val options = Shaderc.shaderc_compile_options_initialize()
	try {
		Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2)
		Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true)
		Shaderc.shaderc_compile_options_set_auto_map_locations(options, true)
		Shaderc.shaderc_compile_options_set_generate_debug_info(options)
		Shaderc.shaderc_compile_options_set_optimization_level(options, 0)

		val sourceBuffer = MemoryUtil.memUTF8(minecraftFragmentSource(source), false)
		val filenameBuffer = MemoryUtil.memUTF8(filename)
		val entrypointBuffer = MemoryUtil.memUTF8("main")
		try {
			val result = Shaderc.shaderc_compile_into_spv(
				compiler, sourceBuffer, Shaderc.shaderc_fragment_shader, filenameBuffer, entrypointBuffer, options,
			)
			try {
				val status = Shaderc.shaderc_result_get_compilation_status(result)
				check(status == 0) { "shaderc failed on $filename (status $status): ${Shaderc.shaderc_result_get_error_message(result)}" }
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
	} finally {
		Shaderc.shaderc_compile_options_release(options)
		Shaderc.shaderc_compiler_release(compiler)
	}
}

/**
 * The exact preprocessed source `compileShader` hands `createIntermediary`: Minecraft's resource
 * preprocessor resolves the `#moj_import` includes, then the global defines are injected right
 * after the `#version` line. The defines alias vertex-only builtins and are inert for fragment
 * output emission, but keeping them makes the compiled module match the game's.
 *
 * Most velocity shaders inline the vanilla blocks they need and carry no import at all; the ones
 * that do import get the exact vanilla body substituted here, which is all the game's preprocessor
 * does.
 */
internal fun minecraftFragmentSource(source: String): String {
	val resolved = MOJ_IMPORT.replace(source) { match -> vanillaInclude(match.groupValues[1]) }
	val versionLineEnd = resolved.indexOf('\n')
	check(versionLineEnd >= 0) { "shader source must start with a #version line" }
	return resolved.substring(0, versionLineEnd + 1) +
		"#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n#line 1 0\n" +
		resolved.substring(versionLineEnd + 1)
}

private val MOJ_IMPORT = Regex("""#moj_import <minecraft:([A-Za-z0-9_]+\.glsl)>""")

/**
 * The vanilla include body, read from Minecraft's own shipped assets exactly where its resource
 * preprocessor reads it, minus the include's own `#version` line (the importing shader already
 * carries one). Reading the real asset means a vanilla uniform-block change reaches these tests
 * the same way it reaches the game, instead of drifting against a hand-copied snapshot.
 */
private fun vanillaInclude(name: String): String {
	val path = "/assets/minecraft/shaders/include/$name"
	val body = checkNotNull(OutputReflection::class.java.getResourceAsStream(path)) {
		"vanilla shader include $path is not on the test classpath"
	}.use { it.readBytes().decodeToString() }
	return body.lineSequence().filterNot { it.trimStart().startsWith("#version") }.joinToString("\n")
}

/** A stage output as spirv-cross reflects it, plus the byte offset of its Location decoration. */
internal class OutputReflection(val name: String, val locationOffset: Int, val location: Int)

/**
 * Reflects the stage outputs of a compiled module the way `createFromSpirv` does: parse the
 * SPIR-V, list STAGE_OUTPUT resources, and read each output's Location decoration value and the
 * binary word offset where that decoration lives. The list comes back in module declaration
 * order, which is glslang's first-assignment order inside `main()`.
 */
internal fun reflectOutputs(spirv: ByteBuffer): List<OutputReflection> {
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
			return (0 until resources.capacity()).map { index ->
				val resource = resources.get(index)
				val name = resource.nameString()
				check(Spvc.spvc_compiler_get_binary_offset_for_decoration(compiler, resource.id(), LOCATION_DECORATION, offsetBuffer)) {
					"no Location decoration on $name"
				}
				OutputReflection(
					name = name,
					locationOffset = offsetBuffer.get(0),
					location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), LOCATION_DECORATION),
				)
			}
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

/** SPIR-V DecorationLocation, the decoration `createFromSpirv` rewrites and these suites read back. */
internal const val LOCATION_DECORATION = 30
