import { readInput } from "../formats";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { createConversionDocument } from "../../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../../compiler/animationCompiler";
import { createPreviewModel } from "../../preview/previewModel";

const REPOSITORY_ROOT = fileURLToPath(new URL("../../../../", import.meta.url));

describe("GeckoLib animation pipeline", () => {
  it("preserves runtime Molang in IR and export independently from preview", async () => {
    const path = "docs/reference/bbmodel/emote.bbmodel";
    const project = await readInput("geckolib_bbmodel", { name: "emote.bbmodel", bytes: await readFile(resolve(REPOSITORY_ROOT, path)) });
    const animation = project.animations.find((candidate) => candidate.name === "indicate");

    expect(animation).toBeDefined();
    expect(animation).not.toHaveProperty("preview");
    const document = createConversionDocument(project, "GeckoLib");
    const index = project.animations.indexOf(animation!);
    const output = compileConversionAnimationArtifact(document, index).animation;
    const snapshot = JSON.stringify(output);
    const later = createPreviewModel(document, document.animations[index], 11);
    expect(later.availability?.status).toBe("approximate");
    expect(later.tick).toBe(10);
    expect(later.parts.length).toBeGreaterThan(0);
    expect(later.parts.every((part) => part.matrix.every(Number.isFinite))).toBe(true);
    expect(output.animation.tracks).toEqual(animation!.ir!.animation.tracks);
    expect(JSON.stringify(output.animation.tracks)).toMatch(/q\.(?:loop_count|target_[xy]_rotation)/);
    expect(JSON.stringify(compileConversionAnimationArtifact(document, index).animation)).toBe(snapshot);
  });
});
