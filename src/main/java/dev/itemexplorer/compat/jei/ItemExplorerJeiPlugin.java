package dev.itemexplorer.compat.jei;

import dev.itemexplorer.ItemExplorer;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Discovered by JEI only; common mod initialization never loads optional API classes. */
@JeiPlugin
public final class ItemExplorerJeiPlugin implements IModPlugin {
    @Override public ResourceLocation getPluginUid() {
        return new ResourceLocation(ItemExplorer.MOD_ID, "terminal_crafting");
    }

    @Override public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new TerminalRecipeTransferInfo(registration.getTransferHelper()
                .createUserErrorWithTooltip(Component.translatable("gui.itemexplorer.jei_return_space"))));
    }
}
