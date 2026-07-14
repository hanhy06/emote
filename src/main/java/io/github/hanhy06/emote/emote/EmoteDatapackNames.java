package io.github.hanhy06.emote.emote;

public final class EmoteDatapackNames {
    private static final String INTERNAL_FUNCTION_PATH = ":_/";
    private static final String ANIMATION_TAG_PREFIX = "animation_";

    private EmoteDatapackNames() {
    }

    public static String createFunctionId(String namespace) {
        return internalFunctionId(namespace, "create");
    }

    public static String entrypointFunctionId(String namespace, String entrypoint) {
        return namespace + ":" + entrypoint;
    }

    public static String stopAnimationFunctionId(String namespace) {
        return internalFunctionId(namespace, "stop_anim");
    }

    public static String deleteFunctionId(String namespace) {
        return internalFunctionId(namespace, "delete");
    }

    public static String rootTag(String namespace) {
        return namespace + "_root";
    }

    public static String cameraTag(String namespace) {
        return namespace + "_camera";
    }

    public static String partTag(String namespace, int partIndex) {
        return namespace + "_" + partIndex;
    }

    public static boolean isAnimationTag(String tag) {
        return tag.startsWith(ANIMATION_TAG_PREFIX);
    }

    public static boolean isCleanupTag(String tag, String namespace) {
        if (tag.equals(namespace) || tag.equals(rootTag(namespace)) || tag.equals(cameraTag(namespace))) {
            return true;
        }
        if (!tag.startsWith(namespace + "_")) {
            return false;
        }

        String suffix = tag.substring(namespace.length() + 1);
        if (suffix.isEmpty()) {
            return false;
        }
        if (suffix.charAt(0) == 'p') {
            return suffix.length() > 1 && suffix.substring(1).chars().allMatch(Character::isDigit);
        }
        return suffix.chars().allMatch(Character::isDigit);
    }

    private static String internalFunctionId(String namespace, String functionName) {
        return namespace + INTERNAL_FUNCTION_PATH + functionName;
    }
}
