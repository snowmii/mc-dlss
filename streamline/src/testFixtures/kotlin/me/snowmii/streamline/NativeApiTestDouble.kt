package me.snowmii.streamline

import java.nio.file.Path

/**
 * Base class for a [NativeApi] test double: extend it and override only the calls the test
 * exercises.
 *
 * `NativeApi` used to carry 27 `default` bodies that threw, for exactly this purpose - so a
 * double could implement three calls and inherit the rest. That shaped the production interface,
 * now the public surface of a Fabric library mod, around its test doubles: an external
 * implementer got no compiler diagnostic for the 27 calls they missed, only a runtime throw. The
 * interface is all-abstract, and the accommodation lives here on the test side, where it belongs.
 *
 * Every call this class answers throws `UnsupportedOperationException` naming it, exactly as the
 * deleted defaults did, so a double that forgets a call it depends on fails the way it always did
 * rather than passing on a silent no-op.
 */
open class NativeApiTestDouble : NativeApi {
	override fun initialize(
		vkInstance: Long,
		vkPhysicalDevice: Long,
		vkDevice: Long,
		sdkPath: Path,
		dataPath: Path,
	): Int = notStubbed("initialize")

	override fun queryOptimalDimensions(
		outputWidth: Int,
		outputHeight: Int,
		qualityMode: Int,
	): Dimensions = notStubbed("queryOptimalDimensions")

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

	// Nullable because the ABI itself is: Native.frameTimings answers null for "no measurement
	// yet", which is not a failure the session latches.
	override fun frameTimings(): FrameTimings? = notStubbed("frameTimings")

	override fun writeMotion(request: MotionRequest): Int = notStubbed("writeMotion")

	override fun fillVelocity(request: FillVelocityRequest): Int = notStubbed("fillVelocity")

	override fun presentOutput(target: PresentTarget): Int = notStubbed("presentOutput")

	override fun evaluateSuperResolution(request: EvaluationRequest): Int = notStubbed("evaluate")

	override fun tagSrResources(request: SrTagRequest): Int = notStubbed("tagSrResources")

	override fun tagFrameGenerationResources(request: FgTagRequest): Int = notStubbed("tagFgResources")

	override fun recordPresentHandoff(): Int = notStubbed("presentHandoff")

	override fun presentStart(): Int = notStubbed("presentStart")

	override fun presentEnd(): Int = notStubbed("presentEnd")

	override fun presentMarkers(): PresentMarkerEvents = notStubbed("presentMarkers")

	override fun installPclWindow(hwnd: Long): Int = notStubbed("installPclWindow")

	override fun reflexInputSample(): Int = notStubbed("reflexInputSample")

	override fun reflexMarker(type: NativeApi.ReflexMarkerType): Int = notStubbed("reflexMarker")

	override fun reflexMarkers(): NativeApi.ReflexMarkerEvents = notStubbed("reflexMarkers")

	override fun queryReflexOptions(): NativeApi.ReflexRegistration = notStubbed("queryReflexOptions")

	override fun recordReflexFrameLimit(frameLimitUs: Int): Int = notStubbed("recordReflexFrameLimit")

	override fun waitFgInputsIdle(): Int = notStubbed("waitFgInputsIdle")

	override fun waitFgInputsValue(vkDevice: Long, semaphore: Long, value: Long): Int =
		notStubbed("waitFgInputsValue")

	override fun queryFgState(): FgState = notStubbed("queryFgState")

	override fun queryCameraConstants(): CameraConstants = notStubbed("queryCameraConstants")

	override fun queryFgCameraConstants(): CameraConstants = notStubbed("queryFgCameraConstants")

	override fun queryFgImages(): NativeApi.FgOrientationImages = notStubbed("queryFgImages")

	override fun queryDeviceFeatures12(): List<String> = notStubbed("queryDeviceFeatures12")

	override fun queryDeviceFeatures13(): List<String> = notStubbed("queryDeviceFeatures13")

	override fun taggedFrameIndexes(): TaggedFrameIndexes = notStubbed("taggedFrameIndexes")

	override fun queryQueueRequirements(): SlQueueRequirements = notStubbed("queryQueueRequirements")

	private fun notStubbed(call: String): Nothing = throw UnsupportedOperationException(call)
}
