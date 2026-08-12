package me.snowmii.dlss.mrt

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.Std140Builder
import com.mojang.blaze3d.buffers.Std140SizeCalculator
import com.mojang.blaze3d.pipeline.BindGroupLayout
import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.systems.CommandEncoder
import com.mojang.blaze3d.systems.RenderPass
import com.mojang.blaze3d.systems.RenderPassDescriptor
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import me.snowmii.dlss.client.ClientRuntime
import me.snowmii.dlss.render.WorldPhase
import net.minecraft.client.model.Model
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.feature.ItemFeatureRenderer
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.feature.TextFeatureRenderer
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.joml.Matrix4f
import org.lwjgl.system.MemoryStack
import java.util.IdentityHashMap
import java.util.Optional
import java.util.OptionalDouble

/**
 * The hand/item velocity writer: the per-slot pose state, the identity plumbing that carries
 * the hand slot from submission through batching to the draw, the always-pass/depth-write-off
 * two-target twins, and the prepared hand draw replacement.
 *
 * The hand draws once per frame, inside `GameRenderer.renderItemInHand`, after world geometry
 * and before `WorldPhase.end` evaluates DLSS. The submission seam
 * ([me.snowmii.dlss.mixin.ItemInHandRendererMotionMixin]) brackets each rendered hand, captures
 * the pieces' interpolated clip poses into [HandMotionState], and the identity plumbing copies
 * the bracket's slot onto every model, item, custom-geometry, and text submit record created
 * inside it. At batch time the slot and pose domain ride the staged draw and its execute info,
 * so [draw] resolves one slot and one domain per prepared draw and writes that piece's
 * reprojection (or the invalid sentinel) before replaying the geometry into the scene color
 * target plus the scene-sized RG16_FLOAT velocity companion.
 *
 * Three geometry families render inside the hand window, each with its own pose domain:
 *
 * - the held item (`renderItem`, `core/item` pipelines), captured at the `renderItem` seam;
 * - the bare player arm (the empty-hand and map branches render the arm through the
 *   `core/entity` family), captured at the staging seam from the model submit's baked pose and
 *   keyed by the stable model-part instance so the two-handed map's arms keep separate
 *   histories;
 * - the map's custom geometry (`core/text` family: background, map texture, decorations, and
 *   labels), captured at the `renderMap` seam.
 *
 * The hand reprojection is `C_prev * inverse(C_cur)` - the full clip-space composition of the
 * two frames' hand poses, with the camera's own translation cancelling because the hand is
 * camera-attached. No jitter terms appear: the hand draws with the unjittered HUD projection.
 * [HandMotionState.reprojection] owns the classification; the writer only gates the phase,
 * resolves the slot and domain, and writes the payload.
 *
 * Route gating is the open velocity-MRT hand scene only. A closed phase, a vanilla session, the
 * latched camera-only route, a frame without a scene velocity companion, a draw without a hand
 * slot or pose domain (an entity or screen-effect draw sharing the same batching seam), a
 * non-main output target (translucent items and shader-transparency routes), a foreign
 * pipeline, and a failed writer setup all answer false and keep the exact vanilla one-target
 * draw - those pixels stay sentinel for the post-scene fill. The slot identity can never alias
 * player/entity history: it is a [HandSlot] resolved in the hand domain only, and the arm's
 * geometry carries no entity id because no entity bracket is open while the hand renders.
 */
object HandVelocityRender {
	/** The shader path the hand twin swaps in for the source's core/item shader. */
	const val SHADER_PATH = "core/velocity_hand"

	/** The shader path the map twin swaps in for the source's core/text shader. */
	const val TEXT_SHADER_PATH = "core/velocity_text"

	/** The payload uniform name, which must match the shader block name exactly. */
	const val UNIFORM_NAME = "HandVelocityConfig"

	/** The approved hand depth policy: always-pass with depth writes disabled. */
	@JvmField
	val HAND_DEPTH: DepthStencilState = DepthStencilState(CompareOp.ALWAYS_PASS, false)

	/** The hand twin adds its own HandVelocityConfig layout, distinct from every other writer's. */
	@JvmField
	val LAYOUT: BindGroupLayout = BindGroupLayout.builder()
		.withUniform(UNIFORM_NAME, UniformType.UNIFORM_BUFFER)
		.build()

	@JvmField
	val FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", SHADER_PATH)

	/** The map twin's velocity fragment: mirrors the vanilla core/text color logic. */
	@JvmField
	val TEXT_FRAGMENT_SHADER: Identifier = Identifier.fromNamespaceAndPath("mc-dlss", TEXT_SHADER_PATH)

