package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline

/** Test command backends record the source without pretending to compile a GPU pipeline. */
internal class FallbackCompiledPipeline(val source: RenderPipeline) : CompiledRenderPipeline {
	override fun isClosed(): Boolean = false
	override fun close() = Unit
}
