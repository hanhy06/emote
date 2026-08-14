type EasingFunction = (progress: number) => number;

const easeOut = (easing: EasingFunction): EasingFunction => (progress) => 1 - easing(1 - progress);
const easeInOut = (easing: EasingFunction): EasingFunction => (progress) => progress < 0.5
  ? easing(progress * 2) / 2
  : 1 - easing((1 - progress) * 2) / 2;
const power = (exponent: number): EasingFunction => (progress) => Math.pow(progress, exponent);

const sine: EasingFunction = (progress) => 1 - Math.cos(progress * Math.PI / 2);
const quadratic: EasingFunction = power(2);
const cubic: EasingFunction = power(3);
const quartic: EasingFunction = power(4);
const quintic: EasingFunction = power(5);
const exponential: EasingFunction = (progress) => Math.pow(2, 10 * (progress - 1));
const circular: EasingFunction = (progress) => 1 - Math.sqrt(1 - progress * progress);
const back: EasingFunction = (progress) => progress * progress * ((1.70158 + 1) * progress - 1.70158);
const elastic: EasingFunction = (progress) => 1 - Math.pow(Math.cos(progress * Math.PI / 2), 3) * Math.cos(progress * Math.PI);
const bounce: EasingFunction = (progress) => {
  const bounciness = 0.5;
  const one = 121 / 16 * progress * progress;
  const two = 121 / 4 * bounciness * Math.pow(progress - 6 / 11, 2) + 1 - bounciness;
  const three = 121 * bounciness * bounciness * Math.pow(progress - 9 / 11, 2) + 1 - bounciness * bounciness;
  const four = 484 * Math.pow(bounciness, 3) * Math.pow(progress - 10.5 / 11, 2) + 1 - Math.pow(bounciness, 3);
  return Math.min(one, two, three, four);
};
const step: EasingFunction = (progress) => progress > 0.5 ? 0.5 : 0;

const EASINGS: Readonly<Record<string, EasingFunction>> = {
  linear: (progress) => progress,
  none: (progress) => progress,
  step,
  easeinsine: sine,
  easeoutsine: easeOut(sine),
  easeinoutsine: easeInOut(sine),
  easeinquad: quadratic,
  easeoutquad: easeOut(quadratic),
  easeinoutquad: easeInOut(quadratic),
  easeincubic: cubic,
  easeoutcubic: easeOut(cubic),
  easeinoutcubic: easeInOut(cubic),
  easeinquart: quartic,
  easeoutquart: easeOut(quartic),
  easeinoutquart: easeInOut(quartic),
  easeinquint: quintic,
  easeoutquint: easeOut(quintic),
  easeinoutquint: easeInOut(quintic),
  easeinexpo: exponential,
  easeoutexpo: easeOut(exponential),
  easeinoutexpo: easeInOut(exponential),
  easeincirc: circular,
  easeoutcirc: easeOut(circular),
  easeinoutcirc: easeInOut(circular),
  easeinback: back,
  easeoutback: easeOut(back),
  easeinoutback: easeInOut(back),
  easeinelastic: elastic,
  easeoutelastic: easeOut(elastic),
  easeinoutelastic: easeInOut(elastic),
  easeinbounce: bounce,
  easeoutbounce: easeOut(bounce),
  easeinoutbounce: easeInOut(bounce),
};

export function cubeEasingProgress(name: string, progress: number): number | undefined {
  return EASINGS[name.toLowerCase()]?.(progress);
}

export const SUPPORTED_GECKOLIB_EASINGS = Object.freeze(Object.keys(EASINGS));