	/** `mat4 ObjectReprojection` + `vec4 VelocityParams`, both std140-aligned. */
	@JvmField
	val UBO_SIZE: Int = Std140SizeCalculator()
		.putMat4f()
		.putVec4()
		.get()

	private var uniformBuffer: GpuBuffer? = null

	/**
	 * The hand pose history, swapped wholesale by the headless evidence. Production never
	 * replaces it; the capture seam, the frame boundary, and the draw all read this one state.
	 */
	@JvmStatic
	internal var handMotion: HandMotionState = HandMotionState()

	/**
	 * The hand's projection for the frame in flight, captured at the `renderItemInHand` seam
	 * before the hand submits. The hand draws with the unjittered HUD projection; the pose
	 * capture composes it with the model-view stack and the piece pose to form the clip matrix.
	 * Null (the seam did not run, or the read failed) makes every capture this frame a failed
	 * observation, which classifies as a reset.
	 */
	private var frameProjectionState: Matrix4f? = null

	/** The frame's hand projection, or null when the hand window has not opened. */
	@JvmStatic
	fun frameProjection(): Matrix4f? = frameProjectionState

	/** Headless test seam: the open phase the writer gates on when no live `ClientRuntime` phase exists. */
	@JvmStatic
	internal var activePhaseOverride: WorldPhase? = null

	/**
	 * The open scene phase the prepared-draw replacement gates on, or null when the draw must
	 * stay vanilla. The read is guarded so a not-yet-initialized client runtime (or the
	 * headless test JVM) degrades to the vanilla route instead of throwing.
	 */
	@JvmStatic
	fun activePhase(): WorldPhase? = runCatching {
		(activePhaseOverride ?: ClientRuntime.active().activeWorldPhase())
	}.getOrNull()

	/**
	 * Whether one pipeline is safe to replace with the hand writer.
	 *
	 * The hand's window renders exactly three owned shader families: the `core/item` family
	 * (`ITEM_CUTOUT` and `ITEM_TRANSLUCENT` pipelines the item branch submits with), the
	 * `core/entity` family (the bare player arm renders through `ENTITY_TRANSLUCENT`), and the
	 * `core/text` family (the map's custom geometry and labels). Each family is admitted with
	 * its own vertex format, so terrain, particles, glint, map-background-in-HUD, and GUI text
	 * pipelines stay out. The hand slot guard keeps the admission safe beyond the window: a
	 * slot can only attach inside `submitArmWithItem`, which never submits any other family.
	 */
	@JvmStatic
	fun isHandPipeline(pipeline: RenderPipeline): Boolean =
		isItemHandPipeline(pipeline) || isArmHandPipeline(pipeline) || isMapHandPipeline(pipeline)

	private fun isItemHandPipeline(pipeline: RenderPipeline): Boolean =
		shaderFamily(pipeline, "core/item") && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.ENTITY

	private fun isArmHandPipeline(pipeline: RenderPipeline): Boolean =
		shaderFamily(pipeline, "core/entity") && pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.ENTITY

	private fun isMapHandPipeline(pipeline: RenderPipeline): Boolean =
		shaderFamily(pipeline, "core/text") &&
			pipeline.getVertexFormatBinding(0) == DefaultVertexFormat.POSITION_TEX_LIGHTMAP_COLOR

	private fun shaderFamily(pipeline: RenderPipeline, path: String): Boolean {
		val vertex = pipeline.vertexShader
		val fragment = pipeline.fragmentShader
		return vertex.path == path && fragment.path == path &&
			vertex.namespace in OWNED_SHADER_NAMESPACES && fragment.namespace in OWNED_SHADER_NAMESPACES
	}

	/**
	 * Control seam used by the draw callback and by headless evidence before any device
	 * operation. A true result means this prepared main-target draw carries a hand slot and pose
	 * domain, an open velocity-MRT phase with a scene velocity view, and an owned hand-family
	 * pipeline; the actual render pass still has its own safe gates.
	 */
	@JvmStatic
	fun canDraw(
		prepared: PreparedRenderType,
		info: StagedVertexBuffer.ExecuteInfo,
		phase: WorldPhase?,
	): Boolean = HandVelocityWriterBindings.executeInfoHandSlot(info) != null &&
		HandVelocityWriterBindings.executeInfoHandDomain(info) != null &&
		phase?.terrainVelocityView != null &&
		isHandPipeline(prepared.pipeline()) &&
		prepared.outputTarget() === OutputTarget.MAIN_TARGET

