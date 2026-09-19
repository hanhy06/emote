package io.github.hanhy06.emote.molang;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public final class MolangQueryCatalog {
    private static final Catalog CATALOG = loadCatalog();
    private static final Set<String> SUPPORTED_VALUES = CATALOG.values();
    private static final Map<String, QuerySignature> SUPPORTED_FUNCTIONS = CATALOG.functions();
    public static final Set<String> SUPPORTED_NAMES = Stream.concat(SUPPORTED_VALUES.stream(), SUPPORTED_FUNCTIONS.keySet().stream())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private MolangQueryCatalog() {
    }

    private static Catalog loadCatalog() {
        try (var reader = new InputStreamReader(
            Objects.requireNonNull(MolangQueryCatalog.class.getResourceAsStream("/emote/molang-queries.json"), "missing Molang query catalog"),
            StandardCharsets.UTF_8
        )) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            var values = new LinkedHashSet<String>();
            root.getAsJsonArray("values").forEach(element -> values.add(element.getAsString()));
            var functions = new LinkedHashMap<String, QuerySignature>();
            for (var entry : root.getAsJsonObject("functions").entrySet()) {
                JsonObject signature = entry.getValue().getAsJsonObject();
                int minimum = signature.get("minimumArguments").getAsInt();
                int maximum = signature.has("maximumArguments") ? signature.get("maximumArguments").getAsInt() : Integer.MAX_VALUE;
                functions.put(entry.getKey(), new QuerySignature(minimum, maximum));
            }
            return new Catalog(Set.copyOf(values), Map.copyOf(functions));
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    public static void validate(MolangEngine.CompiledExpression expression, String path) {
        for (MolangEngine.QueryUse use : expression.queryUses()) {
            if (use.kind() == MolangEngine.QueryUseKind.ASSIGNMENT) {
                throw new IllegalArgumentException(path + " must not assign queries");
            }
            QuerySignature function = SUPPORTED_FUNCTIONS.get(use.name());
            if (use.kind() == MolangEngine.QueryUseKind.VALUE) {
                if (SUPPORTED_VALUES.contains(use.name())) {
                    continue;
                }
                if (function != null) {
                    throw new IllegalArgumentException(path + " must call query function " + use.name());
                }
                throw new IllegalArgumentException(path + " references unsupported query " + use.name());
            }
            if (function == null) {
                if (SUPPORTED_VALUES.contains(use.name())) {
                    throw new IllegalArgumentException(path + " must not call query value " + use.name());
                }
                throw new IllegalArgumentException(path + " references unsupported query " + use.name());
            }
            if (!function.accepts(use.argumentCount())) {
                throw new IllegalArgumentException(
                    path + " calls query " + use.name() + " with " + use.argumentCount() + " arguments; expected " + function.describe()
                );
            }
        }
    }

    private record QuerySignature(int minimumArguments, int maximumArguments) {
        private QuerySignature {
            if (minimumArguments < 0 || maximumArguments < minimumArguments) {
                throw new IllegalArgumentException("invalid query argument range");
            }
        }

        boolean accepts(int argumentCount) {
            return argumentCount >= this.minimumArguments && argumentCount <= this.maximumArguments;
        }

        String describe() {
            if (this.maximumArguments == Integer.MAX_VALUE) {
                return "at least " + this.minimumArguments;
            }
            return this.minimumArguments == this.maximumArguments
                ? Integer.toString(this.minimumArguments)
                : this.minimumArguments + ".." + this.maximumArguments;
        }
    }

    private record Catalog(Set<String> values, Map<String, QuerySignature> functions) {
    }
}
