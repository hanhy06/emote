package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.EmoteMod;

import java.util.*;
import java.util.function.Consumer;

public class EmoteCatalog {
    public static final int MAX_EMOTE_COUNT = 512;

    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();
    private final List<Consumer<List<PlayableEmote>>> listeners = new ArrayList<>();
    private final Deque<ListenerNotification> pendingNotifications = new ArrayDeque<>();

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, PlayableEmote> fileEmotes = Map.of();
    private boolean dispatchingNotifications;

    public int replace(Collection<? extends PlayableEmote> emotes) {
        List<PlayableEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(PlayableEmote::id));

        LinkedHashMap<String, PlayableEmote> byId = new LinkedHashMap<>();
        for (PlayableEmote definition : sorted) {
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

    public UUID register(PreparedAnimation emote) {
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

    public List<PlayableEmote> emotes() {
        return this.state.emotes();
    }

    public List<PlayableEmote> fileEmotes() {
        return this.state.fileEmotes();
    }

    public PlayableEmote find(String id) {
        return this.state.emotesById().get(id);
    }

    public PlayableEmote findFileEmote(String id) {
        return this.state.fileEmotesById().get(id);
    }

    public int size() {
        return this.state.emotes().size();
    }

    public void addListener(Consumer<List<PlayableEmote>> listener) {
        Consumer<List<PlayableEmote>> validatedListener = Objects.requireNonNull(listener, "listener");

        boolean shouldDispatch;
        synchronized (this) {
            this.listeners.add(validatedListener);
            shouldDispatch = enqueueNotification(List.of(validatedListener));
        }
        dispatchNotifications(shouldDispatch);
    }

    private void rebuildState() {
        List<PreparedAnimation> apiList = this.apiEmotes.values().stream()
            .map(ApiEntry::emote)
            .sorted(Comparator.comparing(PreparedAnimation::id))
            .toList();
        List<PlayableEmote> fileList = this.fileEmotes.values().stream()
            .filter(definition -> !this.apiEmotes.containsKey(definition.id()))
            .sorted(Comparator.comparing(PlayableEmote::id))
            .limit(MAX_EMOTE_COUNT - apiList.size())
            .toList();
        List<PlayableEmote> combined = new ArrayList<>(apiList.size() + fileList.size());
        combined.addAll(apiList);
        combined.addAll(fileList);
        combined.sort(Comparator.comparing(PlayableEmote::id));

        LinkedHashMap<String, PlayableEmote> emotesById = new LinkedHashMap<>();
        for (PlayableEmote definition : combined) {
            emotesById.put(definition.id(), definition);
        }
        LinkedHashMap<String, PlayableEmote> fileEmotesById = new LinkedHashMap<>();
        for (PlayableEmote definition : fileList) {
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

    private boolean enqueueNotification(Collection<Consumer<List<PlayableEmote>>> listeners) {
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

            for (Consumer<List<PlayableEmote>> listener : notification.listeners()) {
                try {
                    listener.accept(notification.emotes());
                } catch (RuntimeException exception) {
                    EmoteMod.LOGGER.error("Emote catalog listener failed", exception);
                }
            }
        }
    }

    private record ListenerNotification(
        List<Consumer<List<PlayableEmote>>> listeners,
        List<PlayableEmote> emotes
    ) {
    }

    private record ApiEntry(UUID registrationId, PreparedAnimation emote) {
    }

    private record RegistryState(
        Map<String, PlayableEmote> emotesById,
        Map<String, PlayableEmote> fileEmotesById,
        List<PlayableEmote> emotes,
        List<PreparedAnimation> animations,
        List<PlayableEmote> fileEmotes
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), Map.of(), List.of(), List.of(), List.of());
        }
    }
}
