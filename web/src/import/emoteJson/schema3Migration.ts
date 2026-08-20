import type {
  EmoteAnimation,
  EmoteEvent,
  EmoteNodeTracks,
  EmoteVectorKeyframe,
  LocalTransform,
  Schema3EmoteAnimation,
} from "../../format/emoteAnimation";
import { matrixToLocalTransform } from "../../format/localTransform";
import { formatMinecraftTime, parseMinecraftTime } from "../../format/minecraftTime";

interface MigratedTransformFrame {
  tick: number;
  transform: LocalTransform;
  interpolation?: "step" | "linear";
}

export function migrateSchema3Animation(animation: Schema3EmoteAnimation): EmoteAnimation {
  return {
    type: "animation",
    schema_version: 4,
    id: animation.id,
    metadata: { ...animation.metadata },
    settings: {
      ...animation.settings,
      player: {
        ...animation.settings.player,
        stop_conditions: { ...animation.settings.player.stop_conditions },
      },
      playback: { ...animation.settings.playback },
    },
    nodes: Object.fromEntries(Object.entries(animation.nodes).map(([id, node]) => {
      const transform = matrixToLocalTransform(node.default_matrix, `nodes.${id}.default_matrix`);
      if (node.type === "anchor") return [id, { type: "anchor" as const, space: node.space, transform }];
      const common = {
        space: node.space,
        transform,
        ...(node.visible === undefined ? {} : { visible: node.visible }),
        ...(node.entity_nbt === undefined ? {} : { entity_nbt: node.entity_nbt }),
      };
      if (node.type === "item_display") return [id, {
        ...common,
        type: "item_display" as const,
        item_stack_snbt: node.item_stack_snbt,
        item_display: node.item_display,
        ...(node.skin ? { skin: { ...node.skin } } : {}),
      }];
      if (node.type === "block_display") return [id, { ...common, type: "block_display" as const, block_state_snbt: node.block_state_snbt }];
      return [id, { ...common, type: "text_display" as const, text: node.text }];
    })),
    timeline: {
      duration: animation.timeline.duration,
      tracks: migrateTracks(animation),
      ...(animation.timeline.events ? { events: copyEvents(animation.timeline.events) } : {}),
    },
  };
}

function migrateTracks(animation: Schema3EmoteAnimation): Record<string, EmoteNodeTracks> {
  const tracks: Record<string, EmoteNodeTracks> = {};
  for (const [nodeId, node] of Object.entries(animation.nodes)) {
    const transformFrames = migrateTransformFrames(animation, nodeId);
    const visibility = new Map<number, boolean>();
    if (node.type !== "anchor") visibility.set(0, node.visible ?? true);
    for (const keyframe of animation.timeline.keyframes) {
      const state = keyframe.node_states?.[nodeId];
      if (state) visibility.set(parseMinecraftTime(keyframe.time), state.visible);
    }

    const track: EmoteNodeTracks = {};
    if (transformFrames.length > 1 || animation.timeline.keyframes.some((keyframe) => keyframe.node_transforms?.[nodeId])) {
      track.position = transformFrames.map((frame) => vectorFrame(frame, frame.transform.position));
      track.rotation = transformFrames.map((frame) => vectorFrame(frame, frame.transform.rotation));
      track.scale = transformFrames.map((frame) => vectorFrame(frame, frame.transform.scale));
    }
    if (visibility.size > 1 || animation.timeline.keyframes.some((keyframe) => keyframe.node_states?.[nodeId])) {
      track.visible = [...visibility.entries()].sort(([first], [second]) => first - second).map(([tick, value]) => ({
        time: formatMinecraftTime(tick),
        value,
      }));
    }
    if (Object.keys(track).length > 0) tracks[nodeId] = track;
  }
  return tracks;
}

function migrateTransformFrames(animation: Schema3EmoteAnimation, nodeId: string): MigratedTransformFrame[] {
  const node = animation.nodes[nodeId];
  const result: MigratedTransformFrame[] = [{
    tick: 0,
    transform: matrixToLocalTransform(node.default_matrix, `nodes.${nodeId}.default_matrix`),
  }];
  for (const keyframe of animation.timeline.keyframes) {
    const source = keyframe.node_transforms?.[nodeId];
    if (!source) continue;
    const tick = parseMinecraftTime(keyframe.time);
    const transform = matrixToLocalTransform(source.matrix, `timeline.${keyframe.time}.${nodeId}.matrix`);
    if (tick === 0) {
      result[0] = { tick: 0, transform };
      continue;
    }

    const duration = parseMinecraftTime(source.interpolation_duration ?? keyframe.interpolation_duration ?? "0t");
    const previous = result.at(-1)!;
    if (duration === 0) {
      previous.interpolation = "step";
    } else {
      const transitionStart = tick - duration;
      if (transitionStart > previous.tick) {
        previous.interpolation = "step";
        result.push({ tick: transitionStart, transform: previous.transform, interpolation: "linear" });
      } else {
        previous.interpolation = "linear";
      }
    }
    result.push({ tick, transform });
  }
  return result;
}

function vectorFrame(frame: MigratedTransformFrame, value: LocalTransform["position"]): EmoteVectorKeyframe {
  return {
    time: formatMinecraftTime(frame.tick),
    value,
    ...(frame.interpolation ? { interpolation: frame.interpolation } : {}),
  };
}

function copyEvents(events: NonNullable<Schema3EmoteAnimation["timeline"]["events"]>): NonNullable<EmoteAnimation["timeline"]["events"]> {
  return {
    ...(events.start ? { start: events.start.map(copyEvent) } : {}),
    ...(events.timeline ? { timeline: events.timeline.map((event) => ({ ...copyEvent(event), time: event.time })) } : {}),
    ...(events.loop ? { loop: events.loop.map(copyEvent) } : {}),
    ...(events.stop ? { stop: events.stop.map(copyEvent) } : {}),
  };
}

function copyEvent(event: EmoteEvent): EmoteEvent {
  return {
    source: { ...event.source },
    origin: { ...event.origin, ...(event.origin.offset ? { offset: [...event.origin.offset] as [number, number, number] } : {}) },
    commands: [...event.commands],
  };
}
