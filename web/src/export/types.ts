import type { EmoteAnimation, EmotePlayerBehavior } from "../format/emoteAnimation";

export interface ExportOptions {
  minecraftVersion: string;
  namespace: string;
  playbackMode: "source" | EmoteAnimation["settings"]["playback"]["mode"];
  name: string;
  description: string;
  player: EmotePlayerBehavior;
  additionalMetadata: Record<string, unknown>;
  standalone?: boolean;
  cooldown?: string;
  loopDelay?: string;
}

export interface ExportResult {
  blob: Blob;
  fileName: string;
}
