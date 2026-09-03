import { readBlockState, readDisplayNbt, readItemStack } from "../../format/minecraftData";
import { readRuntimeNodes, readRuntimeTimeline } from "../runtimeOutput";
import type { EmoteAnimation, EmoteEvent, EmoteVectorKeyframe, LocalTransform, Matrix16, Vec3 } from "../../format/emoteAnimation";
import { requireEmoteAnimation } from "../../format/emoteAnimationRuntime";
import { localTransformToMatrix } from "../../format/localTransform";
import { multiplyMatrix16 } from "../../format/matrix";
import { parseMinecraftTime } from "../../format/time";
import { isRecord } from "../../format/runtimeValue";
import { validateEmoteAnimation } from "../../format/validator";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { ConversionError } from "../../foundation/diagnostics";
import { parseInputJson, probeParsedInput } from "../inputCache";
import type { ImportedAnimation, ImportedNode, ImportedNodeBase, ImportedProject } from "../../domain/conversionSeed";
import { migrateSchema1Animation } from "./schema1Migration";
import { migrateSchema3Animation } from "./animationSchema3/animationSchema3Migration";
import { requireSchema3Animation } from "./animationSchema3/animationSchema3Runtime";
import { validateSchema3Animation } from "./animationSchema3/animationSchema3Validator";
import { bakeSchema4Preview } from "./schema4PreviewBaker";

