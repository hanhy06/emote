import type { EmoteEvent } from "../format/emoteAnimation";
import type { RuntimeNode, RuntimeNodeTracks } from "./minecraftData";
import type { ImportedAnimation, ImportedNodeTrack, ImportedTimelineEvent } from "./conversionSeed";

export interface ImportedAnimationIdRemapper {
  nodeId(id: string): string;
  spaceGroupId?(id: string): string;
}

export function remapImportedAnimation(
  animation: ImportedAnimation,
  ids: ImportedAnimationIdRemapper,
): ImportedAnimation {
  const spaceGroupId = ids.spaceGroupId ?? ids.nodeId;
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
          tracks: remapRuntimeTracks(animation.runtime.tracks, ids.nodeId),
          bindings: {
            editorNodeByRuntimeNode: Object.fromEntries(Object.entries(animation.runtime.bindings.editorNodeByRuntimeNode)
              .map(([runtimeNodeId, editorNodeId]) => [ids.nodeId(runtimeNodeId), ids.nodeId(editorNodeId)])),
            spaceGroupByRuntimeRoot: Object.fromEntries(Object.entries(animation.runtime.bindings.spaceGroupByRuntimeRoot)
              .map(([runtimeRootId, editorGroupId]) => [ids.nodeId(runtimeRootId), spaceGroupId(editorGroupId)])),
          },
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
