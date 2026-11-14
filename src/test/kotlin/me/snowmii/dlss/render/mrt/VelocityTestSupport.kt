package me.snowmii.dlss.render.mrt

import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.nio.file.Path
import me.snowmii.streamline.Dimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.DlssSession
import me.snowmii.dlss.DlssStartupConfig
import me.snowmii.dlss.SRMode
import org.joml.Matrix4f

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

internal fun velocityRuntime(enabled: Boolean = true): RenderRuntime {
	val session = DlssSession(startupConfig(enabled))
		.also { if (enabled) check(it.markReadyAfterNativeStartup()) }
	return RenderRuntime(
		session = session,
		sceneTarget = SceneTarget(
			allocate = { width, height -> HeadlessRenderTarget(width, height, withDepthView = true) },
			release = { (it as HeadlessRenderTarget).releases++ },
		),
		startup = { if (enabled) RENDER_DIMENSIONS else null },
	)
}

internal fun velocityWorldPhase(runtime: RenderRuntime, evaluate: Boolean = true) = WorldPhase(
	runtime = runtime,
	present = { _, _ -> },
	onWorldTargetChanged = {},
	evaluateFrame = { _, _, _, _, _ -> evaluate },
)

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
