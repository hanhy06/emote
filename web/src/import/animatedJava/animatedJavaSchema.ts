import {
  optionalArray,
  optionalBoolean,
  optionalNumber,
  optionalRecord,
  optionalString,
  requireNumber,
  requireNumberArray,
  requireRecord,
  requireString,
  requireStringArray,
  requireStringValue,
} from "../../format/runtimeValue";

export interface AjBlueprint {
  format_version: number;
  settings: { id: string };
  textures?: Record<string, AjTexture>;
  texture_palettes?: Record<string, { active_state: string; states: Record<string, { texture: string }> }>;
  nodes?: Record<string, AjNode>;
  animations?: Record<string, AjAnimation>;
}

export type AjTexture =
  | { type: "custom"; base64_string: string; mime_type?: string; animation?: AjTextureAnimation }
  | { type: "reference"; resource_location: string };

export interface AjTextureAnimation {
  width?: number;
  height?: number;
  frametime?: number;
  interpolate?: boolean;
  frames?: (number | { index: number; time: number })[];
}

export interface AjNode {
  type: "bone" | "item_display" | "block_display" | "text_display" | "structure" | "camera" | "locator";
  default_transformation?: { matrix?: number[] };
  display_properties?: Record<string, unknown>;
  elements?: AjElement[];
}

export interface AjElement {
  from: number[];
  to: number[];
  rotation: number[] | {
    angle: number;
    axis: "x" | "y" | "z";
    origin: number[];
  } | {
    x: number;
    y: number;
    z: number;
    origin: number[];
    rescale?: boolean;
  };
  shade?: boolean;
  light_emission?: number;
  faces: Record<string, {
    uv: number[];
    texture_provider: { type: "texture"; texture: string } | { type: "texture_palette"; texture_palette: string };
    tintindex?: number;
    rotation?: number;
  }>;
}

export interface AjAnimation {
  loop_mode: { type: "once" | "hold" | "loop"; loop_delay?: string };
  blend_weight?: string;
  start_delay?: string;
  length: number;
  global_keyframes?: {
    texture?: Record<string, Record<string, string>>;
    event?: Record<string, { events: string[] }>;
  };
  node_keyframes?: Record<string, AjNodeChannels>;
}

export interface AjNodeChannels {
  position?: Record<string, AjKeyframe>;
  rotation?: Record<string, AjKeyframe>;
  scale?: Record<string, AjKeyframe>;
}

export interface AjKeyframe {
  value: string[];
  post?: string[];
  interpolation:
    | { type: "linear"; easing: string; easing_arguments?: number[] }
    | { type: "step" }
    | {
      type: "bezier";
      left_handle_time: number[];
      left_handle_value: number[];
      right_handle_time: number[];
      right_handle_value: number[];
    }
    | { type: "catmullrom" };
}

