import { useEffect, useMemo, useState } from 'react';
import {
  View,
  Text,
  Image,
  FlatList,
  TouchableOpacity,
  StyleSheet,
  PermissionsAndroid,
  Platform,
  ActivityIndicator,
  SafeAreaView,
  type LayoutChangeEvent,
} from 'react-native';
import {
  playInPlaylist,
  pause,
  resume,
  stop,
  seekTo,
  addListener,
  type Track,
} from 'react-native-music-play';

const TRACKS: Track[] = [
  {
    id: '1',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3',
    title: 'Sunrise Drive',
    artist: 'SoundHelix',
    artworkUrl: 'https://picsum.photos/id/1015/400/400',
  },
  {
    id: '2',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3',
    title: 'Midnight City',
    artist: 'T. Schürger',
    artworkUrl: 'https://picsum.photos/id/1025/400/400',
  },
  {
    id: '3',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3',
    title: 'Ocean Waves',
    artist: 'SoundHelix',
    artworkUrl: 'https://picsum.photos/id/1035/400/400',
  },
  {
    id: '4',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-4.mp3',
    title: 'Neon Lights',
    artist: 'T. Schürger',
    artworkUrl: 'https://picsum.photos/id/1043/400/400',
  },
  {
    id: '5',
    url: 'https://www.soundhelix.com/examples/mp3/SoundHelix-Song-5.mp3',
    title: 'Golden Hour',
    artist: 'SoundHelix',
    artworkUrl: 'https://picsum.photos/id/1057/400/400',
  },
];

const fmt = (sec: number) => {
  const s = Math.max(0, Math.floor(sec || 0));
  const m = Math.floor(s / 60);
  return `${m}:${String(s % 60).padStart(2, '0')}`;
};

