import type { ImportAdapter, ImportedSequenceSource, ImportInput, ProbeResult } from "../adapter";
import { parseInputJson, probeParsedInput } from "../inputCache";
import { isRecord } from "../../format/runtimeValue";
import { convertSequenceInput } from "./sequenceJsonConverter";

export const sequenceJsonAdapter: ImportAdapter<ImportedSequenceSource> = {
  id: "emote_sequence",
  label: "Emote sequence JSON",
  extensions: ["json"],

  probe(input: ImportInput): ProbeResult {
    return probeParsedInput(input, parseInputJson, (value) => isRecord(value) && value.type === "sequence"
        ? { confidence: 100, reason: "matches an Emote sequence" }
        : { confidence: 0, reason: "not an Emote sequence" }, "not JSON");
  },

  async import(input: ImportInput) {
    const sequence = convertSequenceInput(input);
    if (!sequence) throw new Error("Input is not an Emote sequence.");
    return { kind: "sequence" as const, sequence, fileName: input.name };
  },
};
