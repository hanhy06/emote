import { Euler, Quaternion } from "three";
import type { AnimationIR, AttachmentIR, CurveIR, EasingIR, EventIR, NodeIR, VectorValueIR } from "../../domain/animationIR";
import { scalarIR } from "../../domain/animationIRConversion";
import type { ImportedNodeHint, ImportedSkinPart } from "../../domain/conversionSeed";
import type { ConversionIssue } from "../../foundation/diagnostics";
import { parseMinecraftTime } from "../../format/time";

interface VectorKey {
  time: string;
  value?: (number | string)[];
  pre?: (number | string)[];
  post?: (number | string)[];
  interpolation?: string;
  easing?: string;
}

interface LegacyNode {
  type: string;
  parent?: string;
  visible?: boolean;
  transform?: { position?: [number, number, number]; rotation?: [number, number, number]; scale?: [number, number, number] };
  entity_nbt?: string;
  item_stack_snbt?: string;
  item_display?: string;
  block_state_snbt?: string;
  text?: unknown;
  skin?: ImportedSkinPart;
}

interface LegacyTracks {
  position?: VectorKey[];
  rotation?: VectorKey[];
  scale?: VectorKey[];
  visible?: { time: string; value: boolean | string }[];
  nbt?: { time: string; value: string | { molang: string } }[];
}

interface LegacyEvent {
  time?: string;
  source: { type: "server" | "player" } | { type: "node"; node: string };
  origin: EventIR["origin"];
  commands?: string[];
}

interface LegacyAnimation extends Omit<AnimationIR, "nodes" | "animation"> {
  settings?: AnimationIR["settings"] & { playback?: AnimationIR["animation"]["playback"]; display_interpolation?: string };
  molang?: AnimationIR["animation"]["molang"];
  nodes: Record<string, LegacyNode>;
  timeline: { duration: string; tracks?: Record<string, LegacyTracks>; events?: Record<string, LegacyEvent[]> };
}

