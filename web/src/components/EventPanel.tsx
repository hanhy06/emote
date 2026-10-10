import { useState } from "preact/hooks";
import type { EmoteCallback } from "../domain/emoteDefinition";
import type { EventIR } from "../domain/animationIR";
import type { ConversionAnimationEvents } from "../domain/conversionDocument";
import { parseMinecraftTime } from "../format/time";

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
  const [draft, setDraft] = useState(initialValue);

  function handleInput(value: string) {
    try {
      const parsed: unknown = JSON.parse(value);
      if (tick !== null) onTimelineChange(tick, parsed as EventIR[]);
      else {
        const { callbacks: rawCallbacks, ...rawEvents } = parsed as Record<string, unknown>;
        const { start, loop, stop } = rawEvents as ConversionAnimationEvents;
        onLifecycleChange({ callbacks: rawCallbacks as EmoteCallback[], start, loop, stop });
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
            ? "Edit callbacks and start, loop, and stop events as JSON. Changes are saved when you leave the input and the JSON is valid."
            : "Edit the events at this tick as a JSON array. The selected tick supplies the event time automatically. Changes are saved when you leave the input and the JSON is valid."}</p>
        </div>
        <span className="event-scope">{tick === null ? "Create pose · Lifecycle" : `Tick ${tick} · Timeline`}</span>
      </div>
      <div className="event-editor-body">
        <textarea className="event-json" value={draft} disabled={disabled} spellcheck={false}
          aria-label={tick === null ? "Lifecycle events JSON" : `Timeline events JSON at tick ${tick}`} aria-invalid={error ? true : undefined}
          onInput={(event) => setDraft(event.currentTarget.value)} onBlur={(event) => handleInput(event.currentTarget.value)} />
        {error && <p className="event-json-error" role="alert">{error} Fix the JSON or press Ctrl+Z before leaving this event scope.</p>}
      </div>
    </section>
  );
}
