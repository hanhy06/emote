package io.github.hanhy06.emote.content;

import io.github.hanhy06.emote.api.EmoteMetadata;
import io.github.hanhy06.emote.api.EmotePlayerBehavior;
import net.minecraft.commands.arguments.coordinates.Coordinates;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public record EmoteSequence(
    Path sourcePath,
    Identifier id,
    EmoteMetadata metadata,
    Settings settings,
    @Nullable Participants participants,
    List<Step> steps
) {
    public enum Control {
        CONTINUE(Identifier.parse("emote:continue")),
        BREAK(Identifier.parse("emote:break"));

        private final Identifier id;

        Control(Identifier id) {
            this.id = id;
        }

        public Identifier id() {
            return this.id;
        }

        public static @Nullable Control fromId(Identifier id) {
            for (Control control : values()) {
                if (control.id.equals(id)) {
                    return control;
                }
            }
            return null;
        }
    }

    public EmoteSequence(Path sourcePath, Identifier id, EmoteMetadata metadata, Settings settings, List<Step> steps) {
        this(sourcePath, id, metadata, settings, null, steps);
    }

    public EmoteSequence {
        Objects.requireNonNull(sourcePath, "sourcePath");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(settings, "settings");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("sequence steps must not be empty");
        }
        long awaitCount = steps.stream().filter(AwaitPartnerStep.class::isInstance).count();
        if (awaitCount > 0) {
            if (steps.size() != 1 || awaitCount != 1) {
                throw new IllegalArgumentException("a partner sequence must contain exactly one await_partner step");
            }
            Objects.requireNonNull(participants, "partner sequence participants");
        } else {
            if (participants != null) {
                throw new IllegalArgumentException("participants require an await_partner step");
            }
            validateLinearSteps(steps, "sequence");
        }
    }

    public record Settings(int cooldownTicks, EmotePlayerBehavior player) {
        public Settings {
            if (cooldownTicks < 0) {
                throw new IllegalArgumentException("cooldown must not be negative");
            }
            Objects.requireNonNull(player, "player");
        }
    }

    public record Participants(ParticipantPlacement initiator, ParticipantPlacement partner) {
        public Participants {
            Objects.requireNonNull(initiator, "initiator");
            Objects.requireNonNull(partner, "partner");
        }
    }

    public record ParticipantPlacement(Coordinates position, Coordinates rotation) {
        public ParticipantPlacement {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(rotation, "rotation");
        }
    }

    public sealed interface Step permits EmoteStep, WaitStep, AwaitPartnerStep {
    }

    public record EmoteStep(List<Choice> choices, int repeat, int transitionTicks) implements Step {
        public EmoteStep {
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("sequence emote candidates must not be empty");
            }
            if (choices.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("choices");
            }
            if (choices.stream().map(Choice::targetId).distinct().count() != choices.size()) {
                throw new IllegalArgumentException("sequence emote candidates must not contain duplicates");
            }
            boolean weighted = choices.getFirst().chance() > 0;
            if (choices.stream().anyMatch(choice -> (choice.chance() > 0) != weighted)) {
                throw new IllegalArgumentException("sequence emote candidates must use either equal or explicit chances");
            }
            if (weighted && choices.stream().mapToInt(Choice::chance).sum() != 100) {
                throw new IllegalArgumentException("sequence emote candidate chances must total 100");
            }
            if (repeat < 1) {
                throw new IllegalArgumentException("sequence repeat must be at least 1");
            }
            if (transitionTicks < 0) {
                throw new IllegalArgumentException("sequence transition must not be negative");
            }
        }

        public EmoteStep(List<Choice> choices, int repeat) {
            this(choices, repeat, 0);
        }

        public EmoteStep(Identifier targetId, int repeat) {
            this(List.of(new Choice(Objects.requireNonNull(targetId, "targetId"), 0)), repeat, 0);
        }

        public EmoteStep(Identifier targetId, int repeat, int transitionTicks) {
            this(List.of(new Choice(Objects.requireNonNull(targetId, "targetId"), 0)), repeat, transitionTicks);
        }

        public EmoteStep(Collection<Identifier> targetIds, int repeat) {
            this(targetIds.stream().map(targetId -> new Choice(targetId, 0)).toList(), repeat, 0);
        }

        public List<Identifier> targetIds() {
            return this.choices.stream().map(Choice::targetId).toList();
        }
    }

    public record WaitStep(int ticks) implements Step {
        public WaitStep {
            if (ticks < 1) {
                throw new IllegalArgumentException("sequence wait must be at least 1 tick");
            }
        }
    }

    public record AwaitPartnerStep(
        Identifier offerAnimationId,
        int timeoutTicks,
        List<Step> matched,
        List<Step> timeout
    ) implements Step {
        public AwaitPartnerStep {
            Objects.requireNonNull(offerAnimationId, "offerAnimationId");
            if (Control.fromId(offerAnimationId) != null) {
                throw new IllegalArgumentException("await_partner offer must reference an animation");
            }
            if (timeoutTicks < 1) {
                throw new IllegalArgumentException("await_partner timeout must be at least 1 tick");
            }
            matched = List.copyOf(matched);
            timeout = List.copyOf(timeout);
            validateLinearSteps(matched, "matched branch");
            validateLinearSteps(timeout, "timeout branch");
        }
    }

    public record Choice(Identifier targetId, int chance) {
        public Choice {
            Objects.requireNonNull(targetId, "targetId");
            if (chance < 0 || chance > 100) {
                throw new IllegalArgumentException("sequence emote candidate chance must be between 1 and 100");
            }
        }
    }

    private static void validateLinearSteps(List<Step> steps, String name) {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(name + " steps must not be empty");
        }
        if (steps.stream().anyMatch(AwaitPartnerStep.class::isInstance)) {
            throw new IllegalArgumentException(name + " must not contain await_partner");
        }
        if (steps.getFirst() instanceof WaitStep || steps.getLast() instanceof WaitStep) {
            throw new IllegalArgumentException(name + " wait steps must be between emote steps");
        }
        for (int index = 1; index < steps.size(); index++) {
            if (steps.get(index - 1) instanceof WaitStep && steps.get(index) instanceof WaitStep) {
                throw new IllegalArgumentException(name + " wait steps must not be consecutive");
            }
        }
    }
}