export function migrateAnimation(value: unknown, nodeHints: Record<string, ImportedNodeHint>, diagnostics: ConversionIssue[]): AnimationIR {
  const input = value as LegacyAnimation;
  const nodes: Record<string, NodeIR> = {};
  const tracks: AnimationIR["animation"]["tracks"] = [];
  const events: NonNullable<AnimationIR["animation"]["events"]> = {};
  const warn = (sourcePath: string, message: string) => diagnostics.push({ severity: "warning", code: "schema_4_migration", sourcePath, message });

  for (const [id, source] of Object.entries(input.nodes)) {
    const sourceTracks = input.timeline.tracks?.[id] ?? {};
    const numericRotation = (sourceTracks.rotation ?? []).every((key) => [key.value, key.pre, key.post].every((vector) => !vector || vector.every((v) => typeof v === "number")));
    const rotation = source.transform?.rotation ?? [0, 0, 0];
    const node: NodeIR = {
      ...(source.parent && input.nodes[source.parent] ? { parent: source.parent } : {}),
      transform: [
        { id: "position", op: "translate", value: source.transform?.position ?? [0, 0, 0] },
        numericRotation ? { id: "rotation", op: "rotate_quaternion", value: quaternion(rotation) }
          : { id: "rotation", op: "rotate_euler", order: "XYZ", value: rotation },
        { id: "scale", op: "scale", value: source.transform?.scale ?? [1, 1, 1] },
      ],
    };
    nodes[id] = node;
    if (source.parent && !input.nodes[source.parent]) warn(`nodes.${id}.parent`, "Missing parent was omitted.");
    let attachment: AttachmentIR | undefined;
    switch (source.type) {
      case "item_display": attachment = { type: "item_display", item_stack_snbt: source.item_stack_snbt!, item_display: source.item_display ?? "none" }; break;
      case "block_display": attachment = { type: "block_display", block_state_snbt: source.block_state_snbt! }; break;
      case "text_display": attachment = { type: "text_display", text: source.text }; break;
      case "anchor": break;
      default: warn(`nodes.${id}`, "Unsupported display type was imported as a logical node.");
    }
    if (attachment) {
      node.attachments = { display: { ...attachment, ...(source.visible === undefined ? {} : { visible: source.visible }),
        ...(source.entity_nbt ? { entity_nbt: source.entity_nbt } : {}) } };
      if (attachment.type === "item_display" && source.skin) {
        nodeHints[id] = { sourceNodeId: id, skinCandidates: { display: { groupId: id, suggestion: { ...source.skin } } } };
      }
    }
    for (const channel of ["position", "rotation", "scale"] as const) {
      const keys = sourceTracks[channel];
      if (!keys?.length) continue;
      const path = `timeline.tracks.${id}.${channel}`;
      const convert = (vector: (number | string)[]): VectorValueIR => channel === "rotation" && numericRotation
        ? quaternion(vector as number[]) : vector.map(scalarIR);
      const sorted = [...keys].sort((a, b) => parseMinecraftTime(a.time) - parseMinecraftTime(b.time));
      const driver: CurveIR = {
        type: "curve", before: "first_pre",
        keys: sorted.map((key) => key.pre || key.post
          ? { time: key.time, pre: convert(key.pre ?? key.value ?? key.post!), post: convert(key.post ?? key.value ?? key.pre!) }
          : { time: key.time, value: convert(key.value!) }),
        segments: sorted.slice(0, -1).map((key, index) => {
          if (key.interpolation === "step") return { interpolation: "step" };
          if (key.interpolation && key.interpolation !== "linear") warn(`${path}[${index}]`, "Unsupported interpolation was replaced with linear interpolation.");
          const easing = migrateEasing(key.easing);
          if (key.easing && !easing) warn(`${path}[${index}]`, "Unsupported easing was omitted.");
          return { interpolation: channel === "rotation" && numericRotation ? "slerp" : "linear", ...(easing ? { easing } : {}) };
        }),
      };
      if (parseMinecraftTime(sorted[0].time) > 0) {
        driver.keys.unshift({ time: "0t", value: convert(sorted[0].post ?? sorted[0].value ?? sorted[0].pre!) });
        driver.segments.unshift({ interpolation: "step" });
      }
      tracks.push({ target: { node: id, operation: channel }, channel: "value", driver });
      if (channel === "rotation" && !numericRotation) warn(path, "Molang rotation was imported as Euler rotation; interpolation may differ from schema 4.");
    }
    for (const channel of ["visible", "nbt"] as const) {
      const keys = sourceTracks[channel];
      if (!keys?.length) continue;
      if (!attachment) {
        warn(`timeline.tracks.${id}.${channel}`, "Track without a supported display was skipped.");
        continue;
      }
      if (channel === "visible") {
        const states = sourceTracks.visible!.map((key) => ({ time: key.time, value: typeof key.value === "string" ? { molang: key.value } : key.value }));
        states.sort((a, b) => parseMinecraftTime(a.time) - parseMinecraftTime(b.time));
        if (parseMinecraftTime(states[0].time) > 0) states.unshift({ ...states[0], time: "0t" });
        tracks.push({ target: { node: id, attachment: "display" }, channel, driver: { type: "state", keys: states } });
      } else {
        tracks.push({ target: { node: id, attachment: "display" }, channel, driver: { type: "state",
          keys: sourceTracks.nbt!.map((key) => ({ time: key.time, value: { merge: key.value } })) } });
      }
    }
    for (const channel of Object.keys(sourceTracks)) if (!["position", "rotation", "scale", "visible", "nbt"].includes(channel)) {
      warn(`timeline.tracks.${id}.${channel}`, "Unsupported track was skipped.");
    }
  }
  for (const id of Object.keys(input.timeline.tracks ?? {})) if (!nodes[id]) warn(`timeline.tracks.${id}`, "Tracks for a missing node were skipped.");
  for (const [phase, entries] of Object.entries(input.timeline.events ?? {})) {
    if (phase !== "start" && phase !== "timeline" && phase !== "loop" && phase !== "stop") {
      warn(`timeline.events.${phase}`, "Unsupported event phase was skipped.");
      continue;
    }
    const converted: EventIR[] = [];
    entries.forEach((event, index) => {
      if (!event.commands || event.source.type === "node" && !nodes[event.source.node]?.attachments?.display
        || event.origin.type === "node" && !nodes[event.origin.node] || phase === "timeline" && event.time === undefined) {
        warn(`timeline.events.${phase}[${index}]`, "Event without supported commands, source, origin or time was skipped.");
        return;
      }
      converted.push({ ...(event.time === undefined ? {} : { time: event.time }),
        source: event.source.type === "node" ? { ...event.source, attachment: "display" } : { ...event.source },
        origin: structuredClone(event.origin), action: { type: "commands", commands: [...event.commands] } });
    });
    if (phase === "timeline") events.timeline = converted as NonNullable<typeof events.timeline>;
    else events[phase] = converted;
  }
  const { playback, display_interpolation, ...settings } = input.settings ?? {};
  if (display_interpolation !== undefined) warn("settings.display_interpolation", "Display interpolation setting was omitted.");
  if (Object.values(nodeHints).some((hint) => hint.skinCandidates)) warn("nodes", "Skin parts and orders use the current equal-region assignment rules.");
  return {
    id: input.id, metadata: structuredClone(input.metadata), settings, nodes,
    ...(input.target_minecraft_version ? { target_minecraft_version: input.target_minecraft_version } : {}),
    ...(input.callbacks ? { callbacks: structuredClone(input.callbacks) } : {}),
    ...(input.skin ? { skin: structuredClone(input.skin) } : {}),
    ...(input.resources ? { resources: structuredClone(input.resources) } : {}),
    ...(input.source ? { source: structuredClone(input.source) } : {}),
    animation: { duration: input.timeline.duration, tracks, events,
      ...(playback ? { playback: structuredClone(playback) } : {}), ...(input.molang ? { molang: { ...input.molang } } : {}) },
  };
}

function quaternion(degrees: readonly number[]): [number, number, number, number] {
  return new Quaternion().setFromEuler(new Euler(degrees[0] * Math.PI / 180, degrees[1] * Math.PI / 180, degrees[2] * Math.PI / 180, "XYZ")).toArray();
}

function migrateEasing(name: string | undefined): EasingIR | undefined {
  if (!name || name === "linear") return { kernel: "linear" };
  const match = /^ease_(in_out|in|out)_(sine|quad|cubic|quart|quint|expo|circ|back|elastic|bounce)$/.exec(name);
  if (!match) return undefined;
  const direction = match[1] as "in" | "out" | "in_out";
  const exponent = { quad: 2, cubic: 3, quart: 4, quint: 5 }[match[2]];
  if (exponent) return { kernel: "power", exponent, direction };
  if (match[2] === "elastic") return { kernel: "blockbench_elastic", frequency: 1, direction };
  if (match[2] === "bounce") return { kernel: "blockbench_bounce", bounciness: 0.5, direction };
  return { kernel: match[2] as "sine" | "expo" | "circ" | "back", direction };
}
