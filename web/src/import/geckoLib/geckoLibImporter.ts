import { sourceSecondsTime, parseAnimationSeconds } from "../../format/time";
import type { GeneratedResource } from "../../domain/generatedResource";
import { Matrix4 } from "three";
import { importedNodeHints } from "../../domain/conversionSeed";
import type { EventIR, TimelineEventIR } from "../../domain/animationIR";
import { composeDegreesTransform } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtString } from "../../format/snbt";
import { ConversionError, skippedAnimationIssue, type ConversionIssue } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedProject } from "../../domain/conversionSeed";
import { createDefaultPlayerBehavior } from "../../domain/emoteDefinition";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbGroup,
  type BbKeyframe,
  type BbLocator,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BbUnknownElement,
  type BlockbenchCubeProject,
} from "../common/blockbenchCubeSchema";
import type { GeckoLibBbmodelProject } from "./geckoLibBbmodelSchema";
import { uniqueCubeNodeId, writeCubeResources, writeEmbeddedTextures } from "../common/blockbenchCubeResources";
import {
  cubePlayerHeadMatrix,
  isHiddenAccessoryBone,
  isHiddenAccessoryName,
  normalizeBlockbenchName,
  prepareCubeModels,
} from "../common/blockbenchCubeSkin";
import { ZERO_VECTOR } from "../common/runtimeOutput";
import { type BoneEntry } from "../common/blockbenchCubeModel";
import { molangScalar } from "../common/molangVector";
import { type GeckoLibAnimationSource, createGeckoLibAnimationIR } from "./geckoLibAnimationIR";

const FORMAT_LABEL = "GeckoLib";
const DIAGNOSTIC_PREFIX = "geckolib";

export function importGeckoLibProject(project: GeckoLibBbmodelProject, sourceName: string): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);

  const sourceStem = sourceName.replace(/\.[^.]+$/i, "").trim() || project.name?.trim() || `${FORMAT_LABEL} Model`;
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "model");
  const resources = new Map<string, GeneratedResource>();
  const bones = buildBoneEntries(project);
  if (bones.length === 0) throw new Error(`${FORMAT_LABEL} cube project does not contain bones.`);
  if (bones.some((bone) => bone.cubes.length > 0)) writeEmbeddedTextures(project.textures, namespace, projectPath, resources);
  const { playableCubesByBone, skinAssignments } = prepareCubeModels(bones);
  const diagnostics: ConversionIssue[] = [...(project.animationDiagnostics ?? [])];
  const nodes: Record<string, ImportedNode> = {};
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      const localMatrix = bone.sourceElement ? new Matrix4().makeScale(...(bone.sourceElement.scale ?? [1, 1, 1]) as [number, number, number]) : new Matrix4();
      if (!bone.sourceElement) nodes[bone.id] = { binding: { sourceNodeId: bone.id }, type: "anchor" };
      bone.nodes.push({ id: bone.id, localMatrix });
      if (bone.sourceElement) diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_logical_node`, message: `${bone.group.name} (${bone.sourceElement.type}) is preserved as a logical node; geometry is unavailable.`, sourcePath: bone.uuid });
      if (bone.sourceElement?.type === "camera") diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_camera_ignored`, message: "Camera control was omitted; camera transforms remain as logical nodes.", sourcePath: bone.uuid });
    } else for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const inflate = cube.inflate ?? 0;
      const bounds = {
        from: [cube.from[0] - bone.group.origin[0] - inflate, cube.from[1] - bone.group.origin[1] - inflate, cube.from[2] - bone.group.origin[2] - inflate],
        to: [cube.to[0] - bone.group.origin[0] + inflate, cube.to[1] - bone.group.origin[1] + inflate, cube.to[2] - bone.group.origin[2] + inflate],
      };
      const conversionMatrix = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone, bounds);
      if (!hiddenAccessory && !conversionMatrix) throw new ConversionError(`invalid_${DIAGNOSTIC_PREFIX}_cube`, `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      const localMatrix = cubeLocalMatrix(cube, bone);
      bone.nodes.push({ id: nodeId, localMatrix });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(cube, bone.group.origin, project.resolution, project.textures, namespace, modelPath, resources);
      nodes[nodeId] = {
        binding: {
          sourceNodeId: nodeId,
          ...(skin ? { skinGroupId: `${skin.part}_${skin.order}` } : {}),
        },
        type: "item_display",
        visible: true,
        itemDisplay: "none",
        itemStack: {
          id: "minecraft:paper",
          count: 1,
          components: [{ name: "minecraft:item_model", value: serializeSnbtString(`${namespace}:${modelPath}`) }],
        },
        ...(conversionMatrix ? { playerHeadConversionMatrix: conversionMatrix } : {}),
        ...(skin ? { suggestedSkin: skin } : {}),
      };
    }
    for (const [locatorIndex, locator] of bone.locators.entries()) {
      const nodeId = uniqueLocatorNodeId(bone, locator, locatorIndex, nodeIds);
      const localMatrix = locatorLocalMatrix(locator, bone);
      bone.nodes.push({ id: nodeId, localMatrix, ignoreInheritedScale: locator.ignore_inherited_scale, locatorName: locator.name });
      nodes[nodeId] = {
        binding: { sourceNodeId: nodeId},
        type: "anchor",
        };
    }
  }

  const animations: ImportedAnimation[] = [];
  for (const [index, animation] of project.animations.entries()) {
    const sourceIndex = project.animationSourceIndices?.[index] ?? index;
    const animationDiagnostics: ConversionIssue[] = [];
    try {
      const imported = importAnimation(animation, sourceIndex, bones, nodes, animationDiagnostics);
      animations.push(imported);
      diagnostics.push(...animationDiagnostics);
    } catch (reason) {
      diagnostics.push(skippedAnimationIssue(animation.name, `animations[${sourceIndex}]`, reason));
    }
  }
  if (animations.length === 0) {
    const reasons = diagnostics.filter((issue) => issue.code === "animation_skipped").map((issue) => issue.message).join(" ");
    throw new ConversionError("no_importable_animations", `No ${FORMAT_LABEL} animations could be imported.${reasons ? ` ${reasons}` : ""}`);
  }
  return {
    source: "geckolib_bbmodel",
    sourceName,
    suggestedMetadata: { name: sourceStem, description: `${sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: namespace,
    nodeHints: importedNodeHints(nodes),
    animations,
    diagnostics,
    resources,
  };
}

