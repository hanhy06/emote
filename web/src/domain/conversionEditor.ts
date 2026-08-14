import type { NodeSpace } from "../format/emoteAnimation";
import type { ImportedAnimation } from "./conversionSeed";
import type { SkinPartId } from "../preview/skinAssignment";
import type { AnimationOutputSettings, ConversionDocument } from "./conversionDocument";

export function assignDocumentSkinPart(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  part: SkinPartId | null,
): ConversionDocument {
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  if (selectedGroupIds.size === 0) return document;
  const order = selectedGroupIds.size === 1 && part !== null
    ? new Set(Object.entries(document.skinGroups)
      .filter(([groupId, group]) => !selectedGroupIds.has(groupId) && group.assignment?.part === part)
      .map(([groupId]) => groupId)).size
    : null;
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) {
    const group = skinGroups[groupId];
    skinGroups[groupId] = {
      ...group,
      assignment: part === null ? null : { part, order: order ?? group.assignment?.order ?? 0 },
    };
  }
  const nodes = Object.fromEntries(Object.entries(document.nodes).map(([nodeId, node]) => [
    nodeId,
    selectedNodeIds.has(nodeId) && part !== null && node.space === "scene" ? { ...node, space: "initiator" as const } : node,
  ])) as ConversionDocument["nodes"];
  return { ...document, nodes, skinGroups };
}

export function assignDocumentSkinOrder(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  order: number,
): ConversionDocument {
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) {
    const group = skinGroups[groupId];
    if (group.assignment) skinGroups[groupId] = { ...group, assignment: { ...group.assignment, order } };
  }
  return { ...document, skinGroups };
}

export function assignDocumentNodeSpace(
  document: ConversionDocument,
  selectedNodeIds: ReadonlySet<string>,
  space: NodeSpace,
): ConversionDocument {
  const nodes = Object.fromEntries(Object.entries(document.nodes).map(([nodeId, node]) => [
    nodeId,
    selectedNodeIds.has(nodeId) ? { ...node, space } : node,
  ])) as ConversionDocument["nodes"];
  if (space !== "scene") return { ...document, nodes };
  const selectedGroupIds = selectedSkinGroupIds(document, selectedNodeIds);
  const skinGroups = { ...document.skinGroups };
  for (const groupId of selectedGroupIds) skinGroups[groupId] = { ...skinGroups[groupId], assignment: null };
  return { ...document, nodes, skinGroups };
}

export function editDocumentAnimation(
  document: ConversionDocument,
  animationIndex: number,
  edit: (animation: ImportedAnimation) => ImportedAnimation,
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex
      ? { ...animation, source: edit(animation.source) }
      : animation),
  };
}

export function updateDocumentAnimationOutput(
  document: ConversionDocument,
  animationIndex: number,
  output: AnimationOutputSettings,
): ConversionDocument {
  if (!document.animations[animationIndex]) return document;
  return {
    ...document,
    animations: document.animations.map((animation, index) => index === animationIndex ? { ...animation, output } : animation),
  };
}

function selectedSkinGroupIds(document: ConversionDocument, selectedNodeIds: ReadonlySet<string>): Set<string> {
  return new Set([...selectedNodeIds].flatMap((nodeId) => {
    const node = document.nodes[nodeId];
    return node?.type === "item_display" && node.skinGroupId ? [node.skinGroupId] : [];
  }));
}
