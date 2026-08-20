import type { EmoteAnimation, EmoteEvent, EmoteVectorKeyframe, LocalTransform, Vec3 } from "../../format/emoteAnimation";
import { requireEmoteAnimation } from "../../format/emoteAnimationRuntime";
import { localTransformToMatrix } from "../../format/localTransform";
import { parseMinecraftTime } from "../../format/minecraftTime";
import { isRecord } from "../../format/runtimeValue";
import { validateEmoteAnimation } from "../../format/validator";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { ConversionError } from "../../foundation/diagnostics";
import { parseInputJson } from "../inputCache";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../../domain/conversionSeed";
import { migrateSchema1Animation } from "./schema1Migration";
import { migrateSchema3Animation } from "./animationSchema3/animationSchema3Migration";
import { requireSchema3Animation } from "./animationSchema3/animationSchema3Runtime";
import { validateSchema3Animation } from "./animationSchema3/animationSchema3Validator";

export const emoteJsonAdapter: ImportAdapter<ImportedProject> = {
  id: "emote_json",
  label: "Emote animation JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    try {
      const value = parseInputJson(input);
      if (!isRecord(value) || !isRecord(value.nodes) || !isRecord(value.timeline)) {
        return { confidence: 0, reason: "does not match an emote animation schema" };
      }
      if (value.schema_version === 1) return { confidence: 100, reason: "matches emote animation schema 1" };
      return value.type === "animation" && (value.schema_version === 3 || value.schema_version === 4)
        ? { confidence: 100, reason: `matches emote animation schema ${value.schema_version}` }
        : { confidence: 0, reason: "does not match a supported emote animation schema" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const parsed = parseInputJson(input);
    const schema1 = isRecord(parsed) && parsed.schema_version === 1 ? migrateSchema1Animation(parsed) : null;
    const schema3 = schema1?.animation ?? (isRecord(parsed) && parsed.schema_version === 3 ? requireSchema3Animation(parsed) : null);
    if (schema3) requireValidSchema3Animation(schema3);
    const animation = schema3 ? migrateSchema3Animation(schema3) : requireEmoteAnimation(parsed);
    const issues = validateEmoteAnimation(animation);
    if (issues.length > 0) {
      throw new ConversionError("invalid_emote_animation", `Invalid emote animation at ${issues[0].path}: ${issues[0].message}`, issues[0].path);
    }
    const separator = animation.id.indexOf(":");
    const namespace = animation.id.slice(0, separator);
    const animationId = animation.id.slice(separator + 1);
    return {
      source: "emote_json",
      sourceName: input.name,
      suggestedMetadata: { ...animation.metadata },
      suggestedPlayer: { ...animation.settings.player, stop_conditions: { ...animation.settings.player.stop_conditions } },
      ...(schema1 ? { suggestedMinecraftVersion: schema1.minecraftVersion } : {}),
      suggestedNamespace: namespace,
      suggestedStandalone: animation.settings.standalone,
      suggestedCooldown: animation.settings.cooldown,
      nodes: importNodes(animation),
      animations: [importTimeline(animation, animationId)],
      diagnostics: [],
      resources: new Map(),
    };
  },
};

function requireValidSchema3Animation(animation: Parameters<typeof validateSchema3Animation>[0]): void {
  const issues = validateSchema3Animation(animation);
  if (issues.length > 0) {
    throw new ConversionError("invalid_emote_animation", `Invalid emote animation at ${issues[0].path}: ${issues[0].message}`, issues[0].path);
  }
}