	/**
	 * Opens the frame's hand window: records the projection the hand will draw with, so the
	 * submission seam can compose clip matrices from it. Driven by the `renderItemInHand` wrap
	 * before the hand submits, on every frame the wrap runs - DLSS or vanilla.
	 */
	@JvmStatic
	fun beginHandFrame(projection: Matrix4f?) {
		frameProjectionState = projection
	}

	/**
	 * Closes the frame's hand window: the frame boundary for [handMotion], then the
	 * projection is dropped. Driven by the `renderItemInHand` wrap after the hand draws, so
	 * every frame advances the hand history exactly once - a slot or pose domain the hand did
	 * not render loses its predecessor, which is the hand-disappearance reset.
	 */
	@JvmStatic
	fun endHandFrame() {
		handMotion.commit()
		frameProjectionState = null
	}

	/**
	 * Records the frame's render selection, driven at each `submitArmWithItem` RETURN seam -
	 * the set of hands the renderer bracketed this frame. Each bracket end re-records the
	 * growing set, so the value is complete by the time the last bracket closes, ahead of any
	 * staged hand draw. Whether the selection changed since the last committed frame is decided
	 * at draw time in [HandMotionState.reprojection], never by a flag an earlier frame boundary
	 * could clear.
	 */
	@JvmStatic
	fun noteHandSelection() {
		handMotion.noteSelection(HandVelocityWriterBindings.frameHandSlots())
	}

	/**
	 * The item submission capture seam: records [slot]'s interpolated render pose for the frame
	 * in flight.
	 *
	 * The pose is captured at the `renderItem` submission seam, where the `PoseStack` holds the
	 * frame's final hand pose and the model-view stack holds the camera rotation the geometry
	 * will draw under. The clip matrix is `projection * modelView * pose` - the exact transform
	 * the item's vertices are rasterized with up to the constant display transforms, which
	 * cancel in the reprojection. A null input on any side (the frame seam did not run, or a
	 * read failed) records a failed observation, which classifies as a reset. A null [slot] (a
	 * `renderItem` call outside the hand bracket) is ignored.
	 */
	@JvmStatic
	fun captureHandPose(
		slot: HandSlot?,
		identity: Any?,
		projection: Matrix4f?,
		modelView: Matrix4f?,
		pose: Matrix4f?,
	) {
		if (slot == null) {
			return
		}
		handMotion.capture(slot, HandPoseDomain.Item, identity, composeClip(projection, modelView, pose))
	}

	/**
	 * The map submission capture seam: records the map branch's interpolated render pose for
	 * the frame in flight, driven at the `renderMap` HEAD seam.
	 *
	 * The pose is captured before `renderMap`'s own fixed transforms (rotation, scale, and the
	 * per-decoration and label offsets), which are constant across frames and therefore cancel
	 * in the reprojection - the same composition serves the background, the map texture, every
	 * decoration, and the labels, all of which move rigidly with the map plane. [identity] is
	 * the map's `ItemStack`, so a visible map swap resets even though the geometry shape is
	 * identical.
	 */
	@JvmStatic
	fun captureMapHandPose(
		slot: HandSlot?,
		identity: Any?,
		projection: Matrix4f?,
		modelView: Matrix4f?,
		pose: Matrix4f?,
	) {
		if (slot == null) {
			return
		}
		handMotion.capture(slot, HandPoseDomain.Map, identity, composeClip(projection, modelView, pose))
	}

	/**
	 * The arm staging capture seam: records a hand-bracketed model submit's interpolated render
	 * pose, driven at the model staging seam.
	 *
	 * The arm's pose is not reachable at the `renderItem` seam - the empty-hand and map branches
	 * render it through the entity model path - but the staged model submit carries the exact
	 * pose baked at submission (`submit.pose()`), which is the same pose chain the item capture
	 * reads, and the model part instance (`submit.model().root()`, a `Model.Simple` wrapping the
	 * arm part) is the stable per-arm identity the history keys on. The model part's own local
	 * pose is constant across frames, so it cancels in the reprojection. Non-hand model submits
	 * (entity geometry) carry no hand slot and are ignored; hand model submits are exactly the
	 * player arms.
	 */
	@JvmStatic
	fun captureStagedHandPose(submit: Any) {
		val slot = HandVelocityWriterBindings.submitHandSlot(submit) ?: return
		if (submit !is ModelFeatureRenderer.Submit<*>) {
			return
		}
		val model = submit.model()
		if (model !is Model.Simple) {
			return
		}
		val part = model.root()
		handMotion.capture(
			slot,
			HandPoseDomain.Arm(part),
			part,
			composeClip(frameProjectionState, RenderSystem.getModelViewMatrixCopy(), submit.pose().pose()),
		)
	}

