package me.snowmii.dlss.mrt

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.IndexType
import com.mojang.blaze3d.PrimitiveTopology
import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.buffers.GpuBufferSlice
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.ScissorState
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import java.nio.file.Path
import kotlin.io.path.readText
import net.minecraft.client.renderer.StagedVertexBuffer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BannerRenderer
import net.minecraft.client.renderer.blockentity.BellRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.ChestRenderer
import net.minecraft.client.renderer.blockentity.ConduitRenderer
import net.minecraft.client.renderer.blockentity.CopperGolemStatueBlockRenderer
import net.minecraft.client.renderer.blockentity.EnchantTableRenderer
import net.minecraft.client.renderer.blockentity.LecternRenderer
import net.minecraft.client.renderer.blockentity.PistonHeadRenderer
import net.minecraft.client.renderer.blockentity.ShulkerBoxRenderer
import net.minecraft.client.renderer.blockentity.SkullBlockRenderer
import net.minecraft.client.renderer.blockentity.SpawnerRenderer
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.blockentity.state.CopperGolemStatueRenderState
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.PreparedRenderType
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.model.geom.EntityModelSet
import net.minecraft.client.model.geom.ModelLayerLocation
import net.minecraft.client.model.geom.ModelPart
import net.minecraft.client.model.geom.builders.LayerDefinition
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.server.packs.metadata.pack.PackFormat
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.storage.DataVersion
import me.snowmii.dlss.bridge.DlssDimensions
import me.snowmii.dlss.render.DlssCameraSample
import me.snowmii.dlss.render.DlssFrameMotion
import me.snowmii.dlss.render.DlssJitterOffset
import me.snowmii.dlss.render.RenderRuntime
import me.snowmii.dlss.render.SceneTarget
import me.snowmii.dlss.render.WorldPhase
import me.snowmii.dlss.session.DlssSession
import me.snowmii.dlss.session.DlssStartupConfig
import me.snowmii.dlss.session.SRMode
import net.minecraft.SharedConstants
import net.minecraft.WorldVersion
import net.minecraft.client.renderer.RenderPipelines
import org.joml.Matrix4f
import org.joml.Vector3f
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import com.google.gson.JsonParser
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Ordinary static block-entity model vertical proof for M-6's velocity writer.
 *
 * Block-entity model geometry goes through the same ModelFeatureRenderer batching and
 * PreparedRenderType draw seam as entity geometry, but has no entity identity and no
 * displacement of its own. The dispatcher bracket must therefore expose a distinct context that
 * can never inherit an adjacent entity id, and the writer must classify that context as a
 * zero-displacement object: the camera reprojection itself when the frame's motion is valid,
 * the invalid sentinel when the frame is missing or reset - the same classification entity
 * draws apply.
 *
 * The test JVM does not apply Fabric mixins or own a live Blaze3D RenderPass, so this suite
 * makes no live transformed/GPU draw claim: descriptors are proven by reflection against the
 * mapped 26.2 classes, the state machine is driven directly at the same seams the mixins use,
 * and passthrough is proven by the control seams answering false (vanilla keeps control).
 */
class MotionVectorBlockEntityTest {
	private val mainTarget = fakeMainTarget()

	companion object {
		/**
		 * The static renderer tests construct real mapped block-entity render states whose
		 * defaults touch Blocks/SoundEvents registries; the headless test JVM needs the vanilla
		 * registry bootstrap first (with a synthetic world version, since no game entrypoint
		 * runs in tests). Idempotent; runs once per class JVM (forkEvery = 1).
		 */
		@JvmStatic
		@org.junit.jupiter.api.BeforeAll
		fun bootstrapVanillaRegistries() {
			SharedConstants.setVersion(
				WorldVersion.Simple(
					"26.2",
					"26.2",
					DataVersion(9999, "main"),
					1,
					PackFormat(1, 1),
					PackFormat(1, 1),
					java.util.Date(),
					true,
				),
			)
			Bootstrap.bootStrap()
		}
	}

