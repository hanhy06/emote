import type { EmoteAnimation } from "../../format/emoteAnimation";
import { requireEmoteAnimation } from "../../format/emoteAnimationRuntime";
import {
  optionalBoolean,
  optionalRecord,
  requireArray,
  requireNumber,
  requireRecord,
  requireString,
  requireStringValue,
  type RuntimeRecord,
} from "../../format/runtimeValue";
import { ConversionError } from "../errors";

const JAVA_INT_MAX = 2_147_483_647;
const LOOP_TYPES = ["once", "loop", "server_sync"] as const;

export interface MigratedSchema1Animation {
  animation: EmoteAnimation;
  minecraftVersion: string;
}

export function migrateSchema1Animation(value: unknown): MigratedSchema1Animation {
  const root = requireRecord(value, "animation");
  requireExactNumber(root.schema_version, 1, "schema_version");
  requireExactNumber(root.tick_rate, 20, "tick_rate");
  const minecraftVersion = requireString(root.minecraft_version, "minecraft_version");
  if (!minecraftVersion.trim()) throw new ConversionError("invalid_input", "minecraft_version must not be empty.", "minecraft_version");

  const transformSpace = requireRecord(root.transform_space, "transform_space");
  requireStringValue(transformSpace.coordinate_space, ["root_local"] as const, "transform_space.coordinate_space");
  requireStringValue(transformSpace.matrix_layout, ["row_major"] as const, "transform_space.matrix_layout");
  requireExactNumber(transformSpace.matrix_size, 16, "transform_space.matrix_size");

  const timeline = requireRecord(root.timeline, "timeline");
  const loop = requireStringValue(timeline.loop, LOOP_TYPES, "timeline.loop");
  const events = optionalRecord(timeline.events, "timeline.events");
  const migrated = requireEmoteAnimation({
    type: "animation",
    schema_version: 3,
    id: root.id,
    metadata: root.metadata,
    settings: {
      standalone: optionalBoolean(root.standalone, "standalone") ?? true,
      cooldown: "0t",
      player: root.player,
      playback: {
        mode: loop,
        loop_delay: formatLegacyTicks(timeline.loop_delay_ticks, "timeline.loop_delay_ticks"),
      },
    },
    nodes: migrateLegacyNodes(root.nodes),
    timeline: {
      duration: formatLegacyTicks(timeline.duration_ticks, "timeline.duration_ticks"),
      keyframes: requireArray(timeline.keyframes, "timeline.keyframes").map(migrateKeyframe),
      ...(events ? { events: migrateEvents(events) } : {}),
    },
  });
  return { animation: migrated, minecraftVersion };
}

export function migrateLegacyNodes(value: unknown): RuntimeRecord {
  const nodes = requireRecord(value, "nodes");
  return Object.fromEntries(Object.entries(nodes).map(([nodeId, nodeValue]) => {
    const node = requireRecord(nodeValue, `nodes.${nodeId}`);
    const skin = optionalRecord(node.skin, `nodes.${nodeId}.skin`);
    return [nodeId, {
      ...node,
      space: skin ? "initiator" : "scene",
      ...(skin ? { skin: { ...skin, participant: "initiator" } } : {}),
    }];
  }));
}

function migrateKeyframe(value: unknown, index: number): RuntimeRecord {
  const path = `timeline.keyframes[${index}]`;
  const keyframe = requireRecord(value, path);
  const transforms = optionalRecord(keyframe.node_transforms, `${path}.node_transforms`);
  return {
    time: formatLegacyTicks(keyframe.tick, `${path}.tick`),
    ...(keyframe.interpolation_duration_ticks === undefined ? {} : {
      interpolation_duration: formatLegacyTicks(
        keyframe.interpolation_duration_ticks,
        `${path}.interpolation_duration_ticks`,
      ),
    }),
    ...(transforms ? {
      node_transforms: Object.fromEntries(Object.entries(transforms).map(([nodeId, transformValue]) => {
        const transformPath = `${path}.node_transforms.${nodeId}`;
        const transform = requireRecord(transformValue, transformPath);
        return [nodeId, {
          matrix: transform.matrix,
          ...(transform.interpolation_duration_ticks === undefined ? {} : {
            interpolation_duration: formatLegacyTicks(
              transform.interpolation_duration_ticks,
              `${transformPath}.interpolation_duration_ticks`,
            ),
          }),
        }];
      })),
    } : {}),
    ...(keyframe.node_states === undefined ? {} : { node_states: keyframe.node_states }),
  };
}

function migrateEvents(events: RuntimeRecord): RuntimeRecord {
  return {
    ...(events.start === undefined ? {} : { start: events.start }),
    ...(events.timeline === undefined ? {} : {
      timeline: requireArray(events.timeline, "timeline.events.timeline").map((eventValue, index) => {
        const path = `timeline.events.timeline[${index}]`;
        const event = requireRecord(eventValue, path);
        const { tick, ...unchanged } = event;
        return { ...unchanged, time: formatLegacyTicks(tick, `${path}.tick`) };
      }),
    }),
    ...(events.loop === undefined ? {} : { loop: events.loop }),
    ...(events.stop === undefined ? {} : { stop: events.stop }),
  };
}

function formatLegacyTicks(value: unknown, path: string): string {
  const ticks = requireNumber(value, path);
  if (!Number.isInteger(ticks) || ticks < 0 || ticks > JAVA_INT_MAX) {
    throw new ConversionError("invalid_input", `${path} must be a non-negative Java integer.`, path);
  }
  return `${ticks}t`;
}

function requireExactNumber(value: unknown, expected: number, path: string): void {
  if (requireNumber(value, path) !== expected) {
    throw new ConversionError("invalid_input", `${path} must be ${expected}.`, path);
  }
}
