export interface EmoteCallback {
  name: string;
  payload?: string;
}

export interface EmoteMetadata {
  name: string;
  description: string;
  [key: string]: unknown;
}

export interface EmotePlayerBehavior {
  hidden: boolean;
  stop_conditions: {
    movement_distance: number;
    jump: boolean;
    submerge: boolean;
    ride: boolean;
    damage: boolean;
    attack: boolean;
    game_mode_change: boolean;
  };
}

export function createDefaultPlayerBehavior(): EmotePlayerBehavior {
  return {
    hidden: true,
    stop_conditions: {
      movement_distance: 0.3,
      jump: true,
      submerge: true,
      ride: true,
      damage: true,
      attack: true,
      game_mode_change: true,
    },
  };
}

export interface ImportedSequence {
  callbacks?: EmoteCallback[];
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

export function isSequenceControlId(id: string): boolean {
  return id === "emote:break" || id === "emote:continue" || id === "emote:empty";
}

export function animationReferenceTargets(animations: readonly { id: string; sourceReferenceId?: string }[]): Map<string, string[]> {
  const references = new Map<string, string[]>();
  for (const animation of animations) {
    if (!animation.sourceReferenceId) continue;
    const targets = references.get(animation.sourceReferenceId);
    if (targets) targets.push(animation.id);
    else references.set(animation.sourceReferenceId, [animation.id]);
  }
  for (const animation of animations) references.set(animation.id, [animation.id]);
  return references;
}
