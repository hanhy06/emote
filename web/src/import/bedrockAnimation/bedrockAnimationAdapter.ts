import type { ImportedProject } from "../../domain/conversionSeed";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { parseInputJsonc } from "../inputCache";
import { importBedrockAnimationDocument } from "./bedrockAnimationImporter";
import { isBedrockAnimationDocument, requireBedrockAnimationDocument } from "./bedrockAnimationSchema";

export const bedrockAnimationAdapter: ImportAdapter<ImportedProject> = {
  id: "bedrock_animation_json",
  label: "Bedrock player animation JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    try {
      return isBedrockAnimationDocument(parseInputJsonc(input))
        ? { confidence: 100, reason: "matches a Bedrock 1.8.0 animation document" }
        : { confidence: 0, reason: "not a Bedrock 1.8.0 animation document" };
    } catch {
      return { confidence: 0, reason: "not JSONC" };
    }
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    return importBedrockAnimationDocument(requireBedrockAnimationDocument(parseInputJsonc(input)), input.name);
  },
};
