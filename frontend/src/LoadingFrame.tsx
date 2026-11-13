/** Shared by successive cold-start gates. Only assistive technology needs to
 * announce which resource is pending; no account action is meaningful yet. */
export default function LoadingFrame({label}:{label:string}) {
  return <div className="app-loading-frame" role="status">
    <span className="app-loading-ring" aria-hidden="true"/>
    <span className="app-loading-label">{label}</span>
  </div>;
}
