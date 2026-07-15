import { describe, expect, it } from "vitest";
import {
  isResourceLocation,
  normalizeResourceLocation,
  parseResourceLocation,
  sanitizeNamespace,
  sanitizeResourcePath,
} from "./resourceLocation";

describe("resource location utilities", () => {
  it("parses and recognizes Minecraft resource locations", () => {
    expect(parseResourceLocation("emote:wave/left", "animation id")).toEqual({ namespace: "emote", path: "wave/left" });
    expect(isResourceLocation("emote:wave/left")).toBe(true);
    expect(isResourceLocation("Emote:Wave")).toBe(false);
  });

  it("normalizes default namespaces and sanitizes resource components", () => {
    expect(normalizeResourceLocation(" Player_Head ")).toBe("minecraft:player_head");
    expect(normalizeResourceLocation("Custom:Thing")).toBe("custom:thing");
    expect(sanitizeNamespace("My Emote/Pack")).toBe("my_emote_pack");
    expect(sanitizeResourcePath("Wave Left/Arm")).toBe("wave_left/arm");
    expect(sanitizeResourcePath("!", "default")).toBe("default");
  });
});
