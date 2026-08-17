package me.snowmii.dlss.mrt

/** Motion-vector writer selected for this client session. */
enum class MotionVectorRoute {
	/** Known Minecraft and mc-dlss shaders may use velocity render-target variants. */
	VELOCITY_MRT,

	/** Unknown shader ownership keeps the existing camera-motion compute writer. */
	CAMERA_ONLY,
}

/** Shader identity captured before Vulkan lazily compiles its owning pipeline. */
data class MotionVectorShader(
	val id: String,
	val owner: String,
)

/** Pipeline plus every shader whose ownership decides MRT compatibility. */
data class MotionVectorPipeline(
	val id: String,
	val shaders: List<MotionVectorShader>,
)

/**
 * Session latch protecting the velocity attachment from pipelines whose shaders the mod does not
 * own and therefore cannot safely transform.
 *
 * Minecraft and mc-dlss shader namespaces are the approved compatibility boundary. First foreign
 * owner permanently selects [MotionVectorRoute.CAMERA_ONLY] for this runtime. That route leaves
 * frame generation eligible; the fallback retains the one-target pipeline and existing
 * camera-motion writer instead of binding an incompatible velocity variant.
 */
class MotionVectorCompatibility(
	private val diagnostics: (String) -> Unit = {},
) {
	@Volatile
	var selectedRoute: MotionVectorRoute = MotionVectorRoute.VELOCITY_MRT
		private set

	@Volatile
	var firstForeignPipeline: MotionVectorPipeline? = null
		private set

	/**
	 * Observes one world pipeline before Vulkan binds it. Never throws: fallback exists specifically
	 * to keep an unknown custom shader from turning lazy first-draw compilation into a render error.
	 */
	fun observe(pipeline: MotionVectorPipeline): MotionVectorRoute {
		if (selectedRoute == MotionVectorRoute.CAMERA_ONLY) {
			return selectedRoute
		}

		val foreign = pipeline.shaders.filterNot { it.owner in OWNED_SHADER_NAMESPACES }
		if (foreign.isEmpty()) {
			return selectedRoute
		}

		synchronized(this) {
			if (selectedRoute == MotionVectorRoute.CAMERA_ONLY) {
				return selectedRoute
			}

			firstForeignPipeline = pipeline
			selectedRoute = MotionVectorRoute.CAMERA_ONLY
			val shaderIds = foreign.joinToString { it.id }
			runCatching {
				diagnostics(
					"DLSS motion vectors: pipeline ${pipeline.id} uses foreign shader(s) " +
						"[$shaderIds]; camera-only fallback latched for this session; " +
						"frame generation remains eligible",
				)
			}
		}
		return selectedRoute
	}

	companion object {
		private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
	}
}
