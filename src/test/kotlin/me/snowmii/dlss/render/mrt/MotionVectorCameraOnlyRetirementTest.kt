package me.snowmii.dlss.render.mrt

import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.FrameEvaluation
import me.snowmii.dlss.render.SceneResources
import me.snowmii.streamline.Dimensions
import me.snowmii.streamline.EvaluationImages
import me.snowmii.streamline.EvaluationRequest
import me.snowmii.streamline.FrameTimings
import me.snowmii.streamline.MotionRequest
import me.snowmii.streamline.PresentTarget
import me.snowmii.streamline.SrTagRequest
import me.snowmii.streamline.StreamlineSession
import me.snowmii.streamline.StreamlineSessionTestDouble
import me.snowmii.dlss.vk.VulkanContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * Camera-only writer retirement:
 * In Minecraft 26.3 with Vulkan dynamic rendering and FrameGraph, camera-only motion compute
 * (MotionVectorRoute.CAMERA_ONLY) is the single, unified route for both DLSS-SR and Ray Reconstruction.
 *
 * All legacy MRT velocity writers (entity, moving block, cloud, weather, particle, breaking block, hand),
 * their fragment shaders, and their mixin hooks have been completely retired with zero backward compatibility.
 */
class MotionVectorCameraOnlyRetirementTest {

	@Test
	fun `mixin registration contains no retired motion mixins and retains only clean engine mixins`() {
		val registration = repositorySource("src/main/resources/mc-dlss.mixins.json")

		for (retired in listOf(
			// Legacy MRT and velocity mixins completely purged in 26.3
			"CloudRendererMotionMixin",
			"WeatherEffectRendererMotionMixin",
			"QuadParticleFeatureRendererMotionMixin",
			"ItemInHandRendererMotionMixin",
			"ItemFeatureRendererMotionMixin",
			"ItemFeatureRendererSubmitMotionMixin",
			"TextFeatureRendererMotionMixin",
			"TextFeatureRendererSubmitMotionMixin",
			"CustomFeatureRendererMotionMixin",
			"CustomFeatureRendererSubmitMotionMixin",
			"EntityRenderDispatcherMotionMixin",
			"MovingBlockFeatureRendererMotionMixin",
			"PreparedRenderTypeMotionMixin",
			"RenderTypeFeatureRendererMotionMixin",
			"StagedVertexBufferMotionMixin",
			"ModelFeatureRendererMotionMixin",
			"ModelFeatureSubmitMotionMixin",
			"BlockEntityRenderDispatcherMotionMixin",
			"LevelExtractorCaptureMixin",
			"VulkanChunkSectionsToRenderMixin",
			"PipelineCacheMixin",
		)) {
			assertFalse(registration.contains(retired), "$retired must be retired from the registration")
		}

		for (retained in listOf(
			"LevelRendererWorldPhaseMixin",
			"SubmitNodeCollectionMixin",
			"GameRendererMixin",
			"GuiRendererMixin",
		)) {
			assertTrue(registration.contains(retained), "$retained must remain registered")
		}
	}

	@Test
	fun `retired MRT writer sources shaders and tests are absent`() {
		for (retired in RETIRED_SOURCES) {
			assertFalse(Files.exists(repositoryRoot.resolve(retired)), "$retired must be absent from repository")
		}

		assertTrue(Files.exists(repositoryRoot.resolve("src/main/kotlin/me/snowmii/dlss/render/FrameEvaluation.kt")))
		assertTrue(Files.exists(repositoryRoot.resolve("src/main/kotlin/me/snowmii/dlss/render/WorldPhase.kt")))
	}

	@Test
	fun `motion vector compatibility defaults to camera-only`() {
		val compat = MotionVectorCompatibility()
		assertEquals(MotionVectorRoute.CAMERA_ONLY, compat.selectedRoute)
	}

	private companion object {
		val RETIRED_SOURCES = listOf(
			"src/main/java/me/snowmii/dlss/mixin/renderer/BlockEntityRenderDispatcherMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/EntityRenderDispatcherMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/LevelExtractorCaptureMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/ModelFeatureRendererMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/ModelFeatureSubmitMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/MovingBlockFeatureRendererMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/PreparedRenderTypeMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/RenderTypeFeatureRendererMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/renderer/StagedVertexBufferMotionMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/blaze3d/VulkanChunkSectionsToRenderMixin.java",
			"src/main/java/me/snowmii/dlss/mixin/blaze3d/PipelineCacheMixin.java",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/CloudVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/EntityVelocityUniforms.kt",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/MovingBlockVelocityRender.kt",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/TerrainVelocityPass.kt",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/VelocityPipelineVariant.kt",
			"src/main/kotlin/me/snowmii/dlss/render/mrt/ObjectMotionState.kt",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_block.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_clouds.fsh",
			"src/main/resources/assets/mc-dlss/shaders/core/velocity_entity.fsh",
		)
	}
}
