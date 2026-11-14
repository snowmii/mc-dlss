package me.snowmii.dlss.render

import me.snowmii.dlss.api.BeforeDlssEvaluation
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.readout.FramePacingProbe
import me.snowmii.dlss.readout.SessionFacts
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.StreamlineSession
import org.joml.Matrix4f
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.backend.vulkan.VulkanConst
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ProjectionMatrixBuffer
import net.minecraft.client.renderer.RenderPipelines
import java.util.Optional

typealias EvaluateFrame = (
	RenderTarget,
	RenderTarget,
	DlssJitterOffset,
	DlssFrameMotion,
	DlssCameraSample?,
) -> Boolean

class WorldPhase(
	private val runtime: RenderRuntime,
	private val present: (RenderTarget, RenderTarget) -> Unit,
	private val onWorldTargetChanged: () -> Unit,
	private val evaluateFrame: EvaluateFrame = { _, _, _, _, _ -> false },
	private val readout: SessionReadout = SessionReadout.NOOP,
) : AutoCloseable {
	private var sceneTarget: RenderTarget? = null
	private var mainRenderTarget: RenderTarget? = null
	private var projectionPrepared = false
	private var lastResolvedTarget: RenderTarget? = null

	private var cameraSample: DlssCameraSample? = null
	private var unjitteredProjectionBuffer: ProjectionMatrixBuffer? = null
	private var unjitteredProjectionSlice: GpuBufferSlice? = null

	var isOpen: Boolean = false
		private set

	val worldTargetOverride: RenderTarget?
		get() = if (isOpen) sceneTarget else null

	val destinationTarget: RenderTarget?
		get() = if (isOpen) mainRenderTarget else null

	val unjitteredProjection: GpuBufferSlice?
		get() = if (isOpen) unjitteredProjectionSlice else null

	fun presentStart(): Boolean {
		runtime.pacing.begin(FramePacingProbe.Span.QUEUE_PRESENT)
		return runtime.frameEvaluation?.presentStart() == true
	}

	fun presentEnd(): Boolean {
		val emitted = runtime.frameEvaluation?.presentEnd() == true
		runtime.pacing.end(FramePacingProbe.Span.QUEUE_PRESENT)
		return emitted
	}

	fun acquireStart() = runtime.pacing.begin(FramePacingProbe.Span.SWAPCHAIN_ACQUIRE)
	fun acquireEnd() = runtime.pacing.end(FramePacingProbe.Span.SWAPCHAIN_ACQUIRE)

	fun submitStart() = runtime.pacing.begin(FramePacingProbe.Span.RENDER_SUBMIT)
	fun submitEnd() = runtime.pacing.end(FramePacingProbe.Span.RENDER_SUBMIT)

	fun reflexInputSample(): Boolean {
		runtime.pacing.begin(FramePacingProbe.Span.REFLEX_SLEEP)
		try {
			return runtime.frameEvaluation?.reflexInputSample() == true
		} finally {
			runtime.pacing.end(FramePacingProbe.Span.REFLEX_SLEEP)
		}
	}

	fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Boolean =
		runtime.frameEvaluation?.reflexMarker(type) == true

	val activeJitter: DlssJitterOffset?
		get() = if (isOpen) runtime.activeJitter else null

	val currentViewProjection: Matrix4f?
		get() = if (isOpen) runtime.currentViewProjection else null

	val sceneDepthView: GpuTextureView?
		get() = if (isOpen) (sceneTarget ?: mainRenderTarget)?.getDepthTextureView() else null

	val activeMotion: DlssFrameMotion?
		get() = if (isOpen) runtime.activeMotion else null

	fun prepare(
		normalInWorldFrame: Boolean,
		mainTarget: RenderTarget,
		camera: DlssCameraSample? = null,
	): DlssJitterOffset? {
		discardUnfinishedFrame()

		this.mainRenderTarget = mainTarget
		val snapshot = camera?.let {
			DlssCameraSample(
				projection = Matrix4f(it.projection),
				viewRotation = Matrix4f(it.viewRotation),
				cameraX = it.cameraX,
				cameraY = it.cameraY,
				cameraZ = it.cameraZ,
			)
		}
		this.cameraSample = snapshot
		if (snapshot != null) {
			val proj = unjitteredProjectionBuffer ?: runCatching {
				ProjectionMatrixBuffer("DLSS Unjittered Projection").also { unjitteredProjectionBuffer = it }
			}.getOrNull()
			unjitteredProjectionSlice = proj?.getBuffer(snapshot.projection)
		} else {
			unjitteredProjectionSlice = null
		}
		sceneTarget = if (mainTarget.width > 0 && mainTarget.height > 0) {
			runtime.beginWorldPhase(
				normalInWorldFrame,
				Dimensions(mainTarget.width, mainTarget.height),
				snapshot,
			)
		} else {
			null
		}
		projectionPrepared = true
		return if (sceneTarget != null) runtime.activeJitter else null
	}

	fun begin(normalInWorldFrame: Boolean, mainTarget: RenderTarget): RenderTarget {
		if (!projectionPrepared || this.mainRenderTarget !== mainTarget) {
			prepare(normalInWorldFrame, mainTarget)
		}
		isOpen = true

		val resolved = sceneTarget ?: mainTarget
		readout.reportWorldPhase(
			mainTarget = mainTarget,
			scene = sceneTarget,
			frame = runtime.worldTargetRoute?.frame,
			facts = SessionFacts(
				enabled = runtime.config.enabled,
				state = runtime.sessionState,
				qualityMode = runtime.qualityMode,
				renderPreset = runtime.renderPreset,
				outputDimensions = runtime.outputDimensions,
				renderDimensions = runtime.dlssRenderDimensions,
			),
			frameTimings = { runtime.frameEvaluation?.sampleTimings() },
			pacing = { runtime.pacing.sampleAndReset() },
			fgState = {
				if (runtime.frameGeneration.effective) runtime.frameEvaluation?.sampleFgState() else null
			},
		)
		if (resolved !== lastResolvedTarget) {
			lastResolvedTarget = resolved
			onWorldTargetChanged()
		}
		return resolved
	}

	fun end() {
		if (!isOpen) {
			return
		}
		BeforeDlssEvaluation.EVENT.invoker().run()

		val rendered = sceneTarget
		val destination = mainRenderTarget
		val jitter = runtime.activeJitter
		val motion = runtime.activeMotion
		val camera = this.cameraSample
		isOpen = false
		projectionPrepared = false
		sceneTarget = null
		mainRenderTarget = null
		this.cameraSample = null
		unjitteredProjectionSlice = null

		var completedDlssFrame = false
		try {
			if (rendered != null && destination != null) {
				completedDlssFrame = evaluateAndCompose(rendered, destination, jitter, motion, camera)
			}
		} finally {
			runtime.endWorldPhase()
		}

		if (rendered != null && destination != null && !completedDlssFrame) {
			present(rendered, destination)
		}
	}

	private fun evaluateAndCompose(
		rendered: RenderTarget,
		destination: RenderTarget,
		jitter: DlssJitterOffset?,
		motion: DlssFrameMotion?,
		camera: DlssCameraSample?,
	): Boolean {
		if (jitter == null || motion == null) {
			return false
		}

		return evaluateFrame(rendered, destination, jitter, motion, camera)
	}

	fun resetHistory() {
		discardUnfinishedFrame()
		runtime.resetHistory()
	}

	override fun close() {
		discardUnfinishedFrame()
		lastResolvedTarget = null
		unjitteredProjectionBuffer?.close()
		unjitteredProjectionBuffer = null
		unjitteredProjectionSlice = null
		runtime.close()
	}

	private fun discardUnfinishedFrame() {
		if (!isOpen && !projectionPrepared) {
			return
		}

		isOpen = false
		projectionPrepared = false
		sceneTarget = null
		mainRenderTarget = null
		cameraSample = null
		unjitteredProjectionSlice = null
		runtime.endWorldPhase()
		runtime.resetMotionHistory()
	}

	companion object {
		@JvmStatic
		fun forMinecraft(runtime: RenderRuntime, readout: SessionReadout): WorldPhase {
			return WorldPhase(
				runtime = runtime,
				present = ::blitSceneToMainTarget,
				onWorldTargetChanged = {
					Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.shouldResetSkyRenderer = true
				},
				readout = readout,
				evaluateFrame = { rendered, destination, jitter, motion, camera ->
					val evaluation = runtime.frameEvaluation
					val resources = sceneResourcesOf(rendered)
					val destinationImage = (destination.colorTextureView as? VulkanGpuTextureView)
						?.texture()
						?.vkImage()
					val destinationBinding = colorBindingOf(destination)
					val storageWritable = (destination.colorTexture as? StoragePresentationImage)?.mcDlssStorageWritable() == true
					val directPresentation = storageWritable && !java.lang.Boolean.getBoolean("mc.dlss.legacy-present")
					if (evaluation == null || resources == null || destinationImage == null) {
						false
					} else {
						evaluation.evaluateFrame(
							resources,
							jitter,
							motion,
							destinationImage,
							camera = camera,
							destination = destinationBinding,
							destinationImageUsage = if (directPresentation) org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT else 0,
						)
					}
				},
			)
		}

		internal fun resolveFrameGenerationInputs(): FgFrameInputs? {
			val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
			val ui = ClientRuntime.active().activeUiPhase()?.uiTarget ?: return null
			if (ui.width != main.width || ui.height != main.height) {
				return null
			}
			val hudless = colorBindingOf(main) ?: return null
			val uiBinding = colorBindingOf(ui) ?: return null
			return FgFrameInputs(hudless, uiBinding)
		}

		private fun sceneResourcesOf(target: RenderTarget): SceneResources? {
			val color = colorBindingOf(target) ?: return null
			val depth = target.depthTextureView as? VulkanGpuTextureView ?: return null
			return SceneResources(
				color = color,
				depth = ImageBinding(
					depth.vkImageView(),
					depth.texture().vkImage(),
					VulkanConst.toVk(depth.texture().format),
				),
			)
		}

		internal fun colorBindingOf(target: RenderTarget): ImageBinding? {
			val color = target.colorTextureView as? VulkanGpuTextureView ?: return null
			return ImageBinding(
				color.vkImageView(),
				color.texture().vkImage(),
				VulkanConst.toVk(color.texture().format),
			)
		}

		private fun blitSceneToMainTarget(scene: RenderTarget, main: RenderTarget) {
			val source = scene.colorTextureView ?: return
			val destination = main.colorTextureView ?: return

			RenderSystem.getDevice()
				.createCommandEncoder()
				.createRenderPass({ "DLSS scene present" }, destination, Optional.empty())
				.use { pass ->
					RenderSystem.bindDefaultUniforms(pass)
					pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT))
					pass.setUniform(
						"InSampler",
						source,
						RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST),
					)
					pass.draw(3, 1, 0, 0)
				}
		}
	}
}
