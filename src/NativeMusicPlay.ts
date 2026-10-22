import { TurboModuleRegistry, type TurboModule } from 'react-native';

export interface Spec extends TurboModule {
  playInPlaylist(items: Array<Object>, startPostId: string): void;
  play(url: string): void;
  pause(url?: string): void;
  resume(url?: string): void;
  stop(url?: string): void;
  seekTo(seconds: number, url?: string): void;
  setLooping(enabled: boolean): void;
  getDuration(): Promise<number>;
  getCurrentTime(): Promise<number>;
  getCurrentTrackInfo(): Promise<Object>;

  // required for event emitters
  addListener(eventName: string): void;
  removeListeners(count: number): void;
}

export default TurboModuleRegistry.getEnforcing<Spec>('MusicPlay');