function buildBoneEntries(project: BlockbenchCubeProject): BoneEntry[] {
  const groups = new Map(project.groups.map((group) => [group.uuid, group]));
  const elements = new Map(project.elements.map((element) => [element.uuid, element]));
  const entries: BoneEntry[] = [];
  const ids = new Set<string>();
  const visit = (entry: BbOutlinerEntry, parent?: BoneEntry) => {
    if (typeof entry === "string") {
      const element = elements.get(entry);
      if (!element) throw new Error(`${FORMAT_LABEL} outliner references unknown element ${entry}.`);
      if (element.type && element.type !== "cube" && element.type !== "locator") {
        const display = element as BbUnknownElement;
        visit({ uuid: display.uuid, name: display.name ?? display.uuid, origin: display.position ?? display.origin ?? [0, 0, 0], rotation: display.rotation ?? [0, 0, 0], children: [] }, parent);
        entries.at(-1)!.sourceElement = display;
        return;
      }
      if (!parent) throw new Error(`${FORMAT_LABEL} cube ${entry} is not parented to a bone.`);
      if (isLocator(element)) parent.locators.push(element);
      else parent.cubes.push(element as BbCube);
      return;
    }
    const saved = groups.get(entry.uuid);
    const group = mergeGroup(saved, entry);
    let id = sanitizeResourcePath(group.name, "bone").replaceAll("/", "_");
    const base = id;
    for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
    ids.add(id);
    const bone: BoneEntry = { id, uuid: group.uuid, group, parent, cubes: [], locators: [], nodes: [] };
    entries.push(bone);
    entry.children.forEach((child) => visit(child, bone));
  };
  project.outliner.forEach((entry) => visit(entry));
  return entries;
}

function isLocator(element: BlockbenchCubeProject["elements"][number]): element is BbLocator {
  return element.type === "locator";
}

function mergeGroup(saved: BbGroup | undefined, outliner: BbOutlinerGroup): BbGroup {
  const name = outliner.name ?? saved?.name;
  const origin = outliner.origin ?? saved?.origin;
  const rotation = outliner.rotation ?? saved?.rotation ?? [0, 0, 0];
  if (!name || !origin) throw new Error(`${FORMAT_LABEL} bone ${outliner.uuid} is missing its saved group data.`);
  return { uuid: outliner.uuid, name, origin, rotation };
}

