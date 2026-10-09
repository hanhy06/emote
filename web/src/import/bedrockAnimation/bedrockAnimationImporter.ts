import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { importedNodeHints } from "../../domain/conversionSeed";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportDiagnostic } from "../../domain/conversionSeed";
import { skippedAnimationIssue } from "../../foundation/diagnostics";
import type { BedrockAnimation, BedrockAnimationDocument } from "./bedrockAnimationSchema";
import {
  createBedrockPlayerNodes,
  isHiddenBedrockAccessoryBone,
  resolveBedrockPlayerBone,
} from "./bedrockPlayerRig";
import { createBedrockAnimationIR } from "./bedrockAnimationIR";

export function importBedrockAnimationDocument(document: BedrockAnimationDocument, sourceName: string): ImportedProject {
  const sourceStem = sourceName.replace(/\.json$/i, "").trim() || "Bedrock Animation";
  const diagnostics: ImportDiagnostic[] = [...(document.animationDiagnostics ?? [])];
  const nodes = createBedrockPlayerNodes();
  const nodeIds = new Set(Object.keys(nodes));
  const unknownBoneIds: Record<string, string> = {};
  for (const animation of Object.values(document.animations)) {
    for (const boneName of Object.keys(animation.bones ?? {})) {
      if (resolveBedrockPlayerBone(boneName) || unknownBoneIds[boneName]) continue;
      const base = `bedrock_custom_${sanitizeResourcePath(boneName, "bone").replaceAll("/", "_")}`;
      let id = base;
      for (let suffix = 2; nodeIds.has(id); suffix++) id = `${base}_${suffix}`;
      nodeIds.add(id);
      unknownBoneIds[boneName] = id;
    }
  }
  const animations = Object.entries(document.animations).flatMap(([name, animation], index) => {
    const animationDiagnostics: ImportDiagnostic[] = [];
    try {
      collectAnimationDiagnostics(name, animation, animationDiagnostics);
      const imported = importAnimation(name, animation, index, animationDiagnostics, nodes, unknownBoneIds);
      diagnostics.push(...animationDiagnostics);
      return [imported];
    } catch (reason) {
      diagnostics.push(skippedAnimationIssue(name, `animations.${name}`, reason));
      return [];
    }
  });
  if (animations.length === 0) {
    const reasons = diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.message).join(" ");
    throw new Error(`No Bedrock animations in this file can be imported.${reasons ? ` ${reasons}` : ""}`);
  }
  return {
    source: "bedrock_animation_json",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: sanitizeNamespace(sourceStem),
    suggestedRotationDeadzone: 0,
    nodeHints: importedNodeHints(nodes),
    animations,
    diagnostics,
    resources: new Map(),
  };
}

function importAnimation(name: string, animation: BedrockAnimation, index: number, diagnostics: ImportDiagnostic[], nodes: Record<string, ImportedNode>, unknownBoneIds: Record<string, string>): ImportedAnimation {
  return {
    ir: createBedrockAnimationIR(animation, name, nodes, unknownBoneIds, diagnostics),
    id: sanitizeResourcePath(name, `animation_${index + 1}`),
    name,
  };
}

function collectAnimationDiagnostics(name: string, animation: BedrockAnimation, diagnostics: ImportDiagnostic[]): void {
  const ignored = [
    [animation.override_previous_animation, "override_previous_animation"],
  ] as const;
  for (const [value, property] of ignored) {
    if (value === undefined) continue;
    diagnostics.push({
      severity: "warning",
      code: "bedrock_animation_property_ignored",
      message: `${name}.${property} is not represented by the experimental importer.`,
      sourcePath: `animations.${name}.${property}`,
    });
  }
  for (const [boneName, bone] of Object.entries(animation.bones ?? {})) {
    if (!resolveBedrockPlayerBone(boneName) && !isHiddenBedrockAccessoryBone(boneName)) {
      diagnostics.push({
        severity: "warning",
        code: "bedrock_animation_logical_bone",
        message: `${name} bone ${boneName} is preserved as a logical node in Animation v5; geometry is unavailable.`,
        sourcePath: `animations.${name}.bones.${boneName}`,
      });
    }
    if (bone.relative_to !== undefined) {
      diagnostics.push({
        severity: "warning",
        code: "bedrock_relative_rotation_preview",
        message: `${name} bone ${boneName} preserves relative_to.rotation in Animation v5; the source preview approximates it.`,
        sourcePath: `animations.${name}.bones.${boneName}.relative_to`,
      });
    }
  }
}
