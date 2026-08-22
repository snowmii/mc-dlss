package me.snowmii.dlss.api;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;

public final class BeforeDlssEvaluation {
    public static final Event<Runnable> EVENT = EventFactory.createArrayBacked(
        Runnable.class,
        callbacks -> () -> {
            for (Runnable callback : callbacks) callback.run();
        }
    );

    private BeforeDlssEvaluation() {
    }
}
