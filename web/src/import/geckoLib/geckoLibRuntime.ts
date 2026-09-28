import { ConversionError } from "../../foundation/diagnostics";
import { createBlockbenchNativeRuntime } from "../common/blockbenchNativeRuntime";
import { GECKOLIB_CHANNELS } from "./geckoLibAnimationPolicy";
import { GECKOLIB_BBMODEL_TRANSFORMS } from "./geckoLibCubeTransform";

export const createGeckoLibRuntime = createBlockbenchNativeRuntime({
  formatLabel: "GeckoLib",
  runtimeSceneId: "geckolib_scene",
  transforms: GECKOLIB_BBMODEL_TRANSFORMS,
  channels: GECKOLIB_CHANNELS,
  invalidPointCount: () => new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair."),
  missingAxis: () => new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value."),
});
