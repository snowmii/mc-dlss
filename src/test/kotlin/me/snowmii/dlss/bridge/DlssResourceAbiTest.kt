package me.snowmii.dlss.bridge

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.LifecycleAdapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path

class DlssResourceAbiTest {
	@Test
	fun adapterStampsConfiguredDimensionsOntoTheRequest() {
		var evaluated: EvaluationRequest? = null
		val native = Proxy.newProxyInstance(
			javaClass.classLoader,
			arrayOf(NativeApi::class.java),
		) { _, method, arguments ->
			when (method.name) {
				"initialize", "configure" -> NativeApi.SUCCESS_RESULT
				"queryOptimalDimensions" -> DlssDimensions(1280, 720)
				"evaluate" -> {
					evaluated = arguments[0] as EvaluationRequest
					NativeApi.SUCCESS_RESULT
				}
				else -> error("Unexpected native call: ${method.name}")
			}
		} as NativeApi
		val outputDimensions = DlssDimensions(2560, 1440)
		val adapter = LifecycleAdapter(DlssSession(config(outputDimensions)), native)
		val request = request()

		assertEquals(
			DlssDimensions(1280, 720),
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")),
		)
		assertTrue(adapter.evaluate(request))
		// The engine's two images cross untouched, and the render size the bridge checks its
		// caller against is added by the adapter rather than by whoever described the frame.
		assertEquals(request.copy(renderDimensions = DlssDimensions(1280, 720)), evaluated)
	}

	@Test
	fun theEvaluationCarriesOnlyTheEnginesOwnImages() {
		// The motion and output images are the bridge's own, so they are absent from the request
		// and from the ABI struct. This is the invariant that removed six of the ABI's arguments;
		// a field reappearing here means a caller is handing the bridge handles it already holds.
		val header = Files.readString(Path.of("native", "mc_dlss.h")).replace("\r\n", "\n")
		val evaluateStruct = header.substringAfter("typedef struct McDlssEvaluateInfo {")
			.substringBefore("} McDlssEvaluateInfo;")
		listOf("color", "depth").forEach {
			assertTrue(evaluateStruct.contains("McDlssImage $it;"), "$it must be carried")
		}
		listOf("motion", "output").forEach {
			assertTrue(
				!evaluateStruct.contains("McDlssImage $it;"),
				"$it is bridge-owned and must not be carried",
			)
		}
		// Nor are the output dimensions: nothing the evaluation records is sized from them that
		// the bridge does not already own.
		assertTrue(evaluateStruct.contains("uint32_t render_width;"))
		assertTrue(!evaluateStruct.contains("uint32_t output_width;"))
	}

	@Test
	fun nativeConstructionCarriesViewImageFormatAndDerivesTheRange() {
		// Normalized for the same reason as DlssFeatureLifecycleTest: a Windows checkout hands
		// these files back with CRLF, and the patterns match the source text literally.
		val common = Files.readString(Path.of("native", "internal", "common.cpp")).replace("\r\n", "\n")
		val ngx = Files.readString(Path.of("native", "internal", "ngx.cpp")).replace("\r\n", "\n")

		// The subresource range is derived, not carried: one constant {0, 1, 0, 1} whose aspect
		// follows from the image's role. This is the invariant the ABI no longer carries.
		assertTrue(common.contains("VkImageSubresourceRange image_range_of(const bool isDepth)"))
		assertTrue(common.contains("isDepth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT"))
		assertTrue(common.contains("return VkImageSubresourceRange{aspect, 0, 1, 0, 1};"))
		// View, image, and format all reach NGX from the one carried struct.
		assertTrue(ngx.contains("from_uint64<VkImageView>(image.view), from_uint64<VkImage>(image.image)"))
		assertTrue(ngx.contains("static_cast<VkFormat>(image.format), width, height, readWrite"))
		// The role that picks the aspect is explicit at each resource's construction.
		assertTrue(ngx.contains("make_image_view_resource(info.color, false,"))
		assertTrue(ngx.contains("make_image_view_resource(info.depth, true,"))
		assertTrue(ngx.contains("make_image_view_resource(motionImage, false,"))
		assertTrue(ngx.contains("make_image_view_resource(outputImage, false,"))
	}