export function requireAjBlueprint(value: unknown): AjBlueprint {
  const root = requireRecord(value, "Animated Java blueprint");
  requireNumber(root.format_version, "format_version");
  const settings = requireRecord(root.settings, "settings");
  requireString(settings.id, "settings.id");

  const textures = optionalRecord(root.textures, "textures");
  for (const [id, textureValue] of Object.entries(textures ?? {})) {
    const path = `textures.${id}`;
    const texture = requireRecord(textureValue, path);
    const type = requireStringValue(texture.type, ["custom", "reference"] as const, `${path}.type`);
    if (type === "custom") {
      requireString(texture.base64_string, `${path}.base64_string`);
      optionalString(texture.mime_type, `${path}.mime_type`);
      const animation = optionalRecord(texture.animation, `${path}.animation`);
      if (animation) {
        for (const dimension of ["width", "height"] as const) {
          const value = optionalNumber(animation[dimension], `${path}.animation.${dimension}`);
          if (value !== undefined && (!Number.isInteger(value) || value < 1)) {
            throw new Error(`${path}.animation.${dimension} must be a positive integer.`);
          }
        }
        const frameTime = optionalNumber(animation.frametime, `${path}.animation.frametime`);
        if (frameTime !== undefined && (!Number.isInteger(frameTime) || frameTime < 1)) {
          throw new Error(`${path}.animation.frametime must be a positive integer.`);
        }
        optionalBoolean(animation.interpolate, `${path}.animation.interpolate`);
        for (const [index, frameValue] of (optionalArray(animation.frames, `${path}.animation.frames`) ?? []).entries()) {
          if (typeof frameValue === "number") {
            if (!Number.isInteger(frameValue) || frameValue < 0) throw new Error(`${path}.animation.frames[${index}] must be a non-negative integer.`);
            continue;
          }
          const frame = requireRecord(frameValue, `${path}.animation.frames[${index}]`);
          const frameIndex = requireNumber(frame.index, `${path}.animation.frames[${index}].index`);
          const frameDuration = requireNumber(frame.time, `${path}.animation.frames[${index}].time`);
          if (!Number.isInteger(frameIndex) || frameIndex < 0) throw new Error(`${path}.animation.frames[${index}].index must be a non-negative integer.`);
          if (!Number.isInteger(frameDuration) || frameDuration < 1) throw new Error(`${path}.animation.frames[${index}].time must be a positive integer.`);
        }
      }
    } else {
      requireString(texture.resource_location, `${path}.resource_location`);
    }
  }

  const palettes = optionalRecord(root.texture_palettes, "texture_palettes");
  for (const [id, paletteValue] of Object.entries(palettes ?? {})) {
    const path = `texture_palettes.${id}`;
    const palette = requireRecord(paletteValue, path);
    requireString(palette.active_state, `${path}.active_state`);
    const states = requireRecord(palette.states, `${path}.states`);
    for (const [stateId, stateValue] of Object.entries(states)) {
      const state = requireRecord(stateValue, `${path}.states.${stateId}`);
      requireString(state.texture, `${path}.states.${stateId}.texture`);
    }
  }

  const nodes = optionalRecord(root.nodes, "nodes");
  for (const [id, nodeValue] of Object.entries(nodes ?? {})) requireAjNode(nodeValue, `nodes.${id}`);
  const animations = optionalRecord(root.animations, "animations");
  for (const [id, animationValue] of Object.entries(animations ?? {})) requireAjAnimation(animationValue, `animations.${id}`);
  return value as AjBlueprint;
}

function requireAjNode(value: unknown, path: string): void {
  const node = requireRecord(value, path);
  requireStringValue(node.type, ["bone", "item_display", "block_display", "text_display", "structure", "camera", "locator"] as const, `${path}.type`);
  const defaultTransformation = optionalRecord(node.default_transformation, `${path}.default_transformation`);
  if (defaultTransformation?.matrix !== undefined) requireNumberArray(defaultTransformation.matrix, `${path}.default_transformation.matrix`);
  const displayProperties = optionalRecord(node.display_properties, `${path}.display_properties`);
  if (displayProperties) optionalBoolean(displayProperties.is_enchanted, `${path}.display_properties.is_enchanted`);
  for (const [index, elementValue] of (optionalArray(node.elements, `${path}.elements`) ?? []).entries()) {
    const elementPath = `${path}.elements[${index}]`;
    const element = requireRecord(elementValue, elementPath);
    requireVector3(element.from, `${elementPath}.from`);
    requireVector3(element.to, `${elementPath}.to`);
    requireElementRotation(element.rotation, `${elementPath}.rotation`);
    const faces = requireRecord(element.faces, `${elementPath}.faces`);
    for (const [direction, faceValue] of Object.entries(faces)) {
      const facePath = `${elementPath}.faces.${direction}`;
      const face = requireRecord(faceValue, facePath);
      const uv = requireNumberArray(face.uv, `${facePath}.uv`);
      if (uv.length !== 4) throw new Error(`${facePath}.uv must contain four numbers.`);
      const provider = requireRecord(face.texture_provider, `${facePath}.texture_provider`);
      const providerType = requireStringValue(provider.type, ["texture", "texture_palette"] as const, `${facePath}.texture_provider.type`);
      const property = providerType === "texture" ? "texture" : "texture_palette";
      requireString(provider[property], `${facePath}.texture_provider.${property}`);
    }
  }
}

function requireElementRotation(value: unknown, path: string): void {
  if (Array.isArray(value)) {
    const rotation = requireNumberArray(value, path);
    if (rotation.length !== 3) throw new Error(`${path} must contain three numbers.`);
    return;
  }
  const rotation = requireRecord(value, path);
  const origin = requireNumberArray(rotation.origin, `${path}.origin`);
  if (origin.length !== 3) throw new Error(`${path}.origin must contain three numbers.`);
  if (typeof rotation.axis === "string") {
    requireStringValue(rotation.axis, ["x", "y", "z"] as const, `${path}.axis`);
    requireNumber(rotation.angle, `${path}.angle`);
    return;
  }
  requireNumber(rotation.x, `${path}.x`);
  requireNumber(rotation.y, `${path}.y`);
  requireNumber(rotation.z, `${path}.z`);
  optionalBoolean(rotation.rescale, `${path}.rescale`);
}

