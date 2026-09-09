package io.github.hanhy06.emote.skin;

import io.github.hanhy06.emote.config.Config;
import io.github.hanhy06.emote.skin.model.PlayerSkinPreparation;
import io.github.hanhy06.emote.skin.model.PlayerSkinRegion;
import io.github.hanhy06.emote.skin.model.PlayerSkinSource;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticSkinProviderTest {
    @Test void registrationSelectsAccountsAndAccountFailureDoesNotFallBack() {
        AtomicBoolean registered = new AtomicBoolean();
        var accounts = new Stub(PlayerSkinPreparation.State.FAILED);
        var mineSkin = new Stub(PlayerSkinPreparation.State.PREPARING);
        var automatic = new AutomaticSkinProvider(registered::get, accounts, mineSkin);
        var source = new PlayerSkinSource(UUID.randomUUID(), "Player", "hash", "https://example.invalid/skin", false);
        assertEquals(PlayerSkinPreparation.State.PREPARING, automatic.prepare(source, Set.of()).state());
        registered.set(true);
        assertEquals(PlayerSkinPreparation.State.FAILED, automatic.prepare(source, Set.of()).state());
        assertEquals(1, mineSkin.preparations);
        registered.set(false);
        assertEquals(PlayerSkinPreparation.State.PREPARING, automatic.prepare(source, Set.of()).state());
        assertEquals(1, accounts.preparations);
        automatic.cancelPendingBakes();
        assertTrue(accounts.canceled);
        assertTrue(mineSkin.canceled);
    }

    private static final class Stub implements PlayerSkinProvider {
        final PlayerSkinPreparation.State state;
        int preparations;
        boolean canceled;
        Stub(PlayerSkinPreparation.State state) { this.state = state; }
        @Override public PlayerSkinPreparation prepare(PlayerSkinSource source, Set<PlayerSkinRegion> regions) {
            this.preparations++;
            return new PlayerSkinPreparation(null, this.state, 0);
        }
        @Override public void onConfigReload(Config config) {}
        @Override public void setListener(Listener listener) {}
        @Override public void cancelPendingBakes() { this.canceled = true; }
    }
}
