import type { RuntimeNode, RuntimeNodeTracks } from "../../domain/minecraftData";
import type { GeneratedResource } from "../../domain/generatedResource";
import { Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type EmoteEvent, type EmoteVectorKeyframe, type Matrix16, type MolangScalar } from "../../format/emoteAnimation";
import { matrixToLocalTransform } from "../../format/localTransform";
import { composeDegreesTransform, matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtString } from "../../format/snbt";
import { formatMinecraftTime, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import {
  type BbAnimation,
  type BbAnimator,
  type BbCube,
  type BbGroup,
  type BbKeyframe,
  type BbLocator,
  type BbOutlinerEntry,
  type BbOutlinerGroup,
  type BbmodelProject,
} from "./cubeProjectSchema";
import { evaluateGeckoChannel } from "./cubeAnimationBaker";
import {
  uniqueCubeNodeId,
  writeCubeResources,
  writeSourceCubeResources,
} from "./cubeModelResources";
import {
  cubePlayerHeadMatrix,
  isHiddenAccessoryBone,
  isHiddenAccessoryName,
  normalizeBlockbenchName,
  prepareCubeModels,
} from "./cubeSkinPreparation";
import { IDENTITY_TRANSFORM, importedNodeToRuntimeNode, ONE_VECTOR, ZERO_VECTOR } from "../runtimeOutput";
import { affineMolang, isolateMolangAxis, molangScalar, negateMolang, type MolangVector } from "../molangVector";
import { GECKOLIB_BBMODEL_TRANSFORMS, type CubeProjectTransformConvention } from "./cubeProjectTransformConvention";
import { planAnimationSamples } from "./cubeAnimationSampling";
import type { BoneEntry } from "./cubeProjectModel";

export const PLAYER_RENDER_SCALE = 0.9375;

export interface CubeProjectImportOptions {
  transforms?: CubeProjectTransformConvention;
  formatLabel?: string;
  molangDiagnosticCode?: string;
}

export interface ImportedCubeProjectContent {
  sourceStem: string;
  namespace: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, GeneratedResource>;
}

export function importBlockbenchCubeProject(project: BbmodelProject, sourceName: string, options: CubeProjectImportOptions = {}): ImportedProject {
  if (project.meta.model_format !== "geckolib_model") throw new Error(`Unsupported Blockbench model format: ${project.meta.model_format}`);
  if (project.elements.some((element) => element.type && element.type !== "cube" && element.type !== "locator")) {
    throw new ConversionError("unsupported_geckolib_element", "GeckoLib meshes and non-cube elements are not supported.", "elements");
  }

  const imported = importBlockbenchCubeContent(project, sourceName, options);
  return {
    source: "geckolib_bbmodel",
    sourceName,
    suggestedMetadata: { name: imported.sourceStem, description: `${imported.sourceStem} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    suggestedNamespace: imported.namespace,
    nodes: imported.nodes,
    animations: imported.animations,
    diagnostics: imported.diagnostics,
    resources: imported.resources,
  };
}

export function importBlockbenchCubeContent(
  project: BbmodelProject,
  sourceName: string,
  options: CubeProjectImportOptions = {},
): ImportedCubeProjectContent {
  const sourceStem = sourceName.replace(/\.bbmodel$/i, "").trim() || project.name?.trim() || "GeckoLib Model";
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "geckolib_model");
  const resources = new Map<string, GeneratedResource>();
  const transforms = options.transforms ?? GECKOLIB_BBMODEL_TRANSFORMS;
  const formatLabel = options.formatLabel ?? "GeckoLib";
  const bones = buildBoneEntries(project);
  if (bones.length === 0) throw new Error(`${formatLabel} cube project does not contain bones.`);
  writeSourceCubeResources(project, bones, namespace, projectPath, resources, transforms);
  const { playableCubesByBone, skinAssignments } = prepareCubeModels(bones);
  const diagnostics: ImportDiagnostic[] = [];
  const nodes: Record<string, ImportedNode> = {};
  const nodeIds = new Set(bones.map((bone) => bone.id));
  for (const bone of bones) {
    const boneMatrix = new Matrix4().set(...boneWorldMatrix(bone, new Map(), transforms));
    const playableCubes = playableCubesByBone.get(bone.uuid) ?? [];
    if (playableCubes.length === 0) {
      nodes[bone.id] = {
        id: bone.id,
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(boneMatrix, `GeckoLib bone ${bone.id}`),
      };
      bone.nodes.push({ id: bone.id, localMatrix: new Matrix4() });
    } else for (const [cubeIndex, cube] of playableCubes.entries()) {
      const nodeId = cubeIndex === 0 ? bone.id : uniqueCubeNodeId(bone, cube, cubeIndex, nodeIds);
      const hiddenAccessory = isHiddenAccessoryBone(bone);
      const conversionMatrix = hiddenAccessory ? undefined : cubePlayerHeadMatrix(cube, bone, transforms);
      if (!hiddenAccessory && !conversionMatrix) throw new ConversionError("invalid_geckolib_cube", `Cube ${cube.name ?? cube.uuid} cannot be fitted to a player head.`, cube.uuid);
      const skin = hiddenAccessory ? undefined : skinAssignments.get(cube.uuid);
      const localMatrix = cubeLocalMatrix(cube, bone, transforms);
      bone.nodes.push({ id: nodeId, localMatrix });
      const modelPath = `${projectPath}/${nodeId}`;
      writeCubeResources(project, bone, cube, namespace, modelPath, resources, transforms);
      nodes[nodeId] = {
        id: nodeId,
        type: "item_display",
        defaultMatrix: matrix4ToRowMajor(boneMatrix.clone().multiply(localMatrix), `GeckoLib cube ${nodeId}`),
        visible: true,
        itemDisplay: "none",
        itemStack: { id: "minecraft:paper", count: 1, components: [{ name: "minecraft:item_model", value: serializeSnbtString(`${namespace}:${modelPath}`) }] },
        ...(conversionMatrix ? { playerHeadConversion: { matrix: conversionMatrix } } : {}),
        ...(skin ? { suggestedSkin: skin, skinAssignmentGroup: `${skin.part}_${skin.order}` } : {}),
      };
    }
    for (const [locatorIndex, locator] of bone.locators.entries()) {
      const nodeId = uniqueLocatorNodeId(bone, locator, locatorIndex, nodeIds);
      const localMatrix = locatorLocalMatrix(locator, bone, transforms);
      const locatorBoneMatrix = locator.ignore_inherited_scale ? matrixWithoutScale(boneMatrix) : boneMatrix;
      bone.nodes.push({ id: nodeId, localMatrix, ignoreInheritedScale: locator.ignore_inherited_scale, locatorName: locator.name });
      nodes[nodeId] = {
        id: nodeId,
        type: "anchor",
        defaultMatrix: matrix4ToRowMajor(locatorBoneMatrix.clone().multiply(localMatrix), `GeckoLib locator ${nodeId}`),
      };
    }
  }

  if (project.animations.length === 0) throw new Error(`${formatLabel} cube project does not contain animations.`);
  const animations = project.animations.map((animation, index) => {
    try {
      return importAnimation(animation, index, bones, diagnostics, transforms);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_geckolib_molang") throw reason;
      const message = `${animation.name}: preview uses the Create pose; runtime Molang is preserved.`;
      diagnostics.push({
        severity: "warning",
        code: options.molangDiagnosticCode ?? "geckolib_animation_molang_unavailable",
        message,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
      return createPreviewOnlyAnimation(animation, index, message, bones, nodes, transforms);
    }
  });
  return {
    sourceStem,
    namespace,
    nodes,
    animations,
    diagnostics,
    resources,
  };
}

function createPreviewOnlyAnimation(animation: BbAnimation, index: number, reason: string, bones: BoneEntry[], nodes: Record<string, ImportedNode>, transforms: CubeProjectTransformConvention): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  const durationTicks = Number.isFinite(animation.length) && animation.length > 0
    ? Math.max(1, Math.round(animation.length * TICKS_PER_SECOND))
    : TICKS_PER_SECOND;
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    playbackMode: playbackMode === "loop" || playbackMode === "hold" ? playbackMode : "once",
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: [], loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: true, reason },
    preview: { durationTicks: TICKS_PER_SECOND, tracks: {} },
    runtime: createGeckoRuntime(animation, index, durationTicks, bones, nodes, transforms),
  };
}

function createGeckoRuntime(
  animation: BbAnimation,
  animationIndex: number,
  durationTicks: number,
  bones: BoneEntry[],
  importedNodes: Record<string, ImportedNode>,
  transforms: CubeProjectTransformConvention,
): NonNullable<ImportedAnimation["runtime"]> {
  const sceneId = "geckolib_scene";
  const nodes: Record<string, RuntimeNode> = {
    [sceneId]: { type: "anchor", space: "initiator", transform: { ...IDENTITY_TRANSFORM, scale: [PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE] } },
  };
  const tracks: Record<string, RuntimeNodeTracks> = {};
  const animators = resolveBoneAnimators(animation, animationIndex, bones);
  for (const bone of bones) {
    const parent = bone.parent ? `${bone.parent.id}_x` : sceneId;
    const parentOrigin = bone.parent?.group.origin ?? ZERO_VECTOR;
    const basePosition = transforms.position(
      bone.group.origin.map((value, axis) => value - parentOrigin[axis]),
      (value) => -value,
    ).map((value) => value / 16) as [number, number, number];
    const baseRotation = transforms.rotation(bone.group.rotation, (value) => -value);
    nodes[`${bone.id}_z`] = { type: "anchor", parent, transform: { position: basePosition, rotation: [0, 0, baseRotation[2]], scale: ONE_VECTOR } };
    nodes[`${bone.id}_y`] = { type: "anchor", parent: `${bone.id}_z`, transform: { position: ZERO_VECTOR, rotation: [0, baseRotation[1], 0], scale: ONE_VECTOR } };
    nodes[`${bone.id}_x`] = { type: "anchor", parent: `${bone.id}_y`, transform: { position: ZERO_VECTOR, rotation: [baseRotation[0], 0, 0], scale: ONE_VECTOR } };
    for (const entry of bone.nodes) {
      const imported = importedNodes[entry.id];
      if (imported) nodes[entry.id] = importedNodeToRuntimeNode(imported, matrixToLocalTransform(matrix4ToRowMajor(entry.localMatrix, `GeckoLib runtime node ${entry.id}`), `GeckoLib runtime node ${entry.id}`), `${bone.id}_x`);
    }
    const animator = animators.get(bone.uuid);
    if (!animator) continue;
    const position = geckoChannelFrames(animator, "position", ZERO_VECTOR, (values) => transforms.position(values, negateMolang)
      .map((value, axis) => affineMolang(value, 1 / 16, basePosition[axis])) as MolangVector);
    const rotation = geckoChannelFrames(animator, "rotation", ZERO_VECTOR, (values) => transforms.rotation(values, negateMolang));
    const scale = geckoChannelFrames(animator, "scale", ONE_VECTOR, (values) => values);
    if (position) tracks[`${bone.id}_z`] = { position };
    if (rotation) {
      tracks[`${bone.id}_z`] = { ...tracks[`${bone.id}_z`], rotation: isolateMolangAxis(rotation, 2, (value) => affineMolang(value, 1, baseRotation[2])) };
      tracks[`${bone.id}_y`] = { rotation: isolateMolangAxis(rotation, 1, (value) => affineMolang(value, 1, baseRotation[1])) };
      tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], rotation: isolateMolangAxis(rotation, 0, (value) => affineMolang(value, 1, baseRotation[0])) };
    }
    if (scale) tracks[`${bone.id}_x`] = { ...tracks[`${bone.id}_x`], scale };
  }
  return { nodes, timeline: { duration: formatMinecraftTime(durationTicks), tracks } };
}

function geckoChannelFrames(
  animator: BbAnimator,
  channel: "position" | "rotation" | "scale",
  fallback: readonly [number, number, number],
  transform: (value: MolangVector) => MolangVector,
): EmoteVectorKeyframe[] | undefined {
  const source = (animator.keyframes ?? []).filter((frame) => frame.channel === channel).sort((first, second) => first.time - second.time);
  if (source.length === 0) return undefined;
  const result = source.map((frame): EmoteVectorKeyframe => {
    const points = frame.data_points;
    if (points.length < 1 || points.length > 2) throw new ConversionError("unsupported_geckolib_keyframe", "GeckoLib transform keyframes must contain one value or a pre/post pair.");
    const vectors = points.map((point) => transform(geckoPointVector(point)));
    const interpolation = frame.interpolation === "step" ? "step" : "linear";
    return vectors.length === 1
      ? { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), value: vectors[0], interpolation }
      : { time: formatMinecraftTime(Math.round(frame.time * TICKS_PER_SECOND)), pre: vectors[0], post: vectors[1], interpolation };
  });
  if (result[0].time !== "0t") result.unshift({ time: "0t", value: transform([...fallback] as MolangVector), interpolation: "step" });
  return result.map((frame, index) => index + 1 < result.length ? frame : (({ interpolation: _, ...last }) => last)(frame));
}

function geckoPointVector(point: BbKeyframe["data_points"][number]): MolangVector {
  if (point.x === undefined || point.y === undefined || point.z === undefined) throw new ConversionError("invalid_geckolib_keyframe", "GeckoLib transform keyframe is missing an axis value.");
  return [point.x, point.y, point.z].map(molangScalar) as MolangVector;
}

function buildBoneEntries(project: BbmodelProject): BoneEntry[] {
  const groups = new Map(project.groups.map((group) => [group.uuid, group]));
  const elements = new Map(project.elements.map((element) => [element.uuid, element]));
  const entries: BoneEntry[] = [];
  const ids = new Set<string>();
  const visit = (entry: BbOutlinerEntry, parent?: BoneEntry) => {
    if (typeof entry === "string") {
      if (!parent) throw new Error(`GeckoLib cube ${entry} is not parented to a bone.`);
      const element = elements.get(entry);
      if (!element) throw new Error(`GeckoLib outliner references unknown element ${entry}.`);
      if (isLocator(element)) parent.locators.push(element);
      else parent.cubes.push(element);
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

function isLocator(element: BbmodelProject["elements"][number]): element is BbLocator {
  return element.type === "locator";
}

function mergeGroup(saved: BbGroup | undefined, outliner: BbOutlinerGroup): BbGroup {
  const name = outliner.name ?? saved?.name;
  const origin = outliner.origin ?? saved?.origin;
  const rotation = outliner.rotation ?? saved?.rotation ?? [0, 0, 0];
  if (!name || !origin) throw new Error(`GeckoLib bone ${outliner.uuid} is missing its saved group data.`);
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

function boneWorldMatrix(bone: BoneEntry, cache: Map<string, Matrix16>, convention: CubeProjectTransformConvention): Matrix16 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const local = bindLocalMatrix(bone, convention);
  const world = bone.parent
    ? new Matrix4().set(...boneWorldMatrix(bone.parent, cache, convention)).multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  const result = matrix4ToRowMajor(world, `GeckoLib bone ${bone.id}`);
  cache.set(bone.uuid, result);
  return result;
}

function bindLocalMatrix(bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  return composeDegreesTransform(
    convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16),
    convention.rotation(bone.group.rotation, (value) => -value),
    [1, 1, 1],
  );
}

function importAnimation(animation: BbAnimation, index: number, bones: BoneEntry[], diagnostics: ImportDiagnostic[], convention: CubeProjectTransformConvention): ImportedAnimation {
  const loop = animation.loop ?? "once";
  const playbackMode = loop === "hold_on_last_frame" ? "hold" : loop;
  if (playbackMode !== "once" && playbackMode !== "hold" && playbackMode !== "loop") throw new Error(`GeckoLib animation ${animation.name} has unsupported loop mode ${loop}.`);
  if (!Number.isFinite(animation.length) || animation.length < 0) throw new Error(`GeckoLib animation ${animation.name} has an invalid length.`);
  const startDelaySeconds = optionalNumericValue(animation.start_delay, 0, `animations[${index}].start_delay`);
  const blendWeight = optionalNumericValue(animation.blend_weight, 1, `animations[${index}].blend_weight`);
  const effectEvents = importEffectEvents(animation, index, bones, diagnostics);
  const durationTicks = requireAnimationDurationTicks(
    Math.max(1, Math.round((animation.length + startDelaySeconds) * TICKS_PER_SECOND), ...effectEvents.map((event) => event.tick + 1)),
    `${animation.name}.length`,
  );
  const boneAnimators = resolveBoneAnimators(animation, index, bones);
  const snapshots = new Map<string, Matrix4[]>();
  const snapshotAt = (time: number) => {
    const key = time.toFixed(9);
    const cached = snapshots.get(key);
    if (cached) return cached;
    const cache = new Map<string, Matrix4>();
    const result = bones.map((bone) => animatedWorldMatrix(bone, animation, boneAnimators, time, cache, index, 1, convention).clone());
    snapshots.set(key, result);
    return result;
  };
  const samplePlan = planAnimationSamples(animation, index, durationTicks, bones.length, snapshotAt);
  const tracks: ImportedAnimation["tracks"] = {};
  for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = startDelaySeconds > 0 ? tick / TICKS_PER_SECOND - startDelaySeconds : samplePlan.sourceTimes.get(tick) ?? tick / TICKS_PER_SECOND;
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, sourceTime, cache, index, blendWeight, convention), `${animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || samplePlan.stepTicks.has(tick) ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    for (const node of bone.nodes) {
      tracks[node.id] = {
        transforms: transforms.map((transform) => ({
          ...transform,
          matrix: matrix4ToRowMajor(
            (node.ignoreInheritedScale ? matrixWithoutScale(new Matrix4().set(...transform.matrix)) : new Matrix4().set(...transform.matrix)).multiply(node.localMatrix),
            `${animation.name}/${node.id}/${transform.tick}`,
          ),
        })),
        visibility: [],
        nbt: [],
      };
    }
  }
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    playbackMode,
    loopDelayTicks: playbackMode === "loop"
      ? Math.round(numericValue(animation.loop_delay ?? 0, `animations[${index}].loop_delay`) * TICKS_PER_SECOND)
      : 0,
    tracks,
    events: { start: [], timeline: effectEvents, loop: [], stop: [] },
  };
}

function resolveBoneAnimators(animation: BbAnimation, animationIndex: number, bones: BoneEntry[]): Map<string, BbAnimator> {
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
    throw new ConversionError(
      "unsupported_geckolib_animator",
      `GeckoLib animation ${animation.name} contains a non-bone animator (${animator.name ?? animatorId}).`,
      `animations[${animationIndex}].animators.${animatorId}`,
    );
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
  diagnostics: ImportDiagnostic[],
): ImportedTimelineEvent[] {
  const events: ImportedTimelineEvent[] = [];
  for (const [animatorId, animator] of Object.entries(animation.animators)) {
    if (!isEffectAnimator(animatorId, animator)) continue;
    for (const [keyframeIndex, keyframe] of (animator.keyframes ?? []).entries()) {
      const tick = Math.round(keyframe.time * TICKS_PER_SECOND);
      const sourcePath = `animations[${animationIndex}].animators.${animatorId}.keyframes[${keyframeIndex}]`;
      if (tick < 0) throw new ConversionError("invalid_geckolib_event", "GeckoLib effect keyframe time must not be negative.", sourcePath);
      for (const point of keyframe.data_points) {
        const origin = effectOrigin(point.locator, bones);
        if (keyframe.channel === "sound" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "player" }, origin, commands: [`playsound ${point.effect.trim()} master @s ~ ~ ~`] });
        } else if (keyframe.channel === "particle" && point.effect?.trim()) {
          appendTimelineEvent(events, tick, { source: { type: "server" }, origin, commands: [`particle ${point.effect.trim()} ~ ~ ~`] });
          if (point.script?.trim()) diagnostics.push({
            severity: "warning",
            code: "geckolib_particle_script_ignored",
            message: `Particle pre-effect script was not converted: ${point.script.trim()}`,
            sourcePath,
          });
        } else if (keyframe.channel === "timeline") {
          const lines = (point.script ?? "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
          const commands = lines.filter((line) => line.startsWith("/")).map((line) => line.slice(1).trim()).filter(Boolean);
          if (commands.length) appendTimelineEvent(events, tick, { source: { type: "player" }, origin, commands });
          const ignored = lines.filter((line) => !line.startsWith("/"));
          if (ignored.length) diagnostics.push({
            severity: "warning",
            code: "geckolib_custom_instruction_ignored",
            message: `Custom instruction was not converted because it is not a slash command: ${ignored.join("; ")}`,
            sourcePath,
          });
        }
      }
    }
  }
  return events.sort((first, second) => first.tick - second.tick);
}

