import { compileConversionAnimation } from "../compiler/animationCompiler";
import { createConversionDocument, type ConversionDocument } from "../domain/conversionDocument";
import type { EmoteAnimation, EmoteMetadata, EmotePlayerBehavior } from "../format/emoteAnimation";
import type { ImportedProject } from "../domain/conversionSeed";

interface FixtureCompileOptions {
  minecraftVersion?: string;
  namespace?: string;
  metadata?: EmoteMetadata;
  player?: EmotePlayerBehavior;
  loop?: EmoteAnimation["settings"]["playback"]["mode"];
  standalone?: boolean;
  cooldown?: string;
  loopDelay?: string;
}

export function compileImportedProject(project: ImportedProject, options: FixtureCompileOptions): EmoteAnimation[] {
  const document = fixtureDocument(project, options);
  return document.animations.map((_, index) => compileConversionAnimation(document, index));
}

export function compileImportedAnimation(project: ImportedProject, options: FixtureCompileOptions, animationIndex: number): EmoteAnimation {
  return compileConversionAnimation(fixtureDocument(project, options), animationIndex);
}

function fixtureDocument(project: ImportedProject, options: FixtureCompileOptions): ConversionDocument {
  const document = createConversionDocument(project, "Test adapter");
  return {
    ...document,
    animations: document.animations.map((animation) => ({
      ...animation,
      output: {
        ...animation.output,
        namespace: options.namespace ?? options.metadata?.name ?? animation.output.namespace,
        displayName: options.metadata?.name ?? animation.output.displayName,
        description: options.metadata?.description ?? animation.output.description,
        additionalMetadata: options.metadata
          ? Object.fromEntries(Object.entries(options.metadata).filter(([key]) => key !== "name" && key !== "description"))
          : animation.output.additionalMetadata,
        player: options.player ?? animation.output.player,
        playbackMode: options.loop ?? "source",
        standalone: options.standalone ?? true,
        cooldown: options.cooldown ?? "0t",
        loopDelay: options.loopDelay ?? animation.output.loopDelay,
      },
    })),
  };
}
