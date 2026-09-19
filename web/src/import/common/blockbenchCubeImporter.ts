import type { GeneratedResource } from "../../domain/generatedResource";
import { Matrix4, Quaternion, Vector3 } from "three";
import type { EmoteEvent, Matrix16, MolangScalar } from "../../format/emoteAnimation";
import { composeDegreesTransform, matrix4ToRowMajor } from "../../format/matrix";
import { sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtString } from "../../format/snbt";
import { formatMinecraftTime, requireAnimationDurationTicks, TICKS_PER_SECOND } from "../../format/time";
import { ConversionError } from "../../foundation/diagnostics";
import type { ImportedAnimation, ImportedNode, ImportedTimelineEvent, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
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
} from "./blockbenchCubeSchema";
import { evaluateApproximateBlockbenchChannel, evaluateBlockbenchChannel } from "./blockbenchKeyframeEvaluator";
import { blockbenchIntervalIsStep } from "./animationEasing";
import {
  uniqueCubeNodeId,
  writeCubeResources,
  writeSourceCubeResources,
} from "./blockbenchCubeResources";
import {
  cubePlayerHeadMatrix,
  isHiddenAccessoryBone,
  isHiddenAccessoryName,
  normalizeBlockbenchName,
  prepareCubeModels,
} from "./blockbenchCubeSkin";
import { ZERO_VECTOR } from "./runtimeOutput";
import type { CubeProjectTransformConvention } from "./blockbenchCubeTransform";
import { planAnimationSamples } from "./blockbenchAnimationSampling";
import type { AnimationSamplePlan } from "./animationSampling";
import type { BoneEntry } from "./blockbenchCubeModel";
import { usesRuntimeMolangState } from "../../format/molang/runtimeAnalysis";

export const PLAYER_RENDER_SCALE = 0.9375;
export const BLOCKBENCH_RUNTIME_SCENE_ID = "geckolib_scene";

export interface BlockbenchNativeRuntimeContext {
  animation: BbAnimation;
  animationIndex: number;
  bones: BoneEntry[];
  importedNodes: Record<string, ImportedNode>;
  animators: ReadonlyMap<string, BbAnimator>;
  durationTicks: number;
  startDelayTicks: number;
  blendWeight: number;
  samplePlan?: AnimationSamplePlan;
}

export type BlockbenchNativeRuntimeFactory = (
  context: BlockbenchNativeRuntimeContext,
) => Omit<Extract<ImportedAnimation["runtime"], { kind: "native" }>, "kind">;

export interface CubeProjectImportOptions {
  transforms: CubeProjectTransformConvention;
  formatLabel: string;
  runtimeOutput?: "auto" | "native";
  createNativeRuntime: BlockbenchNativeRuntimeFactory;
}

export interface ImportedCubeProjectContent {
  sourceStem: string;
  namespace: string;
  nodes: Record<string, ImportedNode>;
  animations: ImportedAnimation[];
  diagnostics: ImportDiagnostic[];
  resources: Map<string, GeneratedResource>;
  runtimeSceneId: string;
  runtimeParentByGroupUuid: Readonly<Record<string, string>>;
}

export function importBlockbenchCubeContent(
  project: BbmodelProject,
  sourceName: string,
  options: CubeProjectImportOptions,
): ImportedCubeProjectContent {
  const sourceStem = sourceName.replace(/\.bbmodel$/i, "").trim() || project.name?.trim() || "GeckoLib Model";
  const namespace = validNamespace(project.geckolib_modid) ?? sanitizeNamespace(sourceStem);
  const projectPath = sanitizeResourcePath(project.name?.trim() || sourceStem, "geckolib_model");
  const resources = new Map<string, GeneratedResource>();
  const transforms = options.transforms;
  const formatLabel = options.formatLabel;
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
        space: "initiator",
        spaceAssignmentGroup: BLOCKBENCH_RUNTIME_SCENE_ID,
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
        space: "initiator",
        spaceAssignmentGroup: BLOCKBENCH_RUNTIME_SCENE_ID,
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
        space: "initiator",
        spaceAssignmentGroup: BLOCKBENCH_RUNTIME_SCENE_ID,
      };
    }
  }

  if (project.animations.length === 0) throw new Error(`${formatLabel} cube project does not contain animations.`);
  const animations = project.animations.map((animation, index) =>
    importAnimation(animation, index, bones, nodes, diagnostics, transforms, options.runtimeOutput === "native", options.createNativeRuntime));
  return {
    sourceStem,
    namespace,
    nodes,
    animations,
    diagnostics,
    resources,
    runtimeSceneId: BLOCKBENCH_RUNTIME_SCENE_ID,
    runtimeParentByGroupUuid: Object.fromEntries(bones.map((bone) => [bone.uuid, `${bone.id}_x`])),
  };
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