	@Test
	fun compiledFfmBindingMatchesTheNativeStructLayout() {
		val request = request().copy(renderDimensions = DlssDimensions(1280, 720))
		assertTrue(Files.isRegularFile(Path.of("build", "native", "mc_dlss.dll")))

		// The probe is compiled against native/mc_dlss.h itself, so it reads every field at the
		// offset the real C compiler placed it. A Java StructLayout that disagreed - a missing
		// padding declaration, a transposed pair, a field of the wrong width - reads back as a
		// value the probe rejects rather than as a silently wrong frame.
		Native.open(compileAbiProbe()).use { native ->
			assertEquals(NativeApi.SUCCESS_RESULT, native.evaluate(request))
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.writeMotion(
					MotionRequest(
						commandBuffer = 101L,
						depth = ImageBinding(301L, 302L, 303),
						reprojection = FloatArray(16) { it.toFloat() },
						renderDimensions = DlssDimensions(1280, 720),
					),
				),
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.presentOutput(
					PresentTarget(
						commandBuffer = 101L,
						image = 601L,
						outputDimensions = DlssDimensions(2560, 1440),
					),
				),
			)
			// The out-parameter direction of the same struct: the bridge fills two McDlssImage
			// values and the binding has to read back exactly what was written.
			assertEquals(
				DlssEvaluationImages(
					motion = ImageBinding(401L, 402L, 403),
					output = ImageBinding(501L, 502L, 503),
				),
				native.acquireImages(),
			)
		}
	}

	private fun compileAbiProbe(): Path {
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
				uint32_t, uint32_t, uint32_t, uint32_t, uint32_t, uint32_t) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_evaluate(const McDlssEvaluateInfo* info) {
				if (info == nullptr) return 0;
				return info->command_buffer == 101 &&
					info->color.view == 201 && info->color.image == 202 && info->color.format == 203 &&
					info->depth.view == 301 && info->depth.image == 302 && info->depth.format == 303 &&
					info->jitter.x == 0.25f && info->jitter.y == -0.5f &&
					info->motion_scale.x == 1.25f && info->motion_scale.y == 1.5f &&
					info->render_width == 1280 && info->render_height == 720 &&
					info->frame_time_milliseconds == 16.7f && info->reset_history == 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_write_motion(const McDlssMotionInfo* info) {
				if (info == nullptr || info->reprojection == nullptr) return 0;
				for (int i = 0; i < 16; ++i) {
					if (info->reprojection[i] != static_cast<float>(i)) return 0;
				}
				return info->command_buffer == 101 &&
					info->depth.view == 301 && info->depth.image == 302 && info->depth.format == 303 &&
					info->render_width == 1280 && info->render_height == 720;
			}
			__declspec(dllexport) int __cdecl mc_dlss_present_output(const McDlssPresentInfo* info) {
				if (info == nullptr) return 0;
				return info->command_buffer == 101 && info->image == 601 &&
					info->width == 2560 && info->height == 1440;
			}
			__declspec(dllexport) int __cdecl mc_dlss_acquire_images(
				McDlssImage* motion, McDlssImage* output) {
				if (motion == nullptr || output == nullptr) return 0;
				motion->view = 401; motion->image = 402; motion->format = 403;
				output->view = 501; output->image = 502; output->format = 503;
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_release_images() { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_activate_vulkan_proxies(
				uint64_t, uint64_t, uint64_t, uint32_t, uint32_t, uint32_t, uint32_t) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_query_device_feature_12(
				uint32_t index, char* name, uint32_t name_capacity, uint32_t* feature_count) {
				if (feature_count == nullptr) return 0;
				*feature_count = 0;
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_query_device_feature_13(
				uint32_t index, char* name, uint32_t name_capacity, uint32_t* feature_count) {
				if (feature_count == nullptr) return 0;
				*feature_count = 0;
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_query_queue_requirements(
				uint32_t* extra_graphics_queues, uint32_t* extra_compute_queues, uint32_t* extra_optical_flow_queues) {
				if (extra_graphics_queues == nullptr || extra_compute_queues == nullptr || extra_optical_flow_queues == nullptr) return 0;
				*extra_graphics_queues = 0; *extra_compute_queues = 0; *extra_optical_flow_queues = 0;
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_wait_device_idle() { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_query_frame_timings(
				float*, float*, float*, float*) { return 1; }
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

	private fun request() = EvaluationRequest(
		commandBuffer = 101L,
		color = ImageBinding(201L, 202L, 203),
		depth = ImageBinding(301L, 302L, 303),
		jitter = Vec2(0.25f, -0.5f),
		motionScale = Vec2(1.25f, 1.5f),
		frameTimeMilliseconds = 16.7f,
		resetHistory = true,
	)

	private fun config(outputDimensions: DlssDimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)
}
