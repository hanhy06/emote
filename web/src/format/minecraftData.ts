import type { BlockStateData, DisplayNbtPatch, DisplayNbtValue, ItemStackData, RawNbtField } from "../domain/minecraftData";
import type { EmoteNbtValue } from "./emoteAnimation";
import type { MinecraftVersionProfile } from "./minecraftVersionProfiles";
import { parseSnbtCompound, readSnbtStringField, serializeSnbtCompound, serializeSnbtString } from "./snbt";

export function readBlockState(value: string): BlockStateData;
export function readBlockState(value: string, partial: true): Partial<BlockStateData>;
export function readBlockState(value: string, partial = false): Partial<BlockStateData> {
  if (!value.trim().startsWith("{")) return { id: snbtString(value) };
  const result: Partial<BlockStateData> = {};
  const extraFields: RawNbtField[] = [];
  for (const field of parseSnbtCompound(value)) {
    if (field.name === "id" || field.name === "Name") result.id = snbtString(field.value);
    else if (field.name === "properties" || field.name === "Properties") {
      result.properties = Object.fromEntries(parseSnbtCompound(field.value).map((property) => [property.name, snbtString(property.value)]));
    } else extraFields.push({ name: field.name, value: field.value });
  }
  if (!partial && result.id === undefined) throw new Error("Block state does not contain an id.");
  if (extraFields.length) result.extraFields = extraFields;
  return result;
}

export function writeBlockState(value: Partial<BlockStateData>, profile: MinecraftVersionProfile): string {
  return serializeSnbtCompound([
    [profile.blockState.idKey, value.id === undefined ? undefined : serializeSnbtString(value.id)],
    [profile.blockState.propertiesKey, value.properties === undefined ? undefined : serializeSnbtCompound(
      Object.entries(value.properties).map(([name, property]) => [name, serializeSnbtString(property)]),
    )],
    ...(value.extraFields ?? []).map(({ name, value }) => [name, value] as const),
  ]);
}

export function readItemStack(value: string): ItemStackData;
export function readItemStack(value: string, partial: true): Partial<ItemStackData>;
export function readItemStack(value: string, partial = false): Partial<ItemStackData> {
  const result: Partial<ItemStackData> = {};
  const extraFields: RawNbtField[] = [];
  for (const field of parseSnbtCompound(value)) {
    if (field.name === "id") result.id = snbtString(field.value);
    else if (field.name === "count" || field.name === "Count") result.count = Number(field.value.replace(/[bBsSlL]$/, ""));
    else if (field.name === "components") result.components = parseSnbtCompound(field.value).map(({ name, value }) => ({ name, value }));
    else extraFields.push({ name: field.name, value: field.value });
  }
  if (!partial && result.id === undefined) throw new Error("Item stack does not contain an id.");
  if (extraFields.length) result.extraFields = extraFields;
  return result;
}

export function writeItemStack(value: Partial<ItemStackData>, profile: MinecraftVersionProfile): string {
  return serializeSnbtCompound([
    [profile.itemStack.idKey, value.id === undefined ? undefined : serializeSnbtString(value.id)],
    [profile.itemStack.countKey, value.count === undefined ? undefined : String(value.count)],
    [profile.itemStack.componentsKey, value.components === undefined ? undefined : serializeSnbtCompound(value.components.map(({ name, value }) => [name, value]))],
    ...(value.extraFields ?? []).map(({ name, value }) => [name, value] as const),
  ]);
}

export function readDisplayNbt(value: string): DisplayNbtPatch {
  const result: DisplayNbtPatch = { rawFields: [] };
  for (const field of parseSnbtCompound(value)) {
    if (field.name === "block_state") result.blockState = readBlockState(field.value, true);
    else if (field.name === "item") result.itemStack = readItemStack(field.value, true);
    else result.rawFields.push({ name: field.name, value: field.value });
  }
  return result;
}

export function writeDisplayNbt(value: DisplayNbtPatch, profile: MinecraftVersionProfile): string {
  return serializeSnbtCompound([
    ...(value.rawFields ?? []).map(({ name, value }) => [name, value] as const),
    ["block_state", value.blockState === undefined ? undefined : writeBlockState(value.blockState, profile)],
    ["item", value.itemStack === undefined ? undefined : writeItemStack(value.itemStack, profile)],
  ]);
}

export function readDisplayNbtValue(value: EmoteNbtValue): DisplayNbtValue {
  return typeof value === "string" ? readDisplayNbt(value) : { select: value.select, options: value.options.map(readDisplayNbt) };
}

function snbtString(value: string): string {
  const decoded = readSnbtStringField(serializeSnbtCompound([["value", value]]), "value");
  return decoded ?? value.trim();
}