export const emoteJsonAdapter: ImportAdapter<ImportedProject> = {
  id: "emote_json",
  label: "Emote animation JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    return probeParsedInput(input, parseInputJson, (value) => {
      if (!isRecord(value) || !isRecord(value.nodes) || !isRecord(value.timeline)) {
        return { confidence: 0, reason: "does not match an emote animation schema" };
      }
      if (value.schema_version === 1) return { confidence: 100, reason: "matches emote animation schema 1" };
      return value.type === "animation" && (value.schema_version === 3 || value.schema_version === 4)
        ? { confidence: 100, reason: `matches emote animation schema ${value.schema_version}` }
        : { confidence: 0, reason: "does not match a supported emote animation schema" };
    }, "not JSON");
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
    let nodes: Record<string, ImportedNode>;
    let importedAnimation: ImportedAnimation;
    const diagnostics: ImportedProject["diagnostics"] = [];
    try {
      nodes = importNodes(animation);
      importedAnimation = importTimeline(animation, animationId);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_schema_4_import" || schema3) throw reason;
      nodes = importRuntimeNodes(animation);
      try {
        importedAnimation = importRuntimeTimeline(animation, animationId, bakeSchema4Preview(animation));
      } catch (previewReason) {
        const message = "Advanced schema 4 data is preserved for export; preview uses the Create pose because its runtime values cannot be evaluated safely.";
        importedAnimation = importRuntimeTimeline(animation, animationId, undefined, message);
        diagnostics.push({
          severity: "warning",
          code: "schema_4_preview_limited",
          message,
          sourcePath: previewReason instanceof ConversionError ? previewReason.sourcePath : reason.sourcePath,
        });
      }
    }
    return {
      source: "emote_json",
      sourceName: input.name,
      suggestedMetadata: { ...animation.metadata },
      suggestedPlayer: { ...animation.settings.player, stop_conditions: { ...animation.settings.player.stop_conditions } },
      ...(schema1 ? { suggestedMinecraftVersion: schema1.minecraftVersion } : {}),
      suggestedNamespace: namespace,
      suggestedStandalone: animation.settings.standalone,
      suggestedCooldown: animation.settings.cooldown,
      suggestedRotationDeadzone: animation.settings.rotation_deadzone,
      nodes,
      animations: [importedAnimation],
      diagnostics,
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

function importRuntimeNodes(animation: EmoteAnimation): Record<string, ImportedNode> {
  const worldMatrices = new Map<string, Matrix16>();
  const rootIds = new Map<string, string>();
  const worldMatrix = (id: string): Matrix16 => {
    const existing = worldMatrices.get(id);
    if (existing) return existing;
    const node = animation.nodes[id];
    const local = localTransformToMatrix(node.transform, `${id}.transform`);
    const world = node.parent
      ? multiplyMatrix16(worldMatrix(node.parent), local, `${id}.world_transform`)
      : local;
    worldMatrices.set(id, world);
    return world;
  };
  const rootId = (id: string): string => {
    const existing = rootIds.get(id);
    if (existing) return existing;
    const parent = animation.nodes[id].parent;
    const root = parent ? rootId(parent) : id;
    rootIds.set(id, root);
    return root;
  };

  return Object.fromEntries(Object.entries(animation.nodes).map(([id, node]) => {
    const root = rootId(id);
    const space = animation.nodes[root].space!;
    const defaultMatrix = worldMatrix(id);
    return [id, importNode(id, node, { defaultMatrix, space, spaceAssignmentGroup: root })];
  }));
}

function importNodes(animation: EmoteAnimation): Record<string, ImportedNode> {
  return Object.fromEntries(Object.entries(animation.nodes).map(([id, node]) => {
    if (node.parent) throw unsupportedSchema4(`${id}.parent`, "parented schema 4 nodes cannot be represented by the web editor");
    const defaultMatrix = localTransformToMatrix(node.transform, `${id}.transform`);
    return [id, importNode(id, node, { defaultMatrix, space: node.space })];
  }));
}

function importNode(
  id: string,
  node: EmoteAnimation["nodes"][string],
  placement: Pick<ImportedNodeBase, "defaultMatrix" | "space" | "spaceAssignmentGroup">,
): ImportedNode {
  if (node.type === "anchor" || (node.type === "item_display" && node.item_source)) return { id, type: "anchor", ...placement };
  const common = {
    id,
    ...placement,
    visible: node.visible ?? true,
    ...(node.entity_nbt ? { entityNbt: node.entity_nbt } : {}),
  };
  if (node.type === "item_display") return {
    ...common,
    type: "item_display",
    itemStack: readItemStack(node.item_stack_snbt!),
    itemDisplay: node.item_display,
    ...(node.skin ? { skin: { ...node.skin } } : {}),
  };
  if (node.type === "block_display") return { ...common, type: "block_display", blockState: readBlockState(node.block_state_snbt) };
  return { ...common, type: "text_display", text: node.text };
}

function importTimeline(animation: EmoteAnimation, id: string): ImportedAnimation {
  if (animation.molang?.initialize || animation.molang?.tick) {
    throw unsupportedSchema4("molang", "animation-level Molang cannot be represented by the web editor");
  }
  const tracks: ImportedAnimation["tracks"] = {};
  for (const [nodeId, source] of Object.entries(animation.timeline.tracks)) {
    const node = animation.nodes[nodeId];
    if (source.nbt?.some((frame) => typeof frame.value !== "string")) {
      throw unsupportedSchema4(`${id}/${nodeId}.nbt`, "Molang-selected NBT cannot be represented by the web editor");
    }
    const track = {
      transforms: importTransformTrack(source, node.transform, `${id}/${nodeId}`),
      visibility: [],
      nbt: (source.nbt ?? []).map((frame) => ({ tick: parseMinecraftTime(frame.time), value: readDisplayNbt(frame.value as string) })),
    } as ImportedAnimation["tracks"][string];
    for (const frame of source.visible ?? []) {
      if (typeof frame.value !== "boolean") throw unsupportedSchema4(`${id}/${nodeId}/${frame.time}.visible`, "Molang visibility cannot be represented by the web editor");
      track.visibility.push({ tick: parseMinecraftTime(frame.time), visible: frame.value });
    }
    tracks[nodeId] = track;
  }
  return {
    id,
    name: animation.metadata.name,
    suggestedMetadata: { ...animation.metadata },
    durationTicks: parseMinecraftTime(animation.timeline.duration, 1),
    loop: animation.settings.playback.mode,
    loopDelayTicks: parseMinecraftTime(animation.settings.playback.loop_delay),
    tracks,
    events: importEvents(animation),
  };
}

function importRuntimeTimeline(
  animation: EmoteAnimation,
  id: string,
  previewTracks?: Record<string, ImportedAnimation["tracks"][string]>,
  reason?: string,
): ImportedAnimation {
  const durationTicks = parseMinecraftTime(animation.timeline.duration, 1);
  return {
    id,
    name: animation.metadata.name,
    suggestedMetadata: { ...animation.metadata },
    durationTicks,
    loop: animation.settings.playback.mode,
    loopDelayTicks: parseMinecraftTime(animation.settings.playback.loop_delay),
    tracks: {},
    events: importEvents(animation),
    ...(previewTracks
      ? { preview: { durationTicks, tracks: previewTracks } }
      : {
          availability: { preview: "create_pose", exportable: true, reason },
          preview: { durationTicks, tracks: {} },
        }),
    runtime: {
      ...(animation.molang ? { molang: animation.molang } : {}),
      nodes: readRuntimeNodes(animation.nodes),
      timeline: readRuntimeTimeline(animation.timeline),
    },
  };
}

function importEvents(animation: EmoteAnimation): ImportedAnimation["events"] {
  const events = animation.timeline.events;
  return {
    start: copyEvents(events?.start),
    timeline: (events?.timeline ?? []).map(({ time, ...event }) => ({ ...copyEvent(event), tick: parseMinecraftTime(time) })),
    loop: copyEvents(events?.loop),
    stop: copyEvents(events?.stop),
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
    ...(event.callbacks ? { callbacks: event.callbacks.map((callback) => ({ ...callback })) } : {}),
  };
}
