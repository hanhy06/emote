import type { RuntimeEasing } from "../../domain/minecraftData";
import type { BbKeyframe } from "./blockbenchCubeSchema";
import { SUPPORTED_EASINGS } from "./curveMath";

export const SUPPORTED_BLOCKBENCH_EASINGS = Object.freeze([...SUPPORTED_EASINGS, "step"]);

export function blockbenchEasingToEmote(name: string | undefined): RuntimeEasing | undefined {
  const normalized = (name ?? "linear").replace(/([a-z0-9])([A-Z])/g, "$1_$2").toLowerCase();
  if (normalized === "none") return "linear";
  return SUPPORTED_BLOCKBENCH_EASINGS.includes(normalized.replaceAll("_", "")) && normalized !== "step"
    ? normalized as RuntimeEasing
    : undefined;
}

export function blockbenchIntervalIsStep(frames: readonly BbKeyframe[], fromTime: number, toTime: number): boolean {
  for (let index = 1; index < frames.length; index++) {
    const before = frames[index - 1];
    const after = frames[index];
    if (before.interpolation === "step" && fromTime >= before.time && fromTime < after.time) return true;
    if (after.easing?.toLowerCase() !== "step" || after.time <= before.time) continue;
    const steps = Math.max(2, Math.floor(after.easingArgs?.[0] ?? 5));
    for (let step = 1; step <= steps; step++) {
      const boundary = before.time + (after.time - before.time) * step / steps;
      if (boundary > fromTime + 1e-9 && boundary <= toTime + 1e-9) return true;
    }
  }
  return false;
}
