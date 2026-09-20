import type { EmoteEvent } from "../format/emoteAnimation";
import type { RuntimeNode, RuntimeNodeTracks } from "./minecraftData";
import type { ImportedAnimation, ImportedNodeTrack, ImportedTimelineEvent } from "./conversionSeed";
import { remapNativeRuntimeBindings } from "./nodeBindings";

export interface ImportedAnimationIdRemapper {
  editorNodeId(id: string): string;
  runtimeNodeId(id: string): string;
  editorGroupId?(id: string): string;
}

export function remapImportedAnimation(
  animation: ImportedAnimation,
  ids: ImportedAnimationIdRemapper,
): ImportedAnimation {
  const editorGroupId = ids.editorGroupId ?? ids.editorNodeId;
  return {
    ...animation,
    events: {
      start: animation.events.start.map((event) => remapEvent(event, ids.editorNodeId)),
      timeline: animation.events.timeline.map((event) => remapTimelineEvent(event, ids.editorNodeId)),
      loop: animation.events.loop.map((event) => remapEvent(event, ids.editorNodeId)),
      stop: animation.events.stop.map((event) => remapEvent(event, ids.editorNodeId)),
    },
    preview: { ...animation.preview, tracks: remapTracks(animation.preview.tracks, ids.editorNodeId) },
    runtime: animation.runtime.kind === "baked"
      ? { kind: "baked", tracks: remapTracks(animation.runtime.tracks, ids.editorNodeId) }
      : {
          ...animation.runtime,
          nodes: remapRuntimeNodes(animation.runtime.nodes, ids.runtimeNodeId),
          tracks: remapRuntimeTracks(animation.runtime.tracks, ids.runtimeNodeId),
          bindings: remapNativeRuntimeBindings(animation.runtime.bindings, {
            editorNodeId: ids.editorNodeId,
            editorGroupId,
            runtimeNodeId: ids.runtimeNodeId,
          }),
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

function remapRuntimeTracks(
  tracks: Record<string, RuntimeNodeTracks>,
  nodeId: (id: string) => string,
): Record<string, RuntimeNodeTracks> {
  return Object.fromEntries(Object.entries(tracks).map(([id, track]) => [nodeId(id), track]));
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