function effectOrigin(locator: string | undefined, bones: BoneEntry[]): EmoteEvent["origin"] {
  const name = normalizeBlockbenchName(locator);
  if (!name) return { type: "root" };
  for (const bone of bones) {
    const locatorNode = bone.nodes.find((node) => normalizeBlockbenchName(node.locatorName) === name);
    if (locatorNode) return { type: "node", node: locatorNode.id };
  }
  const bone = bones.find((candidate) => normalizeBlockbenchName(candidate.group.name) === name);
  return bone?.nodes[0] ? { type: "node", node: bone.nodes[0].id } : { type: "root" };
}

function appendTimelineEvent(events: ImportedTimelineEvent[], tick: number, event: EmoteEvent): void {
  const matching = events.find((candidate) => candidate.tick === tick
    && JSON.stringify(candidate.source) === JSON.stringify(event.source)
    && JSON.stringify(candidate.origin) === JSON.stringify(event.origin));
  if (matching) matching.commands.push(...event.commands);
  else events.push({ ...event, tick });
}

function validateBoneAnimator(animation: BbAnimation, animationIndex: number, bone: BoneEntry, animator: BbAnimator | undefined): void {
  for (const [keyframeIndex, keyframe] of (animator?.keyframes ?? []).entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      throw new ConversionError(
        "unsupported_geckolib_channel",
        `GeckoLib bone ${bone.group.name} uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${bone.uuid}.keyframes[${keyframeIndex}].channel`,
      );
    }
  }
}

