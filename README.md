# react-native-music-play

Native music player for React Native (New Architecture / Turbo Module).
Play remote or local audio, build playlists, control playback from JavaScript, and get progress and state events. Includes a media notification on Android and lock screen / Control Center controls on iOS.

[![npm version](https://img.shields.io/npm/v/react-native-music-play.svg)](https://www.npmjs.com/package/react-native-music-play)
[![license](https://img.shields.io/npm/l/react-native-music-play.svg)](./LICENSE)

## Features

- Play audio from a remote URL or a local file path
- Playlist support with auto-advance to the next track
- Play, pause, resume, stop, seek, and loop
- Real-time events: progress, load, loading, state change, track change, finish, error
- Resumes each track from where you left it when you come back to it
- Background playback
- Media notification (Android) and lock screen / Control Center controls (iOS)
- Track metadata: title, artist, and artwork
- Android: 100 MB disk cache for streamed audio
- Written in TypeScript with full type definitions

## Requirements

| | Minimum |
|---|---|
| React Native | 0.76+ with the **New Architecture** enabled |
| Android | API 24+ |
| iOS | 13.4+ |

## Installation

```sh
npm install react-native-music-play
# or
yarn add react-native-music-play
```

For iOS, install the pods:

```sh
cd ios && pod install
```

## Platform setup

### Android

The player service, `INTERNET`, and foreground service permissions are added automatically through manifest merging. You only need one thing:

**Android 13+ (API 33): request the notification permission at runtime**, otherwise the playback notification will not appear.

```ts
import { PermissionsAndroid, Platform } from 'react-native';

if (Platform.OS === 'android' && Platform.Version >= 33) {
  await PermissionsAndroid.request(
    PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
  );
}
```

If your app loads audio over plain `http://`, also set `android:usesCleartextTraffic="true"` on your `<application>` tag.

### iOS

Enable background audio so music keeps playing when the app is in the background.

In Xcode: **Signing & Capabilities → + Capability → Background Modes → Audio, AirPlay, and Picture in Picture**.

Or add this to `Info.plist`:

```xml
<key>UIBackgroundModes</key>
<array>
  <string>audio</string>
</array>
```

Plain `http://` URLs need an App Transport Security exception in `Info.plist`. `https://` URLs work as is.

## Quick start

```tsx
import { useEffect, useState } from 'react';
import { View, Text, Button } from 'react-native';
import {
  playInPlaylist,
  pause,
  resume,
  addListener,
  type Track,
} from 'react-native-music-play';

const tracks: Track[] = [
  {
    postId: '1',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
    title: 'Sunrise Drive',
    artist: 'SoundHelix',
    artworkUrl: 'https://picsum.photos/id/1015/400/400',
  },
  {
    postId: '2',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
    title: 'Midnight City',
    artist: 'T. Schürger',
  },
];

export default function App() {
  const [time, setTime] = useState(0);

  useEffect(() => {
    const sub = addListener('onProgress', (e) => setTime(e.currentTime));
    return () => sub.remove(); // always clean up
  }, []);

  return (
    <View style={{ padding: 24 }}>
      <Text>{Math.floor(time)}s</Text>
      <Button title="Play" onPress={() => playInPlaylist(tracks, '1')} />
      <Button title="Pause" onPress={() => pause()} />
      <Button title="Resume" onPress={() => resume()} />
    </View>
  );
}
```

## API

```ts
import {
  playInPlaylist,
  play,
  pause,
  resume,
  stop,
  seekTo,
  setLooping,
  getDuration,
  getCurrentTime,
  getCurrentTrackInfo,
  addListener,
} from 'react-native-music-play';
```

### Track object

Used by `playInPlaylist`.

| Property | Type | Required | Description |
|---|---|---|---|
| `postId` | `string` | **Yes** | Unique ID of the track. Used to identify it in events and to remember its position. |
| `url` | `string` | **Yes** | Remote URL (`https://...`) or local file path (`/path/to/file.mp3`). |
| `title` | `string` | No | Shown in the notification / lock screen. |
| `artist` | `string` | No | Shown in the notification / lock screen. |
| `artworkUrl` | `string` | No | Cover image URL shown in the notification / lock screen. |

Tracks without a `postId` or `url` are skipped.

### Methods

#### Required: the basics you need to play audio

| Method | Description |
|---|---|
| `playInPlaylist(items: Track[], startPostId: string): void` | Loads the playlist and starts playing the track whose `postId` equals `startPostId`. When a track ends, the next one starts automatically. |
| `pause(url?: string): void` | Pauses playback. |
| `resume(url?: string): void` | Resumes playback. |
| `stop(url?: string): void` | Stops playback, clears the playlist, and removes the notification. |

#### Optional: extra controls

| Method | Description |
|---|---|
| `play(url: string): void` | Plays a single URL without a playlist (no `postId`). Good for quick use. |
| `seekTo(seconds: number, url?: string): void` | Jumps to a position, in seconds. |
| `setLooping(enabled: boolean): void` | Repeats the current track when `true`. |
| `getDuration(): Promise<number>` | Duration of the current track in seconds (`0` if not known yet). |
| `getCurrentTime(): Promise<number>` | Current position in seconds. |
| `getCurrentTrackInfo(): Promise<TrackInfo>` | Snapshot of the current state (see below). |
| `addListener(event, callback)` | Subscribes to an event. Returns a subscription with `.remove()`. |

> **The optional `url` parameter.** `pause`, `resume`, `stop`, and `seekTo` accept an optional `url`. If you pass it, the command only runs when that URL is the one currently playing. This stops a screen from controlling a track it does not own. Leave it out to control whatever is playing.

#### `getCurrentTrackInfo()` result

```ts
{
  postId: string | null;
  url: string | null;
  positionSeconds: number;
  isPlaying: boolean;
}
```

### Events

```ts
const sub = addListener('onProgress', (e) => {
  console.log(e.currentTime, e.duration);
});

sub.remove(); // when you no longer need it
```

| Event | Payload | When it fires |
|---|---|---|
| `onProgress` | `{ currentTime, duration, url?, postId? }` | About every 500 ms while playing. Times are in seconds. |
| `onLoad` | `{ duration, url?, postId? }` | The track is ready to play. `duration` is in seconds. |
| `onLoading` | `{ loading, url?, postId? }` | Buffering starts (`true`) or ends (`false`). |
| `onStateChange` | `{ state, url?, postId? }` | `state` is `'playing'`, `'paused'`, or `'stopped'`. |
| `onTrackChanged` | `{ postId?, url?, reason }` | The track changed. `reason` is `'auto'` (finished and advanced) or `'seek'` (user, notification, or lock screen). |
| `onFinish` | `{ url?, postId? }` | The last track in the playlist finished. |
| `onError` | `{ message, url?, postId? }` | Playback or loading failed. |

### TypeScript types

```ts
import type { Track } from 'react-native-music-play';

type PlayerState = 'playing' | 'paused' | 'stopped';
```

## Usage examples

### Playlist with next / previous

```ts
const next = (currentId: string) => {
  const i = tracks.findIndex((t) => t.postId === currentId);
  const nextTrack = tracks[(i + 1) % tracks.length]!;
  playInPlaylist(tracks, nextTrack.postId);
};
```

### Progress bar

```tsx
const [time, setTime] = useState(0);
const [duration, setDuration] = useState(0);

useEffect(() => {
  const sub = addListener('onProgress', (e) => {
    setTime(e.currentTime);
    setDuration(e.duration);
  });
  return () => sub.remove();
}, []);

const progress = duration > 0 ? time / duration : 0;
```

### Seek from a bar tap

```ts
const onBarPress = (x: number, barWidth: number) => {
  if (duration > 0) seekTo((x / barWidth) * duration);
};
```

### Loading spinner and errors

```ts
useEffect(() => {
  const subs = [
    addListener('onLoading', (e) => setLoading(e.loading)),
    addListener('onError', (e) => console.warn('Audio error:', e.message)),
  ];
  return () => subs.forEach((s) => s.remove());
}, []);
```

### Play a local file

```ts
play('/data/user/0/com.myapp/files/song.mp3');
```

## Platform differences

| Feature | Android | iOS |
|---|---|---|
| Engine | Media3 / ExoPlayer in a foreground service | `AVPlayer` |
| Notification controls | Media notification | Lock screen and Control Center |
| Disk cache for streams | Yes (100 MB) | No |
| Tapping the notification | Opens the app | Opens the app |
| Swiping the app away | Stops playback | Ends with the app process |

## Behaviour notes

- **Resume position.** When you switch away from a track and later start it again with `playInPlaylist`, it continues from where it stopped. A track that finished starts from the beginning. Positions are kept in memory and reset when the app is closed.
- **One player.** There is a single player for the whole app. Starting new audio replaces the current audio.
- **Looping** applies to the current track only.
- **Duration** may be `0` until the track has loaded. Use the `onLoad` event or `onProgress` to get it.

## Troubleshooting

**`TurboModuleRegistry.getEnforcing(...): 'MusicPlay' could not be found`**
The New Architecture is not enabled, or the app was not rebuilt after installing. Enable it, then rebuild (`npx react-native run-android` / `run-ios`).

**No notification on Android 13+**
Request the `POST_NOTIFICATIONS` permission at runtime (see [Android setup](#android)).

**Music stops when the app goes to the background on iOS**
Enable the *Audio, AirPlay, and Picture in Picture* background mode (see [iOS setup](#ios)).

**`onError` fires immediately**
Check that the URL is reachable and is HTTPS (or that cleartext / ATS exceptions are set up).

**Build errors after installing**
Clean and rebuild:

```sh
# Android
cd android && ./gradlew clean && cd ..

# iOS
cd ios && pod install && cd ..
```

## Roadmap

- Disk cache on iOS
- Playback speed control
- Shuffle and repeat-all modes
- Equalizer

## Contributing

Pull requests are welcome. See the [contributing guide](CONTRIBUTING.md) for how to set up the project and run the example app:

```sh
yarn
yarn example android
yarn example ios
```

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)