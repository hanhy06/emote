import type { EmoteAnimation, EmoteEvent } from "../../format/emoteAnimation";
import { asMatrix16 } from "../../format/matrix";
import { validateEmoteAnimation } from "../../format/validator";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../types";

const decoder = new TextDecoder();

export const emoteJsonAdapter: ImportAdapter = {
  id: "emote_json",
  label: "Emote animation JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    try {
      const value = JSON.parse(decoder.decode(input.bytes)) as Record<string, unknown>;
      return value.schema_version === 1 && value.tick_rate === 20 && isRecord(value.nodes) && isRecord(value.timeline)
        ? { confidence: 100, reason: "matches emote animation schema 1" }
        : { confidence: 0, reason: "does not match emote animation schema 1" };
    } catch {
      return { confidence: 0, reason: "not JSON" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    const animation = JSON.parse(decoder.decode(input.bytes)) as EmoteAnimation;
    const issues = validateEmoteAnimation(animation);
    if (issues.length > 0) {
      throw new Error(`Invalid emote animation at ${issues[0].path}: ${issues[0].message}`);
    }
    const separator = animation.id.indexOf(":");
    const namespace = animation.id.slice(0, separator);
    const animationId = animation.id.slice(separator + 1);
    return {
      source: "emote_json",
      sourceName: input.name,
      suggestedMetadata: { ...animation.metadata },
      suggestedMinecraftVersion: animation.minecraft_version,
      suggestedNamespace: namespace,
      nodes: importNodes(animation),
      animations: [importTimeline(animation, animationId)],
      diagnostics: [],
      artifacts: [],
    };
  },
};

function importNodes(animation: EmoteAnimation): Record<string, ImportedNode> {
  return Object.fromEntries(Object.entries(animation.nodes).map(([id, node]): [string, ImportedNode] => {
    const defaultMatrix = asMatrix16(node.default_matrix, `${id}.default_matrix`);
    if (node.type === "anchor") return [id, { id, parentId: null, type: "anchor", defaultMatrix }];
    const common = {
      id,
      parentId: null,
      defaultMatrix,
      visible: node.visible ?? true,
      ...(node.entity_nbt ? { entityNbt: node.entity_nbt } : {}),
    };
    if (node.type === "item_display") {
      return [id, {
        ...common,
        type: "item_display",
        itemStackSnbt: node.item_stack_snbt,
        itemDisplay: node.item_display,
        ...(node.skin ? { skin: { ...node.skin } } : {}),
      }];
    }
    if (node.type === "block_display") return [id, { ...common, type: "block_display", blockStateSnbt: node.block_state_snbt }];
    return [id, { ...common, type: "text_display", text: node.text }];
  }));
}

function importTimeline(animation: EmoteAnimation, id: string): ImportedAnimation {
  const tracks: ImportedAnimation["tracks"] = {};
  for (const keyframe of animation.timeline.keyframes) {
    for (const [nodeId, transform] of Object.entries(keyframe.node_transforms ?? {})) {
      const track = tracks[nodeId] ?? { transforms: [], visibility: [] };
      const durationTicks = transform.interpolation_duration_ticks ?? keyframe.interpolation_duration_ticks ?? 0;
      track.transforms.push({
        timeSeconds: keyframe.tick / 20,
        matrix: asMatrix16(transform.matrix, `${id}/${nodeId}/${keyframe.tick}.matrix`),
        interpolation: durationTicks === 0 ? { type: "step" } : { type: "linear", durationSeconds: durationTicks / 20 },
      });
      tracks[nodeId] = track;
    }
    for (const [nodeId, state] of Object.entries(keyframe.node_states ?? {})) {
      const track = tracks[nodeId] ?? { transforms: [], visibility: [] };
      track.visibility.push({ timeSeconds: keyframe.tick / 20, visible: state.visible });
      tracks[nodeId] = track;
    }
  }
  const events = animation.timeline.events;
  return {
    id,
    name: animation.metadata.name,
    durationSeconds: animation.timeline.duration_ticks / 20,
    loop: animation.timeline.loop,
    loopDelaySeconds: animation.timeline.loop_delay_ticks / 20,
    tracks,
    events: {
      start: copyEvents(events?.start),
      timeline: (events?.timeline ?? []).map(({ tick, ...event }) => ({ ...copyEvent(event), timeSeconds: tick / 20 })),
      loop: copyEvents(events?.loop),
      stop: copyEvents(events?.stop),
    },
  };
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
