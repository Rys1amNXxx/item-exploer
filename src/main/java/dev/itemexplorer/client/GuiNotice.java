package dev.itemexplorer.client;

import net.minecraft.Util;
import net.minecraft.network.chat.Component;

/** A timed status message with its tone; screens poll it while rendering. */
final class GuiNotice {
    private Component text = Component.empty();
    private VanillaGui.Tone tone = VanillaGui.Tone.MUTED;
    private long until;

    void show(Component text, VanillaGui.Tone tone, long millis) {
        this.text = text; this.tone = tone; until = Util.getMillis() + millis;
    }

    /** Server replies carry a message key; the tone says whether the reply reports success. */
    void message(String key, VanillaGui.Tone tone, long millis) {
        show(Component.translatable("message.itemexplorer." + key), tone, millis);
    }

    void clear() { until = 0; }
    boolean active() { return Util.getMillis() < until; }
    Component text() { return text; }
    VanillaGui.Tone tone() { return tone; }
}
