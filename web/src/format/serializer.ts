import type { EmoteAnimation } from "./emoteAnimation";
import { ConversionError } from "../foundation/diagnostics";
import { validateEmoteAnimation } from "./validator";

export function serializeEmoteAnimation(animation: EmoteAnimation): string {
  const issues = validateEmoteAnimation(animation);
  if (issues.length > 0) {
    const first = issues[0];
    throw new ConversionError("invalid_emote_animation", `Invalid emote animation at ${first.path}: ${first.message}`, first.path);
  }
  return JSON.stringify(animation);
}
