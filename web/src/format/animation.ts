import { orderedNodeIdsIR, type AnimationIR, type ClipIR, type EventIR, type EasingIR } from "../domain/animationIR";
import { isResourceLocation } from "./resourceLocation";
import { parseSnbtCompound } from "./snbt";
import { EMOTE_SCHEMA_VERSION } from "./emote";

export interface AnimationJson extends AnimationIR {
  type: "animation";
  schema_version: typeof EMOTE_SCHEMA_VERSION;
}

export function requireAnimation(value: unknown): AnimationJson {
  const object = (v: unknown, path: string): Record<string, any> => {
    if (!v || typeof v !== "object" || Array.isArray(v)) throw new Error(`${path} must be an object.`);
    return v as Record<string, any>;
  };
  const number = (v: unknown, path: string): number => {
    if (typeof v !== "number" || !Number.isFinite(v)) throw new Error(`${path} must be a finite number.`);
    return v;
  };
  const string = (v: unknown, path: string): string => {
    if (typeof v !== "string" || !v.trim()) throw new Error(`${path} must be a nonempty string.`);
    return v;
  };
  const scalar = (v: unknown, path: string): void => {
    if (typeof v === "number") number(v, path);
    else { const m = object(v, path); string(m.molang, `${path}.molang`); }
  };
  const vector = (v: unknown, size: number, path: string, literal = false): void => {
    if (!Array.isArray(v) || v.length !== size) throw new Error(`${path} must contain ${size} values.`);
    v.forEach((s, i) => literal ? number(s, `${path}[${i}]`) : scalar(s, `${path}[${i}]`));
  };
  const visibility = (v: unknown, path: string) => { if (typeof v !== "boolean") scalar(v, path); };
  const root = object(value, "animation");
  if (root.type !== "animation" || root.schema_version !== EMOTE_SCHEMA_VERSION) throw new Error(`Animation requires type animation and schema_version ${EMOTE_SCHEMA_VERSION}.`);
  if (!isResourceLocation(string(root.id, "id"))) throw new Error("id must be a resource location.");
  const metadata = object(root.metadata, "metadata"); string(metadata.name, "metadata.name");
  if (typeof metadata.description !== "string") throw new Error("metadata.description must be a string.");
  const nodes = object(root.nodes, "nodes");
  if (!Object.keys(nodes).length) throw new Error("nodes must not be empty.");
  for (const [id, raw] of Object.entries(nodes)) {
    string(id, "node id"); const n = object(raw, `nodes.${id}`);
    if (n.parent !== undefined) string(n.parent, `${id}.parent`);
    if (n.visible !== undefined && typeof n.visible !== "boolean") throw new Error(`${id}.visible must be boolean.`);
    if (n.inherit) {
      if (n.inherit.rotation !== undefined && !["parent", "entity"].includes(n.inherit.rotation)) throw new Error(`${id}.inherit.rotation is invalid.`);
      for (const field of ["scale", "visibility"]) if (n.inherit[field] !== undefined && typeof n.inherit[field] !== "boolean") throw new Error(`${id}.inherit.${field} must be boolean.`);
    }
    const operations = new Set<string>();
    for (const op of n.transform ?? []) {
      string(op.id, `${id}.operation.id`); if (operations.has(op.id)) throw new Error(`Duplicate operation ${id}/${op.id}.`); operations.add(op.id);
      if (!["translate", "scale", "rotate_euler", "rotate_quaternion", "matrix"].includes(op.op)) throw new Error(`Unknown operation ${op.op}.`);
      vector(op.value, op.op === "matrix" ? 16 : op.op === "rotate_quaternion" ? 4 : 3, `${id}/${op.id}.value`, true);
      if (op.op === "rotate_euler" && !["XYZ", "XZY", "YXZ", "YZX", "ZXY", "ZYX"].includes(op.order)) throw new Error(`Invalid rotation order at ${id}/${op.id}.`);
      if (op.op === "rotate_quaternion" && !op.value.some((x: number) => x !== 0)) throw new Error("Quaternion must not be zero.");
    }
    for (const [aid, rawAttachment] of Object.entries(n.attachments ?? {})) {
      const a = object(rawAttachment, `${id}/${aid}`);
      if (a.visible !== undefined && typeof a.visible !== "boolean") throw new Error("Attachment visibility must be boolean.");
      if (a.entity_nbt !== undefined) parseSnbtCompound(a.entity_nbt);
      if (a.type === "item_display") { parseSnbtCompound(string(a.item_stack_snbt, "item_stack_snbt")); string(a.item_display, "item_display"); }
      else if (a.type === "block_display") parseSnbtCompound(string(a.block_state_snbt, "block_state_snbt"));
      else if (a.type === "text_display") { if (a.text === undefined) throw new Error("text is required."); }
      else if (a.type === "external") { if (!isResourceLocation(string(a.key, "external.key"))) throw new Error("Invalid external key."); }
      else if (a.type === "player_skin") {
        if (!["head", "body", "left_arm", "right_arm", "left_leg", "right_leg"].includes(a.part)) throw new Error("Invalid skin part.");
        number(a.region?.from, "region.from"); number(a.region?.to, "region.to");
        if (!(0 <= a.region.from && a.region.from < a.region.to && a.region.to <= 1)) throw new Error("Invalid skin region.");
      } else throw new Error(`Unknown attachment type ${a.type}.`);
    }
  }
  orderedNodeIdsIR(nodes);
  const clip = object(root.animation, "animation"); const duration = number(clip.duration, "duration");
  if (duration <= 0 || duration > 600) throw new Error("duration must be within (0,600] seconds.");
  if (clip.clock && !["elapsed", "molang"].includes(clip.clock.type)) throw new Error("Unknown clock.");
  if (clip.clock?.type === "molang") string(clip.clock.expression, "clock.expression");
  if (clip.playback) {
    const p = clip.playback;
    if (p.mode !== undefined && !["once", "hold", "loop", "server_sync"].includes(p.mode)) throw new Error("Invalid playback mode.");
    for (const field of ["start_delay", "loop_delay"]) if (p[field] !== undefined) { scalar(p[field], field); if (typeof p[field] === "number" && p[field] < 0) throw new Error("Delay must not be negative."); }
    if (p.loop_start !== undefined) { number(p.loop_start, "loop_start"); if (p.loop_start < 0 || p.loop_start >= duration || (p.mode !== "loop" && p.loop_start !== 0)) throw new Error("Invalid loop_start."); }
  }
  if (!Array.isArray(clip.tracks)) throw new Error("animation.tracks must be an array.");
  const targets = new Set<string>();
  for (const raw of clip.tracks) {
    const t = object(raw, "track"), target = object(t.target, "track.target"), n = nodes[target.node];
    if (!n) throw new Error(`Track references missing node ${target.node}.`);
    if (target.operation && target.attachment) throw new Error("Track cannot target operation and attachment together.");
    const operation = n.transform?.find((o: any) => o.id === target.operation);
    if (target.operation && !operation) throw new Error(`Missing operation ${target.operation}.`);
    if (target.attachment && !n.attachments?.[target.attachment]) throw new Error(`Missing attachment ${target.attachment}.`);
    const signature = JSON.stringify([target.node, target.operation, target.attachment, t.channel]);
    if (targets.has(signature)) throw new Error("Duplicate track target."); targets.add(signature);
    if (!["value", "visible", "nbt"].includes(t.channel)) throw new Error("Invalid channel.");
    if (t.channel === "value" && !operation) throw new Error("Value track requires an operation.");
    if (t.channel !== "value" && target.operation) throw new Error("State track cannot target operation.");
    const d = object(t.driver, "driver"); const size = operation?.op === "matrix" ? 16 : operation?.op === "rotate_quaternion" ? 4 : 3;
    const checkValue = (v: unknown, path: string): void => {
      if (t.channel === "value") vector(v, size, path, size !== 3);
      else if (t.channel === "visible") visibility(v, path);
      else {
        const patch = object(v, path);
        if (typeof patch.merge === "string") parseSnbtCompound(patch.merge); else string(object(patch.merge, "merge").molang, "merge.molang");
        if (patch.remove !== undefined && (!Array.isArray(patch.remove) || patch.remove.some((field: unknown) => typeof field !== "string"))) throw new Error("remove must be a string array.");
        if (!target.attachment || !["item_display", "block_display", "text_display"].includes(n.attachments[target.attachment].type)) throw new Error("NBT requires a display attachment.");
      }
    };
    if (d.type === "expression") { if (t.channel === "nbt" || operation?.op === "matrix") throw new Error("NBT and matrix require state or step curve drivers."); checkValue(d.value, "expression.value"); }
    else if (d.type === "curve" || d.type === "state") {
      if (!Array.isArray(d.keys) || !d.keys.length) throw new Error("Driver requires keys.");
      let previous = -1;
      for (const key of d.keys) {
        number(key.time, "key.time"); if (key.time < 0 || key.time <= previous || key.time > duration) throw new Error(`Key times must be increasing and inside duration: ${target.node}/${target.operation ?? target.attachment ?? t.channel}, ${previous} -> ${key.time}, duration ${duration}.`); previous = key.time;
        if (d.type === "curve") {
          if (t.channel !== "value") throw new Error("Curve requires value channel.");
          if (key.value !== undefined) { if (key.pre !== undefined || key.post !== undefined) throw new Error("value and pre/post are exclusive."); checkValue(key.value, "key.value"); }
          else { checkValue(key.pre, "key.pre"); checkValue(key.post, "key.post"); }
        } else { if (t.channel === "value") throw new Error("Value requires curve or expression."); checkValue(key.value, "key.value"); }
      }
      if (d.type === "curve") {
        if (!Array.isArray(d.segments) || d.segments.length !== d.keys.length - 1) throw new Error("Segment count must equal keys.length-1.");
        if (d.before !== undefined && !["base", "first_pre"].includes(d.before)) throw new Error("Invalid before policy.");
        for (const s of d.segments) {
          if (!["step", "linear", "slerp", "catmull_rom", "hermite", "bezier"].includes(s.interpolation)) throw new Error("Invalid interpolation.");
          if (size === 16 && s.interpolation !== "step" || size === 4 && !["step", "slerp"].includes(s.interpolation) || size === 3 && s.interpolation === "slerp") throw new Error("Interpolation does not match operation.");
          if (s.easing) {
            const e = s.easing as EasingIR;
            if (s.interpolation === "step") throw new Error("Step does not accept easing.");
            if (!["linear", "power", "sine", "expo", "circ", "back", "blockbench_elastic", "blockbench_bounce", "steps"].includes(e.kernel)) throw new Error("Invalid easing kernel.");
            if (e.kernel !== "linear" && !["in", "out", "in_out"].includes(e.direction!)) throw new Error("Easing direction required.");
            if (e.kernel === "power" && number(e.exponent, "exponent") <= 0) throw new Error("Exponent must be positive.");
            if (e.kernel === "steps" && (!Number.isInteger(e.count) || e.count! < 2)) throw new Error("Step count must be integer >=2.");
            for (const f of ["overshoot", "frequency", "bounciness"] as const) if (e[f] !== undefined) number(e[f], f);
            if (e.kernel === "blockbench_bounce" && e.bounciness !== undefined && !(e.bounciness > 0 && e.bounciness < 1)) throw new Error("Invalid bounciness.");
            if (s.interpolation === "bezier" && ["back", "blockbench_elastic"].includes(e.kernel)) throw new Error("Overshoot easing is not supported for Bezier.");
          }
          if (s.tension !== undefined) number(s.tension, "tension");
          for (const f of ["previous", "following", "out_tangent", "in_tangent"]) if (s[f] !== undefined) vector(s[f], size, f);
          if (s.interpolation === "hermite" && (!s.out_tangent || !s.in_tangent)) throw new Error("Hermite tangents required.");
          if (s.interpolation === "bezier") {
            if (!Array.isArray(s.handles) || s.handles.length !== size) throw new Error("Bezier requires component handles.");
            for (const h of s.handles) { number(h.out?.time, "handle.out.time"); number(h.in?.time, "handle.in.time"); if (!(0 <= h.out.time && h.out.time <= h.in.time && h.in.time <= 1)) throw new Error("Bezier time handles must be monotonic."); scalar(h.out.value, "handle.out.value"); scalar(h.in.value, "handle.in.value"); }
          }
        }
      }
    } else throw new Error("Invalid driver type.");
  }
  const parsedEvents = requireAnimationEvents(clip.events, duration);
  for (const events of Object.values(parsedEvents)) {
    for (const e of events) {
      if (e.origin.type === "node" && !nodes[e.origin.node]) throw new Error("Missing event origin node.");
      if (e.source.type === "node" && !nodes[e.source.node]?.attachments?.[e.source.attachment]) throw new Error("Missing event source attachment.");
    }
  }
  if (clip.playback?.mode === "server_sync" && (clip.clock?.type === "molang" || clip.programs?.update || typeof clip.playback.start_delay === "object" || typeof clip.playback.loop_delay === "object"
    || clip.tracks.some((t: any) => t.channel === "nbt" && t.driver.keys?.some((k: any) => typeof k.value.merge === "object")))) throw new Error("server_sync requires a reconstructible clock and state.");
  return root as AnimationJson;
}

