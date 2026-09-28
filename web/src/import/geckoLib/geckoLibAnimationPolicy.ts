import { ConversionError } from "../../foundation/diagnostics";
import { createBlockbenchChannelEvaluator } from "../common/blockbenchKeyframeEvaluator";
import { MolangBakeEvaluator } from "../common/molangBakeEvaluator";

const BAKE_ERROR = "unsupported_geckolib_molang";
const PREVIEW_ERROR = "unsupported_geckolib_preview_molang";

export const GECKOLIB_CHANNELS = createBlockbenchChannelEvaluator({
  bakeEvaluator: new MolangBakeEvaluator({
    error: { code: BAKE_ERROR, message: (expression) => `GeckoLib expression ${expression} cannot be baked.` },
  }),
  previewEvaluator: new MolangBakeEvaluator({
    rejectNondeterministic: true,
    error: { code: PREVIEW_ERROR, previewUnavailable: true, message: (expression) => `GeckoLib expression ${expression} is not available in preview.` },
  }),
  invalidPointCount: (path) => new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair.", path),
  missingAxis: (path) => new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value.", path),
  unsupportedInterpolation: (channel, interpolation, path) => new ConversionError("unsupported_geckolib_interpolation", `GeckoLib ${channel} keyframe uses unsupported ${interpolation} interpolation.`, path),
  unsupportedEasing: (channel, easing, path) => new ConversionError("unsupported_geckolib_easing", `GeckoLib ${channel} keyframe uses unsupported easing ${easing}.`, path),
  canFallbackFromBake: (error) => error instanceof ConversionError && error.code === BAKE_ERROR,
});
