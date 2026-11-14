package me.snowmii.dlss.render
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FgTagRequest
import me.snowmii.streamline.FgState
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget

import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.RrPrepareRequest
import me.snowmii.streamline.Vec2
import me.snowmii.dlss.vk.VulkanContext
import me.snowmii.dlss.api.RayReconstruction
import me.snowmii.dlss.api.RayReconstructionGuides
import me.snowmii.dlss.readout.SessionReadout
import me.snowmii.dlss.streamline.LifecycleAdapter
import org.joml.Matrix4f
import org.joml.Vector4f
import kotlin.math.abs
import org.lwjgl.vulkan.VkCommandBuffer

data class SceneResources(
	val color: ImageBinding,
	val depth: ImageBinding,
)

data class FgFrameInputs(
	val hudless: ImageBinding,
	val ui: ImageBinding,
)

internal fun dlssFrustum(
        projection: Matrix4f,
        inverse: Matrix4f = Matrix4f(projection).invert(),
        output: FloatArray = FloatArray(4),
): FloatArray {
        require(output.size >= 4) { "frustum output needs four values" }
	val atDepthZero = inverse.transform(Vector4f(0f, 0f, 0f, 1f))
	val atDepthOne = inverse.transform(Vector4f(0f, 0f, 1f, 1f))
	val firstPlane = abs(atDepthZero.z / atDepthZero.w)
	val secondPlane = abs(atDepthOne.z / atDepthOne.w)
	val aspect = if (projection.m00() != 0f) projection.m11() / projection.m00() else 0f
        output[0] = minOf(firstPlane, secondPlane)
        output[1] = maxOf(firstPlane, secondPlane)
        output[2] = projection.perspectiveFov()
        output[3] = aspect
        return output
}