function importAnimation(animation: BbAnimation, index: number, bones: BoneEntry[], nodes: Record<string, ImportedNode>, diagnostics: ImportDiagnostic[], convention: CubeProjectTransformConvention, forceNativeRuntime: boolean, createNativeRuntime: BlockbenchNativeRuntimeFactory): ImportedAnimation {
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
  let nativeRuntime = forceNativeRuntime || blockbenchAnimationUsesRuntimeState(animation);
  let samplePlan: AnimationSamplePlan | undefined;
  if (!blockbenchAnimationUsesRuntimeState(animation)) {
    const snapshots = new Map<string, Matrix4[]>();
    const snapshotAt = (time: number) => {
      const key = time.toFixed(9);
      const cached = snapshots.get(key);
      if (cached) return cached;
      const cache = new Map<string, Matrix4>();
      const result = bones.map((bone) => animatedWorldMatrix(bone, animation, boneAnimators, time, cache, index, 1, convention, evaluateBlockbenchChannel).clone());
      snapshots.set(key, result);
      return result;
    };
    try {
      samplePlan = planAnimationSamples(animation, index, durationTicks, bones.length, snapshotAt);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_geckolib_molang") throw reason;
      nativeRuntime = true;
      diagnostics.push({
        severity: "warning",
        code: "approximate_preview_molang",
        message: `${animation.name}: runtime Molang is preserved; preview uses supported math/time expressions only.`,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
    }
  }

  const runtimeTracks: ImportedAnimation["preview"]["tracks"] = {};
  if (!nativeRuntime && samplePlan) for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = startDelaySeconds > 0 ? tick / TICKS_PER_SECOND - startDelaySeconds : samplePlan.sourceTimes.get(tick) ?? tick / TICKS_PER_SECOND;
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, sourceTime, cache, index, blendWeight, convention, evaluateBlockbenchChannel), `${animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || samplePlan.stepTicks.has(tick) ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    assignBoneTracks(runtimeTracks, bone, transforms, animation.name);
  }

  const previewTracks: ImportedAnimation["preview"]["tracks"] = {};
  const previewTicks = approximatePreviewTicks(animation, durationTicks, Math.round(startDelaySeconds * TICKS_PER_SECOND));
  for (const bone of bones) {
    validateBoneAnimator(animation, index, bone, boneAnimators.get(bone.uuid));
    const transforms: ImportedTransformKeyframe[] = [];
    for (const [tickIndex, tick] of previewTicks.entries()) {
      const cache = new Map<string, Matrix4>();
      const sourceTime = tick / TICKS_PER_SECOND - startDelaySeconds;
      const previousTick = previewTicks[tickIndex - 1];
      transforms.push({
        tick,
        matrix: matrix4ToRowMajor(animatedWorldMatrix(bone, animation, boneAnimators, sourceTime, cache, index, blendWeight, convention, evaluateApproximateBlockbenchChannel), `${animation.name}/${bone.id}/${tick}`),
        interpolation: tick === 0 || approximatePreviewStepAt(boneAnimators, previousTick / TICKS_PER_SECOND - startDelaySeconds, sourceTime)
          ? { type: "step" }
          : { type: "linear", durationTicks: Math.max(1, tick - previousTick) },
      });
    }
    assignBoneTracks(previewTracks, bone, transforms, animation.name);
  }
  const runtime = nativeRuntime
    ? { kind: "native" as const, ...createNativeRuntime({
      animation,
      animationIndex: index,
      bones,
      importedNodes: nodes,
      animators: boneAnimators,
      durationTicks,
      startDelayTicks: Math.round(startDelaySeconds * TICKS_PER_SECOND),
      blendWeight,
      samplePlan,
    }) }
    : { kind: "baked" as const, tracks: runtimeTracks };
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: animation.name,
    durationTicks,
    playbackMode,
    loopDelayTicks: playbackMode === "loop"
      ? Math.round(numericValue(animation.loop_delay ?? 0, `animations[${index}].loop_delay`) * TICKS_PER_SECOND)
      : 0,
    events: { start: [], timeline: effectEvents, loop: [], stop: [] },
    preview: { durationTicks, tracks: previewTracks, availability: { preview: "full" } },
    exportAvailability: { exportable: true },
    runtime,
  };
}

function assignBoneTracks(
  tracks: ImportedAnimation["preview"]["tracks"],
  bone: BoneEntry,
  transforms: ImportedTransformKeyframe[],
  animationName: string,
): void {
  for (const node of bone.nodes) {
    tracks[node.id] = {
      transforms: transforms.map((transform) => ({
        ...transform,
        matrix: matrix4ToRowMajor(
          (node.ignoreInheritedScale ? matrixWithoutScale(new Matrix4().set(...transform.matrix)) : new Matrix4().set(...transform.matrix)).multiply(node.localMatrix),
          `${animationName}/${node.id}/${transform.tick}`,
        ),
      })),
      visibility: [],
      nbt: [],
    };
  }
}

function approximatePreviewTicks(animation: BbAnimation, durationTicks: number, startDelayTicks: number): number[] {
  const ticks = new Set<number>([0, durationTicks]);
  const stride = Math.max(1, Math.ceil(durationTicks / 199));
  for (let tick = 0; tick <= durationTicks; tick += stride) ticks.add(tick);
  for (const animator of Object.values(animation.animators)) {
    for (const frame of animator.keyframes ?? []) {
      if (!["position", "rotation", "scale"].includes(frame.channel)) continue;
      ticks.add(Math.max(0, Math.min(durationTicks, startDelayTicks + Math.round(frame.time * TICKS_PER_SECOND))));
    }
  }
  return [...ticks].sort((first, second) => first - second);
}

function approximatePreviewStepAt(
  animators: ReadonlyMap<string, BbAnimator>,
  fromTime: number,
  toTime: number,
): boolean {
  if (toTime <= 0) return true;
  for (const animator of animators.values()) {
    for (const channel of ["position", "rotation", "scale"]) {
      const frames = (animator.keyframes ?? []).filter((frame) => frame.channel === channel);
      if (blockbenchIntervalIsStep(frames, fromTime, toTime)) return true;
    }
  }
  return false;
}

function blockbenchAnimationUsesRuntimeState(animation: BbAnimation): boolean {
  return Object.values(animation.animators).some((animator) => (animator.keyframes ?? []).some((keyframe) => keyframe.data_points.some((point) =>
    usesRuntimeMolangState(point.x) || usesRuntimeMolangState(point.y) || usesRuntimeMolangState(point.z),
  )));
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
  convention: CubeProjectTransformConvention,
  evaluateChannel: typeof evaluateBlockbenchChannel,
): Matrix4 {
  const cached = cache.get(bone.uuid);
  if (cached) return cached;
  const animator = boneAnimators.get(bone.uuid);
  const animationPath = `animations[${animationIndex}].animators.${bone.uuid}`;
  const position = convention.position(
    evaluateChannel(animator?.keyframes ?? [], "position", time, [0, 0, 0], animationPath),
    (value) => -value,
  ).map((value) => value * blendWeight / 16);
  const rotationDelta = evaluateChannel(animator?.keyframes ?? [], "rotation", time, [0, 0, 0], animationPath).map((value) => value * blendWeight);
  const scale = evaluateChannel(animator?.keyframes ?? [], "scale", time, [1, 1, 1], animationPath).map((value) => 1 + (value - 1) * blendWeight);
  const parentOrigin = bone.parent?.group.origin ?? [0, 0, 0];
  const basePosition = convention.position(bone.group.origin.map((value, index) => value - parentOrigin[index]), (value) => -value).map((value) => value / 16);
  const baseRotation = convention.rotation(
    bone.group.rotation.map((value, index) => value + rotationDelta[index]),
    (value) => -value,
  );
  const local = composeDegreesTransform(basePosition.map((value, index) => value + position[index]), baseRotation, scale);
  const world = bone.parent
    ? animatedWorldMatrix(bone.parent, animation, boneAnimators, time, cache, animationIndex, blendWeight, convention, evaluateChannel).clone().multiply(local)
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
