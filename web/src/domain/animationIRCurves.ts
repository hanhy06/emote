import { parseMinecraftTime } from "../format/time";
import { Quaternion } from "three";
import type { CurveIR, EasingIR, ScalarIR, VectorValueIR } from "./animationIR";
import { affineScalarIR, scalarIR } from "./animationIRConversion";
import { mapMolangResult } from "../format/molang/sourceTransformer";

export type SharedValueResolverIR = (id: string, component: number) => number;
export type ScalarEvaluatorIR = (value: ScalarIR, progress: number, base?: number, resolveValue?: SharedValueResolverIR) => number;

export function evaluateEasing(easing: EasingIR | undefined, u: number, evaluate: ScalarEvaluatorIR = (value) => {
  if (typeof value !== "number") throw new Error("Dynamic easing requires a scalar evaluator.");
  return value;
}): number {
  if (!easing || easing.kernel === "linear") return u;
  const raw = easing.kernel === "power" ? easing.exponent : easing.kernel === "back" ? easing.overshoot ?? 1.70158
    : easing.kernel === "blockbench_elastic" ? easing.frequency ?? 1 : easing.kernel === "blockbench_bounce" ? easing.bounciness ?? 0.5
      : easing.kernel === "steps" || easing.kernel === "blockbench_steps" ? easing.count : 0;
  if (raw === undefined) throw new Error("Missing easing parameter.");
  const parameter = evaluate(raw, u);
  if (!Number.isFinite(parameter) || easing.kernel === "power" && parameter <= 0
    || ["steps", "blockbench_steps"].includes(easing.kernel) && (parameter < 2 || !Number.isInteger(parameter))) throw new Error("Invalid easing parameter.");
  const f = (x: number): number => {
    switch (easing.kernel) {
      case "power": return x ** parameter;
      case "sine": return 1 - Math.cos(Math.PI * x / 2);
      case "expo": return x === 0 ? 0 : 2 ** (10 * (x - 1));
      case "blockbench_expo": return 2 ** (10 * (x - 1));
      case "circ": return 1 - Math.sqrt(1 - x * x);
      case "back": return x * x * ((parameter + 1) * x - parameter);
      case "blockbench_elastic": return 1 - Math.cos(Math.PI * x / 2) ** 3 * Math.cos(Math.PI * parameter * x);
      case "blockbench_bounce": {
        const b = parameter;
        return Math.min(121 / 16 * x * x, 121 / 4 * b * (x - 6 / 11) ** 2 + 1 - b,
          121 * b * b * (x - 9 / 11) ** 2 + 1 - b * b, 484 * b ** 3 * (x - 10.5 / 11) ** 2 + 1 - b ** 3);
      }
      case "steps": return Math.floor(x * parameter) / parameter;
      case "blockbench_steps": {
        const step = 1 / parameter;
        if (x > (parameter - 1) * step) return (parameter - 1) * step;
        let low = 0, high = parameter - 1;
        while (high - low > 1) {
          const middle = low + Math.floor((high - low) / 2);
          if (x >= middle * step) low = middle; else high = middle;
        }
        return low * step;
      }
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
  if (time < parseMinecraftTime(first.time) / 20) return curve.before === "first_pre" ? vector(first.pre ?? first.value!, 0) : [...base];
  let index = 0;
  while (index + 1 < curve.keys.length && parseMinecraftTime(curve.keys[index + 1].time) / 20 <= time) index++;
  if (curve.at_key === "segment" && curve.keys.length > 1) index = Math.min(index, curve.keys.length - 2);
  const left = curve.keys[index];
  if (curve.at_key !== "segment" && time === parseMinecraftTime(left.time) / 20) return vector(curve.at_key === "pre" ? left.pre ?? left.value! : left.post ?? left.value!, 1);
  if (index === curve.keys.length - 1) return vector(left.post ?? left.value!, 1);
  const right = curve.keys[index + 1];
  const duration = (parseMinecraftTime(right.time) - parseMinecraftTime(left.time)) / 20;
  const u = Math.min(1, (time - parseMinecraftTime(left.time) / 20) / duration);
  const segment = curve.segments[index];
  const startValues = left.post ?? left.value!, endValues = right.pre ?? right.value!;
  if (segment.interpolation === "step" && curve.at_key !== "segment") return vector(startValues, u);
  const component = curve.evaluation === "component";
  const start = component ? undefined : vector(startValues, u);
  const end = component ? undefined : vector(endValues, u);
  if (curve.at_key === "segment" && u === 1) return end ?? vector(endValues, u);
  if (segment.interpolation === "step" || curve.at_key === "segment" && segment.interpolation === "bezier" && u === 0) return start ?? vector(startValues, u);
  const eased = component ? undefined : evaluateEasing(segment.easing, u, evaluate);
  if (segment.interpolation === "slerp") {
    if (component) throw new Error("Quaternion curves require vector evaluation.");
    return new Quaternion(...start as [number, number, number, number]).normalize()
      .slerp(new Quaternion(...end as [number, number, number, number]).normalize(), eased!).toArray();
  }
  const previous = !component && segment.interpolation === "catmull_rom" && segment.previous ? vector(segment.previous, u) : start;
  const following = !component && segment.interpolation === "catmull_rom" && segment.following ? vector(segment.following, u) : end;
  const out = !component && segment.interpolation === "hermite" && segment.out_tangent ? vector(segment.out_tangent, u) : undefined;
  const incoming = !component && segment.interpolation === "hermite" && segment.in_tangent ? vector(segment.in_tangent, u) : undefined;
  return startValues.map((_, axis) => {
    const t = component ? evaluateEasing(segment.easing, u, evaluate) : eased!;
    const p0 = component && segment.interpolation === "catmull_rom" && segment.previous ? evaluate(segment.previous[axis], u, base[axis]) : previous?.[axis];
    const a = component ? evaluate(startValues[axis], u, base[axis]) : start![axis];
    const b = component ? evaluate(endValues[axis], u, base[axis]) : end![axis];
    const p1 = component && segment.interpolation === "catmull_rom" && segment.following ? evaluate(segment.following[axis], u, base[axis]) : following?.[axis];
    if (segment.interpolation === "linear") return a + (b - a) * t;
    if (segment.interpolation === "bezier") {
      const handle = segment.handles![axis];
      let outTime: number, inTime: number, outValue: number, inValue: number;
      if (segment.control_order === "in_out") {
        inValue = evaluate(handle.in.value, u, handle.in.relative ? 0 : base[axis]);
        inTime = evaluate(handle.in.time, u);
        outValue = evaluate(handle.out.value, u, handle.out.relative ? 0 : base[axis]);
        outTime = evaluate(handle.out.time, u);
      } else {
        outTime = evaluate(handle.out.time, u);
        inTime = evaluate(handle.in.time, u);
        if (handle.out.clamp !== "rescale_value" && !(outTime >= 0 && outTime <= 1) || handle.in.clamp !== "rescale_value" && !(inTime >= 0 && inTime <= 1)) throw new Error("Bezier time handles must be within [0, 1].");
        outValue = evaluate(handle.out.value, u, handle.out.relative ? 0 : base[axis]);
        inValue = evaluate(handle.in.value, u, handle.in.relative ? 0 : base[axis]);
      }
      if (handle.out.clamp === "rescale_value") { const clamped = Math.max(0, Math.min(1, outTime)); outValue /= 1 + Math.abs(clamped - outTime); outTime = clamped; }
      if (handle.in.clamp === "rescale_value") { const clamped = Math.max(0, Math.min(1, inTime)); inValue /= 1 + Math.abs(clamped - inTime); inTime = clamped; }
      if (!(outTime >= 0 && outTime <= 1 && inTime >= 0 && inTime <= 1)) throw new Error("Bezier time handles must be within [0, 1].");
      const h1 = outValue + (handle.out.relative ? a : 0);
      const h2 = inValue + (handle.in.relative ? b : 0);
      let low = 0, high = 1;
      for (let i = 0; i < 48; i++) {
        const k = (low + high) / 2;
        const x = 3 * (1 - k) ** 2 * k * outTime + 3 * (1 - k) * k * k * inTime + k ** 3;
        if (x === t) { low = high = k; break; }
        if (x < t) low = k; else high = k;
      }
      const k = (low + high) / 2;
      return (1 - k) ** 3 * a + 3 * (1 - k) ** 2 * k * h1 + 3 * (1 - k) * k * k * h2 + k ** 3 * b;
    }
    const m0 = segment.interpolation === "hermite" ? (component ? evaluate(segment.out_tangent![axis], u, base[axis]) : out![axis]) * duration : (segment.tension ?? 0.5) * (b - (p0 ?? a));
    const m1 = segment.interpolation === "hermite" ? (component ? evaluate(segment.in_tangent![axis], u, base[axis]) : incoming![axis]) * duration : (segment.tension ?? 0.5) * ((p1 ?? b) - a);
    return (2 * t ** 3 - 3 * t * t + 1) * a + (t ** 3 - 2 * t * t + t) * m0
      + (-2 * t ** 3 + 3 * t * t) * b + (t ** 3 - t * t) * m1;
  });
}

export function blockbenchEasingIR(name: string | undefined, args?: readonly (number | string)[]): EasingIR | undefined {
  const normalized = (name ?? "linear").replaceAll("_", "").toLowerCase();
  if (normalized === "linear" || normalized === "none") return undefined;
  if (normalized === "step") {
    const raw = scalarIR(args?.[0] ?? 5);
    const count = typeof raw === "number" ? Math.max(2, Math.floor(raw)) : { molang: mapMolangResult(raw.molang, (result) => `math.max(2, math.floor(${result}))`) };
    return { kernel: "blockbench_steps", direction: "in", count };
  }
  const match = /^ease(inout|in|out)(sine|quad|cubic|quart|quint|expo|circ|back|elastic|bounce)$/.exec(normalized);
  if (!match) throw new Error(`Unsupported easing ${name}.`);
  const direction = match[1] === "inout" ? "in_out" : match[1] as "in" | "out";
  const kind = match[2];
  const exponent = ({ quad: 2, cubic: 3, quart: 4, quint: 5 } as Record<string, number>)[kind];
  if (exponent) return { kernel: "power", direction, exponent };
  if (kind === "back") return { kernel: "back", direction, overshoot: affineScalarIR(scalarIR(args?.[0] ?? 1), 1.70158) };
  if (kind === "elastic") return { kernel: "blockbench_elastic", direction, frequency: scalarIR(args?.[0] ?? 1) };
  if (kind === "bounce") return { kernel: "blockbench_bounce", direction, bounciness: scalarIR(args?.[0] ?? 0.5) };
  if (kind === "expo") return { kernel: "blockbench_expo", direction };
  return { kernel: kind as EasingIR["kernel"], direction };
}
