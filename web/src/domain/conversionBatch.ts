import { ConversionError } from "../foundation/diagnostics";
import { sanitizeNamespace, sanitizeResourcePath } from "../format/resourceLocation";
import { MINECRAFT_VERSION_PROFILES } from "../format/minecraftVersionProfiles";
import type { GeneratedResource } from "./generatedResource";
import type { ConversionAnimation, ConversionDocument, ConversionNode, SkinGroup } from "./conversionDocument";
import { remapAnimationRuntimeData, remapImportedAnimationEvents, remapPreviewProjection } from "./importedAnimationRemapper";
import { remapEditorNodeBinding } from "./nodeBindings";
import type { ImportedSequence, SequenceAnimationStep, SequenceStep } from "./emoteDefinition";

export function combineConversionDocuments(documents: readonly ConversionDocument[], importedSequences: readonly ImportedSequence[] = []): ConversionDocument {
  if (documents.length === 0) throw new ConversionError("empty_import", "No animation projects were imported.");
  if (importedSequences.length > 1) throw new ConversionError("multiple_sequences", "Open at most one sequence at a time.");
  if (documents.length === 1) return applyImportedSequence(documents[0], importedSequences[0]);

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
      const ids = { editorNodeId: nodeId, runtimeNodeId: nodeId, editorGroupId: groupId };
      const id = uniqueAnimationId(animation.output.namespace, animation.source.id, animationIds);
      return {
        ...animation,
        nodeIds: animation.nodeIds.map(nodeId),
        source: { ...animation.source, id },
        preview: remapPreviewProjection(animation.preview, ids),
        runtime: { ...animation.runtime, id, data: remapAnimationRuntimeData(animation.runtime.data, ids) },
        events: remapImportedAnimationEvents(animation.events, nodeId),
      };
    }));
    if (index > 0) mergeResources(resources, document.resources);
  });

  const first = documents[0];
  return applyImportedSequence({
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
  }, importedSequences[0]);
}

function applyImportedSequence(document: ConversionDocument, sequence: ImportedSequence | undefined): ConversionDocument {
  if (!sequence) return document;
  const animationIds = new Set<string>();
  for (const animation of document.animations) {
    const id = animation.source.sourceReferenceId;
    if (!id) continue;
    if (animationIds.has(id)) throw new ConversionError("duplicate_source_animation_id", `Multiple imported animations use the same id: ${id}`, id);
    animationIds.add(id);
  }
  if (animationIds.has(sequence.id)) {
    throw new ConversionError("duplicate_emote_id", `Animation and sequence use the same id: ${sequence.id}`, sequence.id);
  }
  for (const id of sequenceAnimationReferences(sequence.steps)) {
    if (!animationIds.has(id)) throw new ConversionError("missing_sequence_animation", `Sequence references an animation that was not opened: ${id}`, id);
  }
  const separator = sequence.id.indexOf(":");
  return {
    ...document,
    targetMinecraftVersion: sequence.targetMinecraftVersion && Object.hasOwn(MINECRAFT_VERSION_PROFILES, sequence.targetMinecraftVersion)
      ? sequence.targetMinecraftVersion : document.targetMinecraftVersion,
    sequence: {
      namespace: sequence.id.slice(0, separator),
      idPath: sequence.id.slice(separator + 1),
      displayName: sequence.metadata.name,
      description: sequence.metadata.description,
      additionalMetadata: Object.fromEntries(Object.entries(sequence.metadata).filter(([key]) => key !== "name" && key !== "description")),
      cooldown: sequence.cooldown,
      player: sequence.player,
      sourceReferenceId: sequence.id,
      steps: sequence.steps,
    },
  };
}

function sequenceAnimationReferences(steps: readonly SequenceStep[]): string[] {
  return steps.flatMap((step) => "wait" in step ? [] : animationStepReferences(step));
}

function animationStepReferences(step: SequenceAnimationStep): string[] {
  return typeof step.emote === "string" ? [step.emote] : step.emote.map((choice) => choice.id);
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
