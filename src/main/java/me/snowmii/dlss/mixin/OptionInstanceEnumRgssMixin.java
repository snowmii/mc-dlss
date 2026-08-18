package me.snowmii.dlss.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import me.snowmii.dlss.client.VideoOptionLocks;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.components.CycleButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla Texture Filtering is an {@link OptionInstance.Enum} whose cycle list is fixed at
 * construction. Swap that list for the alt-list supplier so RGSS disappears only while SR is on.
 */
@Mixin(OptionInstance.Enum.class)
public class OptionInstanceEnumRgssMixin {
	@WrapOperation(
		method = "valueListSupplier",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/CycleButton$ValueListSupplier;create(Ljava/util/Collection;)Lnet/minecraft/client/gui/components/CycleButton$ValueListSupplier;"
		)
	)
	private CycleButton.ValueListSupplier<?> mcDlssRgssCycle(
		final Collection<?> values,
		final Operation<CycleButton.ValueListSupplier<?>> original
	) {
		if (values.isEmpty() || !(values.iterator().next() instanceof TextureFilteringMethod)) {
			return original.call(values);
		}
		final List<TextureFilteringMethod> all = new ArrayList<>(values.size());
		final List<TextureFilteringMethod> withoutRgss = new ArrayList<>(values.size());
		for (final Object value : values) {
			final TextureFilteringMethod method = (TextureFilteringMethod)value;
			all.add(method);
			if (method != TextureFilteringMethod.RGSS) {
				withoutRgss.add(method);
			}
		}
		return CycleButton.ValueListSupplier.create(VideoOptionLocks::rgssAllowed, withoutRgss, all);
	}
}
