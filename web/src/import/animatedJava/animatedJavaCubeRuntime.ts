import { ConversionError } from "../../foundation/diagnostics";
import { createBlockbenchNativeRuntime } from "../common/blockbenchNativeRuntime";
import { ANIMATED_JAVA_CHANNELS } from "./animatedJavaAnimationPolicy";
import { ANIMATED_JAVA_BLUEPRINT_TRANSFORMS } from "./animatedJavaCubeTransform";

export const createAnimatedJavaCubeRuntime = createBlockbenchNativeRuntime({
  formatLabel: "Animated Java",
  runtimeSceneId: "animated_java_scene",
  transforms: ANIMATED_JAVA_BLUEPRINT_TRANSFORMS,
  channels: ANIMATED_JAVA_CHANNELS,
  invalidPointCount: () => new ConversionError("unsupported_animated_java_keyframe", "Animated Java transform keyframes must contain one value or a pre/post pair."),
  missingAxis: () => new ConversionError("invalid_animated_java_keyframe", "Animated Java transform keyframe is missing an axis value."),
});