export default function App() {
  const [currentId, setCurrentId] = useState<string | null>(null);
  const [state, setState] = useState<'playing' | 'paused' | 'stopped'>(
    'stopped'
  );
  const [loading, setLoading] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [durations, setDurations] = useState<Record<string, number>>({});
  const [barWidth, setBarWidth] = useState(1);
  const [lastError, setLastError] = useState('');

  const currentTrack = useMemo(
    () => TRACKS.find((t) => t.id === currentId) ?? null,
    [currentId]
  );
  const currentIndex = TRACKS.findIndex((t) => t.id === currentId);

  // Android 13+ needs this for the playback notification
  useEffect(() => {
    if (Platform.OS === 'android' && Platform.Version >= 33) {
      PermissionsAndroid.request(
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      );
    }
  }, []);

  useEffect(() => {
    const subs = [
      addListener('onProgress', (e) => {
        setTime(e.currentTime);
        setDuration(e.duration);
      }),
      addListener('onLoad', (e) => {
        setDuration(e.duration);
        if (e.id) {
          setDurations((d) => ({ ...d, [e.id as string]: e.duration }));
        }
      }),
      addListener('onLoading', (e) => setLoading(e.loading)),
      addListener('onStateChange', (e) => setState(e.state)),
      addListener('onTrackChanged', (e) => {
        if (e.id) {
          setCurrentId(e.id);
          setTime(0);
        }
      }),
      addListener('onFinish', () => setTime(0)),
      addListener('onError', (e) => setLastError(e.message)),
    ];
    return () => subs.forEach((s) => s.remove());
  }, []);

  const playTrack = (id: string) => {
    setLastError('');
    setCurrentId(id);
    setTime(0);
    playInPlaylist(TRACKS, id);
  };

  const onPlayPause = () => {
    if (!currentTrack) return playTrack(TRACKS[0]!.id);
    state === 'playing' ? pause() : resume();
  };

  const onNext = () => {
    const next = TRACKS[(currentIndex + 1) % TRACKS.length]!;
    playTrack(next.id);
  };

  const onPrev = () => {
    const prev = TRACKS[(currentIndex - 1 + TRACKS.length) % TRACKS.length]!;
    playTrack(prev.id);
  };

  const onBarPress = (x: number) => {
    if (duration > 0) seekTo((x / barWidth) * duration);
  };

  const progress = duration > 0 ? Math.min(time / duration, 1) : 0;

  return (
    <SafeAreaView style={styles.container}>
      {/* NOW PLAYING */}
      <View style={styles.player}>
        {currentTrack?.artworkUrl ? (
          <Image
            source={{ uri: currentTrack.artworkUrl }}
            style={styles.cover}
          />
        ) : (
          <View style={[styles.cover, styles.coverEmpty]} />
        )}
        <Text style={styles.title} numberOfLines={1}>
          {currentTrack?.title ?? 'Nothing playing'}
        </Text>
        <Text style={styles.artist}>{currentTrack?.artist ?? '-'}</Text>

        <TouchableOpacity
          activeOpacity={1}
          style={styles.barTouch}
          onLayout={(e: LayoutChangeEvent) =>
            setBarWidth(e.nativeEvent.layout.width)
          }
          onPress={(e) => onBarPress(e.nativeEvent.locationX)}
        >
          <View style={styles.barBg}>
            <View style={[styles.barFill, { width: `${progress * 100}%` }]} />
          </View>
        </TouchableOpacity>
        <View style={styles.times}>
          <Text style={styles.time}>{fmt(time)}</Text>
          <Text style={styles.time}>{fmt(duration)}</Text>
        </View>

        <View style={styles.controls}>
          <TouchableOpacity onPress={onPrev}>
            <Text style={styles.ctrl}>⏮</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={onPlayPause} style={styles.playBtn}>
            {loading ? (
              <ActivityIndicator color="#fff" />
            ) : (
              <Text style={styles.playTxt}>
                {state === 'playing' ? '⏸' : '▶'}
              </Text>
            )}
          </TouchableOpacity>
          <TouchableOpacity onPress={onNext}>
            <Text style={styles.ctrl}>⏭</Text>
          </TouchableOpacity>
          <TouchableOpacity onPress={() => stop()}>
            <Text style={styles.ctrl}>⏹</Text>
          </TouchableOpacity>
        </View>

        <Text style={styles.status}>
          state: {state}
          {lastError ? `  |  error: ${lastError}` : ''}
        </Text>
      </View>

      {/* TRACK LIST */}
      <FlatList
        data={TRACKS}
        keyExtractor={(t) => t.id}
        renderItem={({ item }) => {
          const active = item.id === currentId;
          return (
            <TouchableOpacity
              style={[styles.row, active && styles.rowActive]}
              onPress={() => playTrack(item.id)}
            >
              <Image source={{ uri: item.artworkUrl }} style={styles.thumb} />
              <View style={styles.boxContainer}>
                <Text style={styles.rowTitle} numberOfLines={1}>
                  {item.title}
                </Text>
                <Text style={styles.rowArtist}>{item.artist}</Text>
              </View>
              <Text style={styles.rowDur}>
                {durations[item.id] ? fmt(durations[item.id]!) : '--:--'}
              </Text>
            </TouchableOpacity>
          );
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#121212' },
  boxContainer: { flex: 1 },
  player: { alignItems: 'center', padding: 20 },
  cover: { width: 200, height: 200, borderRadius: 12, backgroundColor: '#333' },
  coverEmpty: { justifyContent: 'center', alignItems: 'center' },
  title: { color: '#fff', fontSize: 20, fontWeight: '700', marginTop: 14 },
  artist: { color: '#aaa', fontSize: 14, marginTop: 2 },
  barTouch: { width: '100%', paddingVertical: 12, marginTop: 10 },
  barBg: { height: 5, backgroundColor: '#444', borderRadius: 3 },
  barFill: { height: 5, backgroundColor: '#1db954', borderRadius: 3 },
  times: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    width: '100%',
  },
  time: { color: '#aaa', fontSize: 12 },
  controls: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 28,
    marginTop: 10,
  },
  ctrl: { color: '#fff', fontSize: 28 },
  playBtn: {
    width: 60,
    height: 60,
    borderRadius: 30,
    backgroundColor: '#1db954',
    justifyContent: 'center',
    alignItems: 'center',
  },
  playTxt: { color: '#fff', fontSize: 26 },
  status: { color: '#777', fontSize: 11, marginTop: 10 },
  row: { flexDirection: 'row', alignItems: 'center', padding: 12, gap: 12 },
  rowActive: { backgroundColor: '#1e2a22' },
  thumb: { width: 48, height: 48, borderRadius: 6, backgroundColor: '#333' },
  rowTitle: { color: '#fff', fontSize: 15, fontWeight: '600' },
  rowArtist: { color: '#aaa', fontSize: 12 },
  rowDur: { color: '#aaa', fontSize: 12 },
});
