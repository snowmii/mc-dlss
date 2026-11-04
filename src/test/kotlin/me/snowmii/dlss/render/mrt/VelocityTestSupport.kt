package me.snowmii.dlss.render.mrt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
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

internal class HeadlessRenderTarget(
	width: Int,
	height: Int,
	format: GpuFormat = GpuFormat.RGBA8_UNORM,
	withView: Boolean = false,
	withDepthView: Boolean = false,
) : RenderTarget("fake", format, if (withDepthView) GpuFormat.D32_FLOAT else null) {
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

/** [GpuTexture] and [GpuTextureView] are plain interfaces in 26.3, so fakes implement them directly. */
internal open class FakeTexture(
	private val format: GpuFormat,
	private val fakeWidth: Int = 16,
	private val fakeHeight: Int = 16,
) : GpuTexture {
	override fun getWidth(mipLevel: Int) = fakeWidth
	override fun getHeight(mipLevel: Int) = fakeHeight
	override fun getDepthOrLayers() = 1
	override fun getMipLevels() = 1
	override fun getFormat(): GpuFormat = format
	override fun usage() = GpuTexture.USAGE_RENDER_ATTACHMENT
	override fun getLabel() = "fake"
	override fun isClosed() = false
	override fun close() = Unit
}

internal open class FakeView(private val fakeTexture: GpuTexture) : GpuTextureView {
	override fun isClosed() = false
	override fun texture(): GpuTexture = fakeTexture
	override fun baseMipLevel() = 0
	override fun mipLevels() = 1
	override fun getWidth(mipLevel: Int) = fakeTexture.getWidth(mipLevel)
	override fun getHeight(mipLevel: Int) = fakeTexture.getHeight(mipLevel)
	override fun close() = Unit
}

internal open class FakeBuffer(
	private val bufferUsage: Int = GpuBuffer.USAGE_VERTEX,
	private val bufferSize: Long = 0,
) : GpuBuffer {
	override fun size() = bufferSize
	override fun usage() = bufferUsage
	override fun isClosed() = false
	override fun close() = Unit
	override fun map(offset: Long, length: Long, read: Boolean, write: Boolean): GpuBufferSlice.MappedView =
		throw UnsupportedOperationException("test buffer is never mapped")
}
