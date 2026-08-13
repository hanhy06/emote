package io.github.hanhy06.emote.emote;

import java.util.*;

public class EmoteRegistry {
    public static final int MAX_EMOTE_COUNT = 512;

    private final Map<String, ApiEntry> apiEmotes = new HashMap<>();

    private volatile RegistryState state = RegistryState.empty();
    private Map<String, RegisteredEmote> fileEmotes = Map.of();
    private Map<String, RegisteredSequence> fileSequences = Map.of();

    public synchronized int replace(Collection<RegisteredEmote> emotes, Collection<RegisteredSequence> sequences) {
        List<RegisteredEmote> sorted = new ArrayList<>(emotes);
        sorted.sort(Comparator.comparing(RegisteredEmote::id));

        LinkedHashMap<String, RegisteredEmote> byId = new LinkedHashMap<>();
        for (RegisteredEmote emote : sorted) {
            if (byId.putIfAbsent(emote.id(), emote) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + emote.id());
            }
        }

        List<RegisteredSequence> sortedSequences = new ArrayList<>(sequences);
        sortedSequences.sort(Comparator.comparing(RegisteredSequence::id));
        LinkedHashMap<String, RegisteredSequence> sequencesById = new LinkedHashMap<>();
        for (RegisteredSequence sequence : sortedSequences) {
            if (byId.containsKey(sequence.id()) || sequencesById.putIfAbsent(sequence.id(), sequence) != null) {
                throw new IllegalArgumentException("Duplicate emote id: " + sequence.id());
            }
        }

        this.fileEmotes = Map.copyOf(byId);
        this.fileSequences = Map.copyOf(sequencesById);
        rebuildState();
        return this.fileEmotes.size() + this.fileSequences.size() - this.state.fileDefinitions().size();
    }

    public synchronized UUID registerApi(RegisteredEmote emote) {
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

    public List<RegisteredEmote> getAll() {
        return this.state.animations();
    }

    public List<EmoteDefinition> getAllDefinitions() {
        return this.state.definitions();
    }

    public List<EmoteDefinition> getFileDefinitions() {
        return this.state.fileDefinitions();
    }

    public EmoteDefinition findDefinition(String id) {
        return this.state.definitionsById().get(id);
    }

    public EmoteDefinition findFileDefinition(String id) {
        return this.state.fileDefinitions().stream()
            .filter(definition -> definition.id().equals(id))
            .findFirst()
            .orElse(null);
    }

    public int size() {
        return this.state.definitions().size();
    }

    private void rebuildState() {
        List<RegisteredEmote> apiList = this.apiEmotes.values().stream()
            .map(ApiEntry::emote)
            .sorted(Comparator.comparing(RegisteredEmote::id))
            .toList();
        List<EmoteDefinition> fileList = java.util.stream.Stream.concat(
                this.fileEmotes.values().stream(),
                this.fileSequences.values().stream()
            )
            .map(EmoteDefinition.class::cast)
            .filter(definition -> !this.apiEmotes.containsKey(definition.id()))
            .sorted(Comparator.comparing(EmoteDefinition::id))
            .limit(MAX_EMOTE_COUNT - apiList.size())
            .toList();
        List<EmoteDefinition> combined = new ArrayList<>(apiList.size() + fileList.size());
        combined.addAll(apiList);
        combined.addAll(fileList);
        combined.sort(Comparator.comparing(EmoteDefinition::id));

        LinkedHashMap<String, EmoteDefinition> definitionsById = new LinkedHashMap<>();
        for (EmoteDefinition definition : combined) {
            definitionsById.put(definition.id(), definition);
        }
        this.state = new RegistryState(
            Map.copyOf(definitionsById),
            List.copyOf(combined),
            combined.stream().filter(RegisteredEmote.class::isInstance).map(RegisteredEmote.class::cast).toList(),
            List.copyOf(fileList)
        );
    }

    private record ApiEntry(UUID registrationId, RegisteredEmote emote) {
    }

    private record RegistryState(
        Map<String, EmoteDefinition> definitionsById,
        List<EmoteDefinition> definitions,
        List<RegisteredEmote> animations,
        List<EmoteDefinition> fileDefinitions
    ) {
        private static RegistryState empty() {
            return new RegistryState(Map.of(), List.of(), List.of(), List.of());
        }
    }
}
