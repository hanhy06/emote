import { readInput } from "../import/formats";
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { expect, it } from "vitest";
import { createConversionDocument, assignDocumentSkinPart } from "../domain/conversionDocument";
import { compileConversionAnimationArtifact } from "../compiler/animationCompiler";
import { generatedResourceFiles } from "../export/generatedResources";

const fingerprints: Record<string, string> = {
  emote: "2b7bb336f18701dc9b7d5d9bd171e363f4785aa6e97e1c61f0818bd471f0ed9a",
  hug: "1a9cc7220ce60ca6edc6f83c1d608afce62d851066c583a25cb1eac895de0e5d",
  handshake: "31e1728847cc878f2a8b932ec4755ed2aa8a73bff73e6f38f72e06ea7ed7e688",
};

function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical((value as Record<string, unknown>)[key])]));
  return value;
}

it.each(["emote", "hug", "handshake"])("preserves the existing %s bbmodel output and exported resources", async (name) => {
  const bytes = await readFile(new URL(`../../../docs/reference/bbmodel/${name}.bbmodel`, import.meta.url));
  const imported = await readInput("geckolib_bbmodel", { name: `${name}.bbmodel`, bytes });
  const document = createConversionDocument(imported, "GeckoLib");
  const references = new Set<string>();
  const restored = assignDocumentSkinPart(document, new Set(Object.keys(document.skinCandidates)), null);
  for (let index = 0; index < restored.animations.length; index++) for (const reference of compileConversionAnimationArtifact(restored, index).generatedResourceReferences) references.add(reference);
  const animations = imported.animations.map((_, index) => {
    const artifact = compileConversionAnimationArtifact(document, index);
    for (const reference of artifact.generatedResourceReferences) references.add(reference);
    return artifact.animation;
  });
  const output = canonical({ animations, resources: Object.fromEntries(generatedResourceFiles(document, document.targetMinecraftVersion, references)) });
  const fingerprint = createHash("sha256").update(JSON.stringify(output)).digest("hex");
  expect(fingerprint).toBe(fingerprints[name]);
}, 60_000);
