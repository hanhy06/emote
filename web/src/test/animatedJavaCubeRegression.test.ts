import { readInput } from "../import/formats";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { expect, it } from "vitest";
import { createConversionDocument, assignDocumentSkinPart } from "../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { generatedResourceFiles } from "../export/generatedResources";

const fingerprints: Record<string, string> = {
  emote: "17d5b4e14763147bd16b9a4110839034555f3ddc7b57159190bf05bc1ad5469e",
  sit: "5fc33e80977fbf79a49da6e308a4d284a4325a21d9e66152d08501ad5a639ff7",
  music: "8c68506c6c13b8bbe27b6a694ded86f4c61e25fcc540c8b4a8fa8110a8bed346",
};

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical((value as Record<string, unknown>)[key])]));
  return value;
}

it.each(["emote", "sit", "music"])("preserves the existing %s AJ cube output and exported resources", async (name) => {
  const bytes = await readFile(new URL(`../../../docs/reference/aj/${name}.ajblueprint`, import.meta.url));
  const imported = await readInput("animated_java_blueprint", { name: `${name}.ajblueprint`, bytes });
  const document = createConversionDocument(imported, "Animated Java");
  const references = new Set<string>();
  const restored = assignDocumentSkinPart(document, new Set(Object.keys(document.skinCandidates)), null);
  for (let index = 0; index < restored.animations.length; index++) for (const reference of compileConversionAnimationArtifact(restored, index).generatedResourceReferences) references.add(reference);
  const actual = canonical({
    animations: imported.animations.map((animation, index) => {
      const artifact = compileConversionAnimationArtifact(document, index);
      for (const reference of artifact.generatedResourceReferences) references.add(reference);
      return { id: animation.id, name: animation.name, output: artifact.animation };
    }),
    resources: Object.fromEntries(generatedResourceFiles(document, document.targetMinecraftVersion, references)),
  });
  const json = JSON.stringify(actual);
  const fingerprint = createHash("sha256").update(json).digest("hex");
  expect(fingerprint).toBe(fingerprints[name]);
}, 60_000);

it("preserves source-only models referenced by the existing AJ model's default and variant NBT", async () => {
  const source = JSON.parse(await readFile(new URL("../../../docs/reference/aj/emote.ajblueprint", import.meta.url), "utf8"));
  source.animations = [source.animations.find((animation: any) => animation.name === "anvil")];
  const element = source.elements.find((value: any) => value.name === "anvil_display");
  const nbt = (model: string) => `{item:{id:"minecraft:paper",count:1,components:{"minecraft:item_model":"emote:emote/${model}"}}}`;
  element.type = "animated_java:vanilla_item_display";
  element.item = "minecraft:paper";
  element.config = { use_nbt: true, nbt: nbt("head_hat_layer") };
  element.configs = { variants: { resource_check: { use_nbt: true, nbt: nbt("body_body_layer") } } };
  source.variants.list = [{ uuid: "resource_check", name: "resource_check", excluded_nodes: [], texture_map: {} }];
  source.animations[0].animators.effects = { type: "effect", keyframes: [
    ...(source.animations[0].animators.effects?.keyframes ?? []),
    { channel: "variant", time: 0.25, data_points: [{ variant: "resource_check" }] },
  ] };
  const imported = await readInput("animated_java_blueprint", { name: "emote.ajblueprint", bytes: new TextEncoder().encode(JSON.stringify(source)) });
  const document = createConversionDocument(imported, "Animated Java");
  const artifact = compileConversionAnimationArtifact(document, 0);
  const refs = ["assets/emote/items/emote/head_hat_layer.json", "assets/emote/items/emote/body_body_layer.json"];
  for (const ref of refs) {
    expect(imported.resources.has(ref)).toBe(true);
    expect(artifact.generatedResourceReferences.has(ref)).toBe(true);
  }
  const resources = Object.fromEntries(generatedResourceFiles(document, document.targetMinecraftVersion, artifact.generatedResourceReferences));
  const hash = createHash("sha256").update(JSON.stringify(canonical({ animation: artifact.animation, resources }))).digest("hex");
  expect(hash).toBe("cbc7cc790e1a42e779b3d62c176d4956fd91da74618a0bb3a59b12bc81789922");
});
