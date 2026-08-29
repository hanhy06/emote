import type { ImportedProject } from "../../domain/conversionSeed";
import type { ImportAdapter, ImportInput, ProbeResult } from "../adapter";
import { decodeLatestEmotecraft, probeLatestEmotecraft } from "./emotecraftBinary";
import { importEmotecraftFile } from "./emotecraftImporter";

export const emotecraftAdapter: ImportAdapter<ImportedProject> = {
  id: "emotecraft_binary",
  label: "Emotecraft binary",
  extensions: ["emotecraft"],

  probe(input: ImportInput): ProbeResult {
    return probeLatestEmotecraft(input.bytes)
      ? { confidence: 100, reason: "matches the latest Emotecraft v8/v6 binary format" }
      : { confidence: 0, reason: "not the latest Emotecraft v8/v6 binary format" };
  },

  async import(input: ImportInput): Promise<ImportedProject> {
    return importEmotecraftFile(decodeLatestEmotecraft(input.bytes), input.name);
  },
};