function requireVector3(value: unknown, path: string): void {
  const vector = requireNumberArray(value, path);
  if (vector.length !== 3) throw new Error(`${path} must contain three numbers.`);
}

function requireAjAnimation(value: unknown, path: string): void {
  const animation = requireRecord(value, path);
  const loopMode = requireRecord(animation.loop_mode, `${path}.loop_mode`);
  requireStringValue(loopMode.type, ["once", "hold", "loop"] as const, `${path}.loop_mode.type`);
  optionalString(loopMode.loop_delay, `${path}.loop_mode.loop_delay`);
  optionalString(animation.blend_weight, `${path}.blend_weight`);
  optionalString(animation.start_delay, `${path}.start_delay`);
  requireNumber(animation.length, `${path}.length`);
  const global = optionalRecord(animation.global_keyframes, `${path}.global_keyframes`);
  if (global) {
    const texture = optionalRecord(global.texture, `${path}.global_keyframes.texture`);
    for (const [time, frameValue] of Object.entries(texture ?? {})) {
      requireKeyframeTime(time, `${path}.global_keyframes.texture.${time}`);
      const frame = requireRecord(frameValue, `${path}.global_keyframes.texture.${time}`);
      for (const [palette, state] of Object.entries(frame)) {
        requireString(state, `${path}.global_keyframes.texture.${time}.${palette}`);
      }
    }
    const event = optionalRecord(global.event, `${path}.global_keyframes.event`);
    for (const [time, frameValue] of Object.entries(event ?? {})) {
      requireKeyframeTime(time, `${path}.global_keyframes.event.${time}`);
      const frame = requireRecord(frameValue, `${path}.global_keyframes.event.${time}`);
      requireStringArray(frame.events, `${path}.global_keyframes.event.${time}.events`).forEach((name, index) => {
        if (!/^[a-z0-9_]+$/.test(name)) throw new Error(`${path}.global_keyframes.event.${time}.events[${index}] has an invalid event name.`);
      });
    }
  }
  const nodeKeyframes = optionalRecord(animation.node_keyframes, `${path}.node_keyframes`);
  for (const [nodeId, channelsValue] of Object.entries(nodeKeyframes ?? {})) {
    const channels = requireRecord(channelsValue, `${path}.node_keyframes.${nodeId}`);
    for (const channelName of ["position", "rotation", "scale"] as const) {
      const channel = optionalRecord(channels[channelName], `${path}.node_keyframes.${nodeId}.${channelName}`);
      for (const [time, keyframeValue] of Object.entries(channel ?? {})) {
        const keyframePath = `${path}.node_keyframes.${nodeId}.${channelName}.${time}`;
        const keyframe = requireRecord(keyframeValue, keyframePath);
        requireStringArray(keyframe.value, `${keyframePath}.value`);
        if (keyframe.post !== undefined) requireStringArray(keyframe.post, `${keyframePath}.post`);
        const interpolation = requireRecord(keyframe.interpolation, `${keyframePath}.interpolation`);
        const type = requireStringValue(interpolation.type, ["linear", "step", "bezier", "catmullrom"] as const, `${keyframePath}.interpolation.type`);
        if (type === "linear") {
          requireString(interpolation.easing, `${keyframePath}.interpolation.easing`);
          if (interpolation.easing_arguments !== undefined) requireNumberArray(interpolation.easing_arguments, `${keyframePath}.interpolation.easing_arguments`);
        } else if (type === "bezier") {
          requireNumberArray(interpolation.left_handle_time, `${keyframePath}.interpolation.left_handle_time`);
          requireNumberArray(interpolation.left_handle_value, `${keyframePath}.interpolation.left_handle_value`);
          requireNumberArray(interpolation.right_handle_time, `${keyframePath}.interpolation.right_handle_time`);
          requireNumberArray(interpolation.right_handle_value, `${keyframePath}.interpolation.right_handle_value`);
        }
      }
    }
  }
}

function requireKeyframeTime(value: string, path: string): void {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0) throw new Error(`${path} has an invalid keyframe time.`);
}
