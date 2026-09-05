package io.github.hanhy06.emote.config;

import net.minecraft.resources.Identifier;

import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public record AccessConfig(List<String> disabled, List<PermissionEntry> permissions) {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final int LEGACY_SCHEMA_VERSION = 2;

    public AccessConfig {
        disabled = normalizeIds(disabled, "disabled", "disabled emote id must not be blank", false);

        Objects.requireNonNull(permissions, "permissions");
        Set<String> seenPermissions = new HashSet<>();
        for (PermissionEntry entry : permissions) {
            Objects.requireNonNull(entry, "permission entry");
            if (!seenPermissions.add(entry.permission())) {
                throw new IllegalArgumentException("duplicate permission: " + entry.permission());
            }
        }
        permissions = List.copyOf(permissions);
    }

    public static AccessConfig createDefault() {
        return new AccessConfig(
            List.of(),
            List.of(new PermissionEntry(
                "emote.default",
                List.of("*"),
                Optional.empty(),
                Optional.of(new IdleSettings(3 * 60 * 20, List.of("emote:sit")))
            ))
        );
    }

    public boolean isEnabled(String id) {
        return !disabled.contains(id);
    }

    public static final class PermissionEntry {
        private static final String ALL_EMOTES = "*";

        private final String permission;
        private final List<String> emotes;
        private final Optional<CooldownModifier> cooldown;
        private final Optional<IdleSettings> idle;
        private final boolean allEmotes;
        private final List<Pattern> emotePatterns;

        public PermissionEntry(
            String permission,
            List<String> emotes,
            Optional<CooldownModifier> cooldown,
            Optional<IdleSettings> idle
        ) {
            if (permission == null || permission.isBlank()) {
                throw new IllegalArgumentException("permission must not be blank");
            }
            this.permission = permission.trim();

            this.emotes = normalizeIds(
                emotes,
                "permission emotes",
                "permission emote pattern must not be blank",
                false
            );
            this.cooldown = Objects.requireNonNull(cooldown, "cooldown");
            this.idle = Objects.requireNonNull(idle, "idle");
            this.allEmotes = this.emotes.contains(ALL_EMOTES);
            this.emotePatterns = this.emotes.stream()
                .filter(pattern -> !pattern.equals(ALL_EMOTES))
                .map(this::compilePattern)
                .toList();
        }

        public String permission() {
            return this.permission;
        }

        public List<String> emotes() {
            return this.emotes;
        }

        public Optional<IdleSettings> idle() {
            return this.idle;
        }

        public Optional<CooldownModifier> cooldown() {
            return this.cooldown;
        }

        public boolean appliesToAllEmotes() {
            return this.allEmotes;
        }

        public boolean matchesEmote(String id) {
            return this.emotePatterns.stream().anyMatch(pattern -> pattern.matcher(id).matches());
        }

        private Pattern compilePattern(String source) {
            if (Identifier.tryParse(source) != null) {
                return Pattern.compile(source, Pattern.LITERAL);
            }
            try {
                return Pattern.compile(source);
            } catch (PatternSyntaxException exception) {
                throw new IllegalArgumentException(
                    "invalid emote pattern '" + source + "' for permission '" + this.permission + "': " + exception.getDescription(),
                    exception
                );
            }
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof PermissionEntry other)) {
                return false;
            }
            return this.permission.equals(other.permission)
                && this.emotes.equals(other.emotes)
                && this.cooldown.equals(other.cooldown)
                && this.idle.equals(other.idle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.permission, this.emotes, this.cooldown, this.idle);
        }

        @Override
        public String toString() {
            return "PermissionEntry[permission=" + this.permission + ", emotes=" + this.emotes
                + ", cooldown=" + this.cooldown + ", idle=" + this.idle + "]";
        }
    }

    public record CooldownModifier(Type type, double value) {
        public CooldownModifier {
            Objects.requireNonNull(type, "type");
            if (!Double.isFinite(value) || value < 0.0D) {
                throw new IllegalArgumentException("cooldown modifier must be a finite nonnegative number");
            }
            if (type == Type.SUBTRACT && value != Math.rint(value)) {
                throw new IllegalArgumentException("cooldown tick reduction must be a whole number");
            }
        }

        public static CooldownModifier multiply(double multiplier) {
            return new CooldownModifier(Type.MULTIPLY, multiplier);
        }

        public static CooldownModifier subtract(int ticks) {
            return new CooldownModifier(Type.SUBTRACT, ticks);
        }

        public int apply(int cooldownTicks) {
            double adjusted = this.type == Type.MULTIPLY
                ? cooldownTicks * this.value
                : cooldownTicks - this.value;
            return (int)Math.clamp(Math.round(adjusted), 0L, Integer.MAX_VALUE);
        }

        public String configValue() {
            return this.type == Type.MULTIPLY ? "x" + this.value : (int)this.value + "t";
        }

        public enum Type {
            MULTIPLY,
            SUBTRACT
        }
    }

    public record IdleSettings(int delayTicks, List<Choice> choices) {
        public IdleSettings {
            if (delayTicks < 1) {
                throw new IllegalArgumentException("idle delay must be at least 1 tick");
            }
            choices = List.copyOf(choices);
            if (choices.isEmpty()) {
                throw new IllegalArgumentException("idle emote must not be empty");
            }
            if (choices.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("idle emote choices");
            }
            if (choices.stream().map(Choice::id).distinct().count() != choices.size()) {
                throw new IllegalArgumentException("idle emote must not contain duplicate ids");
            }
            boolean weighted = choices.getFirst().chance() > 0;
            if (choices.stream().anyMatch(choice -> (choice.chance() > 0) != weighted)) {
                throw new IllegalArgumentException("idle emote must use either equal or explicit chances");
            }
            if (weighted && choices.stream().mapToInt(Choice::chance).sum() != 100) {
                throw new IllegalArgumentException("idle emote chances must total 100");
            }
        }

        public IdleSettings(int delayTicks, Collection<String> emotes) {
            this(delayTicks, normalizeIds(
                List.copyOf(emotes),
                "idle emote",
                "idle emote id must not be blank",
                true
            ).stream().map(id -> new Choice(id, 0)).toList());
        }

        public List<String> emote() {
            return this.choices.stream().map(Choice::id).toList();
        }

        public record Choice(String id, int chance) {
            public Choice {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException("idle emote id must not be blank");
                }
                id = id.trim();
                if (chance < 0 || chance > 100) {
                    throw new IllegalArgumentException("idle emote chance must be between 1 and 100");
                }
            }
        }
    }

    private static List<String> normalizeIds(
        List<String> ids,
        String fieldName,
        String invalidIdMessage,
        boolean requireNonEmpty
    ) {
        Objects.requireNonNull(ids, fieldName);
        if (requireNonEmpty && ids.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        return ids.stream()
            .map(id -> {
                if (id == null || id.isBlank()) {
                    throw new IllegalArgumentException(invalidIdMessage);
                }
                return id.trim();
            })
            .distinct()
            .toList();
    }
}