	private fun composeClip(projection: Matrix4f?, modelView: Matrix4f?, pose: Matrix4f?): Matrix4f? = when {
		projection == null || modelView == null || pose == null -> null
		else -> Matrix4f(projection).mul(modelView).mul(pose)
	}

	/**
	 * The prepared hand draw replacement. Returning false leaves
	 * `PreparedRenderType.drawFromBuffer`'s exact vanilla one-target implementation in control,
	 * which is what vanilla, CAMERA_ONLY, foreign, identity-less, non-main-output, and
	 * unsupported draws require.
	 *
	 * The false path is a no-throw passthrough, and it is the only path that can answer false.
	 * Everything the owned pass will touch is preflighted before the writer takes ownership:
	 * the eligibility gates, the target textures, the twin pipeline (a pure cache lookup), the
	 * payload buffer (the writer's own cached allocation), and the descriptor inputs. None of
	 * that mutates encoder or pass state, so a failure there still leaves the exact source draw
	 * safe to replay.
	 *
	 * Ownership begins at the command encoder, exactly as the entity and moving-block writers
	 * commit their draws: a failure inside the device work commits the draw instead of falling
	 * through, so the source draw is never replayed over partial writer work (an encoder or
	 * pass that already carries the writer's state) and the batch never sees a torn
	 * two-attachment pass. See [committedDrawFailure].
	 */
	@JvmStatic
	fun draw(prepared: PreparedRenderType, info: StagedVertexBuffer.ExecuteInfo): Boolean {
		// Eligibility gates: plain getter reads on objects already known to be live, so an
		// ineligible draw answers false without throwing and keeps the exact vanilla route.
		val phase = activePhase() ?: return false
		if (!canDraw(prepared, info, phase)) return false
		val slot = HandVelocityWriterBindings.executeInfoHandSlot(info) ?: return false
		val domain = HandVelocityWriterBindings.executeInfoHandDomain(info) ?: return false

		// Target preflight: the scene, its textures, and the velocity companion. Still pure
		// reads; a missing or foreign target is the ineligible passthrough, not a failure.
		val scene = phase.worldTargetOverride ?: return false
		val renderTarget = prepared.outputTarget().getRenderTarget()
		val colorTexture = RenderSystem.outputColorTextureOverride ?: renderTarget.colorTextureView
		val depthTexture = if (renderTarget.useDepth) {
			RenderSystem.outputDepthTextureOverride ?: renderTarget.depthTextureView
		} else {
			null
		}
		val velocityTexture = phase.terrainVelocityView ?: return false
		if (colorTexture == null || colorTexture !== scene.colorTextureView) return false

		// Owned-work preflight: the twin (a pure cache lookup), the payload buffer (the
		// writer's own cached allocation), and every descriptor input the pass will consume.
		// Nothing here mutates encoder or pass state - the twin is a pure cache lookup, the
		// buffer is the writer's own cached allocation, and the rest are plain reads - so a
		// failure here still leaves the exact source draw safe to replay. The buffer's first
		// allocation is a device call, so it is guarded: a device failure degrades to the
		// passthrough rather than throwing out of the writer.
		val writer = handWriterFor(prepared.pipeline())
		val twin = writerTwin(prepared.pipeline(), writer, HAND_DEPTH)
		val payload = runCatching { buffer() }.getOrNull() ?: return false
		val scissor = prepared.scissorState()
		val scissorEnabled = scissor.enabled()
		val scissorX = scissor.x()
		val scissorY = scissor.y()
		val scissorWidth = scissor.width()
		val scissorHeight = scissor.height()
		val dynamicTransforms = prepared.dynamicTransforms()
		val textures = prepared.textures()
		val vertexBuffer = info.vertexBuffer()
		val indexBuffer = info.indexBuffer()
		val indexType = info.indexType()
		val indexCount = info.indexCount()
		val firstIndex = info.firstIndex()
		val baseVertex = info.baseVertex()
		val renderWidth = colorTexture.getWidth(0)
		val renderHeight = colorTexture.getHeight(0)

		// Ownership: the device encoder. From here the writer owns the draw, so any failure
		// commits it - the source draw is never replayed over partial writer work - and the
		// writer answers true either way.
		return try {
			val encoder = RenderSystem.getDevice().createCommandEncoder()
			writeFrame(encoder, payload, phase, slot, domain, velocityTexture)

			val descriptor = RenderPassDescriptor.create { "Hand velocity draw with ${prepared.pipeline()}" }
				.withColorAttachment(colorTexture, Optional.empty())
				.withColorAttachment(velocityTexture, Optional.empty())
			if (depthTexture != null) {
				descriptor.withDepthAttachment(depthTexture, OptionalDouble.empty())
			}
			descriptor.withRenderArea(RenderPass.RenderArea(0, 0, renderWidth, renderHeight))

			encoder.createRenderPass(descriptor).use { pass ->
				pass.setPipeline(twin)
				if (scissorEnabled) {
					pass.enableScissor(scissorX, scissorY, scissorWidth, scissorHeight)
				}
				RenderSystem.bindDefaultUniforms(pass)
				pass.setUniform("DynamicTransforms", dynamicTransforms)
				pass.setUniform(payloadUniformName(writer), payload.slice())
				pass.setVertexBuffer(0, vertexBuffer.slice())
				for (texture in textures) {
					pass.bindTexture(texture.name(), texture.textureView(), texture.sampler())
				}
				pass.setIndexBuffer(indexBuffer, indexType)
				pass.drawIndexed(indexCount, 1, firstIndex, baseVertex, 0)
			}
			true
		} catch (failure: Throwable) {
			committedDrawFailure(failure)
			true
		}
	}

