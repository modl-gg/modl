import { type ModelDef, cube, part } from './ModelParts.ts';

const HALF_PI = Math.PI / 2;

export function playerModel(slim: boolean): ModelDef {
  const armWidth = slim ? 3 : 4;
  return {
    parts: [
      part('head', [0, 0, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8])], undefined, [
        part('hat', [0, 0, 0], [cube(32, 0, [-4, -8, -4], [8, 8, 8], 0.5)]),
      ]),
      part('body', [0, 0, 0], [cube(16, 16, [-4, 0, -2], [8, 12, 4])], undefined, [
        part('jacket', [0, 0, 0], [cube(16, 32, [-4, 0, -2], [8, 12, 4], 0.25)]),
      ]),
      part('right_arm', [-5, slim ? 2.5 : 2, 0], [cube(40, 16, [slim ? -2 : -3, -2, -2], [armWidth, 12, 4])], undefined, [
        part('right_sleeve', [0, 0, 0], [cube(40, 32, [slim ? -2 : -3, -2, -2], [armWidth, 12, 4], 0.25)]),
      ]),
      part('left_arm', [5, slim ? 2.5 : 2, 0], [cube(32, 48, [-1, -2, -2], [armWidth, 12, 4])], undefined, [
        part('left_sleeve', [0, 0, 0], [cube(48, 48, [-1, -2, -2], [armWidth, 12, 4], 0.25)]),
      ]),
      part('right_leg', [-1.9, 12, 0], [cube(0, 16, [-2, 0, -2], [4, 12, 4])], undefined, [
        part('right_pants', [0, 0, 0], [cube(0, 32, [-2, 0, -2], [4, 12, 4], 0.25)]),
      ]),
      part('left_leg', [1.9, 12, 0], [cube(16, 48, [-2, 0, -2], [4, 12, 4])], undefined, [
        part('left_pants', [0, 0, 0], [cube(0, 48, [-2, 0, -2], [4, 12, 4], 0.25)]),
      ]),
    ],
  };
}

export function legacyPlayerModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 0, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8])], undefined, [
        part('hat', [0, 0, 0], [cube(32, 0, [-4, -8, -4], [8, 8, 8], 0.5)]),
      ]),
      part('body', [0, 0, 0], [cube(16, 16, [-4, 0, -2], [8, 12, 4])]),
      part('right_arm', [-5, 2, 0], [cube(40, 16, [-3, -2, -2], [4, 12, 4])]),
      part('left_arm', [5, 2, 0], [cube(40, 16, [-1, -2, -2], [4, 12, 4], 0, true)]),
      part('right_leg', [-1.9, 12, 0], [cube(0, 16, [-2, 0, -2], [4, 12, 4])]),
      part('left_leg', [1.9, 12, 0], [cube(0, 16, [-2, 0, -2], [4, 12, 4], 0, true)]),
    ],
  };
}

export function humanoidModel(inflate: number, hat: boolean): ModelDef {
  return {
    parts: [
      part('head', [0, 0, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8], inflate)], undefined, hat ? [
        part('hat', [0, 0, 0], [cube(32, 0, [-4, -8, -4], [8, 8, 8], inflate + 0.5)]),
      ] : []),
      part('body', [0, 0, 0], [cube(16, 16, [-4, 0, -2], [8, 12, 4], inflate)]),
      part('right_arm', [-5, 2, 0], [cube(40, 16, [-3, -2, -2], [4, 12, 4], inflate)]),
      part('left_arm', [5, 2, 0], [cube(40, 16, [-1, -2, -2], [4, 12, 4], inflate, true)]),
      part('right_leg', [-1.9, 12, 0], [cube(0, 16, [-2, 0, -2], [4, 12, 4], inflate)]),
      part('left_leg', [1.9, 12, 0], [cube(0, 16, [-2, 0, -2], [4, 12, 4], inflate, true)]),
    ],
  };
}

export function zombieModel(): ModelDef {
  return humanoidModel(0, true);
}

export function skeletonModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 0, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8])], undefined, [
        part('hat', [0, 0, 0], [cube(32, 0, [-4, -8, -4], [8, 8, 8], 0.5)]),
      ]),
      part('body', [0, 0, 0], [cube(16, 16, [-4, 0, -2], [8, 12, 4])]),
      part('right_arm', [-5, 2, 0], [cube(40, 16, [-1, -2, -1], [2, 12, 2])]),
      part('left_arm', [5, 2, 0], [cube(40, 16, [-1, -2, -1], [2, 12, 2], 0, true)]),
      part('right_leg', [-2, 12, 0], [cube(0, 16, [-1, 0, -1], [2, 12, 2])]),
      part('left_leg', [2, 12, 0], [cube(0, 16, [-1, 0, -1], [2, 12, 2], 0, true)]),
    ],
  };
}

