import { describe, expect, it } from "vitest";
import {
  findMatchingSnbtDelimiter,
  omitSnbtFields,
  parseSnbtCompound,
  readSnbtCompoundField,
  readSnbtStringField,
  serializeSnbtCompound,
  serializeSnbtString,
  splitSnbtPair,
  splitSnbtTopLevel,
} from "./snbt";

describe("SNBT utilities", () => {
  it("reads nested compounds without splitting quoted or nested commas", () => {
    const compound = '{id:"minecraft:paper",components:{"minecraft:custom_name":\'{"text":"a,b"}\'},Tags:["one","two"]}';

    expect(parseSnbtCompound(compound).map((field) => field.name)).toEqual(["id", "components", "Tags"]);
    expect(readSnbtStringField(compound, "id")).toBe("minecraft:paper");
    expect(readSnbtCompoundField(compound, "components")).toBe('{"minecraft:custom_name":\'{"text":"a,b"}\'}');
    expect(omitSnbtFields(compound, new Set(["Tags"]))).toBe('{id:"minecraft:paper",components:{"minecraft:custom_name":\'{"text":"a,b"}\'}}');
  });

  it("serializes quoted keys and escaped string values", () => {
    expect(serializeSnbtCompound([
      ["id", serializeSnbtString("minecraft:paper")],
      ["minecraft:custom_name", serializeSnbtString('a "name"')],
    ])).toBe('{id:"minecraft:paper","minecraft:custom_name":"a \\"name\\""}');
  });

  it("finds and splits only top-level SNBT delimiters", () => {
    expect(splitSnbtTopLevel('first={value:"a,b"},second=[1,2]')).toEqual(['first={value:"a,b"}', "second=[1,2]"]);
    expect(splitSnbtPair('component={value:"a=b"}', "=")).toEqual(["component", '{value:"a=b"}']);
    expect(findMatchingSnbtDelimiter('prefix{value:"}"}suffix', 6, "{", "}")).toBe(16);
  });
});