	@Test
	fun `block entity context cannot inherit an adjacent entity identity`() {
		val staged = StagedVertexBuffer({ "block-entity-isolation-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()

			// An entity draw before the block submit leaves a real id bound.
			EntityVelocityWriterBindings.beginEntity(101)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val entityDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val entityInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(entityDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(entityDraw, entityInfo)
			assertEquals(101, EntityVelocityWriterBindings.executeInfoEntityId(entityInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// The block-entity bracket exposes no entity id, only its own block-entity marker.
			EntityVelocityWriterBindings.beginBlockEntity()
			val blockSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(blockSubmit)
			assertEquals(
				null,
				EntityVelocityWriterBindings.submitEntityId(blockSubmit),
				"block submit must not expose an entity id",
			)
			assertTrue(EntityVelocityWriterBindings.submitIsBlockEntity(blockSubmit))
			EntityVelocityWriterBindings.beginSubmit(blockSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val blockDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(blockDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertTrue(EntityVelocityWriterBindings.executeInfoIsBlockEntity(blockInfo))
			assertEquals(
				null,
				EntityVelocityWriterBindings.executeInfoEntityId(blockInfo),
				"block geometry must not inherit entity 101",
			)
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endBlockEntity()

			// A following entity keeps its own real id, never the block-entity marker.
			EntityVelocityWriterBindings.beginEntity(202)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val nextDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val nextInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(nextDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(nextDraw, nextInfo)
			assertEquals(202, EntityVelocityWriterBindings.executeInfoEntityId(nextInfo))
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(nextInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endEntity()

			// The frame clear drops the block-entity association too.
			EntityVelocityWriterBindings.clearFrame()
			assertFalse(EntityVelocityWriterBindings.submitIsBlockEntity(blockSubmit))
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `block entity eligible draw is accepted by the writer control seam on a main target`() {
		val runtime = velocityRuntime(withVelocity = true)
		val phase = worldPhase(runtime)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		val staged = StagedVertexBuffer({ "block-entity-control-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginBlockEntity()
			val submit = Any()
			EntityVelocityWriterBindings.bindSubmit(submit)
			EntityVelocityWriterBindings.beginSubmit(submit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(draw)
			EntityVelocityWriterBindings.bindExecuteInfo(draw, info)

			val prepared = PreparedRenderType(
				RenderPipelines.ENTITY_SOLID,
				OutputTarget.MAIN_TARGET,
				FakeBuffer().slice(),
				ScissorState(),
				emptyList(),
			)
			assertTrue(EntityVelocityRender.canDraw(prepared, info, phase))

			// Non-main output targets are not a velocity route for block entities either.
			val itemTarget = PreparedRenderType(
				RenderPipelines.ENTITY_SOLID,
				OutputTarget.ITEM_ENTITY_TARGET,
				FakeBuffer().slice(),
				ScissorState(),
				emptyList(),
			)
			assertFalse(EntityVelocityRender.canDraw(itemTarget, info, phase))

			// Unsupported pipelines fall through to their exact source draw.
			val itemCutout = PreparedRenderType(
				RenderPipelines.ITEM_CUTOUT,
				OutputTarget.MAIN_TARGET,
				FakeBuffer().slice(),
				ScissorState(),
				emptyList(),
			)
			assertFalse(EntityVelocityRender.canDraw(itemCutout, info, phase))
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
			if (phase.isOpen) phase.end()
		}
	}

	@Test
	fun `zero displacement reprojection is the camera reprojection and reset still invalidates`() {
		val camera = DlssFrameMotion(Matrix4f(), 1f, 1f, 16f, false)
		val viewProjection = Matrix4f()
		val jitter = DlssJitterOffset(0, 0f, 0f, DlssDimensions(1280, 720))

		// A static block has no displacement: its object reprojection is exactly the camera's.
		assertEquals(camera.reprojection, EntityVelocityUniforms.blockEntityReprojection(camera, viewProjection, jitter))
		assertEquals(
			camera.reprojection,
			objectReprojection(camera, viewProjection, jitter, Vector3f(0f, 0f, 0f)),
			"zero object delta must leave the camera reprojection untouched",
		)

		// Same invalid/reset classification as entity draws: a missing or reset frame writes
		// the invalid sentinel, and so does a missing view-projection or jitter.
		assertEquals(null, EntityVelocityUniforms.blockEntityReprojection(null, viewProjection, jitter))
		assertEquals(null, EntityVelocityUniforms.blockEntityReprojection(camera.copy(reset = true), viewProjection, jitter))
		assertEquals(null, EntityVelocityUniforms.blockEntityReprojection(camera, null, jitter))
		assertEquals(null, EntityVelocityUniforms.blockEntityReprojection(camera, viewProjection, null))
	}

	@Test
	fun `block entity reprojection reads the open phase and the frame boundary`() {
		val runtime = velocityRuntime(withVelocity = true)
		val phase = worldPhase(runtime)
		renderFrame(phase, mainTarget)
		phase.prepare(true, mainTarget, cameraSample())
		phase.begin(true, mainTarget)
		assertTrue(phase.entityVelocityActive)
		val motion = checkNotNull(phase.activeMotion)
		val reprojection = EntityVelocityUniforms.blockEntityReprojection(
			motion,
			phase.currentViewProjection,
			phase.activeJitter,
		)
		assertNotNull(reprojection)
		assertEquals(motion.reprojection, reprojection)
		phase.end()

		// A closed phase publishes no motion, so the block-entity writer classifies invalid.
		assertEquals(
			null,
			EntityVelocityUniforms.blockEntityReprojection(phase.activeMotion, phase.currentViewProjection, phase.activeJitter),
			"a closed phase must classify the static block invalid, exactly like an entity",
		)
	}

	@Test
	fun `vanilla camera-only non-main and unsupported block entity paths stay vanilla`() {
		val staged = StagedVertexBuffer({ "block-entity-passthrough-test" }, 256)
		try {
			// Vanilla: no active velocity phase anywhere, so no block-entity draw is eligible.
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginBlockEntity()
			val submit = Any()
			EntityVelocityWriterBindings.bindSubmit(submit)
			EntityVelocityWriterBindings.beginSubmit(submit)
			assertFalse(
				EntityVelocityWriterBindings.shouldIsolateDraw(RenderPipelines.ENTITY_SOLID),
				"without an active velocity phase block geometry keeps the vanilla route",
			)
			assertFalse(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, false))
			val draw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val info = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(draw)
			EntityVelocityWriterBindings.bindExecuteInfo(draw, info)
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(info))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endBlockEntity()
			assertFalse(
				EntityVelocityRender.draw(
					PreparedRenderType(
						RenderPipelines.ENTITY_SOLID,
						OutputTarget.MAIN_TARGET,
						FakeBuffer().slice(),
						ScissorState(),
						emptyList(),
					),
					info,
				),
				"a draw without block-entity identity must leave the vanilla draw in control",
			)

			// Non-main render types are never eligible even when the pipeline is entity-shaped.
			val nonMain = RenderTypes.entityTranslucentCullItemTarget(
				Identifier.fromNamespaceAndPath("minecraft", "textures/entity/entity-boundary.png"),
			)
			assertFalse(EntityVelocityWriterBindings.isEligibleRenderType(nonMain, true))

			// Camera-only: the open phase has no velocity attachment, so the control seam refuses.
			val cameraOnly = velocityRuntime(withVelocity = false)
			val cameraOnlyPhase = worldPhase(cameraOnly)
			cameraOnlyPhase.prepare(true, mainTarget, cameraSample())
			cameraOnlyPhase.begin(true, mainTarget)
			assertFalse(cameraOnlyPhase.entityVelocityActive)
			assertFalse(EntityVelocityRender.canDraw(preparedOf(RenderPipelines.ENTITY_SOLID), emptyExecuteInfo(), cameraOnlyPhase))
			cameraOnlyPhase.end()
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `block entity and entity submits interleave without leaking the block marker into entity ids`() {
		val staged = StagedVertexBuffer({ "block-entity-interleave-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()

			// Block submit, then entity submit in the same frame: each keeps its own association.
			EntityVelocityWriterBindings.beginBlockEntity()
			val blockSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(blockSubmit)
			EntityVelocityWriterBindings.endBlockEntity()

			EntityVelocityWriterBindings.beginEntity(707)
			val entitySubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(entitySubmit)
			assertEquals(707, EntityVelocityWriterBindings.submitEntityId(entitySubmit))
			assertFalse(EntityVelocityWriterBindings.submitIsBlockEntity(entitySubmit))
			EntityVelocityWriterBindings.endEntity()

			EntityVelocityWriterBindings.beginSubmit(blockSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val blockDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val blockInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(blockDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(blockDraw, blockInfo)
			assertTrue(EntityVelocityWriterBindings.executeInfoIsBlockEntity(blockInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()

			EntityVelocityWriterBindings.beginSubmit(entitySubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val entityDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val entityInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(entityDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(entityDraw, entityInfo)
			assertEquals(707, EntityVelocityWriterBindings.executeInfoEntityId(entityInfo))
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(entityInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `static block entity family accepts mapped static models and rejects dynamic families`() {
		// The positive static family is read from the mapped 26.2 BlockEntityRenderers registry:
		// only renderers whose submit geometry is time-invariant qualify. Reference the real
		// mapped classes so a rename or registry change breaks this test at compile time.
		assertTrue(
			EntityVelocityWriterBindings.isStaticBlockEntityRenderer(LecternRenderer::class.java),
			"the lectern book is static model geometry",
		)
		assertTrue(
			EntityVelocityWriterBindings.isStaticBlockEntityRenderer(CopperGolemStatueBlockRenderer::class.java),
			"the copper golem statue is static model geometry",
		)

		// Dynamic-capable families must never enter the static set, or their moving geometry
		// would be drawn with a zero-displacement reprojection and ghost.
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(BannerRenderer::class.java), "banner flag waves")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(ChestRenderer::class.java), "chest lid opens")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(EnchantTableRenderer::class.java), "enchantment book animates")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(ShulkerBoxRenderer::class.java), "shulker lid opens")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(BellRenderer::class.java), "bell rings")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(ConduitRenderer::class.java), "conduit spins and bobs")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(SkullBlockRenderer::class.java), "skulls animate")
		assertFalse(EntityVelocityWriterBindings.isStaticBlockEntityRenderer(PistonHeadRenderer::class.java), "piston head is a moving block model")
		assertFalse(
			EntityVelocityWriterBindings.isStaticBlockEntityRenderer(SpawnerRenderer::class.java),
			"spawner display entity spins and nests an entity submit",
		)

		// Admission is exact positive allowlist membership: a subclass of a static renderer is
		// never admitted, because its submit override could introduce dynamic geometry that the
		// zero-displacement reprojection would then ghost. These classes are only classified
		// here - never instantiated - so a rename of the mapped base breaks this test at
		// compile time while the subclass stays a faithful foreign-family stand-in.
		assertFalse(
			EntityVelocityWriterBindings.isStaticBlockEntityRenderer(DynamicStatueSubclass::class.java),
			"a subclass of the copper statue renderer must not be admitted even with a dynamic override",
		)
		assertFalse(
			EntityVelocityWriterBindings.isStaticBlockEntityRenderer(DynamicLecternSubclass::class.java),
			"a subclass of the lectern renderer must not be admitted",
		)
	}

	@Test
	fun `dispatcher handler passes an unsupported renderer through with exactly one vanilla submit and no bracket`() {
		val handler = dispatcherHandler()
		var submitModelCalls = 0
		val collector = submitCollector { submitModelCalls++ }

		EntityVelocityWriterBindings.clearFrame()
		val dynamic = CountingDynamicRenderer()
		// The redirect handler is the production seam: a dynamic/unsupported renderer must be
		// invoked exactly as vanilla invokes it - one submit, no block-entity context, no throw.
		handler.invoke(mixinInstance(), dynamic, BlockEntityRenderState(), PoseStack(), collector, CameraRenderState(), submitOperation())

		assertEquals(1, dynamic.submitCalls, "the unsupported renderer must be invoked exactly once, like vanilla")
		assertEquals(false, dynamic.blockMarkerDuringInvocation, "no block-entity marker may be installed during an unsupported submit")
		assertEquals(null, dynamic.submitEntityIdDuringInvocation, "no entity identity may leak into an unsupported submit either")
		assertEquals(0, submitModelCalls, "the counting unsupported renderer stages no model geometry")
		assertNoBlockContextAfterwards()
	}

	@Test
	fun `dispatcher handler rejects a foreign static subclass and keeps vanilla passthrough`() {
		val handler = dispatcherHandler()
		var submitModelCalls = 0
		val collector = submitCollector { submitModelCalls++ }

		EntityVelocityWriterBindings.clearFrame()
		// A subclass of the mapped static renderer with a dynamic override: under isAssignableFrom
		// admission this would be bracketed and ghosted; exact membership must keep it vanilla.
		val subclass = DynamicStatueSubclass(blockEntityRendererContext())
		handler.invoke(mixinInstance(), subclass, CopperGolemStatueRenderState(), PoseStack(), collector, CameraRenderState(), submitOperation())

		assertEquals(1, subclass.submitCalls, "the foreign subclass must be invoked exactly once, like vanilla")
		assertEquals(false, subclass.blockMarkerDuringInvocation, "the block bracket must not open for a subclass with a dynamic override")
		assertEquals(1, submitModelCalls, "delegation to the real submit stages the model exactly once")
		assertNoBlockContextAfterwards()
	}

	@Test
	fun `dispatcher handler brackets the mapped static renderer around its submit`() {
		val handler = dispatcherHandler()
		val renderer = CopperGolemStatueBlockRenderer(blockEntityRendererContext())

		var submitModelCalls = 0
		var blockMarkerDuringSubmit: Boolean? = null
		val collector = submitCollector {
			submitModelCalls++
			val probe = Any()
			EntityVelocityWriterBindings.bindSubmit(probe)
			blockMarkerDuringSubmit = EntityVelocityWriterBindings.submitIsBlockEntity(probe)
		}

		EntityVelocityWriterBindings.clearFrame()
		handler.invoke(mixinInstance(), renderer, CopperGolemStatueRenderState(), PoseStack(), collector, CameraRenderState(), submitOperation())

		assertEquals(1, submitModelCalls, "the static renderer submit must run exactly once")
		assertEquals(true, blockMarkerDuringSubmit, "the static submit must run inside the block-entity bracket")
		assertNoBlockContextAfterwards()
	}

	@Test
	fun `dynamic block entity renderer without a bracket binds no identity at all`() {
		EntityVelocityWriterBindings.clearFrame()
		// Dynamic renderers keep the exact source route: no bracket and no token, so a submit
		// constructed while no entity identity is on the thread binds nothing and stays vanilla.
		val submit = Any()
		EntityVelocityWriterBindings.bindSubmit(submit)
		assertEquals(null, EntityVelocityWriterBindings.submitEntityId(submit))
		assertFalse(EntityVelocityWriterBindings.submitIsBlockEntity(submit))
	}

	@Test
	fun `nested identity-less entity inside a block bracket cannot inherit the block token`() {
		val staged = StagedVertexBuffer({ "nested-null-entity-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()

			// Outer static block bracket: geometry before the nested call is block geometry.
			EntityVelocityWriterBindings.beginBlockEntity()
			val outerSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(outerSubmit)
			assertTrue(EntityVelocityWriterBindings.submitIsBlockEntity(outerSubmit))

			// A nested identity-less entity submit (the spawner display entity path) masks the
			// outer block context: its geometry must not be drawn as static block geometry.
			EntityVelocityWriterBindings.beginEntity(null)
			val nestedSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(nestedSubmit)
			assertEquals(null, EntityVelocityWriterBindings.submitEntityId(nestedSubmit))
			assertFalse(
				EntityVelocityWriterBindings.submitIsBlockEntity(nestedSubmit),
				"a null-id entity must not inherit the block token",
			)
			EntityVelocityWriterBindings.beginSubmit(nestedSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val nestedDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val nestedInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(nestedDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(nestedDraw, nestedInfo)
			assertFalse(
				EntityVelocityWriterBindings.executeInfoIsBlockEntity(nestedInfo),
				"nested identity-less entity geometry must never draw as static block geometry",
			)
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endEntity()

			// The outer block context resumes: further model geometry stays static block geometry.
			val resumedSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(resumedSubmit)
			assertTrue(
				EntityVelocityWriterBindings.submitIsBlockEntity(resumedSubmit),
				"the outer block context must resume after the nested identity-less entity",
			)
			EntityVelocityWriterBindings.beginSubmit(resumedSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val resumedDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val resumedInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(resumedDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(resumedDraw, resumedInfo)
			assertTrue(EntityVelocityWriterBindings.executeInfoIsBlockEntity(resumedInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endBlockEntity()
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	@Test
	fun `nested stable-id entity inside a block bracket keeps its own identity and the block resumes`() {
		val staged = StagedVertexBuffer({ "nested-entity-test" }, 256)
		try {
			EntityVelocityWriterBindings.clearFrame()
			EntityVelocityWriterBindings.beginBlockEntity()
			val outerSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(outerSubmit)
			assertTrue(EntityVelocityWriterBindings.submitIsBlockEntity(outerSubmit))

			// A nested stable-id entity submit keeps its real identity, never the block marker.
			EntityVelocityWriterBindings.beginEntity(909)
			val nestedSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(nestedSubmit)
			assertEquals(909, EntityVelocityWriterBindings.submitEntityId(nestedSubmit))
			assertFalse(EntityVelocityWriterBindings.submitIsBlockEntity(nestedSubmit))
			EntityVelocityWriterBindings.beginSubmit(nestedSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val nestedDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val nestedInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(nestedDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(nestedDraw, nestedInfo)
			assertEquals(909, EntityVelocityWriterBindings.executeInfoEntityId(nestedInfo))
			assertFalse(EntityVelocityWriterBindings.executeInfoIsBlockEntity(nestedInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endEntity()

			// The outer block context resumes after the nested stable-id entity too.
			val resumedSubmit = Any()
			EntityVelocityWriterBindings.bindSubmit(resumedSubmit)
			assertTrue(EntityVelocityWriterBindings.submitIsBlockEntity(resumedSubmit))
			EntityVelocityWriterBindings.beginSubmit(resumedSubmit)
			assertTrue(EntityVelocityWriterBindings.beginDraw(RenderPipelines.ENTITY_SOLID, true))
			val resumedDraw = staged.appendDraw(DefaultVertexFormat.ENTITY, PrimitiveTopology.QUADS)
			val resumedInfo = emptyExecuteInfo()
			EntityVelocityWriterBindings.bindDraw(resumedDraw)
			EntityVelocityWriterBindings.bindExecuteInfo(resumedDraw, resumedInfo)
			assertTrue(EntityVelocityWriterBindings.executeInfoIsBlockEntity(resumedInfo))
			EntityVelocityWriterBindings.endDraw()
			EntityVelocityWriterBindings.endSubmit()
			EntityVelocityWriterBindings.endBlockEntity()
		} finally {
			EntityVelocityWriterBindings.clearFrame()
			staged.close()
		}
	}

	private fun preparedOf(pipeline: com.mojang.blaze3d.pipeline.RenderPipeline) = PreparedRenderType(
		pipeline,
		OutputTarget.MAIN_TARGET,
		FakeBuffer().slice(),
		ScissorState(),
		emptyList(),
	)

	private fun emptyExecuteInfo() = StagedVertexBuffer.ExecuteInfo(
		FakeBuffer(),
		FakeBuffer(),
		IndexType.INT,
		0,
		0,
		3,
	)

	/** The mixin handler itself: executable proof of the dispatcher branch without a live Mixin transform. */
	private fun dispatcherHandler(): Method {
		val mixinClass = Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin")
		val handler = mixinClass.getDeclaredMethod(
			"mcDlssSubmitBlockEntity",
			BlockEntityRenderer::class.java,
			BlockEntityRenderState::class.java,
			PoseStack::class.java,
			SubmitNodeCollector::class.java,
			CameraRenderState::class.java,
			Operation::class.java,
		)
		handler.isAccessible = true
		return handler
	}

	/**
	 * The wrapped `BlockEntityRenderer.submit` as MixinExtras hands it to a `@WrapOperation`
	 * handler: the receiver leads the operation arguments, so calling through here is the same
	 * invocation the untransformed source call would have made.
	 */
	private fun submitOperation() = Operation<Void> { args ->
		@Suppress("UNCHECKED_CAST")
		val renderer = args[0] as BlockEntityRenderer<BlockEntity, BlockEntityRenderState>
		renderer.submit(
			args[1] as BlockEntityRenderState,
			args[2] as PoseStack,
			args[3] as SubmitNodeCollector,
			args[4] as CameraRenderState,
		)
		null
	}

	/** The untransformed mixin class is a plain object at test runtime: the receiver for the handler. */
	private fun mixinInstance(): Any =
		Class.forName("me.snowmii.dlss.mixin.BlockEntityRenderDispatcherMotionMixin").getDeclaredConstructor().newInstance()

	/** A SubmitNodeCollector proxy that counts model stages (and may probe the bracket mid-submit). */
	private fun submitCollector(onSubmitModel: () -> Unit): SubmitNodeCollector {
		val proxy = Proxy.newProxyInstance(
			SubmitNodeCollector::class.java.classLoader,
			arrayOf(SubmitNodeCollector::class.java),
			InvocationHandler { _, method, _ ->
				if (method.name == "submitModel") onSubmitModel()
				null
			},
		)
		return proxy as SubmitNodeCollector
	}

	private fun assertNoBlockContextAfterwards() {
		val probe = Any()
		EntityVelocityWriterBindings.bindSubmit(probe)
		assertFalse(EntityVelocityWriterBindings.submitIsBlockEntity(probe), "no block-entity context may remain after the handler returns")
		assertEquals(null, EntityVelocityWriterBindings.submitEntityId(probe))
	}

	/** Builds the mapped renderer-provider context with every heavyweight dependency stubbed. */
	private fun blockEntityRendererContext() = BlockEntityRendererProvider.Context(
		uninitialized(),
		uninitialized(),
		uninitialized(),
		uninitialized(),
		FakeModelSet(),
		uninitialized(),
		uninitialized(),
		uninitialized(),
	)

	/** Passes null through a non-null Java parameter: the record components are never touched. */
	@Suppress("UNCHECKED_CAST")
	private fun <T> uninitialized(): T = null as T

	/** Bakes any model layer to an empty part so the mapped static renderers can be constructed headlessly. */
	private class FakeModelSet : EntityModelSet(mapOf<ModelLayerLocation, LayerDefinition>()) {
		override fun bakeLayer(id: ModelLayerLocation): ModelPart = ModelPart(listOf<ModelPart.Cube>(), emptyMap())
	}

	/** An unsupported dynamic-family renderer: counts vanilla submits and probes the thread context. */
	private class CountingDynamicRenderer : BlockEntityRenderer<BlockEntity, BlockEntityRenderState> {
		var submitCalls = 0
		var blockMarkerDuringInvocation: Boolean? = null
		var submitEntityIdDuringInvocation: Int? = null

		override fun createRenderState(): BlockEntityRenderState = BlockEntityRenderState()

		override fun submit(
			state: BlockEntityRenderState,
			poseStack: PoseStack,
			submitNodeCollector: SubmitNodeCollector,
			camera: CameraRenderState,
		) {
			submitCalls++
			val probe = Any()
			EntityVelocityWriterBindings.bindSubmit(probe)
			blockMarkerDuringInvocation = EntityVelocityWriterBindings.submitIsBlockEntity(probe)
			submitEntityIdDuringInvocation = EntityVelocityWriterBindings.submitEntityId(probe)
		}
	}

	/**
	 * A foreign subclass of a mapped static renderer whose submit override is dynamic. Under
	 * isAssignableFrom admission this class would be bracketed and drawn as static geometry;
	 * exact positive membership keeps it on the vanilla route.
	 */
	private class DynamicStatueSubclass(context: BlockEntityRendererProvider.Context) : CopperGolemStatueBlockRenderer(context) {
		var submitCalls = 0
		var blockMarkerDuringInvocation: Boolean? = null

		override fun submit(
			state: CopperGolemStatueRenderState,
			poseStack: PoseStack,
			submitNodeCollector: SubmitNodeCollector,
			camera: CameraRenderState,
		) {
			submitCalls++
			val probe = Any()
			EntityVelocityWriterBindings.bindSubmit(probe)
			blockMarkerDuringInvocation = EntityVelocityWriterBindings.submitIsBlockEntity(probe)
			super.submit(state, poseStack, submitNodeCollector, camera)
		}
	}

	/** Class-literal stand-in for a foreign lectern subclass; only ever classified, never instantiated. */
	private class DynamicLecternSubclass(context: BlockEntityRendererProvider.Context) : LecternRenderer(context)
}
