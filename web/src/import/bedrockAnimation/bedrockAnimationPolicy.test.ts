import { describe, expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import { importBedrockAnimationDocument } from "./bedrockAnimationImporter";
import { createConversionDocument } from "../../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../../compiler/animationCompiler";
import { createPreviewModel } from "../../preview/previewModel";

describe("Bedrock animation Molang policy", () => {
  it("converts the AJ sample's sound and particle timing into Bedrock command events with warnings", async () => {
    const source = JSON.parse(await readFile(new URL("../../../../docs/reference/aj/emote.ajblueprint", import.meta.url), "utf8"));
    const frames = Object.values(source.animations.find((animation: any) => animation.name === "anvil").animators)
      .flatMap((animator: any) => animator.keyframes ?? []).filter((frame: any) => frame.channel === "function") as any[];
    const particle = frames.find((frame) => frame.data_points[0].function.startsWith("particle "));
    const sound = frames.find((frame) => frame.data_points[0].function.startsWith("playsound "));
    const project = importBedrockAnimationDocument({ format_version: "1.8.0", animations: { anvil: {
      animation_length: 2, bones: { body: { rotation: [0, 0, 0] } },
      sound_effects: { [sound.time]: { effect: sound.data_points[0].function.split(" ")[1] } },
      particle_effects: { [particle.time]: { effect: particle.data_points[0].function.split(" ")[1], locator: "missing_locator", pre_effect_script: particle.data_points[0].function } },
      timeline: { [particle.time]: [particle.data_points[0].function, { unsupported: true }] },
    } } }, "anvil.animation.json");
    const output = compileConversionAnimationArtifact(createConversionDocument(project, "Bedrock"), 0).animation;
    expect(output.schema_version).toBe(5);
    expect(output.animation.events?.timeline?.map((event) => ({ time: event.time, origin: event.origin, commands: event.action.type === "commands" ? event.action.commands : [] }))).toEqual([
      { time: sound.time, origin: { type: "root" }, commands: [`playsound ${sound.data_points[0].function.split(" ")[1]} master @s ~ ~ ~`] },
      { time: particle.time, origin: { type: "root" }, commands: [`particle ${particle.data_points[0].function.split(" ")[1]} ~ ~ ~`] },
      { time: particle.time, origin: { type: "root" }, commands: [particle.data_points[0].function] },
    ]);
    expect(project.diagnostics.map((issue) => issue.code)).toEqual(expect.arrayContaining(["bedrock_effect_approximated", "bedrock_effect_origin_approximated", "bedrock_particle_script_ignored", "bedrock_instruction_approximated", "bedrock_instruction_ignored"]));
    for (const issue of project.diagnostics) expect(issue.message).not.toContain(particle.data_points[0].function);
  });

  it("preserves unknown Molang for export while preview falls back only for the affected components", () => {
    const project = importBedrockAnimationDocument({
      format_version: "1.8.0",
      animations: {
        wave: {
          animation_length: 1,
          bones: { body: { rotation: ["q.unknown", 0, "q.anim_time * 90"], scale: [1, "q.unknown_scale", 1] } },
        },
      },
    }, "wave.animation.json");

    const animation = project.animations[0];
    expect(animation).not.toHaveProperty("preview");
    const document = createConversionDocument(project, "Bedrock");
    const output = compileConversionAnimationArtifact(document, 0).animation;
    const snapshot = JSON.stringify(output);
    const before = createPreviewModel(document, document.animations[0], 1);
    const preview = createPreviewModel(document, document.animations[0], 11);
    expect(preview.availability?.status).toBe("approximate");
    expect(preview.tick).toBe(10);
    expect(preview.parts.length).toBeGreaterThan(0);
    expect(preview.parts.some((part) => JSON.stringify(part.matrix) !== JSON.stringify(before.parts.find((other) => other.nodeId === part.nodeId)?.matrix))).toBe(true);
    expect(preview.parts.every((part) => part.matrix.every(Number.isFinite))).toBe(true);
    const body = preview.parts.find((part) => document.nodes[part.nodeId].parent === "bone:body")!;
    const initialBody = before.parts.find((part) => part.nodeId === body.nodeId)!;
    for (const axis of [0, 1, 2]) {
      const length = (matrix: readonly number[]) => Math.hypot(matrix[axis], matrix[axis + 4], matrix[axis + 8]);
      expect(length(body.matrix)).toBeCloseTo(length(initialBody.matrix), 9);
    }
    expect(output.schema_version).toBe(5);
    expect(output.animation.tracks).toEqual(animation.ir!.animation.tracks);
    expect(JSON.stringify(output.animation.tracks)).toContain("q.unknown");
    expect(JSON.stringify(compileConversionAnimationArtifact(document, 0).animation)).toBe(snapshot);
  });
});
