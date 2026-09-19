import { InjectionToken } from '@angular/core';

/**
 * three.js is not bundled: it is loaded from cdnjs the same way as the reference mockup
 * (`r128`, UMD), and the globe is the only view depending on it. Bundling it would put
 * ~600 kB of a library used by one component into every visit, including those that never
 * open the globe.
 *
 * `@types/three` (devDependency, pinned to the matching `0.128.0`) supplies the type
 * information at compile time only — it contributes nothing to the runtime bundle.
 */
export const THREE_SCRIPT_URL = 'https://cdnjs.cloudflare.com/ajax/libs/three.js/r128/three.min.js';

declare global {
  interface Window {
    THREE?: typeof import('three');
  }
}

let pending: Promise<typeof import('three')> | undefined;

function fetchThree(): Promise<typeof import('three')> {
  if (window.THREE) return Promise.resolve(window.THREE);
  if (pending) return pending;
  pending = new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = THREE_SCRIPT_URL;
    script.onload = () => window.THREE ? resolve(window.THREE) : reject(new Error('three.js loaded without defining THREE'));
    script.onerror = () => reject(new Error('three.js failed to load'));
    document.head.appendChild(script);
  });
  return pending;
}

/**
 * Injectable indirection around `fetchThree`, so a test can supply a loader that never
 * touches the network instead of depending on cdnjs being reachable in CI.
 */
export const THREE_LOADER = new InjectionToken<() => Promise<typeof import('three')>>('THREE_LOADER', {
  providedIn: 'root',
  factory: () => fetchThree,
});
