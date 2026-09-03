export type GeneratedResource =
  | Uint8Array
  | { kind: "cuboid_model"; textures: Record<string, string>; elements: Record<string, unknown>[] }
  | { kind: "item_model"; model: string }
  | { kind: "json"; value: unknown };
