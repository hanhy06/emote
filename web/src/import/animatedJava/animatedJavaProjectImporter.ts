import { Euler, MathUtils, Matrix4, Quaternion, Vector3 } from "three";
import { createDefaultPlayerBehavior, type Matrix16 } from "../../format/emoteAnimation";
import { matrix4ToRowMajor } from "../../format/matrix";
import { normalizeResourceLocation, sanitizeNamespace, sanitizeResourcePath } from "../../format/resourceLocation";
import { serializeSnbtCompound, serializeSnbtString, splitSnbtPair, splitSnbtTopLevel } from "../../format/snbt";
import { requireAnimationDurationTicks, secondsToTicks } from "../../format/time";
import type { ImportInput } from "../adapter";
import { ConversionError } from "../../foundation/diagnostics";
import { importBlockbenchCubeProject } from "../blockbench/cubeProjectImporter";
import { requireBlockbenchCubeProject } from "../blockbench/cubeProjectSchema";
import type { ImportedAnimation, ImportedNode, ImportedProject, ImportedTransformKeyframe, ImportDiagnostic } from "../../domain/conversionSeed";
import type {
  AjProject,
  AjProjectAnimation,
  AjProjectDisplayElement,
  AjProjectKeyframe,
} from "./animatedJavaProjectSchema";
import { createAjProjectRuntime } from "./animatedJavaAnimationOutput";

interface ProjectChannelCursor {
  frames: AjProjectKeyframe[];
  nextIndex: number;
}

export function importAnimatedJavaProject(input: ImportInput, project: AjProject): ImportedProject {
  if (project.meta.format !== "animated-java:format/blueprint") {
    throw new Error(`Unsupported Animated Java project format: ${project.meta.format}`);
  }
  if (!project.meta.format_version.startsWith("1.")) {
    throw new Error(`Unsupported Animated Java project version: ${project.meta.format_version}`);
  }
  const cubeElements = project.elements.filter((element) => element.type === "cube");
  if (cubeElements.length > 0 && cubeElements.length === project.elements.length) {
    return importAnimatedJavaCubeProject(input, project);
  }
  if (project.groups.length > 0 || project.elements.some((element) => !isDirectDisplay(element.type))) {
    throw new ConversionError(
      "unsupported_animated_java_project_nodes",
      "This Animated Java project contains groups or model cubes. Export the plugin blueprint JSON until native group conversion is added.",
      "elements",
    );
  }
  if (project.elements.length === 0) throw new Error("Animated Java project does not contain display nodes.");
  if (project.animations.length === 0) throw new Error("Animated Java project does not contain animations.");

  const displayElements = project.elements as AjProjectDisplayElement[];
  const nodes = Object.fromEntries(displayElements.map((element) => [element.uuid, importProjectElement(element)]));
  const diagnostics: ImportDiagnostic[] = [];
  const animations = project.animations.map((animation, index) => {
    try {
      return importProjectAnimation(animation, index, displayElements);
    } catch (reason) {
      if (!(reason instanceof ConversionError) || reason.code !== "unsupported_animated_java_molang") throw reason;
      const message = `${animation.name}: preview uses the Create pose; runtime Molang is preserved.`;
      diagnostics.push({
        severity: "warning",
        code: "animated_java_animation_molang_unavailable",
        message,
        sourcePath: reason.sourcePath ?? `animations[${index}]`,
      });
      return createPreviewOnlyProjectAnimation(animation, index, message, displayElements, nodes);
    }
  });
  const sourceStem = input.name.replace(/\.ajblueprint$/i, "").trim() || "Animated Java";
  const name = prettify(sourceStem);
  return {
    source: "animated_java_json",
    sourceName: input.name,
    suggestedMetadata: { name, description: `${name} emote.` },
    suggestedPlayer: createDefaultPlayerBehavior(),
    nodes,
    animations,
    diagnostics,
    resources: new Map(),
  };
}