	/**
	 * Writes one hand draw's reprojection and classification on [encoder]. The classification
	 * is [HandMotionState.reprojection]: a missing or reset camera chain, a first observation,
	 * a slot or domain absent last frame, an item-identity change, a render-selection change,
	 * or a failed pose capture all mean the sentinel.
	 */
	@JvmStatic
	fun writeFrame(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		phase: WorldPhase,
		slot: HandSlot,
		domain: HandPoseDomain,
		view: GpuTextureView,
	) {
		val reprojection = handMotion.reprojection(slot, domain, phase.activeMotion)
		writePayload(encoder, buffer, reprojection ?: IDENTITY, reprojection == null, view)
	}

	private fun writePayload(
		encoder: CommandEncoder,
		buffer: GpuBuffer,
		reprojection: Matrix4f,
		invalid: Boolean,
		view: GpuTextureView,
	) {
		MemoryStack.stackPush().use { stack ->
			val data = Std140Builder.onStack(stack, UBO_SIZE)
				.putMat4f(reprojection)
				.putVec4(
					if (invalid) 1f else 0f,
					view.getWidth(0).toFloat(),
					view.getHeight(0).toFloat(),
					0f,
				)
				.get()
			encoder.writeToBuffer(buffer.slice(), data)
		}
	}

	/**
	 * Disposes a draw the writer owned but could not complete: the device work threw after the
	 * command encoder existed, so the encoder or pass may already carry partial writer state.
	 *
	 * The failure is committed rather than reported: the caller answers true and the source
	 * draw is not replayed, because replaying it over partial writer work would draw the hand
	 * twice into a torn pass. The phase and session expose no per-draw failure seam to signal
	 * mid-frame; the established failure handling is the phase's end-of-frame disposition,
	 * where a frame whose evaluation does not complete resets the object history. That runs
	 * regardless of this draw's outcome, so nothing needs to be retained here. Never throws.
	 */
	@Suppress("UNUSED_PARAMETER")
	private fun committedDrawFailure(failure: Throwable) {
	}

	/**
	 * Headless test seam: drops the writer's cached payload allocation and hands the pose state
	 * a fresh instance, so the next capture is a first observation. Production never calls this.
	 */
	@JvmStatic
	internal fun resetState() {
		uniformBuffer = null
		handMotion = HandMotionState()
		frameProjectionState = null
	}

	private fun buffer(): GpuBuffer {
		return uniformBuffer ?: RenderSystem.getDevice().createBuffer(
			{ "DLSS hand velocity config" },
			GpuBuffer.USAGE_UNIFORM or GpuBuffer.USAGE_COPY_DST,
			UBO_SIZE.toLong(),
		).also { uniformBuffer = it }
	}

	/** The writer twin for one hand-family pipeline: the item, arm, and map twins. */
	private fun handWriterFor(pipeline: RenderPipeline): VelocityWriter = when {
		isItemHandPipeline(pipeline) -> VelocityWriter.HAND
		isArmHandPipeline(pipeline) -> VelocityWriter.HAND_ARM
		else -> VelocityWriter.HAND_TEXT
	}

