import {
  optionalArray,
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
  | { type: "custom"; base64_string: string; mime_type?: string }
  | { type: "reference"; resource_location: string };

export interface AjNode {
  type: "bone" | "item_display" | "block_display" | "text_display" | "structure" | "camera" | "locator";
  default_transformation?: { matrix?: number[] };
  display_properties?: Record<string, unknown>;
  elements?: AjElement[];
}

export interface AjElement {
  from: number[];
  to: number[];
  rotation: unknown;
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
  global_keyframes?: { texture?: Record<string, unknown>; event?: Record<string, unknown> };
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
  interpolation: { type: "linear"; easing: string } | { type: "step" } | { type: "bezier" | "catmullrom" };
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
  optionalRecord(node.display_properties, `${path}.display_properties`);
  for (const [index, elementValue] of (optionalArray(node.elements, `${path}.elements`) ?? []).entries()) {
    const elementPath = `${path}.elements[${index}]`;
    const element = requireRecord(elementValue, elementPath);
    requireNumberArray(element.from, `${elementPath}.from`);
    requireNumberArray(element.to, `${elementPath}.to`);
    const faces = requireRecord(element.faces, `${elementPath}.faces`);
    for (const [direction, faceValue] of Object.entries(faces)) {
      const facePath = `${elementPath}.faces.${direction}`;
      const face = requireRecord(faceValue, facePath);
      requireNumberArray(face.uv, `${facePath}.uv`);
      const provider = requireRecord(face.texture_provider, `${facePath}.texture_provider`);
      const providerType = requireStringValue(provider.type, ["texture", "texture_palette"] as const, `${facePath}.texture_provider.type`);
      const property = providerType === "texture" ? "texture" : "texture_palette";
      requireString(provider[property], `${facePath}.texture_provider.${property}`);
    }
  }
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
    optionalRecord(global.texture, `${path}.global_keyframes.texture`);
    optionalRecord(global.event, `${path}.global_keyframes.event`);
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
        if (type === "linear") requireString(interpolation.easing, `${keyframePath}.interpolation.easing`);
      }
    }
  }
}