function uniqueLocatorNodeId(bone: BoneEntry, locator: BbLocator, locatorIndex: number, ids: Set<string>): string {
  const name = sanitizeResourcePath(locator.name?.trim() || `locator_${locatorIndex + 1}`, `locator_${locatorIndex + 1}`).replaceAll("/", "_");
  const base = `${bone.id}_${name}`;
  let id = base;
  for (let suffix = 2; ids.has(id); suffix++) id = `${base}_${suffix}`;
  ids.add(id);
  return id;
}

function importAnimation(
  animation: BbAnimation,
  index: number,
  bones: BoneEntry[],
  nodes: Record<string, ImportedNode>,
  diagnostics: ConversionIssue[],
): ImportedAnimation {
  const source = resolveGeckoLibAnimationSource(animation, index, bones, diagnostics);
  for (const bone of bones) collectBoneAnimatorDiagnostics(animation, index, bone, source.animators.get(bone.uuid), diagnostics);
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    ir: createGeckoLibAnimationIR(source, bones, nodes),
    name: animation.name,
  };
}

function resolveGeckoLibAnimationSource(
  animation: BbAnimation,
  index: number,
  bones: BoneEntry[],
  diagnostics: ConversionIssue[],
): GeckoLibAnimationSource {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  const blendWeight = animation.blend_weight === undefined || animation.blend_weight === "" ? 1 : molangScalar(animation.blend_weight);
  for (const property of ["start_delay", "loop_delay"] as const) {
    const value = animation[property];
    if (value !== undefined && typeof value === "string" && value.trim() && !Number.isFinite(Number(value))) diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_runtime_timing`, message: `${animation.name}.${property} is preserved as Molang in Animation v5.`, sourcePath: `animations[${index}].${property}` });
  }
  const effectEvents = importEffectEvents(animation, index, bones, diagnostics);
  const boneAnimators = resolveBoneAnimators(animation, index, bones, diagnostics);
  return {
    animation,
    animationIndex: index,
    animators: boneAnimators,
    blendWeight,
    playbackMode: playbackMode as GeckoLibAnimationSource["playbackMode"],
    events: effectEvents,
  };
}

function resolveBoneAnimators(animation: BbAnimation, animationIndex: number, bones: BoneEntry[], diagnostics: ConversionIssue[]): Map<string, BbAnimator> {
  const result = new Map<string, BbAnimator>();
  const boneByUuid = new Map(bones.map((bone) => [bone.uuid, bone]));
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId)) result.set(animatorId, animator);
  }

  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (boneByUuid.has(animatorId) || isEffectAnimator(animatorId, animator) || isHiddenAccessoryName(animator.name ?? animatorId) || (animator.keyframes?.length ?? 0) === 0) continue;
    const normalizedName = normalizeBlockbenchName(animator.name);
    const matchingBones = normalizedName
      ? bones.filter((bone) => !result.has(bone.uuid) && normalizeBlockbenchName(bone.group.name) === normalizedName)
      : [];
    if (matchingBones.length === 1) {
      result.set(matchingBones[0].uuid, animator);
      continue;
    }
    diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_animator_ignored`, message: `${animation.name}: an unsupported animator was omitted.`, sourcePath: `animations[${animationIndex}].animators.${animatorId}` });
  }
  return result;
}

function isEffectAnimator(animatorId: string, animator: BbAnimator): boolean {
  return animatorId === "effects" || animator.type === "effect" || (animator.keyframes ?? []).every((keyframe) => ["sound", "particle", "timeline"].includes(keyframe.channel));
}