	/**
	 * The payload uniform name one hand-family twin declares: the arm twin shares the safe
	 * entity velocity fragment, whose block is named after the entity payload layout.
	 */
	private fun payloadUniformName(writer: VelocityWriter): String = when (writer) {
		VelocityWriter.HAND_ARM -> EntityVelocityUniforms.UNIFORM_NAME
		else -> UNIFORM_NAME
	}

	/** Identity reprojection for invalid frames; never mutated, only read into the block. */
	private val IDENTITY = Matrix4f()

	private val OWNED_SHADER_NAMESPACES = setOf("minecraft", "mc-dlss")
}

/**
 * Render-thread identity plumbing for the hand/item draw.
 *
 * The hand's submissions and their staged draws are separated by the batching boundary: the
 * submission seam (the `submitArmWithItem` bracket) runs before any feature renderer stages
 * geometry, so the bracket alone cannot reach the draws. The plumbing therefore copies the
 * bracket's slot onto every submit record created inside it (model, item, custom-geometry, and
 * text submits), installs the slot again while each submit's pose-baked vertices are staged,
 * tags the staged draw with it, and threads it through the execute-info boundary - the same
 * shape the entity and moving-block writers use, in the hand domain. Each staged draw also
 * carries its [HandPoseDomain], resolved from the submit record's type and baked identity, so
 * the writer can resolve the per-piece pose history at draw time.
 *
 * Isolation: within the hand's own batch, consecutive same-slot same-domain same-render-type
 * submits may consolidate (they share one configuration and one reprojection, so one draw is
 * correct), while a slot or pose-domain transition forces a fresh draw so no draw ever mixes
 * two hands' or two pieces' geometry under one configuration - the two-handed map's arms must
 * never share one draw, because one draw carries one part's reprojection. A transition away
 * from an eligible hand draw forces a fresh draw and suppresses the reorder lookup, so an
 * ineligible or foreign draw sharing the group can never reuse the hand's tagged draw and
 * inherit its reprojection; a hand draw re-entering after one forces its own fresh draw, so it
 * never lands in the untagged foreign draw. The reorder lookup is suppressed while a hand
 * submit is staged, so a draw can never be reused across slots. A submit with no slot (an
 * entity or screen-effect draw sharing the same batch machinery) installs nothing, tags
 * nothing, and keeps vanilla consolidation.
 */
object HandVelocityWriterBindings {
	private val handContext = ThreadLocal<HandSlot?>()
	private val submitContext = ThreadLocal<HandSlot?>()
	private val domainContext = ThreadLocal<HandPoseDomain?>()
	private val eligibleDrawContext = ThreadLocal<Boolean>()
	private val previousEligibleDraw = ThreadLocal<Boolean>()
	private val afterEligibleDraw = ThreadLocal<Boolean>()
	private val consolidationContext = ThreadLocal<Boolean>()
	private val lastDrawSlot = ThreadLocal<HandSlot?>()
	private val lastDrawDomain = ThreadLocal<HandPoseDomain?>()
	private val submitSlots = IdentityHashMap<Any, HandSlot>()
	private val drawSlots = IdentityHashMap<Any, HandSlot>()
	private val drawDomains = IdentityHashMap<Any, HandPoseDomain>()
	private val executeInfoSlots = IdentityHashMap<Any, HandSlot>()
	private val executeInfoDomains = IdentityHashMap<Any, HandPoseDomain>()
	private val frameSlots = HashSet<HandSlot>()
	private val foreignDrawIndexes = IdentityHashMap<List<*>, MutableMap<Any, Int>>()

	/** The hand slot of the submission window currently open, or null outside one. */
	@JvmStatic
	fun currentHand(): HandSlot? = handContext.get()

	/** Opens the per-hand submission window. Driven by the `submitArmWithItem` bracket. */
	@JvmStatic
	fun beginHand(slot: HandSlot) {
		handContext.set(slot)
		frameSlots.add(slot)
	}

	/** Closes the per-hand submission window. */
	@JvmStatic
	fun endHand() {
		handContext.remove()
	}

	/**
	 * The frame's render selection: the set of hands the renderer bracketed this frame, read by
	 * the selection seam at each `submitArmWithItem` RETURN so the value is complete before any
	 * hand draw is staged.
	 */
	@JvmStatic
	fun frameHandSlots(): Set<HandSlot> = HashSet(frameSlots)

