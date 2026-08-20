package io.github.hanhy06.emote.content;

import java.util.*;

public class EmoteCatalog {
    public static final int MAX_EMOTE_COUNT = 512;

    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, PlayableEmote> fileEmotes = Map.of();

    public synchronized int replace(Collection<? extends PlayableEmote> emotes) {
        List<PlayableEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(PlayableEmote::id));

        LinkedHashMap<String, PlayableEmote> byId = new LinkedHashMap<>();
        for (PlayableEmote definition : sorted) {
            if (byId.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + definition.id());
            }
        }

        this.fileEmotes = Map.copyOf(byId);
        rebuildState();
        return this.fileEmotes.size() - this.state.fileEmotes().size();
    }

    public synchronized UUID register(PreparedAnimation emote) {
        Objects.requireNonNull(emote, "emote");
        if (this.fileEmotes.containsKey(emote.id()) || this.apiEmotes.containsKey(emote.id())) {
            throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
        }
        if (this.apiEmotes.size() >= MAX_EMOTE_COUNT) {
            throw new IllegalStateException("The emote registry is full.");
        }

        UUID registrationId = UUID.randomUUID();
        this.apiEmotes.put(emote.id(), new ApiEntry(registrationId, emote));
        rebuildState();
        return registrationId;
    }

    public synchronized boolean unregister(String id, UUID registrationId) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(registrationId, "registrationId");
        ApiEntry entry = this.apiEmotes.get(id);
        if (entry == null || !entry.registrationId().equals(registrationId)) {
            return false;
        }
        this.apiEmotes.remove(id);
        rebuildState();
        return true;
    }

    public synchronized boolean isApiRegistrationActive(String id, UUID registrationId) {
        ApiEntry entry = this.apiEmotes.get(id);
        return entry != null && entry.registrationId().equals(registrationId);
    }

    public synchronized int clearApiRegistrations() {
        int removedCount = this.apiEmotes.size();

        if (removedCount > 0) {
            this.apiEmotes.clear();
            rebuildState();
        }

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
        return this.state.fileEmotes().stream()
            .filter(definition -> definition.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    public int size() {
        return this.state.emotes().size();
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
        this.state = new RegistryState(
            Map.copyOf(emotesById),
            List.copyOf(combined),
            combined.stream().filter(PreparedAnimation.class::isInstance).map(PreparedAnimation.class::cast).toList(),
            List.copyOf(fileList)
        );
    }

    private record ApiEntry(UUID registrationId, PreparedAnimation emote) {
    }

    private record RegistryState(
        Map<String, PlayableEmote> emotesById,
        List<PlayableEmote> emotes,
        List<PreparedAnimation> animations,
        List<PlayableEmote> fileEmotes
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of(), List.of(), List.of());
        }
    }
}
