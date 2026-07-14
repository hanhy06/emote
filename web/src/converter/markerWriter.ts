import { isSkinPartId, markerFor, type PartAssignments, type PartOrders } from "./skinMapping";

const ITEM_DISPLAY_PATTERN = /\{id:"minecraft:item_display",item:\{(.*?)\},.*?Tags:\[[^\]]*?"([a-z0-9_.-]+)_(\d+)"[^\]]*?\]\}/gs;

export function applySkinMarkers(
  createFunctionText: string,
  namespace: string,
  assignments: PartAssignments,
  orders: PartOrders = {},
): string {
  const chunks: string[] = [];
  let lastIndex = 0;

  for (const match of createFunctionText.matchAll(ITEM_DISPLAY_PATTERN)) {
    const partIndex = Number.parseInt(match[3], 10);
    const assignment = assignments[partIndex];
    if (match[2] !== namespace
      || !match[1].includes('id:"minecraft:player_head"')
      || !Object.hasOwn(assignments, partIndex)) {
      continue;
    }

    chunks.push(createFunctionText.slice(lastIndex, match.index));
    chunks.push(assignment ? injectProfileName(match[0], markerFor(assignment, orders[partIndex])) : removeProfileMarker(match[0]));
    lastIndex = match.index + match[0].length;
  }

  chunks.push(createFunctionText.slice(lastIndex));
  return chunks.join("");
}

function removeProfileMarker(itemDisplayText: string): string {
  const profileKey = '"minecraft:profile":{';
  const profileIndex = itemDisplayText.indexOf(profileKey);
  if (profileIndex < 0) return itemDisplayText;
  const startIndex = profileIndex + profileKey.length - 1;
  const endIndex = findMatchingBrace(itemDisplayText, startIndex);
  const fields = splitTopLevelFields(itemDisplayText.slice(startIndex + 1, endIndex));
  const filteredFields = fields.filter((field) => !/^\s*name\s*:\s*"emote:[a-z_]+"/.test(field));
  if (filteredFields.length === fields.length) return itemDisplayText;
  return itemDisplayText.slice(0, startIndex + 1) + filteredFields.join(",") + itemDisplayText.slice(endIndex);
}

export function injectProfileName(itemDisplayText: string, markerName: string): string {
  const markerMatch = /^emote:([a-z_]+)(?::(\d+))?$/.exec(markerName);
  if (!markerMatch || !isSkinPartId(markerMatch[1])) {
    throw new Error(`지원하지 않는 스킨 부위 마커입니다: ${markerName}`);
  }

  const profileKey = '"minecraft:profile":{';
  const profileIndex = itemDisplayText.indexOf(profileKey);
  if (profileIndex >= 0) {
    const startIndex = profileIndex + profileKey.length - 1;
    const endIndex = findMatchingBrace(itemDisplayText, startIndex);
    const fields = splitTopLevelFields(itemDisplayText.slice(startIndex + 1, endIndex));
    const filteredFields = fields.filter((field) => !field.trimStart().startsWith("name:"));
    const profileBody = [`name:"${markerName}"`, ...filteredFields].join(",");
    return itemDisplayText.slice(0, startIndex + 1) + profileBody + itemDisplayText.slice(endIndex);
  }

  const componentsKey = "components:{";
  const componentsIndex = itemDisplayText.indexOf(componentsKey);
  if (componentsIndex >= 0) {
    const startIndex = componentsIndex + componentsKey.length - 1;
    const endIndex = findMatchingBrace(itemDisplayText, startIndex);
    const componentsBody = itemDisplayText.slice(startIndex + 1, endIndex);
    const profile = `"minecraft:profile":{name:"${markerName}"}`;
    const newBody = componentsBody.trim() ? `${profile},${componentsBody}` : profile;
    return itemDisplayText.slice(0, startIndex + 1) + newBody + itemDisplayText.slice(endIndex);
  }

  const itemKey = "item:{";
  const itemIndex = itemDisplayText.indexOf(itemKey);
  if (itemIndex < 0) {
    throw new Error("item_display의 item 데이터를 찾지 못했습니다.");
  }
  const startIndex = itemIndex + itemKey.length - 1;
  const endIndex = findMatchingBrace(itemDisplayText, startIndex);
  const itemBody = itemDisplayText.slice(startIndex + 1, endIndex);
  const separator = itemBody.trim() ? "," : "";
  return itemDisplayText.slice(0, startIndex + 1)
    + itemBody
    + `${separator}components:{"minecraft:profile":{name:"${markerName}"}}`
    + itemDisplayText.slice(endIndex);
}

function findMatchingBrace(text: string, startIndex: number): number {
  let depth = 0;
  let inString = false;
  let escaped = false;

  for (let index = startIndex; index < text.length; index++) {
    const character = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') {
      inString = true;
    } else if (character === "{") {
      depth++;
    } else if (character === "}" && --depth === 0) {
      return index;
    }
  }
  throw new Error("닫히지 않은 NBT 객체가 있습니다.");
}

function splitTopLevelFields(text: string): string[] {
  const fields: string[] = [];
  let fieldStart = 0;
  let objectDepth = 0;
  let arrayDepth = 0;
  let inString = false;
  let escaped = false;

  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === '"') {
        inString = false;
      }
      continue;
    }
    if (character === '"') inString = true;
    else if (character === "{") objectDepth++;
    else if (character === "}") objectDepth--;
    else if (character === "[") arrayDepth++;
    else if (character === "]") arrayDepth--;
    else if (character === "," && objectDepth === 0 && arrayDepth === 0) {
      fields.push(text.slice(fieldStart, index));
      fieldStart = index + 1;
    }
  }
  if (fieldStart < text.length) {
    fields.push(text.slice(fieldStart));
  }
  return fields.filter((field) => field.trim().length > 0);
}