function createPreviewOnlyProjectAnimation(
  animation: AjProjectAnimation,
  index: number,
  reason: string,
  elements: AjProjectDisplayElement[],
  nodes: Record<string, ImportedNode>,
): ImportedAnimation {
  const durationTicks = Number.isFinite(animation.length) && animation.length > 0 ? Math.max(1, Math.round(animation.length * 20)) : 20;
  return {
    id: sanitizeResourcePath(animation.name, `animation_${index + 1}`),
    name: prettify(animation.name),
    durationTicks,
    loop: animation.loop === "loop" ? "loop" : "once",
    loopDelayTicks: 0,
    tracks: {},
    events: { start: [], timeline: [], loop: [], stop: [] },
    availability: { preview: "create_pose", exportable: true, reason },
    preview: { durationTicks: 20, tracks: {} },
    runtime: createAjProjectRuntime(animation, durationTicks, elements, nodes),
  };
}

function importAnimatedJavaCubeProject(input: ImportInput, project: AjProject): ImportedProject {
  const sourceStem = input.name.replace(/\.ajblueprint$/i, "").trim() || project.name?.trim() || "Animated Java";
  const cubeProject = requireBlockbenchCubeProject({
    ...project,
    meta: { format_version: project.meta.format_version, model_format: "geckolib_model" },
    name: project.name?.trim() || sourceStem,
    geckolib_modid: sanitizeNamespace(sourceStem, "animated_java"),
  });
  const imported = importBlockbenchCubeProject(cubeProject, `${sourceStem}.bbmodel`);
  const name = prettify(sourceStem);
  return {
    ...imported,
    source: "animated_java_json",
    sourceName: input.name,
    suggestedMetadata: { name, description: `${name} emote.` },
  };
}

function isDirectDisplay(type: string): boolean {
  return [
    "animated_java:vanilla_block_display",
    "animated_java:vanilla_item_display",
    "animated_java:vanilla_text_display",
  ].includes(type);
}

function importProjectElement(element: AjProjectDisplayElement): ImportedNode {
  ensureEmptyProjectConfigs(element);
  const defaultMatrix = composeProjectMatrix(element.position, element.rotation, element.scale, `Animated Java node ${element.name}`);
  if (element.type === "animated_java:vanilla_block_display") {
    return {
      id: element.uuid,
      type: "block_display",
      defaultMatrix,
      visible: element.visibility,
      blockStateSnbt: blockArgumentToSnbt(element.block ?? "minecraft:air"),
    };
  }
  if (element.type === "animated_java:vanilla_item_display") {
    return {
      id: element.uuid,
      type: "item_display",
      defaultMatrix,
      visible: element.visibility,
      itemDisplay: "none",
      itemStackSnbt: itemArgumentToSnbt(element.item ?? "minecraft:air"),
    };
  }
  return {
    id: element.uuid,
    type: "text_display",
    defaultMatrix,
    visible: element.visibility,
    text: element.text ?? { text: element.name },
  };
}

function ensureEmptyProjectConfigs(element: AjProjectDisplayElement): void {
  const defaults = Object.keys(element.configs?.default ?? {});
  const variants = Object.keys(element.configs?.variants ?? {});
  if (defaults.length || variants.length) {
    throw new ConversionError(
      "unsupported_animated_java_display_config",
      `Animated Java node ${element.name} contains display configuration that cannot yet be preserved.`,
      `elements.${element.uuid}.configs`,
    );
  }
}

