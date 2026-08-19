package me.snowmii.dlss.readout

import me.snowmii.dlss.client.ClientRuntime
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
		DlssDebugSnapshot.lines().forEach(displayer::addLine)
	}
}

object DlssPacingEntry : DebugScreenEntry {
	override fun display(
		displayer: DebugScreenDisplayer,
		serverOrClientLevel: Level?,
		clientChunk: LevelChunk?,
		serverChunk: LevelChunk?,
	) {
		val controls = ClientRuntime.active().activeControls() ?: return
		controls.pacingLine()?.let(displayer::addLine)
	}
}
