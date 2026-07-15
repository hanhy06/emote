import type { EmoteAnimation } from "./emoteAnimation";
import { validateEmoteAnimation } from "./validator";

export function serializeEmoteAnimation(animation: EmoteAnimation): string {
  const issues = validateEmoteAnimation(animation);
  if (issues.length > 0) {
    const first = issues[0];
    throw new Error(`Invalid emote animation at ${first.path}: ${first.message}`);
  }
  return `${JSON.stringify(animation, null, 2)}\n`;
}
