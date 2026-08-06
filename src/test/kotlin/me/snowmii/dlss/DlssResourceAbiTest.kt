package me.snowmii.dlss

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

class DlssResourceAbiTest {
	@Test
	fun adapterForwardsDistinctResourceMetadataAndConfiguredDimensions() {
		var evaluationArguments: List<Any?>? = null
		val native = Proxy.newProxyInstance(
			javaClass.classLoader,
			arrayOf(DlssNativeApi::class.java),
		) { _, method, arguments ->
			when (method.name) {
				"initialize", "configure" -> DlssNativeApi.SUCCESS_RESULT
				"queryOptimalDimensions" -> DlssDimensions(1280, 720)
				"evaluate" -> {
					evaluationArguments = arguments.toList()
					DlssNativeApi.SUCCESS_RESULT
				}
				else -> error("Unexpected native call: ${method.name}")
			}
		} as DlssNativeApi
		val outputDimensions = DlssDimensions(2560, 1440)
		val adapter = DlssLifecycleAdapter(DlssSession(config(outputDimensions)), native)
		val request = request()

		assertEquals(
			DlssDimensions(1280, 720),
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")),
		)
		assertTrue(adapter.evaluate(request))
		assertEquals(
			listOf(
				request.commandBuffer,
				request.colorView, request.colorImage, request.colorFormat, request.colorAspectMask,
				request.colorBaseMipLevel, request.colorLevelCount, request.colorBaseArrayLayer,
				request.colorLayerCount,
				request.depthView, request.depthImage, request.depthFormat, request.depthAspectMask,
				request.depthBaseMipLevel, request.depthLevelCount, request.depthBaseArrayLayer,
				request.depthLayerCount,
				request.motionView, request.motionImage, request.motionFormat, request.motionAspectMask,
				request.motionBaseMipLevel, request.motionLevelCount, request.motionBaseArrayLayer,
				request.motionLayerCount,
				request.outputView, request.outputImage, request.outputFormat, request.outputAspectMask,
				request.outputBaseMipLevel, request.outputLevelCount, request.outputBaseArrayLayer,
				request.outputLayerCount,
				1280, 720, outputDimensions.width, outputDimensions.height,
				request.jitterX, request.jitterY, request.motionScaleX, request.motionScaleY,
				request.frameTimeMilliseconds, request.resetHistory,
			),
			evaluationArguments,
		)
	}

	@Test
	fun nativeConstructionPreservesEveryResourceMetadataAndDimensions() {
		val source = Files.readString(Path.of("native", "mc_dlss.cpp"))

		listOf("color", "depth", "motion", "output").forEach { resource ->
			assertTrue(
				Regex(
					"""const DlssImageResourceInput ${resource}ResourceInput\{\s*${resource}_view, ${resource}_image, ${resource}_format, ${resource}_aspect_mask, ${resource}_base_mip_level,\s*${resource}_level_count, ${resource}_base_array_layer, ${resource}_layer_count\}""",
				).containsMatchIn(source),
				"$resource input must retain view, image, format, and complete subresource range",
			)
		}
		assertTrue(source.contains("static_cast<VkImageAspectFlags>(resource.aspectMask)"))
		assertTrue(source.contains("resource.baseMipLevel,"))
		assertTrue(source.contains("resource.levelCount,"))
		assertTrue(source.contains("resource.baseArrayLayer,"))
		assertTrue(source.contains("resource.layerCount,"))
		assertTrue(source.contains("from_uint64<VkImageView>(resource.imageView), from_uint64<VkImage>(resource.image)"))
		assertTrue(source.contains("static_cast<VkFormat>(resource.format), width, height, readWrite"))
		assertTrue(source.contains("colorResourceInput, render_width, render_height, false"))
		assertTrue(source.contains("depthResourceInput, render_width, render_height, false"))
		assertTrue(source.contains("motionResourceInput, render_width, render_height, false"))
		assertTrue(source.contains("outputResourceInput, output_width, output_height, true"))
	}