class FrameEvaluation(
	private val adapter: LifecycleAdapter,
	private val context: () -> VulkanContext?,
	private val readout: SessionReadout? = null,
	private val frameGeneration: FgSurfacePolicy = FgSurfacePolicy(),
	private val fgInputs: () -> FgFrameInputs? = { null },
) : AutoCloseable {
	private var nativeEvaluationImages: EvaluationImages? = null
	private var firstEvaluationReported = false
	private var lastConfiguredBackBuffers = 0
        private val frustumScratch = FloatArray(4)
	private var previousEvaluationUsedRr: Boolean? = null

	fun presentStart(): Boolean = adapter.presentStart()
	fun presentEnd(): Boolean = adapter.presentEnd()

	fun reflexInputSample(): Boolean = adapter.reflexInputSample()
	fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Boolean = adapter.reflexMarker(type)

	val evaluationImages: EvaluationImages?
		get() = nativeEvaluationImages

	fun evaluateFrame(
		scene: SceneResources,
		jitter: DlssJitterOffset,
		motion: DlssFrameMotion,
		destinationImage: Long = NO_DESTINATION,
		camera: DlssCameraSample? = null,
		destination: ImageBinding? = null,
		destinationImageUsage: Int = 0,
	): Boolean {
		val vulkan = context() ?: return false
		val held = nativeEvaluationImages ?: adapter.acquireImages()?.also { nativeEvaluationImages = it } ?: return false

		val frame = EvaluationFrame(scene, jitter, motion, camera,
			if (RayReconstruction.enabled && camera != null) RayReconstruction.guides?.get() else null,
			if (frameGeneration.effective) fgInputs() else null)
		val buffer = vulkan.allocateRecordingCommandBuffer()
		var recorded = false
		try {
			recorded = recordFrame(buffer, frame, destinationImage, destination, destinationImageUsage)
		} finally {
			if (!recorded) previousEvaluationUsedRr = null
			vulkan.enqueueOnEngineEncoder(buffer)
		}
		reportFirstEvaluation(recorded, scene, held)
		return recorded
	}

	private class EvaluationFrame(
		val scene: SceneResources,
		val jitter: DlssJitterOffset,
		val motion: DlssFrameMotion,
		val camera: DlssCameraSample?,
		val rr: RayReconstructionGuides?,
		val fg: FgFrameInputs?,
	)

	private fun timed(buffer: VkCommandBuffer, name: String, record: () -> Boolean): Boolean =
		me.snowmii.dlss.api.GpuProfiling.begin?.invoke(buffer, name).use { record() }

	private fun recordFrame(
		buffer: VkCommandBuffer,
		frame: EvaluationFrame,
		destinationImage: Long,
		destination: ImageBinding?,
		destinationImageUsage: Int,
	): Boolean = timed(buffer, "g-dlss-total") {
		val scene = frame.scene
		val fg = frame.fg
		val motionScene = if (fg == null && frame.rr?.depth != null) scene.copy(depth = frame.rr.depth) else scene
		val handle = buffer.address()
		timed(buffer, "g-dlss-motion") {
			recordMotion(handle, motionScene, frame.motion, fg != null)
		} && (fg == null || timed(buffer, "g-dlss-fg-start") { recordFrameGenerationStart(handle, scene, fg) }) &&
			timed(buffer, if (frame.rr != null) "g-dlss-rr" else "g-dlss-sr") {
				recordSuperResolution(buffer, frame, motionScene.depth)
			} && (destinationImage == NO_DESTINATION || timed(buffer, "g-dlss-output-copy") {
				val reflection = if (previousEvaluationUsedRr == true) frame.rr?.reflection else null
				copyDlssOutput(buffer, destinationImage, reflection, destination, destinationImageUsage)
			}) && (fg == null || timed(buffer, "g-dlss-fg-end") { recordFrameGenerationEnd(handle, scene, fg) })
	}

	fun sampleTimings(): FrameTimings? = adapter.frameTimings()

	fun sampleFgState(): FgState? = adapter.queryFgState()

	override fun close() {
		previousEvaluationUsedRr = null
		if (nativeEvaluationImages == null) {
			return
		}

		nativeEvaluationImages = null
		lastConfiguredBackBuffers = 0
		adapter.releaseImages()
	}

	private fun copyDlssOutput(
		buffer: VkCommandBuffer,
		destinationImage: Long,
		reflection: ImageBinding?,
		destination: ImageBinding?,
		imageUsage: Int,
	): Boolean = adapter.presentOutput(
		if (destination != null) PresentTarget(buffer.address(), destination, null, reflection, imageUsage)
		else PresentTarget(buffer.address(), destinationImage, null, reflection),
	)

	private fun recordSuperResolution(
		buffer: VkCommandBuffer,
		frame: EvaluationFrame,
		initialMotionDepth: ImageBinding,
	): Boolean {
		val handle = buffer.address()
		val scene = frame.scene
		val rr = frame.rr
		val useRr = rr != null
		val externalReset = RayReconstruction.consumeReset() || (rr?.resetHistory == true)
		if (!prepareReconstruction(frame)) {
			return recordSuperResolutionSrOnly(handle, frame, initialMotionDepth, externalReset)
		}

		val depth = rr?.depth ?: scene.depth
		if (!recordDepthMotion(handle, frame, initialMotionDepth, depth)) return false
		val color = rr?.noisyColor ?: scene.color
		if (!adapter.tagSrResources(SrTagRequest(handle, color, depth), allowFallback = useRr)) {
			return useRr && recordSuperResolutionSrOnly(handle, frame, depth, externalReset)
		}
		val succeeded = recordEvaluation(handle, frame, color, depth, useRr, externalReset)
		val restored = frame.fg == null || recordDepthMotion(handle, frame, depth, scene.depth)
		return succeeded && restored
	}

	private fun prepareReconstruction(frame: EvaluationFrame): Boolean {
		val rr = frame.rr ?: return adapter.prepareRayReconstruction(RrPrepareRequest(enabled = false))
		val camera = checkNotNull(frame.camera)
		adapter.setExposure(RayReconstruction.exposure)
		adapter.setTonemapOperator(RayReconstruction.tonemapOperator)
		val worldToView = Matrix4f(camera.viewRotation)
		return adapter.prepareRayReconstruction(RrPrepareRequest(
			enabled = true,
			rrPreset = RayReconstruction.modelPreset.sdkValue,
			diffuseAlbedo = rr.diffuseAlbedo,
			specularAlbedo = rr.specularAlbedo,
			normalRoughness = rr.normalRoughness,
			specularHitDistance = rr.specularHitDistance,
			specularMotion = rr.specularMotion ?: ImageBinding(0L, 0L, 0),
			biasCurrentColor = rr.biasCurrentColor ?: ImageBinding(0L, 0L, 0),
			worldToCameraView = CameraConstants.rowMajorOf(worldToView),
			cameraViewToWorld = CameraConstants.rowMajorOf(Matrix4f(worldToView).invert()),
		))
	}

	private fun recordDepthMotion(handle: Long, frame: EvaluationFrame, previous: ImageBinding, depth: ImageBinding): Boolean =
		previous == depth || recordMotion(handle, frame.scene.copy(depth = depth),
			frame.motion, frame.fg != null)

	private fun recordSuperResolutionSrOnly(
		handle: Long,
		frame: EvaluationFrame,
		motionDepth: ImageBinding,
		externalReset: Boolean,
	): Boolean {
		if (!adapter.prepareRayReconstruction(RrPrepareRequest(enabled = false))) return false
		if (!recordDepthMotion(handle, frame, motionDepth, frame.scene.depth)) return false
		if (!adapter.tagSrResources(SrTagRequest(handle, frame.scene.color, frame.scene.depth))) return false
		return recordEvaluation(handle, frame, frame.scene.color, frame.scene.depth, false, externalReset)
	}

	private fun recordEvaluation(
		handle: Long,
		frame: EvaluationFrame,
		color: ImageBinding,
		depth: ImageBinding,
		useRr: Boolean,
		externalReset: Boolean,
	): Boolean {
		val motion = frame.motion
		val jitter = frame.jitter
		val resetHistory = motion.reset || previousEvaluationUsedRr != useRr || externalReset
		readout?.recordFrameJitter(jitter.index, jitter.pixelX, jitter.pixelY, resetHistory)
		return adapter.evaluate(EvaluationRequest.builder()
			.commandBuffer(handle)
			.color(color)
			.depth(depth)
			.jitter(Vec2(jitter.pixelX, jitter.pixelY))
			.motionScale(Vec2(motion.motionScaleX, motion.motionScaleY))
			.frameTimeMilliseconds(motion.frameTimeMillis)
			.resetHistory(resetHistory)
			.camera(frame.camera?.let { cameraConstants(it, motion) })
			.build()
		).also { succeeded -> previousEvaluationUsedRr = if (succeeded) useRr else null }
	}

	private fun recordMotion(
		handle: Long,
		scene: SceneResources,
		motion: DlssFrameMotion,
		fgConsumingFrame: Boolean = false,
	): Boolean = adapter.writeMotion(
		MotionRequest(
			handle,
			scene.depth,
			FloatArray(16).also { motion.reprojection.get(it) },
			fgConsumingFrame,
			null,
		),
	)

	private fun recordFrameGenerationStart(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean {
		if (fg == null) return true
		val backBuffers = frameGeneration.requiredSwapchainImages
		if (backBuffers != lastConfiguredBackBuffers) {
			if (!adapter.configureFg(backBuffers)) return false
			lastConfiguredBackBuffers = backBuffers
		}
		return tagFg(handle, scene, fg)
	}

	private fun recordFrameGenerationEnd(handle: Long, scene: SceneResources, fg: FgFrameInputs?): Boolean =
		fg == null || (tagFg(handle, scene, fg) && adapter.presentHandoff())

	private fun tagFg(handle: Long, scene: SceneResources, fg: FgFrameInputs): Boolean =
		adapter.tagFgResources(
			FgTagRequest(
				handle,
				scene.depth,
				fg.hudless,
				fg.ui,
			),
		)

	private fun reportFirstEvaluation(
		recorded: Boolean,
		scene: SceneResources,
		held: EvaluationImages,
	) {
		if (firstEvaluationReported) {
			return
		}

		firstEvaluationReported = true
		readout?.reportFirstEvaluation(
			recorded = recorded,
			colorImage = scene.color.image,
			depthImage = scene.depth.image,
			motionImage = held.motion.image,
			outputImage = held.output.image,
		)
	}

	private fun cameraConstants(camera: DlssCameraSample, motion: DlssFrameMotion): CameraConstants {
		val rotation = camera.viewRotation
		val invProj = Matrix4f(camera.projection).invert()
                val frustum = dlssFrustum(camera.projection, invProj, frustumScratch)
		return CameraConstants(
			CameraConstants.rowMajorOf(camera.projection),
			CameraConstants.rowMajorOf(invProj),
			floatArrayOf(
				camera.cameraX.toFloat(),
				camera.cameraY.toFloat(),
				camera.cameraZ.toFloat(),
			),
			floatArrayOf(rotation.m00(), rotation.m10(), rotation.m20()),
			floatArrayOf(rotation.m01(), rotation.m11(), rotation.m21()),
			floatArrayOf(-rotation.m02(), -rotation.m12(), -rotation.m22()),
			CameraConstants.rowMajorOf(motion.clipToPrevClip),
			CameraConstants.rowMajorOf(Matrix4f(motion.clipToPrevClip).invert()),
			frustum[0],
			frustum[1],
			frustum[2],
			frustum[3],
			0f,
			0f,
		)
	}

	private companion object {
		const val NO_DESTINATION = 0L
	}
}
