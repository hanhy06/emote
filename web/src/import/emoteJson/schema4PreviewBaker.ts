import MolangParser from "molangjs/dist/molang.esm.js";
import { Euler, Matrix4, Quaternion, Vector3 } from "three";
import type {
  EmoteAnimation,
  EmoteEasing,
  EmoteNode,
  EmoteVectorKeyframe,
  LocalTransform,
  Matrix16,
  MolangScalar,
  Vec3,
} from "../../format/emoteAnimation";
import { matrix4ToRowMajor, multiplyMatrix16 } from "../../format/matrix";
import { parseMinecraftTime, TICKS_PER_SECOND } from "../../format/time";
import type { ImportedNodeTrack } from "../../domain/conversionSeed";
import { ConversionError } from "../../foundation/diagnostics";
import { PREVIEW_PLAYER_STATE_QUERIES } from "../runtimeMolangQueries";

const NONDETERMINISTIC_FUNCTION = /math\.(?:random|random_integer|die_roll|die_roll_integer)\b/i;
const QUERY_ASSIGNMENT = /\b(?:q|query)\s*\.[a-z_][a-z0-9_]*\s*=(?!=)/i;
const PERSISTENT_ASSIGNMENT = /\b(?:v|variable)\s*\.[a-z_][a-z0-9_]*\s*=(?!=)/i;

interface PreparedVectorFrame {
  tick: number;
  pre: readonly MolangScalar[];
  post: readonly MolangScalar[];
  prePath: string;
  postPath: string;
  interpolation: "step" | "linear";
  easing: EmoteEasing;
}

interface NodeState {
  id: string;
  node: EmoteNode;
  parent?: NodeState;
  position: PreparedVectorFrame[];
  rotation: PreparedVectorFrame[];
  scale: PreparedVectorFrame[];
  visible: { tick: number; value: boolean | string; path: string }[];
  positionCursor: number;
  rotationCursor: number;
  scaleCursor: number;
  visibilityCursor: number;
  worldMatrix: Matrix16;
  isVisible: boolean;
}

export function bakeSchema4Preview(animation: EmoteAnimation): Record<string, ImportedNodeTrack> {
  const durationTicks = parseMinecraftTime(animation.timeline.duration, 1);
  const session = new PreviewMolangSession(durationTicks);
  const states = prepareNodeStates(animation);
  const result = Object.fromEntries(states.map((state) => [state.id, {
    transforms: [],
    visibility: [],
    nbt: (animation.timeline.tracks[state.id]?.nbt ?? []).map((frame) => ({ tick: parseMinecraftTime(frame.time), value: frame.value })),
  }])) as Record<string, ImportedNodeTrack>;

  session.setTick(0, 0);
  if (animation.molang?.initialize) session.evaluate(animation.molang.initialize, "molang.initialize", true);

  for (let tick = 0; tick <= durationTicks; tick++) {
    session.setTick(tick, tick === 0 ? 0 : 1 / TICKS_PER_SECOND);
    if (animation.molang?.tick) session.evaluate(animation.molang.tick, "molang.tick", true);

    for (const state of states) {
      const position = evaluateVector(session, state.position, state.positionCursor, tick, state.node.transform.position);
      state.positionCursor = position.cursor;
      const scale = evaluateVector(session, state.scale, state.scaleCursor, tick, state.node.transform.scale);
      state.scaleCursor = scale.cursor;
      const rotation = evaluateRotation(session, state.rotation, state.rotationCursor, tick, state.node.transform.rotation);
      state.rotationCursor = rotation.cursor;

      const localMatrix = matrix4ToRowMajor(new Matrix4().compose(
        new Vector3(...position.value),
        rotation.value,
        new Vector3(...scale.value),
      ), `${state.id}/${tick}t local preview transform`);
      state.worldMatrix = state.parent
        ? multiplyMatrix16(state.parent.worldMatrix, localMatrix, `${state.id}/${tick}t world preview transform`)
        : localMatrix;
      result[state.id].transforms.push({ tick, matrix: state.worldMatrix, interpolation: { type: "step" } });

      if (state.node.type === "anchor") continue;
      const visibility = evaluateVisibility(session, state, tick);
      if (tick === 0 || visibility !== state.isVisible) {
        result[state.id].visibility.push({ tick, visible: visibility });
      }
      state.isVisible = visibility;
    }
  }
  return result;
}

class PreviewMolangSession {
  private readonly parser = new MolangParser();
  private readonly queries: Record<string, number> = { ...PREVIEW_PLAYER_STATE_QUERIES };

