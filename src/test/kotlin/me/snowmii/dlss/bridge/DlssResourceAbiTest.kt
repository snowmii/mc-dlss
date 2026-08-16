package me.snowmii.dlss.bridge
import me.snowmii.streamline.CameraConstants;
import me.snowmii.streamline.Dimensions;
import me.snowmii.streamline.EvaluationImages;
import me.snowmii.streamline.EvaluationRequest;
import me.snowmii.streamline.FillVelocityRequest;
import me.snowmii.streamline.ImageBinding;
import me.snowmii.streamline.MotionRequest;
import me.snowmii.streamline.PresentTarget;

import me.snowmii.streamline.Vec2
import me.snowmii.streamline.Native
import me.snowmii.streamline.NativeApi

import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import me.snowmii.dlss.session.LifecycleAdapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import me.snowmii.dlss.NativeBridge

@NativeBridge
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
				"queryOptimalDimensions" -> Dimensions(1280, 720)
				"evaluate" -> {
					evaluated = arguments[0] as EvaluationRequest
					NativeApi.SUCCESS_RESULT
				}
				else -> error("Unexpected native call: ${method.name}")
			}
		} as NativeApi
		val outputDimensions = Dimensions(2560, 1440)
		val adapter = LifecycleAdapter(DlssSession(config(outputDimensions)), native)
		val request = request().build()

		assertEquals(
			Dimensions(1280, 720),
			adapter.initialize(1L, 2L, 3L, Path.of("sdk"), Path.of("data")),
		)
		assertTrue(adapter.evaluate(request))
		// The engine's two images cross untouched, and the render size the bridge checks its
		// caller against is added by the adapter rather than by whoever described the frame.
		assertEquals(request().renderDimensions(Dimensions(1280, 720)).build(), evaluated)
	}

	@Test
	fun compiledFfmBindingMatchesTheNativeStructLayout() {
		val request = request().renderDimensions(Dimensions(1280, 720)).build()
		assertTrue(Files.isRegularFile(Path.of("streamline", "build", "native", "mc_dlss.dll")))

		// The probe is compiled against native/mc_dlss.h itself, so it reads every field at the
		// offset the real C compiler placed it. A Java StructLayout that disagreed - a missing
		// padding declaration, a transposed pair, a field of the wrong width - reads back as a
		// value the probe rejects rather than as a silently wrong frame.
		Native.open(compileAbiProbe()).use { native ->
			assertEquals(NativeApi.SUCCESS_RESULT, native.evaluate(request))
			// The camera's six arrays are fixed-length ABI fields: a malformed array is a
			// caller bug the boundary must refuse before any byte of the reused scratch is
			// written - a shorter array would leave the field's tail holding the previous
			// frame's floats, and a longer one would write past the field.
			malformedCameras().forEach { malformed ->
				assertThrows(IllegalArgumentException::class.java) {
					native.evaluate(request().renderDimensions(Dimensions(1280, 720)).camera(malformed).build())
				}
			}
			// The refusals left the scratch intact: a valid camera still crosses unchanged.
			assertEquals(NativeApi.SUCCESS_RESULT, native.evaluate(request))
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.writeMotion(
					MotionRequest(
						101L,
						ImageBinding(301L, 302L, 303),
						FloatArray(16) { it.toFloat() },
						Dimensions(1280, 720),
					),
				),
			)
			// The velocity fill crosses the same boundary in the opposite shape: every field of
			// McDlssFillVelocityInfo read back at the offset the real C compiler placed it.
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.fillVelocity(
					FillVelocityRequest(
						101L,
						ImageBinding(301L, 302L, 303),
						ImageBinding(701L, 702L, 124),
						FloatArray(16) { it.toFloat() },
						true,
						Dimensions(1280, 720),
					),
				),
			)
			assertEquals(
				NativeApi.SUCCESS_RESULT,
				native.presentOutput(
					PresentTarget(
						101L,
						601L,
						Dimensions(2560, 1440),
					),
				),
			)
			// The out-parameter direction of the same struct: the bridge fills two McDlssImage
			// values and the binding has to read back exactly what was written.
			assertEquals(
				EvaluationImages(
					ImageBinding(401L, 402L, 403),
					ImageBinding(501L, 502L, 503),
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
				if (!(info->command_buffer == 101 &&
					info->color.view == 201 && info->color.image == 202 && info->color.format == 203 &&
					info->depth.view == 301 && info->depth.image == 302 && info->depth.format == 303 &&
					info->jitter.x == 0.25f && info->jitter.y == -0.5f &&
					info->motion_scale.x == 1.25f && info->motion_scale.y == 1.5f &&
					info->render_width == 1280 && info->render_height == 720 &&
					info->frame_time_milliseconds == 16.7f && info->reset_history == 1)) {
					return 0;
				}
				for (int i = 0; i < 16; ++i) {
					if (info->camera.view_to_clip[i] != static_cast<float>(i + 1)) return 0;
					if (info->camera.clip_to_view[i] != static_cast<float>(101 + i)) return 0;
				}
				for (int i = 0; i < 3; ++i) {
					if (info->camera.pos[i] != static_cast<float>(201 + i)) return 0;
					if (info->camera.right[i] != static_cast<float>(301 + i)) return 0;
					if (info->camera.up[i] != static_cast<float>(401 + i)) return 0;
					if (info->camera.fwd[i] != static_cast<float>(501 + i)) return 0;
				}
				return 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_tag_sr_resources(const McDlssTagInfo* info) {
				if (info == nullptr) return 0;
				return info->command_buffer == 101 &&
					info->color.view == 201 && info->color.image == 202 && info->color.format == 203 &&
					info->depth.view == 301 && info->depth.image == 302 && info->depth.format == 303;
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
			__declspec(dllexport) int __cdecl mc_dlss_fill_velocity(const McDlssFillVelocityInfo* info) {
				if (info == nullptr || info->reprojection == nullptr) return 0;
				for (int i = 0; i < 16; ++i) {
					if (info->reprojection[i] != static_cast<float>(i)) return 0;
				}
				return info->command_buffer == 101 &&
					info->depth.view == 301 && info->depth.image == 302 && info->depth.format == 303 &&
					info->velocity.view == 701 && info->velocity.image == 702 && info->velocity.format == 124 &&
					info->render_width == 1280 && info->render_height == 720 && info->reset == 1;
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
		val nativeHeaders = Path.of("streamline", "native").toAbsolutePath()
		val compiler = "call \"$vsDevCmd\" -arch=x64 -host_arch=x64 >nul && cl.exe /nologo /std:c++17 /LD /O2 /I\"$nativeHeaders\" /Fe\"$library\" \"$source\""
		val process = ProcessBuilder("cmd.exe", "/d", "/c", compiler)
			.directory(directory.toFile())
			.inheritIO()
			.start()
		assertEquals(0, process.waitFor(), "FFM ABI probe compilation failed")
		assertTrue(Files.isRegularFile(library), "FFM ABI probe must produce $library")
		return library
	}

	private fun request() = EvaluationRequest.builder()
		.commandBuffer(101L)
		.color(ImageBinding(201L, 202L, 203))
		.depth(ImageBinding(301L, 302L, 303))
		.jitter(Vec2(0.25f, -0.5f))
		.motionScale(Vec2(1.25f, 1.5f))
		.frameTimeMilliseconds(16.7f)
		.resetHistory(true)
		.camera(CAMERA)

	/**
	 * One malformed variant per camera array: each is a length the ABI field cannot hold, so
	 * the boundary must refuse it before the struct is written.
	 */
	private fun malformedCameras(): List<CameraConstants> = listOf(
		CameraConstants(FloatArray(15), CAMERA.clipToView, CAMERA.pos, CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, FloatArray(17), CAMERA.pos, CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, FloatArray(2), CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, FloatArray(4), CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, CAMERA.right, FloatArray(1), CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, CAMERA.right, CAMERA.up, FloatArray(0)),
	)

	private fun config(outputDimensions: Dimensions) = DlssStartupConfig(
		enabled = true,
		qualityMode = SRMode.QUALITY,
		outputDimensions = outputDimensions,
		sdkPath = null,
		nativeLibraryPath = null,
		dataPath = null,
		warnings = emptyList(),
	)

	private companion object {
		/** The standing-in camera whose floats the ABI probe verifies field by field. */
		private val CAMERA = CameraConstants(
			FloatArray(16) { (it + 1).toFloat() },
			FloatArray(16) { (101 + it).toFloat() },
			floatArrayOf(201f, 202f, 203f),
			floatArrayOf(301f, 302f, 303f),
			floatArrayOf(401f, 402f, 403f),
			floatArrayOf(501f, 502f, 503f),
		)
	}
}
