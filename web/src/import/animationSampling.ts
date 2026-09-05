import { Matrix4, Quaternion, Vector3 } from "three";
import { TICKS_PER_SECOND } from "../format/time";

export interface AnimationAnchor {
  time: number;
  priority: number;
  step: boolean;
}

export interface AnimationSamplePlan {
  sourceTimes: Map<number, number>;
  stepTicks: Set<number>;
}

interface PlannedAnchor {
  anchor: AnimationAnchor;
  tick: number;
}

interface SamplePlanState {
  lastTick: number;
  preservedPriority: number;
  preservedCount: number;
  error: number;
  previous?: SamplePlanState;
  assignment?: PlannedAnchor;
}

export function planAnimationAnchorSamples(
  anchors: readonly AnimationAnchor[],
  durationTicks: number,
  boneCount: number,
  snapshotAt: (time: number) => Matrix4[],
): AnimationSamplePlan {
  if (anchors.length === 0) return { sourceTimes: new Map(), stepTicks: new Set() };

  let states = new Map<number, SamplePlanState>([[-1, {
    lastTick: -1,
    preservedPriority: 0,
    preservedCount: 0,
    error: 0,
  }]]);
  for (const anchor of anchors) {
    const scaledTime = anchor.time * TICKS_PER_SECOND;
    const candidateTicks = [...new Set([Math.floor(scaledTime), Math.ceil(scaledTime)])]
      .filter((tick) => tick >= 0 && tick <= durationTicks);
    const candidateCosts = new Map(candidateTicks.map((tick) => [
      tick,
      sampleCandidateError(anchor.time, anchor.step, tick, durationTicks, boneCount, snapshotAt)
        + Math.abs(tick / TICKS_PER_SECOND - anchor.time) * 1e-6,
    ]));
    const nextStates = new Map<number, SamplePlanState>();
    for (const state of states.values()) {
      retainBetterPlan(nextStates, state);
      for (const tick of candidateTicks) {
        if (tick <= state.lastTick) continue;
        retainBetterPlan(nextStates, {
          lastTick: tick,
          preservedPriority: state.preservedPriority + anchor.priority,
          preservedCount: state.preservedCount + 1,
          error: state.error + (candidateCosts.get(tick) ?? 0),
          previous: state,
          assignment: { anchor, tick },
        });
      }
    }
    states = nextStates;
  }

  const best = [...states.values()].reduce((current, candidate) => isBetterPlan(candidate, current) ? candidate : current);
  const sourceTimes = new Map<number, number>();
  const stepTicks = new Set<number>();
  for (let state: SamplePlanState | undefined = best; state?.assignment; state = state.previous) {
    const { anchor, tick } = state.assignment;
    sourceTimes.set(tick, anchor.time);
    if (anchor.step) stepTicks.add(tick);
  }
  return { sourceTimes, stepTicks };
}

function retainBetterPlan(states: Map<number, SamplePlanState>, candidate: SamplePlanState): void {
  const current = states.get(candidate.lastTick);
  if (!current || isBetterPlan(candidate, current)) states.set(candidate.lastTick, candidate);
}

function isBetterPlan(candidate: SamplePlanState, current: SamplePlanState): boolean {
  if (candidate.preservedPriority !== current.preservedPriority) return candidate.preservedPriority > current.preservedPriority;
  if (candidate.preservedCount !== current.preservedCount) return candidate.preservedCount > current.preservedCount;
  return candidate.error < current.error;
}

function sampleCandidateError(
  sourceTime: number,
  step: boolean,
  tick: number,
  durationTicks: number,
  boneCount: number,
  snapshotAt: (time: number) => Matrix4[],
): number {
  const anchorSnapshot = snapshotAt(sourceTime);
  let error = 0;
  for (const intervalTick of [tick - 1, tick]) {
    if (intervalTick < 0 || intervalTick >= durationTicks) continue;
    const first = intervalTick === tick ? anchorSnapshot : snapshotAt(intervalTick / TICKS_PER_SECOND);
    const second = intervalTick + 1 === tick ? anchorSnapshot : snapshotAt((intervalTick + 1) / TICKS_PER_SECOND);
    for (const alpha of [0.2, 0.4, 0.6, 0.8]) {
      const exact = snapshotAt((intervalTick + alpha) / TICKS_PER_SECOND);
      for (let boneIndex = 0; boneIndex < boneCount; boneIndex++) {
        const rendered = step && intervalTick + 1 === tick
          ? first[boneIndex]
          : interpolateTransformation(first[boneIndex], second[boneIndex], alpha);
        error += transformationError(rendered, exact[boneIndex]);
      }
    }
  }
  return error;
}

function interpolateTransformation(first: Matrix4, second: Matrix4, alpha: number): Matrix4 {
  const firstPosition = new Vector3();
  const firstRotation = new Quaternion();
  const firstScale = new Vector3();
  const secondPosition = new Vector3();
  const secondRotation = new Quaternion();
  const secondScale = new Vector3();
  first.decompose(firstPosition, firstRotation, firstScale);
  second.decompose(secondPosition, secondRotation, secondScale);
  return new Matrix4().compose(
    firstPosition.lerp(secondPosition, alpha),
    firstRotation.slerp(secondRotation, alpha),
    firstScale.lerp(secondScale, alpha),
  );
}

function transformationError(actual: Matrix4, expected: Matrix4): number {
  const actualPosition = new Vector3();
  const actualRotation = new Quaternion();
  const actualScale = new Vector3();
  const expectedPosition = new Vector3();
  const expectedRotation = new Quaternion();
  const expectedScale = new Vector3();
  actual.decompose(actualPosition, actualRotation, actualScale);
  expected.decompose(expectedPosition, expectedRotation, expectedScale);
  const rotationAngle = 2 * Math.acos(Math.min(1, Math.abs(actualRotation.dot(expectedRotation))));
  return actualPosition.distanceToSquared(expectedPosition) * 16
    + rotationAngle * rotationAngle
    + actualScale.distanceToSquared(expectedScale);
}