  constructor(private readonly durationTicks: number) {
    this.parser.variableHandler = (key) => {
      if (key.startsWith("variable.") || key.startsWith("temp.")) return 0;
      throw new Error(`references unsupported Molang value ${key}`);
    };
  }

  setTick(tick: number, deltaTime: number): void {
    this.setQuery("anim_time", tick / TICKS_PER_SECOND);
    this.setQuery("anim_time_ticks", tick);
    this.setQuery("anim_length", this.durationTicks / TICKS_PER_SECOND);
    this.setQuery("delta_time", deltaTime);
    this.setQuery("loop_count", 0);
    this.setQuery("key_frame_lerp_time", 0);
    this.setQuery("life_time", tick / TICKS_PER_SECOND);
  }

  setKeyframeProgress(progress: number): void {
    this.setQuery("key_frame_lerp_time", progress);
  }

  evaluate(source: string | number, path: string, allowPersistentAssignment = false): number {
    if (typeof source === "number") return requireFinite(source, path);
    if (NONDETERMINISTIC_FUNCTION.test(source)) throw previewError(path, "uses nondeterministic Molang");
    if (QUERY_ASSIGNMENT.test(source)) throw previewError(path, "assigns a query");
    if (!allowPersistentAssignment && PERSISTENT_ASSIGNMENT.test(source)) {
      throw previewError(path, "assigns a persistent variable from a track value");
    }
    for (const key of Object.keys(this.parser.variables)) {
      if (key.startsWith("temp.")) delete this.parser.variables[key];
    }
    try {
      return requireFinite(this.parser.parse(source, this.queries), path);
    } catch (reason) {
      if (reason instanceof ConversionError) throw reason;
      throw new ConversionError("schema_4_preview_molang_unavailable", `${path} cannot be evaluated for preview.`, path, { cause: reason });
    }
  }

  private setQuery(name: string, value: number): void {
    this.queries[`q.${name}`] = value;
    this.queries[`query.${name}`] = value;
  }
}

function prepareNodeStates(animation: EmoteAnimation): NodeState[] {
  const result: NodeState[] = [];
  const states = new Map<string, NodeState>();
  const visit = (id: string): NodeState => {
    const existing = states.get(id);
    if (existing) return existing;
    const node = animation.nodes[id];
    const tracks = animation.timeline.tracks[id];
    const state: NodeState = {
      id,
      node,
      ...(node.parent ? { parent: visit(node.parent) } : {}),
      position: prepareVectorFrames(tracks?.position, `timeline.tracks.${id}.position`),
      rotation: prepareVectorFrames(tracks?.rotation, `timeline.tracks.${id}.rotation`),
      scale: prepareVectorFrames(tracks?.scale, `timeline.tracks.${id}.scale`),
      visible: (tracks?.visible ?? []).map((frame, index) => ({
        tick: parseMinecraftTime(frame.time),
        value: frame.value,
        path: `timeline.tracks.${id}.visible[${index}].value`,
      })),
      positionCursor: 0,
      rotationCursor: 0,
      scaleCursor: 0,
      visibilityCursor: 0,
      worldMatrix: [1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1],
      isVisible: node.type === "anchor" ? true : node.visible ?? true,
    };
    states.set(id, state);
    result.push(state);
    return state;
  };
  for (const id of Object.keys(animation.nodes)) visit(id);
  return result;
}

function prepareVectorFrames(frames: EmoteVectorKeyframe[] | undefined, path: string): PreparedVectorFrame[] {
  return (frames ?? []).map((frame, index) => {
    const valuePath = `${path}[${index}].value`;
    return {
      tick: parseMinecraftTime(frame.time),
      pre: frame.pre ?? frame.value!,
      post: frame.post ?? frame.value!,
      prePath: frame.pre ? `${path}[${index}].pre` : valuePath,
      postPath: frame.post ? `${path}[${index}].post` : valuePath,
      interpolation: frame.interpolation ?? "linear",
      easing: frame.easing ?? "linear",
    };
  });
}

function evaluateVector(
  session: PreviewMolangSession,
  frames: PreparedVectorFrame[],
  cursor: number,
  tick: number,
  fallback: Vec3,
): { cursor: number; value: Vec3 } {
  if (frames.length === 0) return { cursor: 0, value: fallback };
  cursor = advanceCursor(frames, cursor, tick);
  const current = frames[cursor];
  const next = frames[cursor + 1];
  const progress = keyframeProgress(current, next, tick);
  session.setKeyframeProgress(progress);
  const start = evaluateScalars(session, current.post, current.postPath);
  if (!next || current.interpolation === "step") return { cursor, value: start };
  const end = evaluateScalars(session, next.pre, next.prePath);
  const eased = easingProgress(current.easing, progress);
  return { cursor, value: start.map((value, axis) => value + (end[axis] - value) * eased) as unknown as Vec3 };
}