	@Test
	fun compiledFfmBindingPreservesCompleteEvaluateAbiArgumentOrder() {
		val request = request()
		assertTrue(Files.isRegularFile(Path.of("build", "native", "mc_dlss.dll")))

		DlssNative.open(compileEvaluateAbiProbe()).use { native ->
			assertEquals(
				DlssNativeApi.SUCCESS_RESULT,
				native.evaluate(
					request.commandBuffer,
					request.colorView, request.colorImage, request.colorFormat, request.colorAspectMask,
					request.colorBaseMipLevel, request.colorLevelCount, request.colorBaseArrayLayer,
					request.colorLayerCount,
					request.depthView, request.depthImage, request.depthFormat, request.depthAspectMask,
					request.depthBaseMipLevel, request.depthLevelCount, request.depthBaseArrayLayer,
					request.depthLayerCount,
					request.motionView, request.motionImage, request.motionFormat, request.motionAspectMask,
					request.motionBaseMipLevel, request.motionLevelCount, request.motionBaseArrayLayer,
					request.motionLayerCount,
					request.outputView, request.outputImage, request.outputFormat, request.outputAspectMask,
					request.outputBaseMipLevel, request.outputLevelCount, request.outputBaseArrayLayer,
					request.outputLayerCount,
					1280, 720, 2560, 1440,
					request.jitterX, request.jitterY, request.motionScaleX, request.motionScaleY,
					request.frameTimeMilliseconds, request.resetHistory,
				),
			)
		}
	}

