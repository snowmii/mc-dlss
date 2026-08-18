package me.snowmii.dlss.client

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer
import net.minecraft.client.gui.components.debug.DebugScreenEntry
import net.minecraft.world.level.Level
import net.minecraft.world.level.chunk.LevelChunk

object DlssDebugEntry : DebugScreenEntry {
	override fun display(
		displayer: DebugScreenDisplayer,
		serverOrClientLevel: Level?,
		clientChunk: LevelChunk?,
		serverChunk: LevelChunk?,
	) {
		displayer.addLine(ClientRuntime.active().activeControls()?.readout() ?: "DLSS runtime not started")
		ClientRuntime.active().activeControls()?.motionProbeLine()?.let(displayer::addLine)
		DlssDebugSnapshot.lines().forEach(displayer::addLine)
	}
}