function evaluateRotation(
  session: PreviewMolangSession,
  frames: PreparedVectorFrame[],
  cursor: number,
  tick: number,
  fallback: Vec3,
): { cursor: number; value: Quaternion } {
  if (frames.length === 0) return { cursor: 0, value: quaternion(fallback) };
  cursor = advanceCursor(frames, cursor, tick);
  const current = frames[cursor];
  const next = frames[cursor + 1];
  const progress = keyframeProgress(current, next, tick);
  session.setKeyframeProgress(progress);
  const start = quaternion(evaluateScalars(session, current.post, current.postPath));
  if (!next || current.interpolation === "step") return { cursor, value: start };
  const end = quaternion(evaluateScalars(session, next.pre, next.prePath));
  return { cursor, value: start.slerp(end, easingProgress(current.easing, progress)) };
}

function evaluateVisibility(session: PreviewMolangSession, state: NodeState, tick: number): boolean {
  if (state.visible.length === 0) return state.node.type === "anchor" ? true : state.node.visible ?? true;
  state.visibilityCursor = advanceCursor(state.visible, state.visibilityCursor, tick);
  session.setKeyframeProgress(0);
  const value = state.visible[state.visibilityCursor].value;
  return typeof value === "boolean" ? value : session.evaluate(value, state.visible[state.visibilityCursor].path) !== 0;
}

function evaluateScalars(session: PreviewMolangSession, values: readonly MolangScalar[], path: string): Vec3 {
  return values.map((value, axis) => session.evaluate(value, `${path}[${axis}]`)) as unknown as Vec3;
}

function advanceCursor(frames: readonly { tick: number }[], cursor: number, tick: number): number {
  while (cursor + 1 < frames.length && frames[cursor + 1].tick <= tick) cursor++;
  return cursor;
}

function keyframeProgress(current: PreparedVectorFrame, next: PreparedVectorFrame | undefined, tick: number): number {
  if (!next) return 0;
  return Math.min(1, Math.max(0, (tick - current.tick) / (next.tick - current.tick)));
}

function quaternion(degrees: readonly number[]): Quaternion {
  return new Quaternion().setFromEuler(new Euler(
    degrees[0] * Math.PI / 180,
    degrees[1] * Math.PI / 180,
    degrees[2] * Math.PI / 180,
    "XYZ",
  ));
}

function easingProgress(name: EmoteEasing, value: number): number {
  const kind = name.replace(/^ease_(?:in_out|in|out)_/, "");
  const base = easeIn(kind, name === "linear" ? value : name.startsWith("ease_in_out_") ? value < 0.5 ? value * 2 : (1 - value) * 2 : name.startsWith("ease_out_") ? 1 - value : value);
  if (name === "linear" || name.startsWith("ease_in_")) return base;
  if (name.startsWith("ease_out_")) return 1 - base;
  return value < 0.5 ? base / 2 : 1 - base / 2;
}

function easeIn(kind: string, value: number): number {
  switch (kind) {
    case "sine": return 1 - Math.cos(Math.PI * value / 2);
    case "quad": return value * value;
    case "cubic": return value * value * value;
    case "quart": return Math.pow(value, 4);
    case "quint": return Math.pow(value, 5);
    case "expo": return value === 0 ? 0 : Math.pow(2, 10 * (value - 1));
    case "circ": return 1 - Math.sqrt(1 - value * value);
    case "back": return value * value * ((1.70158 + 1) * value - 1.70158);
    case "elastic": return 1 - Math.pow(Math.cos(Math.PI * value / 2), 3) * Math.cos(Math.PI * value);
    case "bounce": return Math.min(
      121 / 16 * value * value,
      121 / 8 * Math.pow(value - 6 / 11, 2) + 0.5,
      121 / 4 * Math.pow(value - 9 / 11, 2) + 0.75,
      121 / 2 * Math.pow(value - 10.5 / 11, 2) + 0.875,
    );
    default: return value;
  }
}

function requireFinite(value: number, path: string): number {
  if (Number.isFinite(value)) return value;
  throw previewError(path, "evaluates to a non-finite value");
}

function previewError(path: string, message: string): ConversionError {
  return new ConversionError("schema_4_preview_molang_unavailable", `${path} ${message}.`, path);
}
