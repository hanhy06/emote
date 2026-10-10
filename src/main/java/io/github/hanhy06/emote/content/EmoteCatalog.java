package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.EmoteMod;

import java.util.*;
import java.util.function.Consumer;

public class EmoteCatalog {
    public static final int MAX_EMOTE_COUNT = 512;

    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();
    private final List<Consumer<List<PreparedEmote>>> listeners = new ArrayList<>();
    private final Deque<ListenerNotification> pendingNotifications = new ArrayDeque<>();

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, PreparedEmote> fileEmotes = Map.of();
    private boolean dispatchingNotifications;

    public int replace(Collection<? extends PreparedEmote> emotes) {
        List<PreparedEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(PreparedEmote::id));

        LinkedHashMap<String, PreparedEmote> byId = new LinkedHashMap<>();
        for (PreparedEmote definition : sorted) {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + definition.id());
            }
        }

        int ignoredCount;
        boolean shouldDispatch;
        synchronized (this) {
            this.fileEmotes = Map.copyOf(byId);
            rebuildState();
            ignoredCount = this.fileEmotes.size() - this.state.fileEmotes().size();
            shouldDispatch = enqueueNotification(this.listeners);
        }
        dispatchNotifications(shouldDispatch);
        return ignoredCount;
    }

    public UUID register(PreparedEmote emote) {
        Objects.requireNonNull(emote, "emote");

        UUID registrationId;
        boolean shouldDispatch;
        synchronized (this) {
            if (this.fileEmotes.containsKey(emote.id()) || this.apiEmotes.containsKey(emote.id())) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
            if (this.apiEmotes.size() >= MAX_EMOTE_COUNT) {
                throw new IllegalStateException("The emote registry is full.");
            }

            registrationId = UUID.randomUUID();
            this.apiEmotes.put(emote.id(), new ApiEntry(registrationId, emote));
            rebuildState();
            shouldDispatch = enqueueNotification(this.listeners);
        }
        dispatchNotifications(shouldDispatch);
        return registrationId;
    }

    public boolean unregister(String id, UUID registrationId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(registrationId, "registrationId");

        boolean shouldDispatch;
        synchronized (this) {
            ApiEntry entry = this.apiEmotes.get(id);
            if (entry == null || !entry.registrationId().equals(registrationId)) {
                return false;
            }
            this.apiEmotes.remove(id);
            rebuildState();
            shouldDispatch = enqueueNotification(this.listeners);
        }
        dispatchNotifications(shouldDispatch);
        return true;
    }

    public synchronized boolean isApiRegistrationActive(String id, UUID registrationId) {
        ApiEntry entry = this.apiEmotes.get(id);
        return entry != null && entry.registrationId().equals(registrationId);
    }

    public int clearApiRegistrations() {
        int removedCount;
        boolean shouldDispatch = false;
        synchronized (this) {
            removedCount = this.apiEmotes.size();

            if (removedCount > 0) {
                this.apiEmotes.clear();
                rebuildState();
                shouldDispatch = enqueueNotification(this.listeners);
            }
        }
        dispatchNotifications(shouldDispatch);
        return removedCount;
    }

    public List<PreparedAnimation> animations() {
        return this.state.animations();
    }

    public List<PreparedEmote> emotes() {
        return this.state.emotes();
    }

    public List<PreparedEmote> fileEmotes() {
        return this.state.fileEmotes();
    }

    public PreparedEmote find(String id) {
        return this.state.emotesById().get(id);
    }

    public PreparedEmote findFileEmote(String id) {
        return this.state.fileEmotesById().get(id);
    }

    public int size() {
        return this.state.emotes().size();
    }

    public void addListener(Consumer<List<PreparedEmote>> listener) {
        Consumer<List<PreparedEmote>> validatedListener = Objects.requireNonNull(listener, "listener");

        boolean shouldDispatch;
        synchronized (this) {
            this.listeners.add(validatedListener);
            shouldDispatch = enqueueNotification(List.of(validatedListener));
        }
        dispatchNotifications(shouldDispatch);
    }

    private void rebuildState() {
        List<PreparedEmote> apiList = this.apiEmotes.values().stream()
            .map(ApiEntry::emote)
            .sorted(Comparator.comparing(PreparedEmote::id))
            .toList();
        List<PreparedEmote> fileList = this.fileEmotes.values().stream()
            .filter(definition -> !this.apiEmotes.containsKey(definition.id()))
            .sorted(Comparator.comparing(PreparedEmote::id))
            .limit(MAX_EMOTE_COUNT - apiList.size())
            .toList();
        List<PreparedEmote> combined = new ArrayList<>(apiList.size() + fileList.size());
        combined.addAll(apiList);
        combined.addAll(fileList);
        combined.sort(Comparator.comparing(PreparedEmote::id));

        Map<String, PreparedAnimation> animations = new HashMap<>();
        for (PreparedEmote definition : this.fileEmotes.values()) {
            if (definition instanceof PreparedAnimation animation) animations.put(animation.id(), animation);
        }
        for (ApiEntry entry : this.apiEmotes.values()) {
            if (entry.emote() instanceof PreparedAnimation animation) animations.put(animation.id(), animation);
        }
        boolean removedSequence = false;
        for (int index = 0; index < combined.size(); index++) {
            PreparedEmote definition = combined.get(index);
            ApiEntry entry = this.apiEmotes.get(definition.id());
            if (entry == null || !(definition instanceof PreparedSequence sequence)) continue;
            try {
                PreparedSequence resolved = PreparedSequence.prepare(new LoadedSequence(sequence.sourcePath(), sequence.model()), animations);
                this.apiEmotes.put(definition.id(), new ApiEntry(entry.registrationId(), resolved));
                combined.set(index, resolved);
            } catch (io.github.hanhy06.emote.api.EmoteLoadException exception) {
                this.apiEmotes.remove(definition.id());
                removedSequence = true;
            }
        }
        if (removedSequence) {
            rebuildState();
            return;
        }

        LinkedHashMap<String, PreparedEmote> emotesById = new LinkedHashMap<>();
        for (PreparedEmote definition : combined) {
            emotesById.put(definition.id(), definition);
        }
        LinkedHashMap<String, PreparedEmote> fileEmotesById = new LinkedHashMap<>();
        for (PreparedEmote definition : fileList) {
            fileEmotesById.put(definition.id(), definition);
        }
        this.state = new RegistryState(
            Map.copyOf(emotesById),
            Map.copyOf(fileEmotesById),
            List.copyOf(combined),
            combined.stream().filter(PreparedAnimation.class::isInstance).map(PreparedAnimation.class::cast).toList(),
            List.copyOf(fileList)
        );
    }

    private boolean enqueueNotification(Collection<Consumer<List<PreparedEmote>>> listeners) {
        if (listeners.isEmpty()) {
            return false;
        }
        this.pendingNotifications.add(new ListenerNotification(List.copyOf(listeners), this.state.emotes()));
        if (this.dispatchingNotifications) {
            return false;
        }
        this.dispatchingNotifications = true;
        return true;
    }

    private void dispatchNotifications(boolean shouldDispatch) {
        if (!shouldDispatch) {
            return;
        }

        while (true) {
            ListenerNotification notification;
            synchronized (this) {
                notification = this.pendingNotifications.poll();
                if (notification == null) {
                    this.dispatchingNotifications = false;
                    return;
                }
            }

            for (Consumer<List<PreparedEmote>> listener : notification.listeners()) {
                try {
                    listener.accept(notification.emotes());
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.error("Emote catalog listener failed", exception);
                }
            }
        }
    }

    private record ListenerNotification(
        List<Consumer<List<PreparedEmote>>> listeners,
        List<PreparedEmote> emotes
    ) {
    }

    private record ApiEntry(UUID registrationId, PreparedEmote emote) {
    }

    private record RegistryState(
        Map<String, PreparedEmote> emotesById,
        Map<String, PreparedEmote> fileEmotesById,
        List<PreparedEmote> emotes,
        List<PreparedAnimation> animations,
        List<PreparedEmote> fileEmotes
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), List.of(), List.of(), List.of());
        }
    }
}