function importProjectAnimation(
  animation: AjProjectAnimation,
  animationIndex: number,
  elements: AjProjectDisplayElement[],
): ImportedAnimation {
  if (animation.loop !== "once" && animation.loop !== "loop") throw new Error(`Animated Java animation ${animation.name} has unsupported loop mode ${animation.loop}.`);
  const durationTicks = requireAnimationDurationTicks(
    secondsToTicks(animation.length, `${animation.name}.length`),
    `${animation.name}.length`,
  );
  const tracks: ImportedAnimation["tracks"] = {};
  for (const element of elements) {
    const keyframes = animation.animators[element.uuid]?.keyframes ?? [];
    validateProjectKeyframes(keyframes, animationIndex, element.uuid);
    const channels = indexProjectChannels(keyframes);
    const transforms: ImportedTransformKeyframe[] = [];
    for (let tick = 0; tick <= durationTicks; tick++) {
      const time = tick / 20;
      const positionOffset = evaluateProjectChannel(channels.position, time, [0, 0, 0], animationIndex, element.uuid);
      const rotationOffset = evaluateProjectChannel(channels.rotation, time, [0, 0, 0], animationIndex, element.uuid);
      const scaleMultiplier = evaluateProjectChannel(channels.scale, time, [1, 1, 1], animationIndex, element.uuid);
      const position = element.position.map((value, axis) => value + positionOffset[axis]);
      const rotation = element.rotation.map((value, axis) => value + rotationOffset[axis]);
      const scale = element.scale.map((value, axis) => value * scaleMultiplier[axis]);
      transforms.push({
        tick,
        matrix: composeProjectMatrix(position, rotation, scale, `${animation.name}/${element.name}/${tick}`),
        interpolation: tick === 0 ? { type: "step" } : { type: "linear", durationTicks: 1 },
      });
    }
    tracks[element.uuid] = { transforms, visibility: [], nbt: [] };
  }
  return {
    id: sanitizeResourcePath(animation.name, `animation_${animationIndex + 1}`),
    name: prettify(animation.name),
    durationTicks,
    loop: animation.loop,
    loopDelayTicks: animation.loop === "loop"
      ? secondsToTicks(projectNumeric(animation.loop_delay || "0", `animations[${animationIndex}].loop_delay`), `${animation.name}.loop_delay`)
      : 0,
    tracks,
    events: { start: [], timeline: [], loop: [], stop: [] },
  };
}

function validateProjectKeyframes(keyframes: AjProjectKeyframe[], animationIndex: number, animatorId: string): void {
  for (const [index, keyframe] of keyframes.entries()) {
    if (!["position", "rotation", "scale"].includes(keyframe.channel)) {
      throw new ConversionError(
        "unsupported_animated_java_channel",
        `Animated Java project uses unsupported channel ${keyframe.channel}.`,
        `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`,
      );
    }
    if (keyframe.interpolation !== "linear" && keyframe.interpolation !== "step") {
      throw new ConversionError(
        "unsupported_animated_java_interpolation",
        `Animated Java project uses unsupported interpolation ${keyframe.interpolation}.`,
        `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`,
      );
    }
    if (keyframe.easing && keyframe.easing !== "linear" && keyframe.easing !== "step") {
      throw new ConversionError(
        "unsupported_animated_java_interpolation",
        `Animated Java project uses unsupported easing ${keyframe.easing}.`,
        `animations[${animationIndex}].animators.${animatorId}.keyframes[${index}]`,
      );
    }
  }
}

function indexProjectChannels(keyframes: AjProjectKeyframe[]): Record<"position" | "rotation" | "scale", ProjectChannelCursor> {
  const channels = {
    position: { frames: [] as AjProjectKeyframe[], nextIndex: 0 },
    rotation: { frames: [] as AjProjectKeyframe[], nextIndex: 0 },
    scale: { frames: [] as AjProjectKeyframe[], nextIndex: 0 },
  };
  for (const keyframe of keyframes) {
    if (keyframe.channel === "position") channels.position.frames.push(keyframe);
    else if (keyframe.channel === "rotation") channels.rotation.frames.push(keyframe);
    else if (keyframe.channel === "scale") channels.scale.frames.push(keyframe);
  }
  for (const cursor of Object.values(channels)) cursor.frames.sort((first, second) => first.time - second.time);
  return channels;
}