function importEffectEvents(
  animation: BbAnimation,
  animationIndex: number,
  bones: BoneEntry[],
  diagnostics: ConversionIssue[],
): TimelineEventIR[] {
  const events: TimelineEventIR[] = [];
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (!isEffectAnimator(animatorId, animator)) continue;
    for (const [keyframeIndex, keyframe] of (animator.keyframes ?? []).entries()) {
      const time = keyframe.time;
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      if (!["sound", "particle", "timeline"].includes(keyframe.channel)) {
        diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_effect_ignored`, message: `${animation.name} at ${time * 20}t: an unsupported effect was omitted.`, sourcePath });
        continue;
      }
      for (const point of keyframe.data_points) {
        if (keyframe.channel !== "timeline" && !point.effect?.trim()) {
          diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_effect_ignored`, message: `${animation.name} at ${time * 20}t: an effect without a resource identifier was omitted.`, sourcePath });
          continue;
        }
        const origin = effectOrigin(point.locator, bones);
        if (point.locator?.trim() && origin.type === "root") diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_effect_origin_approximated`, message: `${animation.name} at ${time * 20}t: effect locator could not be resolved; the root position is used. Review and edit the event.`, sourcePath });
        if (keyframe.channel === "sound" && point.effect?.trim()) {
          appendTimelineEvent(events, time, { source: { type: "player" }, origin, action: { type: "commands", commands: [`playsound ${point.effect.trim()} master @s ~ ~ ~`] } });
        } else if (keyframe.channel === "particle" && point.effect?.trim()) {
          appendTimelineEvent(events, time, { source: { type: "server" }, origin, action: { type: "commands", commands: [`particle ${point.effect.trim()} ~ ~ ~`] } });
          if (point.script?.trim()) diagnostics.push({
            severity: "warning",
            code: `${DIAGNOSTIC_PREFIX}_particle_script_ignored`,
            message: `${animation.name} at ${time * 20}t: particle pre-effect script was omitted. Review and edit the event.`,
            sourcePath,
          });
        } else if (keyframe.channel === "timeline") {
          const lines = (point.script ?? "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
          const commands = lines.map((line) => line.replace(/^\//, "").trim()).filter(Boolean);
          if (commands.length) appendTimelineEvent(events, time, { source: { type: "player" }, origin, action: { type: "commands", commands } });
          if (lines.some((line) => !line.startsWith("/"))) diagnostics.push({
            severity: "warning",
            code: `${DIAGNOSTIC_PREFIX}_instruction_approximated`,
            message: `${animation.name} at ${time * 20}t: uninterpreted instructions were kept as commands; behavior may differ. Review and edit them.`,
            sourcePath,
          });
        }
      }
    }
  }
  return events.sort((first, second) => parseAnimationSeconds(first.time) - parseAnimationSeconds(second.time));
}

function effectOrigin(locator: string | undefined, bones: BoneEntry[]): EventIR["origin"] {
  const name = normalizeBlockbenchName(locator);
  if (!name) return { type: "root" };
  for (const bone of bones) {
    const locatorNode = bone.nodes.find((node) => normalizeBlockbenchName(node.locatorName) === name);
    if (locatorNode) return { type: "node", node: locatorNode.id };
  }
  const bone = bones.find((candidate) => normalizeBlockbenchName(candidate.group.name) === name);
  return bone?.nodes[0] ? { type: "node", node: bone.nodes[0].id } : { type: "root" };
}

function appendTimelineEvent(events: TimelineEventIR[], time: number, event: EventIR): void {
  events.push({ ...event, time: sourceSecondsTime(time) });
}

function collectBoneAnimatorDiagnostics(animation: BbAnimation, animationIndex: number, bone: BoneEntry, animator: BbAnimator | undefined, diagnostics: ConversionIssue[]): void {
  for (const [keyframeIndex, keyframe] of (animator?.keyframes ?? []).entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      diagnostics.push({ severity: "warning", code: `${DIAGNOSTIC_PREFIX}_channel_ignored`, message: `${animation.name} at ${keyframe.time * 20}t: an unsupported bone channel was omitted.`, sourcePath: `animations[${animationIndex}].animators.${bone.uuid}.keyframes[${keyframeIndex}].channel` });
    }
  }
}

function cubeLocalMatrix(cube: BbCube, bone: BoneEntry): Matrix4 {
  const cubeRotation = cube.rotation ?? [0, 0, 0];
  const rotation = [cubeRotation[0], cubeRotation[1], cubeRotation[2]];
  if (rotation.every((value) => Math.abs(value) <= 1e-7)) return new Matrix4();
  const cubeOrigin = cube.origin ?? bone.group.origin;
  const origin = [(cubeOrigin[0] - bone.group.origin[0]) / 16, (cubeOrigin[1] - bone.group.origin[1]) / 16, (cubeOrigin[2] - bone.group.origin[2]) / 16];
  return composeDegreesTransform(origin, rotation, [1, 1, 1])
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function locatorLocalMatrix(locator: BbLocator, bone: BoneEntry): Matrix4 {
  return composeDegreesTransform(
    [(locator.position[0] - bone.group.origin[0]) / 16, (locator.position[1] - bone.group.origin[1]) / 16, (locator.position[2] - bone.group.origin[2]) / 16],
    [locator.rotation[0], locator.rotation[1], locator.rotation[2]],
    [1, 1, 1],
  );
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}
