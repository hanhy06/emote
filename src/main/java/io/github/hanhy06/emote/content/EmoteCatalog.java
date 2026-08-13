package io.github.hanhy06.emote.content;

import java.util.*;

public class EmoteCatalog {
    public static final int MAX_EMOTE_COUNT = 512;

    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, PreparedEmote> fileEmotes = Map.of();
    private Map<String, PreparedSequence> fileSequences = Map.of();

    public synchronized int replace(Collection<PreparedEmote> emotes, Collection<PreparedSequence> sequences) {
        List<PreparedEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(PreparedEmote::id));

        LinkedHashMap<String, PreparedEmote> byId = new LinkedHashMap<>();
        for (PreparedEmote emote : sorted) {
            if (byId.putIfAbsent(emote.id(), emote) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
        }

        List<PreparedSequence> sortedSequences = new ArrayList<>(sequences);
        sortedSequences.sort(Comparator.comparing(PreparedSequence::id));
        LinkedHashMap<String, PreparedSequence> sequencesById = new LinkedHashMap<>();
        for (PreparedSequence sequence : sortedSequences) {
            if (byId.containsKey(sequence.id()) || sequencesById.putIfAbsent(sequence.id(), sequence) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + sequence.id());
            }
        }

        this.fileEmotes = Map.copyOf(byId);
        this.fileSequences = Map.copyOf(sequencesById);
        rebuildState();
        return this.fileEmotes.size() + this.fileSequences.size() - this.state.fileDefinitions().size();
    }

    public synchronized UUID registerApi(PreparedEmote emote) {
        Objects.requireNonNull(emote, "emote");
        if (this.fileEmotes.containsKey(emote.id())
            || this.fileSequences.containsKey(emote.id())
            || this.apiEmotes.containsKey(emote.id())) {
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

        if (removedCount > 0) {
            this.apiEmotes.clear();
            rebuildState();
        }

        return removedCount;
    }

    public List<PreparedEmote> getAll() {
        return this.state.animations();
    }

    public List<PreparedDefinition> getAllDefinitions() {
        return this.state.definitions();
    }

    public List<PreparedDefinition> getFileDefinitions() {
        return this.state.fileDefinitions();
    }

    public PreparedDefinition findDefinition(String id) {
        return this.state.definitionsById().get(id);
    }

    public PreparedDefinition findFileDefinition(String id) {
        return this.state.fileDefinitions().stream()
            .filter(definition -> definition.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    public int size() {
        return this.state.definitions().size();
    }

    private void rebuildState() {
        List<PreparedEmote> apiList = this.apiEmotes.values().stream()
            .map(ApiEntry::emote)
            .sorted(Comparator.comparing(PreparedEmote::id))
            .toList();
        List<PreparedDefinition> fileList = java.util.stream.Stream.concat(
                this.fileEmotes.values().stream(),
                this.fileSequences.values().stream()
            )
            .map(PreparedDefinition.class::cast)
            .filter(definition -> !this.apiEmotes.containsKey(definition.id()))
            .sorted(Comparator.comparing(PreparedDefinition::id))
            .limit(MAX_EMOTE_COUNT - apiList.size())
            .toList();
        List<PreparedDefinition> combined = new ArrayList<>(apiList.size() + fileList.size());
        combined.addAll(apiList);
        combined.addAll(fileList);
        combined.sort(Comparator.comparing(PreparedDefinition::id));

        LinkedHashMap<String, PreparedDefinition> definitionsById = new LinkedHashMap<>();
        for (PreparedDefinition definition : combined) {
            definitionsById.put(definition.id(), definition);
        }
        this.state = new RegistryState(
            Map.copyOf(definitionsById),
            List.copyOf(combined),
            combined.stream().filter(PreparedEmote.class::isInstance).map(PreparedEmote.class::cast).toList(),
            List.copyOf(fileList)
        );
    }

    private record ApiEntry(UUID registrationId, PreparedEmote emote) {
    }

    private record RegistryState(
        Map<String, PreparedDefinition> definitionsById,
        List<PreparedDefinition> definitions,
        List<PreparedEmote> animations,
        List<PreparedDefinition> fileDefinitions
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of(), List.of(), List.of());
        }
    }
}
