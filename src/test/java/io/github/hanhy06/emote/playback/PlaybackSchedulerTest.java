package io.github.hanhy06.emote.playback;

import com.google.gson.JsonObject;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PlaybackSchedulerTest {
    @Test
    void tasksRegisteredDuringACallbackStartOnTheNextTick() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        var scope = scheduler.root().child();
        List<String> calls = new ArrayList<>();
        scope.afterTicks(1, () -> {
            calls.add("first");
            scope.everyTicks(1, () -> calls.add("repeat"));
        });
        scheduler.tick(1);
        assertEquals(List.of("first"), calls);
        scheduler.tick(2);
        scheduler.tick(3);
        assertEquals(List.of("first", "repeat", "repeat"), calls);
        scope.cancel();
        scheduler.tick(4);
        assertEquals(3, calls.size());
    }

    @Test
    void scopeClosureCancelsLaterTasksInTheSameTick() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        List<String> calls = new ArrayList<>();
        var scope = scheduler.root().child();
        scope.afterTicks(1, () -> {
            calls.add("stop");
            scope.cancel();
        });
        scope.afterTicks(1, () -> calls.add("late"));
        scheduler.tick(1);
        assertEquals(List.of("stop"), calls);
        assertThrows(IllegalStateException.class, () -> scope.afterTicks(1, () -> {}));
        assertFalse(scope.cancel());
    }

    @Test
    void oneBrokenActionDoesNotStopAnotherAction() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        List<String> calls = new ArrayList<>();
        var broken = scheduler.root().child();
        broken.onClose(() -> calls.add("cleanup"));
        broken.everyTicks(1, () -> { throw new IllegalStateException("expected failure"); });
        scheduler.root().child().everyTicks(1, () -> calls.add("good"));
        scheduler.tick(1);
        scheduler.tick(2);
        assertEquals(List.of("cleanup", "good", "good"), calls);
        assertTrue(broken.isClosed());
        assertFalse(scheduler.root().isClosed());
    }

    @Test
    void signalDeliveryWaitsUntilTheCurrentCallbackReturnsAndCopiesArguments() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        Identifier cue = Identifier.parse("test:cue");
        var scope = scheduler.root().child();
        List<String> calls = new ArrayList<>();
        scope.onSignal(cue, arguments -> {
            calls.add(arguments.get("value").getAsString());
            arguments.addProperty("value", "mutated");
        });
        scope.onSignal(cue, arguments -> calls.add(arguments.get("value").getAsString()));
        scope.invoke(() -> {
            JsonObject parameters = new JsonObject();
            parameters.addProperty("value", "original");
            scope.emitSignal(cue, parameters);
            calls.add("returned");
        });
        assertEquals(List.of("returned", "original", "original"), calls);
    }

    @Test
    void cyclicSignalsFailInsteadOfBlockingTheServerForever() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        Identifier cue = Identifier.parse("test:cycle");
        var scope = scheduler.root().child();
        scope.onSignal(cue, arguments -> scope.emitSignal(cue, arguments));
        assertThrows(IllegalStateException.class, () -> scope.emitSignal(cue, new JsonObject()));
    }

    @Test
    void pausingInsideACallbackSkipsRemainingTasksUntilResumed() {
        AtomicBoolean active = new AtomicBoolean(true);
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {}, active::get);
        List<String> calls = new ArrayList<>();
        var scope = scheduler.root().child();
        scope.afterTicks(1, () -> active.set(false));
        scope.afterTicks(1, () -> calls.add("after-resume"));
        scheduler.tick(1);
        assertTrue(calls.isEmpty());
        active.set(true);
        scheduler.tick(2);
        assertEquals(List.of("after-resume"), calls);
    }

    @Test
    void rootClosureCleansChildrenBeforeRootCleanup() {
        PlaybackScheduler scheduler = new PlaybackScheduler(() -> {});
        List<Integer> cleanup = new ArrayList<>();
        scheduler.root().onClose(() -> cleanup.add(0));
        scheduler.root().child().onClose(() -> cleanup.add(1));
        scheduler.root().child().onClose(() -> cleanup.add(2));
        scheduler.root().cancel();
        assertEquals(List.of(2, 1, 0), cleanup);
    }
}