export function creeperModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 6, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8])]),
      part('body', [0, 6, 0], [cube(16, 16, [-4, 0, -2], [8, 12, 4])]),
      part('right_hind_leg', [-2, 18, 4], [cube(0, 16, [-2, 0, -2], [4, 6, 4])]),
      part('left_hind_leg', [2, 18, 4], [cube(0, 16, [-2, 0, -2], [4, 6, 4])]),
      part('right_front_leg', [-2, 18, -4], [cube(0, 16, [-2, 0, -2], [4, 6, 4])]),
      part('left_front_leg', [2, 18, -4], [cube(0, 16, [-2, 0, -2], [4, 6, 4])]),
    ],
  };
}

const SPIDER_LEGS: readonly (readonly [string, number, number, number, number])[] = [
  ['right_hind_leg', -4, 2, -Math.PI / 4, Math.PI / 4],
  ['left_hind_leg', 4, 2, Math.PI / 4, -Math.PI / 4],
  ['right_middle_hind_leg', -4, 1, -0.58119464, Math.PI / 8],
  ['left_middle_hind_leg', 4, 1, 0.58119464, -Math.PI / 8],
  ['right_middle_front_leg', -4, 0, -0.58119464, -Math.PI / 8],
  ['left_middle_front_leg', 4, 0, 0.58119464, Math.PI / 8],
  ['right_front_leg', -4, -1, -Math.PI / 4, -Math.PI / 4],
  ['left_front_leg', 4, -1, Math.PI / 4, Math.PI / 4],
];

export function spiderModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 15, -3], [cube(32, 4, [-4, -4, -8], [8, 8, 8])]),
      part('neck', [0, 15, 0], [cube(0, 0, [-3, -3, -3], [6, 6, 6])]),
      part('body', [0, 15, 9], [cube(0, 12, [-5, -4, -6], [10, 8, 12])]),
      ...SPIDER_LEGS.map(([name, x, z, zRot, yRot]) => part(name, [x, 15, z], [cube(18, 0, x < 0 ? [-15, -1, -1] : [-1, -1, -1], [16, 2, 2])], [0, yRot, zRot])),
    ],
  };
}

export function endermanModel(): ModelDef {
  return {
    parts: [
      part('head', [0, -13, 0], [cube(0, 0, [-4, -8, -4], [8, 8, 8])], undefined, [
        part('hat', [0, 0, 0], [cube(0, 16, [-4, -8, -4], [8, 8, 8], -0.5)]),
      ]),
      part('body', [0, -14, 0], [cube(32, 16, [-4, 0, -2], [8, 12, 4])]),
      part('right_arm', [-5, -12, 0], [cube(56, 0, [-1, -2, -1], [2, 30, 2])]),
      part('left_arm', [5, -12, 0], [cube(56, 0, [-1, -2, -1], [2, 30, 2], 0, true)]),
      part('right_leg', [-2, -6, 0], [cube(56, 0, [-1, 0, -1], [2, 30, 2])]),
      part('left_leg', [2, -6, 0], [cube(56, 0, [-1, 0, -1], [2, 30, 2], 0, true)]),
    ],
  };
}

export function villagerModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 0, 0], [cube(0, 0, [-4, -10, -4], [8, 10, 8])], undefined, [
        part('hat', [0, 0, 0], [cube(32, 0, [-4, -10, -4], [8, 10, 8], 0.51)]),
        part('nose', [0, -2, 0], [cube(24, 0, [-1, -1, -6], [2, 4, 2])]),
      ]),
      part('body', [0, 0, 0], [cube(16, 20, [-4, 0, -3], [8, 12, 6])], undefined, [
        part('jacket', [0, 0, 0], [cube(0, 38, [-4, 0, -3], [8, 20, 6], 0.5)]),
      ]),
      part('arms', [0, 3, -1], [
        cube(44, 22, [-8, -2, -2], [4, 8, 4]),
        cube(44, 22, [4, -2, -2], [4, 8, 4], 0, true),
        cube(40, 38, [-4, 2, -2], [8, 4, 4]),
      ], [-0.75, 0, 0]),
      part('right_leg', [-2, 12, 0], [cube(0, 22, [-2, 0, -2], [4, 12, 4])]),
      part('left_leg', [2, 12, 0], [cube(0, 22, [-2, 0, -2], [4, 12, 4], 0, true)]),
    ],
  };
}

