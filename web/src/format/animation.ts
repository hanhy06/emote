import type { AnimationIR } from "../domain/animationIR";
import { EMOTE_SCHEMA_VERSION } from "./emote";

export interface AnimationJson extends AnimationIR {
  type: "animation";
  schema_version: typeof EMOTE_SCHEMA_VERSION;
}

export function serializeAnimation(animation: AnimationJson): string {
  return `${JSON.stringify(animation, null, 2)}\n`;
}