function animatedWorldMatrix(
  bone: BoneEntry,
  animation: BbAnimation,
  boneAnimators: Map<string, BbAnimator>,
  time: number,
  cache: Map<string, Matrix4>,
  animationIndex: number,
  blendWeight = 1,
  convention: CubeProjectTransformConvention = GECKOLIB_BBMODEL_TRANSFORMS,
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const animationPath = `animations[${animationIndex}].animators.${bone.uuid}`;
  const position = convention.position(
    evaluateGeckoChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationPath),
    (value) => -value,
  ).map((value) => value * blendWeight / 16);
  const rotationDelta = evaluateGeckoChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationPath).map((value) => value * blendWeight);
  const scale = evaluateGeckoChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationPath).map((value) => 1 + (value - 1) * blendWeight);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16);
  const baseRotation = convention.rotation(
    bone.group.rotation.map((value, index) => value + rotationDelta[index]),
    (value) => -value,
  );
  const local = composeDegreesTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex, blendWeight, convention).clone().multiply(local)
    : new Matrix4().makeScale(PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE, PLAYER_RENDER_SCALE).multiply(local);
  cache.set(bone.uuid, world);
  return world;
}

function cubeLocalMatrix(cube: BbCube, bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  const rotation = convention.rotation(cube.rotation ?? [0, 0, 0], (value) => -value);
  if (rotation.every((value) => Math.abs(value) <= 1e-7)) return new Matrix4();
  const origin = convention.position(
    (cube.origin ?? bone.group.origin).map((value, axis) => value - bone.group.origin[axis]),
    (value) => -value,
  ).map((value) => value / 16);
  return composeDegreesTransform(origin, rotation, [1, 1, 1])
    .multiply(new Matrix4().makeTranslation(-origin[0], -origin[1], -origin[2]));
}

function locatorLocalMatrix(locator: BbLocator, bone: BoneEntry, convention: CubeProjectTransformConvention): Matrix4 {
  return composeDegreesTransform(
    convention.position(locator.position.map((value, axis) => value - bone.group.origin[axis]), (value) => -value).map((value) => value / 16),
    convention.rotation(locator.rotation, (value) => -value),
    [1, 1, 1],
  );
}

function matrixWithoutScale(matrix: Matrix4): Matrix4 {
  const position = new Vector3();
  const rotation = new Quaternion();
  matrix.decompose(position, rotation, new Vector3());
  return new Matrix4().compose(position, rotation, new Vector3(1, 1, 1));
}

function numericValue(value: string | number, path: string): number {
  const number = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(number)) throw new ConversionError("unsupported_geckolib_molang", `GeckoLib expression ${String(value)} is not a numeric constant.`, path);
  return number;
}

function optionalNumericValue(value: string | number | undefined, fallback: number, path: string): number {
  if (value === undefined || (typeof value === "string" && value.trim() === "")) return fallback;
  return numericValue(value, path);
}

function validNamespace(value: string | undefined): string | undefined {
  if (!value) return undefined;
  return /^[a-z0-9_.-]+$/.test(value) ? value : undefined;
}