function evaluateProjectChannel(
  cursor: ProjectChannelCursor,
  time: number,
  fallback: number[],
  animationIndex: number,
  animatorId: string,
): number[] {
  const frames = cursor.frames;
  if (frames.length === 0) return [...fallback];
  while (cursor.nextIndex < frames.length && frames[cursor.nextIndex].time <= time) cursor.nextIndex++;
  const nextIndex = cursor.nextIndex;
  if (nextIndex === 0) return [...fallback];
  if (nextIndex === frames.length) return projectKeyframeVector(frames[frames.length - 1], animationIndex, animatorId);
  const previous = frames[nextIndex - 1];
  const next = frames[nextIndex];
  const previousValue = projectKeyframeVector(previous, animationIndex, animatorId);
  if (next.interpolation === "step" || next.easing === "step") return previousValue;
  const nextValue = projectKeyframeVector(next, animationIndex, animatorId);
  const progress = (time - previous.time) / (next.time - previous.time);
  return previousValue.map((value, axis) => value + (nextValue[axis] - value) * progress);
}

function projectKeyframeVector(keyframe: AjProjectKeyframe, animationIndex: number, animatorId: string): number[] {
  if (keyframe.data_points.length !== 1) {
    throw new ConversionError(
      "unsupported_animated_java_keyframe",
      "Animated Java project pre/post keyframes are not yet supported.",
      `animations[${animationIndex}].animators.${animatorId}`,
    );
  }
  const point = keyframe.data_points[0];
  if (point.x === undefined || point.y === undefined || point.z === undefined) {
    throw new ConversionError("invalid_animated_java_keyframe", "Animated Java transform keyframe is missing an axis value.", `animations[${animationIndex}].animators.${animatorId}`);
  }
  return [point.x, point.y, point.z].map((value, axis) => projectNumeric(value, `animations[${animationIndex}].animators.${animatorId}.${keyframe.channel}[${axis}]`));
}

function composeProjectMatrix(position: number[], rotation: number[], scale: number[], label: string): Matrix16 {
  return matrix4ToRowMajor(new Matrix4().compose(
    new Vector3(position[0] / 16, position[1] / 16, position[2] / 16),
    new Quaternion().setFromEuler(new Euler(
      MathUtils.degToRad(rotation[0]),
      MathUtils.degToRad(rotation[1]),
      MathUtils.degToRad(rotation[2]),
      "ZYX",
    )),
    new Vector3(scale[0], scale[1], scale[2]),
  ), label);
}

function projectNumeric(value: string | number, path: string): number {
  const parsed = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(parsed)) {
    throw new ConversionError("unsupported_animated_java_molang", `Animated Java expression ${String(value)} is not a numeric constant.`, path);
  }
  return parsed;
}

export function itemArgumentToSnbt(value: string): string {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const components = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((component): [string, string][] => {
    const pair = splitSnbtPair(component, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [[normalizeResourceLocation(pair[0]), pair[1]]];
  }) : [];
  return serializeSnbtCompound([
    ["id", serializeSnbtString(id)],
    ["count", "1"],
    ["components", components.length ? serializeSnbtCompound(components) : undefined],
  ]);
}

export function blockArgumentToSnbt(value: string): string {
  const match = /^([^\[]+)(?:\[(.*)\])?$/.exec(value.trim());
  const id = normalizeResourceLocation(match?.[1] ?? "air");
  const properties = match?.[2] ? splitSnbtTopLevel(match[2]).flatMap((property): [string, string][] => {
    const pair = splitSnbtPair(property, "=");
    if (!pair?.[0] || !pair[1]) return [];
    return [[pair[0], serializeSnbtString(pair[1])]];
  }) : [];
  return serializeSnbtCompound([
    ["Name", serializeSnbtString(id)],
    ["Properties", properties.length ? serializeSnbtCompound(properties) : undefined],
  ]);
}

function prettify(value: string): string {
  const result = value.replaceAll("_", " ").replaceAll("-", " ").trim();
  return result ? result[0].toUpperCase() + result.slice(1) : "Emote";
}
