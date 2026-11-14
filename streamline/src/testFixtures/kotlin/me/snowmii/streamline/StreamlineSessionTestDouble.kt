package me.snowmii.streamline

import java.nio.file.Path

open class StreamlineSessionTestDouble : StreamlineSession {
	override fun close() = Unit

	override fun initialize(
		vkInstance: Long,
		vkPhysicalDevice: Long,
		vkDevice: Long,
		sdkPath: Path,
		dataPath: Path,
	): Int = notStubbed("initialize")

	override fun isFeatureSupported(feature: Int, vkPhysicalDevice: Long): Int = notStubbed("isFeatureSupported")
	override fun queryOptimalDimensions(
		outputWidth: Int,
		outputHeight: Int,
		qualityMode: Int,
	): Dimensions = notStubbed("queryOptimalDimensions")

	override fun queryOptimalDimensionsRr(
		outputWidth: Int,
		outputHeight: Int,
		qualityMode: Int,
	): Dimensions = notStubbed("queryOptimalDimensionsRr")

	override fun configureSuperResolution(
		outputWidth: Int,
		outputHeight: Int,
		renderWidth: Int,
		renderHeight: Int,
		qualityMode: Int,
		renderPreset: Int,
	): Int = notStubbed("configure")

	override fun configureFg(numBackBuffers: Int): Int = notStubbed("configureFg")

	override fun setFgMode(fgEnabled: Int): Int = notStubbed("setFgMode")

	override fun setFgMultiplier(numFramesToGenerate: Int): Int = notStubbed("setFgMultiplier")

	override fun queryFgMultiplier(): FgMultiplier = notStubbed("queryFgMultiplier")

	override fun acquireImages(): EvaluationImages = notStubbed("acquireImages")

	override fun releaseImages(): Int = notStubbed("releaseImages")

	override fun waitDeviceIdle(): Int = notStubbed("waitDeviceIdle")

	override fun frameTimings(): FrameTimings? = notStubbed("frameTimings")

	override fun writeMotion(request: MotionRequest): Int = notStubbed("writeMotion")

	override fun presentOutput(target: PresentTarget): Int = notStubbed("presentOutput")

	override fun evaluateSuperResolution(request: EvaluationRequest): Int = notStubbed("evaluate")

	override fun tagSrResources(request: SrTagRequest): Int = notStubbed("tagSrResources")

	override fun prepareRayReconstruction(request: RrPrepareRequest): Int =
		if (!request.enabled) StreamlineSession.SUCCESS_RESULT else notStubbed("prepareRayReconstruction")

	override fun tagFrameGenerationResources(request: FgTagRequest): Int = notStubbed("tagFgResources")

	override fun recordPresentHandoff(): Int = notStubbed("presentHandoff")

	override fun presentStart(): Int = notStubbed("presentStart")

	override fun presentEnd(): Int = notStubbed("presentEnd")

	override fun presentMarkers(): PresentMarkerEvents = notStubbed("presentMarkers")

	override fun installPclWindow(hwnd: Long): Int = notStubbed("installPclWindow")

	override fun reflexInputSample(): Int = notStubbed("reflexInputSample")

	override fun reflexMarker(type: StreamlineSession.ReflexMarkerType): Int = notStubbed("reflexMarker")

	override fun reflexMarkers(): StreamlineSession.ReflexMarkerEvents = notStubbed("reflexMarkers")

	override fun queryReflexOptions(): StreamlineSession.ReflexRegistration = notStubbed("queryReflexOptions")

	override fun setReflexMode(mode: Int): Int = notStubbed("setReflexMode")

	override fun queryReflexState(): StreamlineSession.ReflexStateInfo = notStubbed("queryReflexState")

	override fun recordReflexFrameLimit(frameLimitUs: Int): Int = notStubbed("recordReflexFrameLimit")

	override fun waitFgInputsIdle(): Int = notStubbed("waitFgInputsIdle")

	override fun waitFgInputsValue(vkDevice: Long, semaphore: Long, value: Long): Int =
		notStubbed("waitFgInputsValue")

	override fun queryFgState(): FgState = notStubbed("queryFgState")

	override fun isFgVsyncSupported(): Boolean = false

	override fun queryMotionProbe(): MotionProbeSample = notStubbed("queryMotionProbe")

	override fun queryCameraConstants(): CameraConstants = notStubbed("queryCameraConstants")

	override fun queryFgCameraConstants(): CameraConstants = notStubbed("queryFgCameraConstants")

	override fun queryFgImages(): StreamlineSession.FgOrientationImages = notStubbed("queryFgImages")

	override fun queryDeviceFeatures12(): List<String> = notStubbed("queryDeviceFeatures12")

	override fun queryDeviceFeatures13(): List<String> = notStubbed("queryDeviceFeatures13")

	override fun taggedFrameIndexes(): TaggedFrameIndexes = notStubbed("taggedFrameIndexes")

	override fun queryQueueRequirements(): SlQueueRequirements = notStubbed("queryQueueRequirements")

	private fun notStubbed(call: String): Nothing = throw UnsupportedOperationException(call)
}