	/** Copies the open submission window's slot onto a submit record created inside it. */
	@JvmStatic
	fun bindSubmit(submit: Any) {
		val slot = handContext.get()
		if (slot != null) {
			submitSlots[submit] = slot
		}
	}

	/** The hand slot one submit record belongs to, or null for hand-foreign submits. */
	@JvmStatic
	fun submitHandSlot(submit: Any): HandSlot? = submitSlots[submit]

	/** Installs a submit's slot while its pose-baked vertices are staged. */
	@JvmStatic
	fun beginSubmit(submit: Any) {
		val slot = submitSlots[submit]
		if (slot == null) {
			submitContext.remove()
			domainContext.remove()
		} else {
			submitContext.set(slot)
			domainContext.set(domainOf(submit))
		}
	}

	/**
	 * JVM-test seam for the batch state machine: installs an explicit pose domain instead of
	 * resolving it from the submit record's type. Production resolves the domain; the headless
	 * evidence cannot construct Minecraft submit records.
	 */
	@JvmStatic
	internal fun beginSubmit(submit: Any, domain: HandPoseDomain) {
		val slot = submitSlots[submit]
		if (slot == null) {
			submitContext.remove()
			domainContext.remove()
		} else {
			submitContext.set(slot)
			domainContext.set(domain)
		}
	}

	@JvmStatic
	fun endSubmit() {
		submitContext.remove()
		domainContext.remove()
	}

	/**
	 * Resolves the pose domain of one staged submit: the item branch's `renderItem` submits are
	 * the Item domain, the map's custom geometry and labels the Map domain, and a hand model
	 * submit (the player arm) the Arm domain keyed by the wrapped model part. Entity model
	 * submits carry no hand slot and never reach the resolution. An unknown submit type resolves
	 * to null, so its draw keeps the vanilla route.
	 */
	private fun domainOf(submit: Any): HandPoseDomain? = when (submit) {
		is ItemFeatureRenderer.Submit -> HandPoseDomain.Item
		is CustomFeatureRenderer.Submit -> HandPoseDomain.Map
		is TextFeatureRenderer.Submit -> HandPoseDomain.Map
		is ModelFeatureRenderer.Submit<*> -> {
			val model = submit.model()
			if (model is Model.Simple) HandPoseDomain.Arm(model.root()) else null
		}
		else -> null
	}

	/**
	 * Opens one `Group.getVertexBuilder` boundary with an explicit eligibility result. The
	 * overload is also the JVM-test seam for the batch state machine: a hand slot on an owned
	 * main-target hand-family pipeline is eligible, and a slot or pose-domain transition - or a
	 * transition between an eligible hand draw and an ineligible/foreign draw - forces a fresh
	 * draw while consecutive same-slot same-domain draws keep vanilla consolidation.
	 */
	@JvmStatic
	fun beginDraw(renderType: RenderType): Boolean =
		beginDraw(
			renderType.pipeline(),
			isEligibleRenderType(renderType, HandVelocityRender.isHandPipeline(renderType.pipeline())),
		)

	@JvmStatic
	fun beginDraw(pipeline: RenderPipeline): Boolean = beginDraw(pipeline, HandVelocityRender.isHandPipeline(pipeline))

	@Suppress("UNUSED_PARAMETER")
	@JvmStatic
	internal fun beginDraw(pipeline: RenderPipeline, eligible: Boolean): Boolean {
		val slot = submitContext.get()
		val domain = domainContext.get()
		val handEligible = slot != null && eligible
		val wasEligible = previousEligibleDraw.get() == true
		val endedEligibleDraw = wasEligible && !handEligible
		if (endedEligibleDraw) {
			afterEligibleDraw.set(true)
		}
		previousEligibleDraw.set(handEligible)
		eligibleDrawContext.set(handEligible)
		// A fresh boundary exactly on a hand-identity transition, on the re-entry of a hand draw
		// after an ineligible/foreign one (its draw record is untagged, so consolidation would
		// swallow the hand's geometry without a slot), or on the transition away from an
		// eligible hand draw (the draw the Group would otherwise reuse carries the hand's slot,
		// so foreign geometry would inherit the hand's reprojection).
		val freshBoundary = (handEligible && (lastDrawSlot.get() != slot || lastDrawDomain.get() != domain || !wasEligible)) ||
			endedEligibleDraw
		consolidationContext.set(freshBoundary)
		return freshBoundary
	}

	@JvmStatic
	fun endDraw() {
		consolidationContext.remove()
		eligibleDrawContext.remove()
	}

