import { useState } from "preact/hooks";
import type { EmoteEvent } from "../format/emoteAnimation";
import type { ConversionAnimationEvents } from "../domain/conversionDocument";

interface LifecycleEvents {
  start: EmoteEvent[];
  loop: EmoteEvent[];
  stop: EmoteEvent[];
}

interface EventPanelProps {
  events: ConversionAnimationEvents;
  tick: number | null;
  disabled: boolean;
  onLifecycleChange: (events: LifecycleEvents) => void;
  onTimelineChange: (tick: number, events: EmoteEvent[]) => void;
  onValidityChange: (valid: boolean) => void;
}

export function EventPanel({ events, tick, disabled, onLifecycleChange, onTimelineChange, onValidityChange }: EventPanelProps) {
  const [error, setError] = useState("");
  const initialValue = tick === null
    ? JSON.stringify({
      start: events.start,
      loop: events.loop,
      stop: events.stop,
    }, null, 2)
    : JSON.stringify(events.timeline.flatMap((event) => {
      if (event.tick !== tick) return [];
      const { tick: _tick, ...body } = event;
      return [body];
    }), null, 2);

  function handleInput(value: string) {
    try {
      const parsed: unknown = JSON.parse(value);
      if (tick === null) onLifecycleChange(parseLifecycleEvents(parsed));
      else onTimelineChange(tick, parseEventArray(parsed, "Timeline events", true));
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
            ? "Edit start, loop, and stop events as raw JSON. Changes are saved as soon as the JSON is valid."
            : "Edit the events at this tick as a JSON array. The selected tick supplies the event time automatically."}</p>
        </div>
        <span className="event-scope">{tick === null ? "Create pose · Lifecycle" : `Tick ${tick} · Timeline`}</span>
      </div>
      <div className="event-editor-body">
        <textarea
          className="event-json"
          defaultValue={initialValue}
          disabled={disabled}
          spellcheck={false}
          aria-label={tick === null ? "Lifecycle events JSON" : `Timeline events JSON at tick ${tick}`}
          aria-invalid={error ? true : undefined}
          onInput={(event) => handleInput(event.currentTarget.value)}
        />
        {error && <p className="event-json-error" role="alert">{error} Fix the JSON or press Ctrl+Z before leaving this event scope.</p>}
      </div>
    </section>
  );
}

function parseLifecycleEvents(value: unknown): LifecycleEvents {
  if (!isRecord(value)) throw new Error("Lifecycle events must be a JSON object.");
  return {
    start: value.start === undefined ? [] : parseEventArray(value.start, "start"),
    loop: value.loop === undefined ? [] : parseEventArray(value.loop, "loop"),
    stop: value.stop === undefined ? [] : parseEventArray(value.stop, "stop"),
  };
}

function parseEventArray(value: unknown, path: string, timeline = false): EmoteEvent[] {
  if (!Array.isArray(value)) throw new Error(`${path} must be a JSON array.`);
  return value.map((event, index) => parseEvent(event, `${path}[${index}]`, timeline));
}

function parseEvent(value: unknown, path: string, timeline: boolean): EmoteEvent {
  if (!isRecord(value)) throw new Error(`${path} must be a JSON object.`);
  if (timeline && ("time" in value || "tick" in value)) throw new Error(`${path} must not contain time or tick; the selected preview tick supplies it.`);
  requireSource(value.source, `${path}.source`);
  requireOrigin(value.origin, `${path}.origin`);
  if (!Array.isArray(value.commands) || value.commands.some((command) => typeof command !== "string")) {
    throw new Error(`${path}.commands must be an array of strings.`);
  }
  if (value.callbacks !== undefined) requireCallbacks(value.callbacks, `${path}.callbacks`);
  return value as unknown as EmoteEvent;
}

function requireSource(value: unknown, path: string): void {
  if (!isRecord(value) || (value.type !== "player" && value.type !== "server" && value.type !== "node")) {
    throw new Error(`${path} must contain a player, server, or node source.`);
  }
  if (value.type === "node" && typeof value.node !== "string") throw new Error(`${path}.node must be a string.`);
}

function requireOrigin(value: unknown, path: string): void {
  if (!isRecord(value) || (value.type !== "root" && value.type !== "node")) {
    throw new Error(`${path} must contain a root or node origin.`);
  }
  if (value.type === "node" && typeof value.node !== "string") throw new Error(`${path}.node must be a string.`);
  if (value.offset !== undefined && (!Array.isArray(value.offset) || value.offset.length !== 3 || value.offset.some((item) => typeof item !== "number"))) {
    throw new Error(`${path}.offset must be an array of three numbers.`);
  }
}

function requireCallbacks(value: unknown, path: string): void {
  if (!Array.isArray(value)) throw new Error(`${path} must be a JSON array.`);
  value.forEach((callback, index) => {
    const callbackPath = `${path}[${index}]`;
    if (!isRecord(callback) || typeof callback.name !== "string") throw new Error(`${callbackPath}.name must be a string.`);
    if (callback.payload !== undefined && typeof callback.payload !== "string") throw new Error(`${callbackPath}.payload must be a string.`);
  });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}
