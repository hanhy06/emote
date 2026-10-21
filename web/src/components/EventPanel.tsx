import { useState } from "preact/hooks";
import type { EmoteCallback } from "../domain/emoteDefinition";
import type { EventIR } from "../domain/animationIR";
import type { ConversionAnimationEvents } from "../domain/conversionDocument";
import { parseMinecraftTime } from "../format/time";
import { requireArray, requireNumberArray, requireRecord, requireString } from "../format/runtimeValue";
import { isResourceLocation } from "../format/resourceLocation";

interface EventPanelProps {
  events: ConversionAnimationEvents;
  callbacks?: EmoteCallback[];
  tick: number | null;
  disabled: boolean;
  onLifecycleChange: (events: Pick<ConversionAnimationEvents, "start" | "loop" | "stop"> & { callbacks: EmoteCallback[] }) => void;
  onTimelineChange: (tick: number, events: EventIR[]) => void;
  onValidityChange: (valid: boolean) => void;
}

export function EventPanel({ events, callbacks, tick, disabled, onLifecycleChange, onTimelineChange, onValidityChange }: EventPanelProps) {
  const [error, setError] = useState("");
  const initialValue = tick === null
    ? JSON.stringify({ callbacks: callbacks ?? [], start: events.start, loop: events.loop, stop: events.stop }, null, 2)
    : JSON.stringify(events.timeline.flatMap((event) => {
      if (parseMinecraftTime(event.time) !== tick) return [];
      const { time: _time, ...body } = event;
      return [body];
    }), null, 2);
  function handleInput(value: string) {
    try {
      const parsed: unknown = JSON.parse(value);
      if (tick !== null) onTimelineChange(tick, parseEventArray(parsed, "Timeline events", true));
      else {
        const lifecycle = requireRecord(parsed, "Lifecycle events");
        const callbacks = lifecycle.callbacks === undefined ? [] : requireArray(lifecycle.callbacks, "callbacks").map((value, index) => {
          const path = `callbacks[${index}]`;
          const callback = requireRecord(value, path);
          const name = requireString(callback.name, `${path}.name`);
          if (!isResourceLocation(name)) throw new Error(`${path}.name must be a resource location.`);
          if (callback.payload !== undefined) requireString(callback.payload, `${path}.payload`);
          return callback as unknown as EmoteCallback;
        });
        onLifecycleChange({ callbacks,
          start: lifecycle.start === undefined ? [] : parseEventArray(lifecycle.start, "start"),
          loop: lifecycle.loop === undefined ? [] : parseEventArray(lifecycle.loop, "loop"),
          stop: lifecycle.stop === undefined ? [] : parseEventArray(lifecycle.stop, "stop"),
        });
      }
      setError("");
      onValidityChange(true);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Invalid JSON.");
      onValidityChange(false);
    }
  }

  return (
    <section className="event-editor" aria-labelledby="event-editor-heading">
      <div className="event-editor-heading">
        <div>
          <h3 id="event-editor-heading">Events</h3>
          <p>{tick === null
            ? "Edit callbacks and start, loop, and stop events as JSON. Changes are saved as soon as the JSON is valid."
            : "Edit the events at this tick as a JSON array. The selected tick supplies the event time automatically. Changes are saved as soon as the JSON is valid."}</p>
        </div>
        <span className="event-scope">{tick === null ? "Create pose · Lifecycle" : `Tick ${tick} · Timeline`}</span>
      </div>
      <div className="event-editor-body">
        <textarea className="event-json" defaultValue={initialValue} disabled={disabled} spellcheck={false}
          aria-label={tick === null ? "Lifecycle events JSON" : `Timeline events JSON at tick ${tick}`} aria-invalid={error ? true : undefined}
          onInput={(event) => handleInput(event.currentTarget.value)} />
        {error && <p className="event-json-error" role="alert">{error} Fix the JSON or press Ctrl+Z before leaving this event scope.</p>}
      </div>
    </section>
  );
}

function parseEventArray(value: unknown, path: string, timeline = false): EventIR[] {
  return requireArray(value, path).map((value, index) => {
    const eventPath = `${path}[${index}]`;
    const event = requireRecord(value, eventPath);
    if ("time" in event || "tick" in event) throw new Error(`${eventPath} must not contain time or tick; the selected frame supplies the time.`);
    if ("direction" in event && (!timeline || !["forward", "backward", "both"].includes(requireString(event.direction, `${eventPath}.direction`)))) {
      throw new Error(`${eventPath}.direction must be forward, backward, or both, and is only allowed for timeline events.`);
    }
    const source = requireRecord(event.source, `${eventPath}.source`);
    const sourceType = requireString(source.type, `${eventPath}.source.type`);
    if (!["player", "server", "node"].includes(sourceType)) throw new Error(`${eventPath}.source.type must be player, server, or node.`);
    if (sourceType === "node") {
      if (!requireString(source.node, `${eventPath}.source.node`)) throw new Error(`${eventPath}.source.node must not be empty.`);
      if (!requireString(source.attachment, `${eventPath}.source.attachment`)) throw new Error(`${eventPath}.source.attachment must not be empty.`);
    }
    const origin = requireRecord(event.origin, `${eventPath}.origin`);
    const originType = requireString(origin.type, `${eventPath}.origin.type`);
    if (!["root", "node"].includes(originType)) throw new Error(`${eventPath}.origin.type must be root or node.`);
    if (originType === "node" && !requireString(origin.node, `${eventPath}.origin.node`)) throw new Error(`${eventPath}.origin.node must not be empty.`);
    if (origin.offset !== undefined && requireNumberArray(origin.offset, `${eventPath}.origin.offset`).length !== 3) {
      throw new Error(`${eventPath}.origin.offset must contain three numbers.`);
    }
    const action = requireRecord(event.action, `${eventPath}.action`);
    if (action.type === "commands") {
      requireArray(action.commands, `${eventPath}.action.commands`).forEach((command, index) => requireString(command, `${eventPath}.action.commands[${index}]`));
    } else if (action.type === "external") {
      if (!isResourceLocation(requireString(action.key, `${eventPath}.action.key`))) throw new Error(`${eventPath}.action.key must be a resource location.`);
      if (!("data" in action)) throw new Error(`${eventPath}.action.data is required.`);
    } else throw new Error(`${eventPath}.action.type must be commands or external.`);
    return event as unknown as EventIR;
  });
}