function quadrupedLegs(height: number, uv: readonly [number, number], hindZ: number, frontZ: number, spread: number): ModelDef['parts'] {
  const y = 24 - height;
  return [
    part('right_hind_leg', [-spread, y, hindZ], [cube(uv[0], uv[1], [-2, 0, -2], [4, height, 4])]),
    part('left_hind_leg', [spread, y, hindZ], [cube(uv[0], uv[1], [-2, 0, -2], [4, height, 4], 0, true)]),
    part('right_front_leg', [-spread, y, frontZ], [cube(uv[0], uv[1], [-2, 0, -2], [4, height, 4])]),
    part('left_front_leg', [spread, y, frontZ], [cube(uv[0], uv[1], [-2, 0, -2], [4, height, 4], 0, true)]),
  ];
}

export function cowModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 4, -8], [
        cube(0, 0, [-4, -4, -6], [8, 8, 6]),
        cube(22, 0, [-5, -5, -4], [1, 3, 1]),
        cube(22, 0, [4, -5, -4], [1, 3, 1]),
      ]),
      part('body', [0, 5, 2], [cube(18, 4, [-6, -10, -7], [12, 18, 10]), cube(52, 0, [-2, 2, -8], [4, 6, 1])], [HALF_PI, 0, 0]),
      ...quadrupedLegs(12, [0, 16], 7, -6, 4),
    ],
  };
}

export function pigModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 12, -6], [cube(0, 0, [-4, -4, -8], [8, 8, 8]), cube(16, 16, [-2, 0, -9], [4, 3, 1])]),
      part('body', [0, 11, 2], [cube(28, 8, [-5, -10, -7], [10, 16, 8])], [HALF_PI, 0, 0]),
      ...quadrupedLegs(6, [0, 16], 7, -5, 3),
    ],
  };
}

export function sheepModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 6, -8], [cube(0, 0, [-3, -4, -6], [6, 6, 8])]),
      part('body', [0, 5, 2], [cube(28, 8, [-4, -10, -7], [8, 16, 6])], [HALF_PI, 0, 0]),
      ...quadrupedLegs(12, [0, 16], 7, -5, 3),
    ],
  };
}

export function sheepWoolModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 6, -8], [cube(0, 0, [-3, -4, -4], [6, 6, 6], 0.6)]),
      part('body', [0, 5, 2], [cube(28, 8, [-4, -10, -7], [8, 16, 6], 1.75)], [HALF_PI, 0, 0]),
      part('right_hind_leg', [-3, 12, 7], [cube(0, 16, [-2, 0, -2], [4, 6, 4], 0.5)]),
      part('left_hind_leg', [3, 12, 7], [cube(0, 16, [-2, 0, -2], [4, 6, 4], 0.5)]),
      part('right_front_leg', [-3, 12, -5], [cube(0, 16, [-2, 0, -2], [4, 6, 4], 0.5)]),
      part('left_front_leg', [3, 12, -5], [cube(0, 16, [-2, 0, -2], [4, 6, 4], 0.5)]),
    ],
  };
}

export function chickenModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 15, -4], [cube(0, 0, [-2, -6, -2], [4, 6, 3]), cube(14, 0, [-2, -4, -4], [4, 2, 2]), cube(14, 4, [-1, -2, -3], [2, 2, 2])]),
      part('body', [0, 16, 0], [cube(0, 9, [-3, -4, -3], [6, 8, 6])], [HALF_PI, 0, 0]),
      part('right_hind_leg', [-2, 19, 1], [cube(26, 0, [-1, 0, -3], [3, 5, 3])]),
      part('left_hind_leg', [1, 19, 1], [cube(26, 0, [-1, 0, -3], [3, 5, 3])]),
      part('right_wing', [-4, 13, 0], [cube(24, 13, [0, 0, -3], [1, 4, 6])]),
      part('left_wing', [4, 13, 0], [cube(24, 13, [-1, 0, -3], [1, 4, 6])]),
    ],
  };
}

