package me.snowmii.dlss.render.ui

import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline

internal class FallbackCompiledPipeline(val source: RenderPipeline) : CompiledRenderPipeline {
	override fun isClosed(): Boolean = false
	override fun close() = Unit
}
