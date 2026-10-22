import { NativeEventEmitter } from 'react-native';
import MusicPlay from './NativeMusicPlay';

export type Track = {
  postId: string;
  url: string;
  title?: string;
  artist?: string;
  artworkUrl?: string;
};

export type PlayerState = 'playing' | 'paused' | 'stopped';

export type MusicPlayEvents = {
  onProgress: {
    currentTime: number;
    duration: number;
    url?: string;
    postId?: string;
  };
  onLoad: { duration: number; url?: string; postId?: string };
  onLoading: { loading: boolean; url?: string; postId?: string };
  onStateChange: { state: PlayerState; url?: string; postId?: string };
  onFinish: { url?: string; postId?: string };
  onTrackChanged: { postId?: string; url?: string; reason: 'auto' | 'seek' };
  onError: { message: string; url?: string; postId?: string };
};

const emitter = new NativeEventEmitter(MusicPlay as any);

export function playInPlaylist(items: Track[], startPostId: string) {
  MusicPlay.playInPlaylist(items, startPostId);
}
export function play(url: string) {
  MusicPlay.play(url);
}
export function pause(url?: string) {
  MusicPlay.pause(url);
}
export function resume(url?: string) {
  MusicPlay.resume(url);
}
export function stop(url?: string) {
  MusicPlay.stop(url);
}
export function seekTo(seconds: number, url?: string) {
  MusicPlay.seekTo(seconds, url);
}
export function setLooping(enabled: boolean) {
  MusicPlay.setLooping(enabled);
}
export function getDuration(): Promise<number> {
  return MusicPlay.getDuration();
}
export function getCurrentTime(): Promise<number> {
  return MusicPlay.getCurrentTime();
}
export function getCurrentTrackInfo(): Promise<Object> {
  return MusicPlay.getCurrentTrackInfo();
}

export function addListener<E extends keyof MusicPlayEvents>(
  event: E,
  callback: (data: MusicPlayEvents[E]) => void
) {
  return emitter.addListener(event, callback as any);
}
