export type LampPatternId = 'botanical' | 'forest' | 'orbit';
export type LampShape =
  | { readonly kind: 'circle'; readonly cx: number; readonly cy: number; readonly r: number }
  | { readonly kind: 'path'; readonly d: string };
export type LampBridge = { readonly x: number; readonly y: number; readonly width: number; readonly height: number };
export type LampIsland = { readonly id: string; readonly shape: LampShape; readonly bridge: LampBridge };
export type LampPattern = {
  readonly id: LampPatternId;
  readonly openings: readonly LampShape[];
  readonly islands: readonly LampIsland[];
};

export const lampPanel = { width: 240, height: 320, shape: 'M0 320V20Q0 0 20 0H220Q240 0 240 20V320Z' } as const;

const circle = (cx: number, cy: number, r: number): LampShape => ({ kind: 'circle', cx, cy, r });
const path = (d: string): LampShape => ({ kind: 'path', d });
const pine = (x: number, top: number, bottom: number, spread: number): LampShape => path(
  `M${x} ${top}L${x + spread * .56} ${top + 30}H${x + spread * .25}L${x + spread * .83} ${top + 52}H${x + spread * .4}L${x + spread} ${bottom - 13}H${x + 4}V${bottom}H${x - 4}V${bottom - 13}H${x - spread}L${x - spread * .4} ${top + 52}H${x - spread * .83}L${x - spread * .25} ${top + 30}H${x - spread * .56}Z`,
);

/**
 * Hand-authored preview geometry in a 240 × 320 illustration coordinate system.
 * Each retained island has one explicit connection back to the surrounding panel.
 * Rendering and the illustrative report use these same descriptors; this is not
 * a contour analyser, a millimetre-scale manufacturing file or a strength check.
 */
export const lampPatterns: Readonly<Record<LampPatternId, LampPattern>> = {
  botanical: {
    id: 'botanical',
    openings: [circle(120, 152, 74), circle(64, 55, 3), circle(176, 55, 3), circle(120, 42, 4)],
    islands: [{
      id: 'stem',
      shape: path('M117 199V113Q99 109 90 88Q112 86 123 111V136Q138 113 159 114Q154 139 123 143V166Q100 161 83 141Q109 135 123 159V179Q138 156 161 161Q151 186 123 186V199Z'),
      bridge: { x: 116, y: 194, width: 8, height: 37 },
    }],
  },
  forest: {
    id: 'forest',
    openings: [circle(120, 155, 78), circle(70, 51, 3), circle(170, 57, 4), circle(138, 37, 2)],
    islands: [
      { id: 'pine-left', shape: pine(77, 105, 198, 18), bridge: { x: 73, y: 194, width: 8, height: 47 } },
      { id: 'pine-middle', shape: pine(120, 88, 200, 21), bridge: { x: 116, y: 196, width: 8, height: 42 } },
      { id: 'pine-right', shape: pine(163, 118, 200, 18), bridge: { x: 159, y: 196, width: 8, height: 45 } },
    ],
  },
  orbit: {
    id: 'orbit',
    openings: [circle(120, 130, 50), circle(65, 220, 21), circle(174, 230, 25), path('M179 51L182 61L192 64L182 67L179 77L176 67L166 64L176 61Z'), circle(62, 76, 3), circle(180, 171, 3)],
    islands: [
      { id: 'planet-large', shape: circle(120, 130, 36), bridge: { x: 153, y: 126, width: 21, height: 8 } },
      { id: 'planet-small', shape: circle(65, 220, 12), bridge: { x: 61, y: 229, width: 8, height: 15 } },
      { id: 'planet-medium', shape: circle(174, 230, 14), bridge: { x: 170, y: 241, width: 8, height: 17 } },
    ],
  },
};

/** Counts only the known islands in the selected curated illustration. */
export function lampPatternReport(pattern: LampPatternId, bridges: boolean) {
  const descriptor = lampPatterns[pattern];
  return {
    pattern: descriptor.id,
    disconnectedIslands: bridges ? 0 : descriptor.islands.length,
    addedBridges: bridges ? descriptor.islands.length : 0,
    manufacturingValidated: false as const,
  };
}
