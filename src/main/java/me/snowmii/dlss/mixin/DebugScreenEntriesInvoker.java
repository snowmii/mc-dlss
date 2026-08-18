package me.snowmii.dlss.mixin;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(DebugScreenEntries.class)
public interface DebugScreenEntriesInvoker {
	@Invoker("register")
	static Identifier mcDlssRegister(final Identifier id, final DebugScreenEntry entry) {
		throw new AssertionError();
	}
}
