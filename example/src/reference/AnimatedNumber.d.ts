import type { ComponentType } from 'react';

// Ambient type for the platform-split component (AnimatedNumber.ios.tsx / .android.tsx). The
// bundler resolves the concrete platform file at build time; TypeScript resolves to this.
export type AnimatedNumberProps = { value: number; size?: number };
export declare const AnimatedNumber: ComponentType<AnimatedNumberProps>;
