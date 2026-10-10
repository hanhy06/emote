import { parseAnimationSeconds } from "../format/time";
import { Quaternion } from "three";
import type { CurveIR, EasingIR, ScalarIR, SegmentIR, VectorValueIR } from "./animationIR";

export type ScalarEvaluatorIR = (value: ScalarIR, progress: number, base?: number) => number;

export function evaluateEasing(easing: EasingIR | undefined, u: number): number {
  if (!easing || easing.kernel === "linear") return u;
  const f = (x: number): number => {
    switch (easing.kernel) {
      case "power": return x ** easing.exponent!;
      case "sine": return 1 - Math.cos(Math.PI * x / 2);
      case "expo": return x === 0 ? 0 : 2 ** (10 * (x - 1));
      case "circ": return 1 - Math.sqrt(1 - x * x);
      case "back": { const k = easing.overshoot ?? 1.70158; return x * x * ((k + 1) * x - k); }
      case "blockbench_elastic": return 1 - Math.cos(Math.PI * x / 2) ** 3 * Math.cos(Math.PI * (easing.frequency ?? 1) * x);
      case "blockbench_bounce": {
        const b = easing.bounciness ?? 0.5;
        return Math.min(121 / 16 * x * x, 121 / 4 * b * (x - 6 / 11) ** 2 + 1 - b,
          121 * b * b * (x - 9 / 11) ** 2 + 1 - b * b, 484 * b ** 3 * (x - 10.5 / 11) ** 2 + 1 - b ** 3);
      }
      case "steps": return Math.floor(x * easing.count!) / easing.count!;
      case "linear": return x;
    }
  };
  if (easing.direction === "out") return 1 - f(1 - u);
  if (easing.direction === "in_out") return u < 0.5 ? f(2 * u) / 2 : 1 - f(2 - 2 * u) / 2;
  return f(u);
}

export function sampleCurve(curve: CurveIR, time: number, base: readonly number[], evaluate: ScalarEvaluatorIR): number[] {
  const vector = (value: VectorValueIR, u: number) => value.map((scalar, axis) => evaluate(scalar, u, base[axis]));
  const first = curve.keys[0];
  if (time < parseAnimationSeconds(first.time)) return curve.before === "first_pre" ? vector(first.pre ?? first.value!, 0) : [...base];
  let index = 0;
  while (index + 1 < curve.keys.length && parseAnimationSeconds(curve.keys[index + 1].time) <= time) index++;
  const left = curve.keys[index];
  if (time === parseAnimationSeconds(left.time) || index === curve.keys.length - 1) return vector(left.post ?? left.value!, 1);
  const right = curve.keys[index + 1];
  const duration = parseAnimationSeconds(right.time) - parseAnimationSeconds(left.time);
  const u = (time - parseAnimationSeconds(left.time)) / duration;
  const start = vector(left.post ?? left.value!, u);
  const segment = curve.segments[index];
  if (segment.interpolation === "step") return start;
  const end = vector(right.pre ?? right.value!, u);
  const t = evaluateEasing(segment.easing, u);
  if (segment.interpolation === "slerp") {
    return new Quaternion(...start as [number, number, number, number]).normalize()
      .slerp(new Quaternion(...end as [number, number, number, number]).normalize(), t).toArray();
  }
  const previous = segment.previous ? vector(segment.previous, u) : start;
  const following = segment.following ? vector(segment.following, u) : end;
  const out = segment.out_tangent ? vector(segment.out_tangent, u) : [];
  const incoming = segment.in_tangent ? vector(segment.in_tangent, u) : [];
  return start.map((a, axis) => {
    const b = end[axis];
    if (segment.interpolation === "linear") return a + (b - a) * t;
    if (segment.interpolation === "bezier") {
      const handle = segment.handles![axis];
      const h1 = evaluate(handle.out.value, u, base[axis]), h2 = evaluate(handle.in.value, u, base[axis]);
      let low = 0, high = 1;
      for (let i = 0; i < 48; i++) {
        const k = (low + high) / 2;
        const x = 3 * (1 - k) ** 2 * k * handle.out.time + 3 * (1 - k) * k * k * handle.in.time + k ** 3;
        if (x < t) low = k; else high = k;
      }
      const k = (low + high) / 2;
      return (1 - k) ** 3 * a + 3 * (1 - k) ** 2 * k * h1 + 3 * (1 - k) * k * k * h2 + k ** 3 * b;
    }
    const m0 = segment.interpolation === "hermite" ? out[axis] * duration : (segment.tension ?? 0.5) * (b - previous[axis]);
    const m1 = segment.interpolation === "hermite" ? incoming[axis] * duration : (segment.tension ?? 0.5) * (following[axis] - a);
    return (2 * t ** 3 - 3 * t * t + 1) * a + (t ** 3 - 2 * t * t + t) * m0
      + (-2 * t ** 3 + 3 * t * t) * b + (t ** 3 - t * t) * m1;
  });
}

export function blockbenchEasingIR(name: string | undefined, args?: readonly number[]): EasingIR | undefined {
  const normalized = (name ?? "linear").replaceAll("_", "").toLowerCase();
  if (normalized === "linear" || normalized === "none") return undefined;
  if (normalized === "step") return { kernel: "steps", direction: "in", count: Math.max(2, Math.floor(args?.[0] ?? 5)) };
  const match = /^ease(inout|in|out)(sine|quad|cubic|quart|quint|expo|circ|back|elastic|bounce)$/.exec(normalized);
  if (!match) throw new Error(`Unsupported easing ${name}.`);
  const direction = match[1] === "inout" ? "in_out" : match[1] as "in" | "out";
  const kind = match[2];
  const exponent = ({ quad: 2, cubic: 3, quart: 4, quint: 5 } as Record<string, number>)[kind];
  if (exponent) return { kernel: "power", direction, exponent };
  if (kind === "back") return { kernel: "back", direction, overshoot: (args?.[0] ?? 1) * 1.70158 };
  if (kind === "elastic") return { kernel: "blockbench_elastic", direction, frequency: args?.[0] ?? 1 };
  if (kind === "bounce") return { kernel: "blockbench_bounce", direction, bounciness: args?.[0] ?? 0.5 };
  return { kernel: kind as EasingIR["kernel"], direction };
}