function importNodes(animation: EmoteAnimation): Record<string, ImportedNode> {
  return Object.fromEntries(Object.entries(animation.nodes).map(([id, node]): [string, ImportedNode] => {
    if (node.parent) throw unsupportedSchema4(`${id}.parent`, "parented schema 4 nodes cannot be represented by the web editor");
    const defaultMatrix = localTransformToMatrix(node.transform, `${id}.transform`);
    if (node.type === "anchor") return [id, { id, type: "anchor", defaultMatrix, space: node.space }];
    const common = {
      id,
      defaultMatrix,
      visible: node.visible ?? true,
      space: node.space,
      ...(node.entity_nbt ? { entityNbt: node.entity_nbt } : {}),
    };
    if (node.type === "item_display") {
      if (node.item_source) {
        return [id, {
          id,
          type: "anchor",
          defaultMatrix,
          space: node.space,
          suggestedHeldItemArm: node.item_source.arm,
        }];
      }
      return [id, {
        ...common,
        type: "item_display",
        itemStackSnbt: node.item_stack_snbt!,
        itemDisplay: node.item_display,
        ...(node.skin ? { skin: { ...node.skin } } : {}),
      }];
    }
    if (node.type === "block_display") return [id, { ...common, type: "block_display", blockStateSnbt: node.block_state_snbt }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function importTimeline(animation: EmoteAnimation, id: string): ImportedAnimation {
  if (animation.molang?.initialize || animation.molang?.tick) {
    throw unsupportedSchema4("molang", "animation-level Molang cannot be represented by the web editor");
  }
  const tracks: ImportedAnimation["tracks"] = {};
  for (const [nodeId, source] of Object.entries(animation.timeline.tracks)) {
    const node = animation.nodes[nodeId];
    const track = { transforms: importTransformTrack(source, node.transform, `${id}/${nodeId}`), visibility: [] } as ImportedAnimation["tracks"][string];
    for (const frame of source.visible ?? []) {
      if (typeof frame.value !== "boolean") throw unsupportedSchema4(`${id}/${nodeId}/${frame.time}.visible`, "Molang visibility cannot be represented by the web editor");
      track.visibility.push({ tick: parseMinecraftTime(frame.time), visible: frame.value });
    }
    tracks[nodeId] = track;
  }
  const events = animation.timeline.events;
  return {
    id,
    name: animation.metadata.name,
    durationTicks: parseMinecraftTime(animation.timeline.duration, 1),
    loop: animation.settings.playback.mode,
    loopDelayTicks: parseMinecraftTime(animation.settings.playback.loop_delay),
    tracks,
    events: {
      start: copyEvents(events?.start),
      timeline: (events?.timeline ?? []).map(({ time, ...event }) => ({ ...copyEvent(event), tick: parseMinecraftTime(time) })),
      loop: copyEvents(events?.loop),
      stop: copyEvents(events?.stop),
    },
  };
}

function importTransformTrack(source: EmoteAnimation["timeline"]["tracks"][string], defaults: LocalTransform, path: string): ImportedAnimation["tracks"][string]["transforms"] {
  const channels = [source.position, source.rotation, source.scale].filter((channel): channel is EmoteVectorKeyframe[] => channel !== undefined);
  if (channels.length === 0) return [];
  const reference = channels[0];
  for (const channel of channels) {
    for (const frame of channel) {
      if (frame.easing || frame.pre || frame.post) {
        throw unsupportedSchema4(`${path}/${frame.time}`, "easing and pre/post values cannot be represented by the web editor");
      }
    }
  }
  for (const channel of channels.slice(1)) {
    if (channel.length !== reference.length || channel.some((frame, index) => frame.time !== reference[index].time || frame.interpolation !== reference[index].interpolation)) {
      throw unsupportedSchema4(path, "independently timed transform channels cannot be represented by the web editor");
    }
  }

  return reference.map((frame, index) => {
    const tick = parseMinecraftTime(frame.time);
    const transform: LocalTransform = {
      position: numericFrameValue(source.position?.[index], defaults.position, `${path}/${frame.time}.position`),
      rotation: numericFrameValue(source.rotation?.[index], defaults.rotation, `${path}/${frame.time}.rotation`),
      scale: numericFrameValue(source.scale?.[index], defaults.scale, `${path}/${frame.time}.scale`),
    };
    if (index === 0) return { tick, matrix: localTransformToMatrix(transform, `${path}/${frame.time}`), interpolation: { type: "step" as const } };
    const previous = reference[index - 1];
    const previousTick = parseMinecraftTime(previous.time);
    return {
      tick,
      matrix: localTransformToMatrix(transform, `${path}/${frame.time}`),
      interpolation: previous.interpolation === "step"
        ? { type: "step" as const }
        : { type: "linear" as const, durationTicks: tick - previousTick },
    };
  });
}

function numericFrameValue(frame: EmoteVectorKeyframe | undefined, fallback: Vec3, path: string): Vec3 {
  if (!frame) return fallback;
  if (!frame.value || frame.value.some((value) => typeof value !== "number")) {
    throw unsupportedSchema4(path, "Molang or pre/post-only transform values cannot be represented by the web editor");
  }
  return frame.value as Vec3;
}

function unsupportedSchema4(path: string, message: string): ConversionError {
  return new ConversionError("unsupported_schema_4_import", `Unsupported schema 4 animation at ${path}: ${message}.`, path);
}

function copyEvents(events: EmoteEvent[] | undefined): EmoteEvent[] {
  return (events ?? []).map(copyEvent);
}

function copyEvent(event: EmoteEvent): EmoteEvent {
  return {
    source: { ...event.source },
    origin: { ...event.origin, ...(event.origin.offset ? { offset: [...event.origin.offset] as [number, number, number] } : {}) },
    commands: [...event.commands],
  };
}
