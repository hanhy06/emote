import type { EmoteMetadata, EmotePlayerBehavior } from "../format/emoteAnimation";
import type { ImportedAnimation } from "./conversionSeed";

export type EmoteDefinition = ImportedAnimation | ImportedSequence;

export interface ImportedSequence {
  kind: "sequence";
  source: "emote_sequence";
  sourceName: string;
  id: string;
  targetMinecraftVersion?: string;
  metadata: EmoteMetadata;
  cooldown: string;
  player: EmotePlayerBehavior;
  steps: SequenceStep[];
}

export type SequenceStep = SequenceAnimationStep | SequenceWaitStep;

export interface SequenceAnimationStep {
  emote: string | SequenceWeightedChoice[];
  repeat?: number;
  transition?: string;
}

export interface SequenceWeightedChoice {
  id: string;
  chance?: number;
}

export interface SequenceWaitStep {
  wait: string;
}
