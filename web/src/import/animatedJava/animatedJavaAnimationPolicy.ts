import { ConversionError } from "../../foundation/diagnostics";
import { createBlockbenchChannelEvaluator } from "../common/blockbenchKeyframeEvaluator";
import { MolangBakeEvaluator } from "../common/molangBakeEvaluator";

const BAKE_ERROR = "unsupported_animated_java_molang";
const PREVIEW_ERROR = "unsupported_animated_java_preview_molang";

export const ANIMATED_JAVA_CHANNELS = createBlockbenchChannelEvaluator({
  bakeEvaluator: new MolangBakeEvaluator({
    error: { code: BAKE_ERROR, message: (expression) => `Animated Java expression ${expression} cannot be baked.` },
  }),
  previewEvaluator: new MolangBakeEvaluator({
    rejectNondeterministic: true,
    error: { code: PREVIEW_ERROR, message: (expression) => `Animated Java expression ${expression} is not available in the approximate preview.` },
  }),
  invalidPointCount: (path) => new ConversionError("unsupported_animated_java_keyframe", "Animated Java transform keyframes must contain one value or a pre/post pair.", path),
  missingAxis: (path) => new ConversionError("invalid_animated_java_keyframe", "Animated Java transform keyframe is missing an axis value.", path),
  unsupportedInterpolation: (channel, interpolation, path) => new ConversionError("unsupported_animated_java_interpolation", `Animated Java ${channel} keyframe uses unsupported ${interpolation} interpolation.`, path),
  unsupportedEasing: (channel, easing, path) => new ConversionError("unsupported_animated_java_easing", `Animated Java ${channel} keyframe uses unsupported easing ${easing}.`, path),
  canFallbackFromBake: (error) => error instanceof ConversionError && error.code === BAKE_ERROR,
  canFallbackFromPreview: (error) => error instanceof ConversionError && error.code === PREVIEW_ERROR,
});
