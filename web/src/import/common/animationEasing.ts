import type { RuntimeEasing } from "../../domain/minecraftData";
import type { BbKeyframe } from "./blockbenchCubeSchema";

type EasingFunction = (progress: number) => number;

const easeOut = (easing: EasingFunction): EasingFunction => (progress) => 1 - easing(1 - progress);
const easeInOut = (easing: EasingFunction): EasingFunction => (progress) => progress < 0.5
  ? easing(progress * 2) / 2
  : 1 - easing((1 - progress) * 2) / 2;
const power = (exponent: number): EasingFunction => (progress) => Math.pow(progress, exponent);

const sine: EasingFunction = (progress) => 1 - Math.cos(progress * Math.PI / 2);
const quadratic: EasingFunction = power(2);
const cubic: EasingFunction = power(3);
const quartic: EasingFunction = power(4);
const quintic: EasingFunction = power(5);
const exponential: EasingFunction = (progress) => Math.pow(2, 10 * (progress - 1));
const circular: EasingFunction = (progress) => 1 - Math.sqrt(1 - progress * progress);
const back: EasingFunction = (progress) => progress * progress * ((1.70158 + 1) * progress - 1.70158);
const elastic: EasingFunction = (progress) => 1 - Math.pow(Math.cos(progress * Math.PI / 2), 3) * Math.cos(progress * Math.PI);
const bounce: EasingFunction = (progress) => {
  const bounciness = 0.5;
  const one = 121 / 16 * progress * progress;
  const two = 121 / 4 * bounciness * Math.pow(progress - 6 / 11, 2) + 1 - bounciness;
  const three = 121 * bounciness * bounciness * Math.pow(progress - 9 / 11, 2) + 1 - bounciness * bounciness;
  const four = 484 * Math.pow(bounciness, 3) * Math.pow(progress - 10.5 / 11, 2) + 1 - Math.pow(bounciness, 3);
  return Math.min(one, two, three, four);
};
const EASINGS: Readonly<Record<string, EasingFunction>> = {
  linear: (progress) => progress,
  none: (progress) => progress,
  easeinsine: sine,
  easeoutsine: easeOut(sine),
  easeinoutsine: easeInOut(sine),
  easeinquad: quadratic,
  easeoutquad: easeOut(quadratic),
  easeinoutquad: easeInOut(quadratic),
  easeincubic: cubic,
  easeoutcubic: easeOut(cubic),
  easeinoutcubic: easeInOut(cubic),
  easeinquart: quartic,
  easeoutquart: easeOut(quartic),
  easeinoutquart: easeInOut(quartic),
  easeinquint: quintic,
  easeoutquint: easeOut(quintic),
  easeinoutquint: easeInOut(quintic),
  easeinexpo: exponential,
  easeoutexpo: easeOut(exponential),
  easeinoutexpo: easeInOut(exponential),
  easeincirc: circular,
  easeoutcirc: easeOut(circular),
  easeinoutcirc: easeInOut(circular),
  easeinback: back,
  easeoutback: easeOut(back),
  easeinoutback: easeInOut(back),
  easeinelastic: elastic,
  easeoutelastic: easeOut(elastic),
  easeinoutelastic: easeInOut(elastic),
  easeinbounce: bounce,
  easeoutbounce: easeOut(bounce),
  easeinoutbounce: easeInOut(bounce),
};

export function animationEasingProgress(name: string, progress: number, args?: number[]): number | undefined {
  if (name.toLowerCase() === "step") {
    const steps = Math.max(2, Math.floor(args?.[0] ?? 5));
    return Math.floor(progress * steps + 1e-9) / steps;
  }
  return EASINGS[name.toLowerCase()]?.(progress);
}

export const SUPPORTED_BLOCKBENCH_EASINGS = Object.freeze([...Object.keys(EASINGS), "step"]);

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
