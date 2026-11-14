package me.snowmii.dlss.bridge

import me.snowmii.dlss.NativeBridge
import me.snowmii.streamline.CameraConstants
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.dlss.api.ImageBinding
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.NativeTestAccess
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.RrPrepareRequest
import me.snowmii.streamline.Vec2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

@NativeBridge
class DlssNativeStructAbiTest {
	@Test
	fun `compiled FFM binding matches the native struct layout`() {
		val request = request().renderDimensions(Dimensions(1280, 720)).build()
		assertTrue(Files.isRegularFile(Path.of("build", "native", "mc_dlss.dll")))

		NativeTestAccess.open(compileAbiProbe()).use { native ->
			val rr = RrPrepareRequest(
				enabled = true,
				rrPreset = 6,
				diffuseAlbedo = ImageBinding(901L, 902L, 903),
				specularAlbedo = ImageBinding(911L, 912L, 913),
				normalRoughness = ImageBinding(921L, 922L, 923),
				specularHitDistance = ImageBinding(931L, 932L, 933),
				worldToCameraView = FloatArray(16) { (it + 1).toFloat() },
				cameraViewToWorld = FloatArray(16) { (it + 101).toFloat() },
				specularMotion = ImageBinding(941L, 942L, 943),
				biasCurrentColor = ImageBinding(951L, 952L, 9),
			)
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.prepareRayReconstruction(rr))
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.prepareRayReconstruction(rr.copy(biasCurrentColor = ImageBinding(0L, 0L, 0))))
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.prepareRayReconstruction(rr))
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.prepareRayReconstruction(RrPrepareRequest(enabled = false)))
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.evaluateSuperResolution(request))
			malformedCameras().forEach { malformed ->
				assertThrows(IllegalArgumentException::class.java) {
					native.evaluateSuperResolution(request().renderDimensions(Dimensions(1280, 720)).camera(malformed).build())
				}
			}
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.evaluateSuperResolution(request))
			assertEquals(
				StreamlineSession.SUCCESS_RESULT,
				native.writeMotion(
					MotionRequest(
						101L,
						ImageBinding(301L, 302L, 303),
						FloatArray(16) { it.toFloat() },
						true,
						Dimensions(1280, 720),
					),
				),
			)
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.presentOutput(PresentTarget(
				101L, ImageBinding(602L, 601L, 37), Dimensions(2560, 1440),
				ImageBinding(801L, 802L, 97), 8,
			)))
			assertEquals(StreamlineSession.SUCCESS_RESULT, native.presentOutput(PresentTarget(
				101L, 601L, Dimensions(2560, 1440), ImageBinding(801L, 802L, 97),
			)))
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
			#include <cstddef>
			static_assert(sizeof(McDlssPresentInfo) == 64);
			static_assert(offsetof(McDlssPresentInfo, image_view) == 48);
			static_assert(offsetof(McDlssPresentInfo, image_format) == 56);
			static_assert(offsetof(McDlssPresentInfo, image_usage) == 60);

			extern "C" {
			__declspec(dllexport) int __cdecl mc_dlss_initialize(
				uint64_t, uint64_t, uint64_t, const char*, const char*) { return 1; }
			__declspec(dllexport) int __cdecl mc_dlss_prepare_rr(const McDlssRrPrepareInfo* info) {
				if (info == nullptr || sizeof(*info) != 280 || offsetof(McDlssRrPrepareInfo, bias_current_color) != 256) return 0;
				const auto& bias = info->bias_current_color;
				const bool empty = bias.view == 0 && bias.image == 0 && bias.format == 0;
				if (info->enabled == 0) return empty;
				if (!(info->enabled == 1 && info->rr_preset == 6 &&
					info->diffuse_albedo.view == 901 && info->diffuse_albedo.image == 902 && info->diffuse_albedo.format == 903 &&
					info->specular_albedo.view == 911 && info->specular_albedo.image == 912 && info->specular_albedo.format == 913 &&
					info->normal_roughness.view == 921 && info->normal_roughness.image == 922 && info->normal_roughness.format == 923 &&
					info->specular_hit_distance.view == 931 && info->specular_hit_distance.image == 932 && info->specular_hit_distance.format == 933 &&
					info->specular_motion.view == 941 && info->specular_motion.image == 942 && info->specular_motion.format == 943 &&
					(empty || (bias.view == 951 && bias.image == 952 && bias.format == 9)))) return 0;
				for (int i = 0; i < 16; ++i) {
					if (info->world_to_camera_view[i] != static_cast<float>(i + 1)) return 0;
					if (info->camera_view_to_world[i] != static_cast<float>(i + 101)) return 0;
				}
				return 1;
			}
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
					info->render_width == 1280 && info->render_height == 720 &&
					info->fg_consuming_frame == 1;
			}
			__declspec(dllexport) int __cdecl mc_dlss_present_output(const McDlssPresentInfo* info) {
				if (info == nullptr) return 0;
				const bool metadata = (info->image_view == 602 && info->image_format == 37 && info->image_usage == 8) ||
					(info->image_view == 0 && info->image_format == 0 && info->image_usage == 0);
				return info->command_buffer == 101 && info->image == 601 &&
					info->width == 2560 && info->height == 1440 &&
                    info->reflection.view == 801 && info->reflection.image == 802 && info->reflection.format == 97 && metadata;
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

		val programFiles = requireNotNull(System.getenv("ProgramFiles(x86)")) {
			"ProgramFiles(x86) environment variable is required"
		}
		val vsDevCmd = Path.of(
			programFiles,
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
	private fun request() = EvaluationRequest.builder()
		.commandBuffer(101L)
		.color(ImageBinding(201L, 202L, 203))
		.depth(ImageBinding(301L, 302L, 303))
		.jitter(Vec2(0.25f, -0.5f))
		.motionScale(Vec2(1.25f, 1.5f))
		.frameTimeMilliseconds(16.7f)
		.resetHistory(true)
		.camera(CAMERA)

	private fun malformedCameras(): List<CameraConstants> = listOf(
		CameraConstants(FloatArray(15), CAMERA.clipToView, CAMERA.pos, CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, FloatArray(17), CAMERA.pos, CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, FloatArray(2), CAMERA.right, CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, FloatArray(4), CAMERA.up, CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, CAMERA.right, FloatArray(1), CAMERA.fwd),
		CameraConstants(CAMERA.viewToClip, CAMERA.clipToView, CAMERA.pos, CAMERA.right, CAMERA.up, FloatArray(0)),
	)

	private companion object {
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
