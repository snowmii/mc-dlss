package me.snowmii.dlss

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Puts one line in the client's own chat.
 *
 * The log already carries everything this prints, and a reviewer watching the frame is not
 * watching the log. This exists so the state that produced the frame in front of them is on the
 * same screen as the frame.
 *
 * Failure is silence: a readout is a convenience, and throwing out of a key press to report that a
 * convenience was unavailable would take the client down over a chat line.
 */
object DlssChatReadout {
	@JvmStatic
	fun send(message: String) {
		try {
			val minecraft = Minecraft.getInstance() ?: return
			minecraft.gui.hud.getChat().addClientSystemMessage(Component.literal(message))
		} catch (_: Throwable) {
			// No chat yet, or no client at all; the log line already went out.
		}
	}
}
