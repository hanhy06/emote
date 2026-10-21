package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.EmoteApi;
import io.github.hanhy06.emote.api.Registration;
import io.github.hanhy06.emote.examples.EntityFollowExample;
import io.github.hanhy06.emote.examples.HugExample;
import io.github.hanhy06.emote.examples.TrumpetExample;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

/**
 * Registers the API examples used by the bundled sample emotes.
 * Keep this entry point visible so developers can trace registration to each example.
 */
public final class ExampleCallbacks {
    public static final Identifier IDLE_BUTTERFLY_CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");
    public static final Identifier BAT_CALLBACK_ID = Identifier.parse("emote:idle_bat_callback");
    public static final Identifier TRUMPET_CAN_CAN_CALLBACK_ID = Identifier.parse("emote:trumpet_can_can_callback");
    public static final Identifier HUG_CALLBACK_ID = Identifier.parse("emote:hug_callback");

    private final List<Registration> registrations;
    private final HugExample hug;

    private ExampleCallbacks(EmoteApi api) {
        EntityFollowExample entities = new EntityFollowExample();
        this.hug = new HugExample(api);

        // A callback name matches the "name" field in an emote's callbacks array.
        this.registrations = List.of(
            api.registerCallbacks(IDLE_BUTTERFLY_CALLBACK_ID, entities.butterflyCallbacks),
            api.registerCallbacks(BAT_CALLBACK_ID, entities.batCallbacks),
            api.registerCallbacks(TRUMPET_CAN_CAN_CALLBACK_ID, new TrumpetExample()),
            api.registerCallbacks(HUG_CALLBACK_ID, this.hug)
        );

        // Monitor both sessions, including cancellations before their first frame callbacks.
        ServerTickEvents.END_SERVER_TICK.register(server -> this.hug.tick());
    }

    /** Call once after the Emote API is available; retain the result for cleanup. */
    public static ExampleCallbacks registerAll(EmoteApi api) {
        return new ExampleCallbacks(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        boolean removed = false;

        for (Registration registration : this.registrations) {
            removed |= registration.unregister();
        }

        // The Fabric tick listener stays registered, but has no active work after close().
        this.hug.close();
        return removed;
    }
}