export function horseModel(): ModelDef {
  return {
    parts: [
      part('body', [0, 11, 5], [cube(0, 32, [-5, -8, -17], [10, 10, 22], 0.05)], undefined, [
        part('tail', [0, -5, 2], [cube(42, 36, [-1.5, 0, 0], [3, 14, 4])], [Math.PI / 6, 0, 0]),
      ]),
      part('head_parts', [0, 4, -12], [
        cube(0, 35, [-2.05, -6, -2], [4, 12, 7]),
        cube(0, 13, [-3, -11, -2], [6, 5, 7]),
        cube(56, 36, [-1, -11, 5.01], [2, 16, 2]),
        cube(0, 25, [-2, -11, -7], [4, 5, 5]),
        cube(19, 16, [0.55, -13, 4], [2, 3, 1], -0.001),
        cube(19, 16, [-2.55, -13, 4], [2, 3, 1], -0.001),
      ], [Math.PI / 6, 0, 0]),
      part('left_hind_leg', [4, 14, 7], [cube(48, 21, [-3, -1.01, -1], [4, 11, 4], 0, true)]),
      part('right_hind_leg', [-4, 14, 7], [cube(48, 21, [-1, -1.01, -1], [4, 11, 4])]),
      part('left_front_leg', [4, 14, -12], [cube(48, 21, [-3, -1.01, -1.9], [4, 11, 4], 0, true)]),
      part('right_front_leg', [-4, 14, -12], [cube(48, 21, [-1, -1.01, -1.9], [4, 11, 4])]),
    ],
  };
}

export function armorStandModel(): ModelDef {
  return {
    parts: [
      part('head', [0, 1, 0], [cube(0, 0, [-1, -7, -1], [2, 7, 2])]),
      part('body', [0, 0, 0], [cube(0, 26, [-6, 0, -1.5], [12, 3, 3])]),
      part('right_arm', [-5, 2, 0], [cube(24, 0, [-2, -2, -1], [2, 12, 2])]),
      part('left_arm', [5, 2, 0], [cube(32, 16, [0, -2, -1], [2, 12, 2], 0, true)]),
      part('right_leg', [-1.9, 12, 0], [cube(8, 0, [-1, 0, -1], [2, 11, 2])]),
      part('left_leg', [1.9, 12, 0], [cube(40, 16, [-1, 0, -1], [2, 11, 2], 0, true)]),
      part('right_body_stick', [0, 0, 0], [cube(16, 0, [-3, 3, -1], [2, 7, 2])]),
      part('left_body_stick', [0, 0, 0], [cube(48, 16, [1, 3, -1], [2, 7, 2])]),
      part('shoulder_stick', [0, 0, 0], [cube(0, 48, [-4, 10, -1], [8, 2, 2])]),
      part('base_plate', [0, 12, 0], [cube(0, 32, [-6, 11, -6], [12, 1, 12])]),
    ],
  };
}

export function boatModel(): ModelDef {
  return {
    parts: [
      part('bottom', [0, 3, 1], [cube(0, 0, [-14, -9, -3], [28, 16, 3])], [HALF_PI, 0, 0]),
      part('back', [-15, 4, 4], [cube(0, 19, [-13, -7, -1], [18, 6, 2])], [0, Math.PI * 1.5, 0]),
      part('front', [15, 4, 0], [cube(0, 27, [-8, -7, -1], [16, 6, 2])], [0, HALF_PI, 0]),
      part('right', [0, 4, -9], [cube(0, 35, [-14, -7, -1], [28, 6, 2])], [0, Math.PI, 0]),
      part('left', [0, 4, 9], [cube(0, 43, [-14, -7, -1], [28, 6, 2])]),
    ],
  };
}

export function minecartModel(): ModelDef {
  return {
    parts: [
      part('bottom', [0, 4, 0], [cube(0, 10, [-10, -8, -1], [20, 16, 2])], [HALF_PI, 0, 0]),
      part('front', [-9, 4, 0], [cube(0, 0, [-8, -9, -1], [16, 8, 2])], [0, Math.PI * 1.5, 0]),
      part('back', [9, 4, 0], [cube(0, 0, [-8, -9, -1], [16, 8, 2])], [0, HALF_PI, 0]),
      part('left', [0, 4, -7], [cube(0, 0, [-8, -9, -1], [16, 8, 2])], [0, Math.PI, 0]),
      part('right', [0, 4, 7], [cube(0, 0, [-8, -9, -1], [16, 8, 2])]),
    ],
  };
}

export function armorModel(inflate: number): ModelDef {
  return humanoidModel(inflate, false);
}
