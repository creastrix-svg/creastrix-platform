import { useId } from 'react';
import { lampPanel, lampPatterns } from './lampPatterns';
import type { LampPatternId, LampShape } from './lampPatterns';

export type LampSketchProps = {
  pattern: LampPatternId;
  bridges: boolean;
  highlight?: boolean;
  mode?: 'panel' | 'lamp';
  label: string;
  text?: string;
  fontFamily?: string;
  fontSize?: number;
  imageUrl?: string;
  imageSize?: number;
  /** Disable the curated cut-through motif for an engraving-only reference. */
  showPattern?: boolean;
};

function Shape({ shape }: { shape: LampShape }) {
  return shape.kind === 'circle'
    ? <circle cx={shape.cx} cy={shape.cy} r={shape.r} />
    : <path d={shape.d} />;
}

/**
 * Local SVG illustration, not a manufacturing export or an interactive 3D mesh.
 * The mask removes actual panel material, then restores explicit material bridges.
 * Text and a validated local raster reference are engraving overlays only.
 */
export default function LampSketch({ pattern, bridges, highlight = false, mode = 'panel', label, text = '', fontFamily = 'Georgia, serif', fontSize = 24, imageUrl, imageSize = 100, showPattern = !imageUrl }: LampSketchProps) {
  const id = useId(), descriptor = lampPatterns[pattern];
  const url = (name: string) => `url(#${id}-${name})`;
  // Never let a preview prop trigger an arbitrary remote image request.
  const localImage = imageUrl && /^(blob:|data:image\/(png|jpeg|webp);base64,)/.test(imageUrl) ? imageUrl : undefined;
  const textSize = (Number.isFinite(fontSize) ? Math.max(18, Math.min(32, fontSize)) : 24) * .72;
  const imageScale = (Number.isFinite(imageSize) ? Math.max(20, Math.min(150, imageSize)) : 100) / 100;
  const lines = text.split('\n').slice(0, 2);
  const panel = (side = false) => <g data-lamp-panel={side ? 'side' : 'front'}>
    <g mask={url('material')}>
      <path d={lampPanel.shape} fill={url('wood')} />
      <path d={lampPanel.shape} fill={url('grain')} />
      <path d={lampPanel.shape} fill="none" stroke="#9c6d3d" strokeWidth="2" />
      {side && <path d={lampPanel.shape} fill="#613719" fillOpacity=".2" />}
    </g>
    {!side && <g clipPath={url('panel-clip')} mask={url('material')} data-lamp-engraving="true">
      {localImage && <image href={localImage} x={120 - 62 * imageScale} y={147 - 79 * imageScale} width={124 * imageScale} height={158 * imageScale} preserveAspectRatio="xMidYMid meet" opacity=".8" style={{ filter: 'grayscale(1) sepia(.6)' }} />}
      {text && <text textAnchor="middle" fill="#674329" fontSize={textSize} fontFamily={fontFamily} style={{ whiteSpace: 'pre' }}>
        {lines.map((line, index) => <tspan key={index} x="120" y={275 + index * textSize * 1.2}>{line}</tspan>)}
      </text>}
    </g>}
    {!side && highlight && showPattern && <g data-lamp-guides={bridges ? 'connections' : 'islands'} fill="none" stroke={bridges ? '#23634a' : '#b35824'} strokeWidth="2" strokeLinejoin="round">
      {descriptor.islands.map(island => bridges
        ? <rect key={island.id} {...island.bridge} rx="2" />
        : <g key={island.id} strokeDasharray="3 3"><Shape shape={island.shape} /></g>)}
    </g>}
  </g>;
  return <svg className="studio-sketch studio-lamp-sketch" viewBox="0 0 480 480" role="img" aria-label={label} data-lamp-mode={mode} data-cut-pattern={showPattern ? pattern : 'none'}>
    <defs>
      <linearGradient id={id + '-wood'} x1="0" x2="1" y1="0" y2=".2"><stop stopColor="#dab67e" /><stop offset=".45" stopColor="#efd2a0" /><stop offset="1" stopColor="#cc9f64" /></linearGradient>
      <pattern id={id + '-grain'} width="60" height="320" patternUnits="userSpaceOnUse"><path d="M10 -10Q32 65 13 140T18 330M31 -10Q13 85 34 170T28 330M48 -10Q64 90 49 180T54 330" stroke="#926130" strokeOpacity=".13" strokeWidth=".7" fill="none" /></pattern>
      <radialGradient id={id + '-shadow'}><stop stopColor="#1b2a23" stopOpacity=".23" /><stop offset="1" stopColor="#1b2a23" stopOpacity="0" /></radialGradient>
      <radialGradient id={id + '-glow'}><stop stopColor="#fff3c2" /><stop offset=".42" stopColor="#fbd89c" /><stop offset="1" stopColor="#c2853c" /></radialGradient>
      <radialGradient id={id + '-ambient'}><stop stopColor="#ffd687" stopOpacity=".25" /><stop offset="1" stopColor="#ffd687" stopOpacity="0" /></radialGradient>
      <clipPath id={id + '-panel-clip'}><path d={lampPanel.shape} /></clipPath>
      <mask id={id + '-material'} maskUnits="userSpaceOnUse" x="0" y="0" width={lampPanel.width} height={lampPanel.height} style={{ maskType: 'luminance' }}>
        <path d={lampPanel.shape} fill="#fff" />
        {showPattern && <>
          <g fill="#000" data-lamp-openings="true">{descriptor.openings.map((shape, index) => <Shape key={index} shape={shape} />)}</g>
          <g fill="#fff" data-lamp-islands="true">{descriptor.islands.map(island => <Shape key={island.id} shape={island.shape} />)}</g>
          {bridges && <g fill="#fff" data-lamp-bridges="true">{descriptor.islands.map(island => <rect key={island.id} data-bridge-for={island.id} {...island.bridge} />)}</g>}
        </>}
      </mask>
    </defs>
    {mode === 'panel' ? <>
      <ellipse cx="240" cy="418" rx="151" ry="20" fill={url('shadow')} />
      <g transform="translate(120 70)">
        <path d={lampPanel.shape} fill="#b98e56" transform="translate(3 4)" mask={url('material')} />
        {panel()}
      </g>
    </> : <>
      <ellipse cx="245" cy="262" rx="198" ry="193" fill={url('ambient')} />
      <ellipse cx="245" cy="414" rx="168" ry="27" fill={url('shadow')} />
      <path d="M100 110L182 74H362L280 110Z" fill="#d8b17b" stroke="#b58a51" strokeWidth="2" />
      <path d="M132 104L196 81M158 104L222 81M184 104L248 81M210 104L274 81M236 104L300 81" stroke="#a7773e" strokeWidth="3" strokeLinecap="round" />
      <path d="M100 110H280V392H100Z" fill={url('glow')} />
      <path d="M280 110L362 74V356L280 392Z" fill={url('glow')} />
      <g transform="matrix(.34 -.15 0 .88 280 110)">{panel(true)}</g>
      <g transform="matrix(.75 0 0 .88 100 110)">{panel()}</g>
      <path d="M95 391H280L367 352V363L280 402H95Z" fill="#b98d55" stroke="#956a38" strokeWidth="1" />
      <path d="M95 391H280V402H95Z" fill="#d6ac71" />
      <path d="M110 402V410H129V402M254 402V410H272V402" fill="#9e7345" />
      <path d="M329 379Q386 394 399 420" fill="none" stroke="#9c9180" strokeWidth="2" />
    </>}
  </svg>;
}
