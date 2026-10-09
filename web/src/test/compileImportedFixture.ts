import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { createConversionDocument, type ConversionDocument } from "../domain/conversionDocument";
import type { EmoteMetadata, EmotePlayerBehavior } from "../domain/emoteDefinition";
import type { AnimationJson } from "../format/animation";
import type { ClipIR } from "../domain/animationIR";
import type { ImportedProject } from "../domain/conversionSeed";
import { parseAnimationSeconds } from "../format/time";
import { sanitizeNamespace } from "../format/resourceLocation";

interface FixtureCompileOptions {
  minecraftVersion?: string;
  namespace?: string;
  metadata?: EmoteMetadata;
  player?: EmotePlayerBehavior;
  playbackMode?: NonNullable<ClipIR["playback"]>["mode"];
  standalone?: boolean;
  cooldown?: string;
  loopStart?: string;
  loopDelay?: string;
  rotationDeadzoneByAnimation?: Readonly<Record<string, number>>;
}

export function compileImportedProject(project: ImportedProject, options: FixtureCompileOptions): AnimationJson[] {
  const document = fixtureDocument(project, options);
  return document.animations.map((_, index) => compileConversionAnimationArtifact(document, index).animation);
}

export function compileImportedAnimation(project: ImportedProject, options: FixtureCompileOptions, animationIndex: number): AnimationJson {
  return compileConversionAnimationArtifact(fixtureDocument(project, options), animationIndex).animation;
}

function fixtureDocument(project: ImportedProject, options: FixtureCompileOptions): ConversionDocument {
  const document = createConversionDocument(project, "Test adapter");
  return {
    ...document,
    animations: document.animations.map((animation) => ({
      ...animation,
      id: `${sanitizeNamespace(options.namespace ?? options.metadata?.name ?? animation.id.split(":")[0])}:${animation.id.split(":")[1]}`,
      metadata: options.metadata ?? animation.metadata,
      settings: { ...animation.settings, player: options.player ?? animation.settings?.player,
        standalone: options.standalone ?? true, cooldown: parseAnimationSeconds(options.cooldown ?? "0t"),
        rotation_deadzone: options.rotationDeadzoneByAnimation?.[animation.sourceName] ?? animation.settings?.rotation_deadzone },
      clip: { ...animation.clip, playback: { ...animation.clip.playback,
        mode: options.playbackMode ?? animation.clip.playback?.mode,
        ...(options.loopStart === undefined ? {} : { loop_start: parseAnimationSeconds(options.loopStart) }),
        ...(options.loopDelay === undefined ? {} : { loop_delay: parseAnimationSeconds(options.loopDelay) }),
      } },
    })),
  };
}
