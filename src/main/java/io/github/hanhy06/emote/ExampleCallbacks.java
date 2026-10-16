package io.github.hanhy06.emote;

import io.github.hanhy06.emote.api.EmoteApi;
import io.github.hanhy06.emote.api.Registration;
import io.github.hanhy06.emote.examples.EntityFollowExample;
import io.github.hanhy06.emote.examples.TrumpetExample;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

public final class ExampleCallbacks {
    public static final Identifier IDLE_BUTTERFLY_CALLBACK_ID = Identifier.parse("emote:idle_butterfly_callback");
    public static final Identifier BAT_CALLBACK_ID = Identifier.parse("emote:idle_bat_callback");
    public static final Identifier TRUMPET_CAN_CAN_CALLBACK_ID = Identifier.parse("emote:trumpet_can_can_callback");

    private final List<Registration> registrations;

    private ExampleCallbacks(EmoteApi api) {
        EntityFollowExample entities = new EntityFollowExample();
        this.registrations = List.of(
            api.registerCallbacks(IDLE_BUTTERFLY_CALLBACK_ID, entities.butterflyCallbacks),
            api.registerCallbacks(BAT_CALLBACK_ID, entities.batCallbacks),
            api.registerCallbacks(TRUMPET_CAN_CAN_CALLBACK_ID, new TrumpetExample())
        );
    }

    public static ExampleCallbacks registerAll(EmoteApi api) {
        return new ExampleCallbacks(Objects.requireNonNull(api, "api"));
    }

    public boolean unregister() {
        boolean removed = false;
        for (Registration registration : this.registrations) removed |= registration.unregister();
        return removed;
    }
}
