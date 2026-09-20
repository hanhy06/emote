import { ConversionError } from "../foundation/diagnostics";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionAnimation, ConversionDocument, ConversionNode, SkinGroup } from "./conversionDocument";
import { remapImportedAnimation } from "./importedAnimationRemapper";
import { remapEditorNodeBinding } from "./nodeBindings";

export function combineConversionDocuments(documents: readonly ConversionDocument[]): ConversionDocument {
  if (documents.length === 0) throw new ConversionError("empty_import", "No animation projects were imported.");
  if (documents.length === 1) return documents[0];

  const nodes: Record<string, ConversionNode> = {};
  const skinGroups: Record<string, SkinGroup> = {};
  const animations: ConversionAnimation[] = [];
  const animationIds = new Set<string>();
  const resources = new Map(documents[0].resources);

  documents.forEach((document, index) => {
    const prefix = `input_${index + 1}__`;
    const nodeId = (id: string) => `${prefix}${id}`;
    const groupId = (id: string) => `${prefix}${id}`;

    for (const [id, node] of Object.entries(document.nodes)) {
      nodes[nodeId(id)] = {
        ...node,
        binding: remapEditorNodeBinding(node.binding, { editorNodeId: nodeId, editorGroupId: groupId }),
      };
    }
    for (const [id, group] of Object.entries(document.skinGroups)) {
      skinGroups[groupId(id)] = { ...group, nodeIds: group.nodeIds.map(nodeId) };
    }
    animations.push(...document.animations.map((animation) => {
      const source = remapImportedAnimation(animation.source, { editorNodeId: nodeId, runtimeNodeId: nodeId, editorGroupId: groupId });
      source.id = uniqueAnimationId(animation.output.namespace, source.id, animationIds);
      return { ...animation, nodeIds: animation.nodeIds.map(nodeId), source };
    }));
    if (index > 0) mergeResources(resources, document.resources);
  });

  const first = documents[0];
  return {
    ...first,
    origin: {
      ...first.origin,
      sourceName: documents.map((document) => document.origin.sourceName).join(", "),
      adapterLabel: [...new Set(documents.map((document) => document.origin.adapterLabel))].join(", "),
    },
    nodes,
    skinGroups,
    animations,
    sequence: { ...first.sequence, namespace: "emote" },
    diagnostics: documents.flatMap((document) => document.diagnostics),
    resources,
  };
}

function uniqueAnimationId(namespace: string, sourceId: string, usedIds: Set<string>): string {
  let id = sourceId;
  let suffix = 2;
  while (usedIds.has(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`)) id = `${sourceId}_${suffix++}`;
  usedIds.add(`${sanitizeNamespace(namespace)}:${sanitizeResourcePath(id)}`);
  return id;
}

function mergeResources(target: Map<string, GeneratedResource>, source: ReadonlyMap<string, GeneratedResource>): void {
  for (const [path, resource] of source) {
    if (target.has(path) && !sameResource(target.get(path)!, resource)) {
      throw new ConversionError("conflicting_import_resource", `Multiple inputs generate the same resource path: ${path}`, path);
    }
    target.set(path, resource);
  }
}

function sameResource(first: GeneratedResource, second: GeneratedResource): boolean {
  if (first instanceof Uint8Array || second instanceof Uint8Array) {
    return first instanceof Uint8Array && second instanceof Uint8Array
      && first.length === second.length && first.every((value, index) => value === second[index]);
  }
  return JSON.stringify(first) === JSON.stringify(second);
}