export function serializeAnimation(animation: AnimationJson): string {
  return `${JSON.stringify(animation, null, 2)}\n`;
}

export function requireAnimationEvents(value: unknown, duration: number): Required<NonNullable<ClipIR["events"]>> {
  if (value === undefined) return { start: [], timeline: [], loop: [], stop: [] };
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("events must be an object.");
  const result: Required<NonNullable<ClipIR["events"]>> = { start: [], timeline: [], loop: [], stop: [] };
  for (const [phase, raw] of Object.entries(value)) {
    if (raw === undefined) continue;
    if (!["start", "timeline", "loop", "stop"].includes(phase) || !Array.isArray(raw)) throw new Error("Invalid event phase.");
    for (const event of raw) {
      if (!event || typeof event !== "object" || Array.isArray(event)) throw new Error("Event must be an object.");
      const e = event as EventIR;
      if (phase === "timeline") {
        if (typeof e.time !== "number" || !Number.isFinite(e.time) || e.time < 0 || e.time > duration) throw new Error("Event time must be within the animation duration in seconds.");
        if (e.direction !== undefined && !["forward", "backward", "both"].includes(e.direction)) throw new Error("Invalid event direction.");
      } else if (e.time !== undefined || e.direction !== undefined) throw new Error("Only timeline events accept time and direction.");
      if (!["server", "player", "node"].includes(e.source?.type) || !["root", "node"].includes(e.origin?.type)) throw new Error("Invalid event source/origin.");
      const id = (v: unknown) => typeof v === "string" && v.trim().length > 0;
      if (e.source.type === "node" && (!id(e.source.node) || !id(e.source.attachment))) throw new Error("Node source requires node and attachment.");
      if (e.origin.type === "node" && !id(e.origin.node)) throw new Error("Node origin requires node.");
      if (e.origin.offset !== undefined && (!Array.isArray(e.origin.offset) || e.origin.offset.length !== 3 || e.origin.offset.some((v) => typeof v !== "number" || !Number.isFinite(v)))) throw new Error("origin.offset must contain three finite numbers.");
      if (e.action?.type === "commands") {
        if (!Array.isArray(e.action.commands) || e.action.commands.some((c) => typeof c !== "string")) throw new Error("commands must be strings.");
      } else if (e.action?.type === "external") {
        if (!isResourceLocation(e.action.key) || e.action.data === undefined) throw new Error("External action requires a resource key and data.");
      } else throw new Error("Invalid event action.");
    }
    (result as Record<string, EventIR[]>)[phase] = structuredClone(raw);
  }
  return result;
}
