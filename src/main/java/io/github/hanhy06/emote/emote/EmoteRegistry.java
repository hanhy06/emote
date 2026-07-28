package io.github.hanhy06.emote.emote;

import java.util.*;

public class EmoteRegistry {
    public static final int MAX_EMOTE_COUNT = 512;

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, RegisteredEmote> fileEmotes = Map.of();
    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();

    public synchronized int replace(Collection<RegisteredEmote> emotes) {
        List<RegisteredEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(RegisteredEmote::id));

        LinkedHashMap<String, RegisteredEmote> byId = new LinkedHashMap<>();
        for (RegisteredEmote emote : sorted) {
            if (byId.putIfAbsent(emote.id(), emote) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
        }

        this.fileEmotes = Map.copyOf(byId);
        rebuildState();
        return this.fileEmotes.size() - this.state.fileEmotes().size();
    }

    public synchronized UUID registerApi(RegisteredEmote emote) {
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

    public synchronized boolean unregisterApi(String id, UUID registrationId) {
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
        if (removedCount == 0) {
            return 0;
        }
        this.apiEmotes.clear();
        rebuildState();
        return removedCount;
    }

    public List<RegisteredEmote> getAll() {
        return this.state.emotes();
    }

    public List<RegisteredEmote> getFileEmotes() {
        return this.state.fileEmotes();
    }

    public RegisteredEmote find(String id) {
        return this.state.byId().get(id);
    }

    public RegisteredEmote findFile(String id) {
        return this.state.fileById().get(id);
    }

    public int size() {
        return this.state.emotes().size();
    }

    private void rebuildState() {
        List<RegisteredEmote> apiList = this.apiEmotes.values().stream()
            .map(ApiEntry::emote)
            .sorted(Comparator.comparing(RegisteredEmote::id))
            .toList();
        List<RegisteredEmote> fileList = this.fileEmotes.values().stream()
            .filter(emote -> !this.apiEmotes.containsKey(emote.id()))
            .sorted(Comparator.comparing(RegisteredEmote::id))
            .limit(MAX_EMOTE_COUNT - apiList.size())
            .toList();
        List<RegisteredEmote> combined = new ArrayList<>(apiList.size() + fileList.size());
        combined.addAll(apiList);
        combined.addAll(fileList);
        combined.sort(Comparator.comparing(RegisteredEmote::id));

        LinkedHashMap<String, RegisteredEmote> byId = new LinkedHashMap<>();
        for (RegisteredEmote emote : combined) {
            byId.put(emote.id(), emote);
        }
        LinkedHashMap<String, RegisteredEmote> fileById = new LinkedHashMap<>();
        for (RegisteredEmote emote : fileList) {
            fileById.put(emote.id(), emote);
        }
        this.state = new RegistryState(
            Map.copyOf(byId),
            List.copyOf(combined),
            Map.copyOf(fileById),
            List.copyOf(fileList)
        );
    }

    private record ApiEntry(UUID registrationId, RegisteredEmote emote) {
    }

    private record RegistryState(
        Map<String, RegisteredEmote> byId,
        List<RegisteredEmote> emotes,
        Map<String, RegisteredEmote> fileById,
        List<RegisteredEmote> fileEmotes
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of(), Map.of(), List.of());
        }
    }
}