	/** Whether a hand slot is being staged right now. The Group's reorder-lookup redirect
	 * consults this: while a hand submit is staged, the lookup must never return a draw from
	 * the other hand. */
	@JvmStatic
	fun isActive(): Boolean = submitContext.get() != null

	/**
	 * Whether an eligible hand draw ended and was followed by an ineligible/foreign draw since
	 * the last [clearFrame]. While this holds, the Group's reorder-lookup redirect consults the
	 * hand machine, so foreign geometry can never find the hand's tagged draw through the
	 * reorder path.
	 */
	@JvmStatic
	fun afterEligibleDraw(): Boolean = afterEligibleDraw.get() == true

	/** Whether the current boundary suppressed the reorder lookup, so `getOrAddDraw` must append a fresh draw. */
	@JvmStatic
	fun suppressConsolidation(): Boolean = consolidationContext.get() == true

	/**
	 * Chooses the index for `RenderTypeFeatureRenderer.Group`'s reorder lookup. While a hand
	 * submit is staged this is always -1: a reorder reuse could merge two hands' geometry (or
	 * a foreign submit's) into one draw under one configuration. After an eligible hand draw
	 * ended, each foreign render type reserves its own draw, and later foreign occurrences of
	 * the same type reuse that safe untagged draw instead of the hand's tagged one - the same
	 * isolation the entity machine gives its identity-less submits. Outside the hand window and
	 * away from any hand draw it is exactly vanilla's indexOf.
	 */
	@JvmStatic
	fun consolidationIndex(renderTypes: List<*>, preparedRenderType: Any): Int {
		if (submitContext.get() != null) {
			return -1
		}
		if (afterEligibleDraw.get() != true) {
			return renderTypes.indexOf(preparedRenderType)
		}
		val indexes = foreignDrawIndexes.getOrPut(renderTypes) { HashMap() }
		val existingIndex = indexes[preparedRenderType]
		if (!suppressConsolidation() && existingIndex != null &&
			existingIndex < renderTypes.size && renderTypes[existingIndex] == preparedRenderType
		) {
			return existingIndex
		}
		// getOrAddDraw appends this prepared type immediately after the redirect returns -1.
		// Reserve that append position so subsequent foreign submits can consolidate safely.
		indexes[preparedRenderType] = renderTypes.size
		return -1
	}

	private fun isEligibleRenderType(renderType: RenderType, pipelineEligible: Boolean): Boolean =
		pipelineEligible && renderType.outputTarget() === OutputTarget.MAIN_TARGET

	/** Tags a staged draw with the slot and pose domain being staged, and remembers them as the latest hand draw. */
	@JvmStatic
	fun bindDraw(draw: StagedVertexBuffer.Draw) {
		val slot = submitContext.get()
		if (slot != null && eligibleDrawContext.get() == true) {
			drawSlots[draw] = slot
			lastDrawSlot.set(slot)
			val domain = domainContext.get()
			if (domain != null) {
				drawDomains[draw] = domain
				lastDrawDomain.set(domain)
			}
		}
	}

	@JvmStatic
	fun bindExecuteInfo(draw: StagedVertexBuffer.Draw, info: StagedVertexBuffer.ExecuteInfo?) {
		if (info == null) return
		val slot = drawSlots[draw] ?: return
		executeInfoSlots[info] = slot
		val domain = drawDomains[draw]
		if (domain != null) {
			executeInfoDomains[info] = domain
		}
	}

	/** The hand slot one prepared draw belongs to, or null for hand-foreign draws. */
	@JvmStatic
	fun executeInfoHandSlot(info: StagedVertexBuffer.ExecuteInfo): HandSlot? = executeInfoSlots[info]

	/** The pose domain one prepared hand draw belongs to, or null for hand-foreign draws. */
	@JvmStatic
	fun executeInfoHandDomain(info: StagedVertexBuffer.ExecuteInfo): HandPoseDomain? = executeInfoDomains[info]

	@JvmStatic
	fun clearFrame() {
		submitSlots.clear()
		drawSlots.clear()
		drawDomains.clear()
		executeInfoSlots.clear()
		executeInfoDomains.clear()
		frameSlots.clear()
		foreignDrawIndexes.clear()
		handContext.remove()
		submitContext.remove()
		domainContext.remove()
		eligibleDrawContext.remove()
		previousEligibleDraw.remove()
		afterEligibleDraw.remove()
		consolidationContext.remove()
		lastDrawSlot.remove()
		lastDrawDomain.remove()
	}
}