	private fun compileEvaluateAbiProbe(): Path {
		val directory = Path.of("build", "abi-probe").toAbsolutePath()
		val source = directory.resolve("mc_dlss_abi_probe.cpp")
		val library = directory.resolve("mc_dlss_abi_probe.dll")
		Files.createDirectories(directory)
		Files.writeString(source, """
			#include "mc_dlss.h"

			extern "C" {
			__declspec(dllexport) int __cdecl mc_dlss_initialize(
				uint64_t, uint64_t, uint64_t, const char*, const char*) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_query_optimal_dimensions(
				uint32_t, uint32_t, uint32_t, uint32_t*, uint32_t*) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_configure(
				uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_evaluate(
				uint64_t commandBuffer,
				uint64_t colorView, uint64_t colorImage, uint32_t colorFormat, uint32_t colorAspectMask,
				uint32_t colorBaseMipLevel, uint32_t colorLevelCount, uint32_t colorBaseArrayLayer, uint32_t colorLayerCount,
				uint64_t depthView, uint64_t depthImage, uint32_t depthFormat, uint32_t depthAspectMask,
				uint32_t depthBaseMipLevel, uint32_t depthLevelCount, uint32_t depthBaseArrayLayer, uint32_t depthLayerCount,
				uint64_t motionView, uint64_t motionImage, uint32_t motionFormat, uint32_t motionAspectMask,
				uint32_t motionBaseMipLevel, uint32_t motionLevelCount, uint32_t motionBaseArrayLayer, uint32_t motionLayerCount,
				uint64_t outputView, uint64_t outputImage, uint32_t outputFormat, uint32_t outputAspectMask,
				uint32_t outputBaseMipLevel, uint32_t outputLevelCount, uint32_t outputBaseArrayLayer, uint32_t outputLayerCount,
				uint32_t renderWidth, uint32_t renderHeight, uint32_t outputWidth, uint32_t outputHeight,
				float jitterX, float jitterY, float motionScaleX, float motionScaleY, float frameTimeMilliseconds,
				int32_t resetHistory) {
				return commandBuffer == 101 &&
					colorView == 201 && colorImage == 202 && colorFormat == 203 && colorAspectMask == 204 &&
					colorBaseMipLevel == 205 && colorLevelCount == 206 && colorBaseArrayLayer == 207 && colorLayerCount == 208 &&
					depthView == 301 && depthImage == 302 && depthFormat == 303 && depthAspectMask == 304 &&
					depthBaseMipLevel == 305 && depthLevelCount == 306 && depthBaseArrayLayer == 307 && depthLayerCount == 308 &&
					motionView == 401 && motionImage == 402 && motionFormat == 403 && motionAspectMask == 404 &&
					motionBaseMipLevel == 405 && motionLevelCount == 406 && motionBaseArrayLayer == 407 && motionLayerCount == 408 &&
					outputView == 501 && outputImage == 502 && outputFormat == 503 && outputAspectMask == 504 &&
					outputBaseMipLevel == 505 && outputLevelCount == 506 && outputBaseArrayLayer == 507 && outputLayerCount == 508 &&
					renderWidth == 1280 && renderHeight == 720 && outputWidth == 2560 && outputHeight == 1440 &&
					jitterX == 0.25f && jitterY == -0.5f && motionScaleX == 1.25f && motionScaleY == 1.5f &&
					frameTimeMilliseconds == 16.7f && resetHistory == 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_acquire_images(
				uint64_t*, uint64_t*, uint32_t*, uint64_t*, uint64_t*, uint32_t*) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_release_images() { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_write_motion(
				uint64_t, uint64_t, uint64_t, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t,
				const float*, uint32_t, uint32_t) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_present_output(
				uint64_t, uint64_t, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t,
				uint32_t, uint32_t) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_reset() { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_close() { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_query_instance_extension(
				uint32_t index, char* name, uint32_t name_capacity, uint32_t* extension_count) {
				if (extension_count == nullptr) return 0;
				*extension_count = 1;
				if (name == nullptr) return 1;
				if (index == 0 && name_capacity > 3) { name[0]='V'; name[1]='K'; name[2]='_'; name[3]=0; }
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_query_device_extension(
				uint64_t, uint64_t, uint32_t index, char* name, uint32_t name_capacity, uint32_t* extension_count) {
				if (extension_count == nullptr) return 0;
				*extension_count = 1;
				if (name == nullptr) return 1;
				if (index == 0 && name_capacity > 3) { name[0]='V'; name[1]='K'; name[2]='_'; name[3]=0; }
				return 1;
			}
			}
		""".trimIndent())

		val vsDevCmd = Path.of(
			System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)",
			"Microsoft Visual Studio", "2022", "BuildTools", "Common7", "Tools", "VsDevCmd.bat",
		)
		assertTrue(Files.isRegularFile(vsDevCmd), "Visual Studio Build Tools missing: $vsDevCmd")
		val nativeHeaders = Path.of("native").toAbsolutePath()
		val compiler = "call \"$vsDevCmd\" -arch=x64 -host_arch=x64 >nul && cl.exe /nologo /std:c++17 /LD /O2 /I\"$nativeHeaders\" /Fe\"$library\" \"$source\""
		val process = ProcessBuilder("cmd.exe", "/d", "/c", compiler)
			.directory(directory.toFile())
			.inheritIO()
			.start()
		assertEquals(0, process.waitFor(), "FFM ABI probe compilation failed")
		assertTrue(Files.isRegularFile(library), "FFM ABI probe must produce $library")
		return library
	}

	private fun request() = DlssEvaluationRequest(
		commandBuffer = 101L,
		colorView = 201L,
		colorImage = 202L,
		colorFormat = 203,
		colorAspectMask = 204,
		colorBaseMipLevel = 205,
		colorLevelCount = 206,
		colorBaseArrayLayer = 207,
		colorLayerCount = 208,
		depthView = 301L,
		depthImage = 302L,
		depthFormat = 303,
		depthAspectMask = 304,
		depthBaseMipLevel = 305,
		depthLevelCount = 306,
		depthBaseArrayLayer = 307,
		depthLayerCount = 308,
		motionView = 401L,
		motionImage = 402L,
		motionFormat = 403,
		motionAspectMask = 404,
		motionBaseMipLevel = 405,
		motionLevelCount = 406,
		motionBaseArrayLayer = 407,
		motionLayerCount = 408,
		outputView = 501L,
		outputImage = 502L,
		outputFormat = 503,
		outputAspectMask = 504,
		outputBaseMipLevel = 505,
		outputLevelCount = 506,
		outputBaseArrayLayer = 507,
		outputLayerCount = 508,
		jitterX = 0.25f,
		jitterY = -0.5f,
		motionScaleX = 1.25f,
		motionScaleY = 1.5f,
		frameTimeMilliseconds = 16.7f,
		resetHistory = true,
	)

	private fun config(outputDimensions: DlssDimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = DlssQualityMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
