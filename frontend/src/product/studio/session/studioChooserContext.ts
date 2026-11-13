import {createContext} from 'react';

/** A picker may outlive a foreground identity check. Await the original opener's
 * decision before publishing local pixels. This is never a cart or API grant. */
export type StudioChooser = {open(signal:AbortSignal): (()=>Promise<boolean>)|null};
export const StudioChooserContext=createContext<StudioChooser|null>(null);
