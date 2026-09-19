import type { EmoteEvent, EmoteEvents } from "../format/emoteAnimation";
import type { RuntimeNode, RuntimeTimeline } from "./minecraftData";
import type { ImportedAnimation, ImportedNodeTrack, ImportedTimelineEvent } from "./conversionSeed";

export interface ImportedAnimationIdRemapper {
  nodeId(id: string): string;
}

export function remapImportedAnimation(
  animation: ImportedAnimation,
  ids: ImportedAnimationIdRemapper,
): ImportedAnimation {
  return {
    ...animation,
    events: {
      start: animation.events.start.map((event) => remapEvent(event, ids.nodeId)),
      timeline: animation.events.timeline.map((event) => remapTimelineEvent(event, ids.nodeId)),
      loop: animation.events.loop.map((event) => remapEvent(event, ids.nodeId)),
      stop: animation.events.stop.map((event) => remapEvent(event, ids.nodeId)),
    },
    preview: { ...animation.preview, tracks: remapTracks(animation.preview.tracks, ids.nodeId) },
    runtime: animation.runtime.kind === "baked"
      ? { kind: "baked", tracks: remapTracks(animation.runtime.tracks, ids.nodeId) }
      : {
          ...animation.runtime,
          nodes: remapRuntimeNodes(animation.runtime.nodes, ids.nodeId),
          timeline: remapRuntimeTimeline(animation.runtime.timeline, ids.nodeId),
        },
  };
}

function remapTracks(
  tracks: Record<string, ImportedNodeTrack>,
  nodeId: (id: string) => string,
): Record<string, ImportedNodeTrack> {
  return Object.fromEntries(Object.entries(tracks).map(([id, track]) => [nodeId(id), track]));
}

function remapRuntimeNodes(
  nodes: Record<string, RuntimeNode>,
  nodeId: (id: string) => string,
): Record<string, RuntimeNode> {
  return Object.fromEntries(Object.entries(nodes).map(([id, node]) => [
    nodeId(id),
    node.parent ? { ...node, parent: nodeId(node.parent) } : node,
  ]));
}

function remapRuntimeTimeline(timeline: RuntimeTimeline, nodeId: (id: string) => string): RuntimeTimeline {
  return {
    ...timeline,
    tracks: Object.fromEntries(Object.entries(timeline.tracks).map(([id, track]) => [nodeId(id), track])),
    ...(timeline.events ? { events: remapRuntimeEvents(timeline.events, nodeId) } : {}),
  };
}

function remapRuntimeEvents(events: EmoteEvents, nodeId: (id: string) => string): EmoteEvents {
  return {
    ...(events.start ? { start: events.start.map((event) => remapEvent(event, nodeId)) } : {}),
    ...(events.timeline ? { timeline: events.timeline.map((event) => remapEvent(event, nodeId)) } : {}),
    ...(events.loop ? { loop: events.loop.map((event) => remapEvent(event, nodeId)) } : {}),
    ...(events.stop ? { stop: events.stop.map((event) => remapEvent(event, nodeId)) } : {}),
  };
}

function remapTimelineEvent(event: ImportedTimelineEvent, nodeId: (id: string) => string): ImportedTimelineEvent {
  return { ...remapEvent(event, nodeId), tick: event.tick };
}

function remapEvent<T extends EmoteEvent>(event: T, nodeId: (id: string) => string): T {
  return {
    ...event,
    source: event.source.type === "node" ? { ...event.source, node: nodeId(event.source.node) } : event.source,
    origin: event.origin.type === "node" ? { ...event.origin, node: nodeId(event.origin.node) } : event.origin,
  };
}
