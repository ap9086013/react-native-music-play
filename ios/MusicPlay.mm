#import "MusicPlay.h"
#import <AVFoundation/AVFoundation.h>
#import <MediaPlayer/MediaPlayer.h>

static void *kItemStatusCtx = &kItemStatusCtx;
static void *kTimeControlCtx = &kTimeControlCtx;

@implementation MusicPlay {
  AVPlayer *_player;
  AVPlayerItem *_observedItem;
  id _timeObserver;

  NSArray<NSDictionary *> *_queue;
  NSInteger _index;
  NSString *_currentUrl;
  NSString *_currentMediaId;

  // id -> last position in seconds (same idea as savedPositions on Android)
  NSMutableDictionary<NSString *, NSNumber *> *_saved;

  BOOL _loop;
  BOOL _stopped;
  BOOL _commandsReady;
}

RCT_EXPORT_MODULE(MusicPlay)

+ (BOOL)requiresMainQueueSetup { return NO; }
- (dispatch_queue_t)methodQueue { return dispatch_get_main_queue(); }

- (instancetype)init {
  if (self = [super init]) {
    _saved = [NSMutableDictionary new];
    _queue = @[];
    _index = -1;
    _stopped = YES;
  }
  return self;
}

- (NSArray<NSString *> *)supportedEvents {
  return @[ @"onProgress", @"onLoad", @"onLoading", @"onStateChange",
            @"onFinish", @"onTrackChanged", @"onError" ];
}

#pragma mark - Helpers

- (void)emit:(NSString *)name extra:(NSDictionary *)extra {
  NSMutableDictionary *body = extra ? [extra mutableCopy] : [NSMutableDictionary new];
  if (_currentUrl) body[@"url"] = _currentUrl;
  if (_currentMediaId) body[@"id"] = _currentMediaId;
  [self sendEventWithName:name body:body];
}

- (double)safeSeconds:(CMTime)t {
  double s = CMTimeGetSeconds(t);
  return (isfinite(s) && s > 0) ? s : 0;
}

- (double)duration { return [self safeSeconds:_player.currentItem.duration]; }
- (double)currentSeconds { return [self safeSeconds:_player.currentTime]; }

- (NSURL *)urlFrom:(NSString *)s {
  if ([s hasPrefix:@"/"]) return [NSURL fileURLWithPath:s];
  return [NSURL URLWithString:s];
}

- (void)rememberPosition:(NSString *)mediaId seconds:(double)sec finished:(BOOL)finished {
  if (mediaId.length == 0) return;
  if (finished) {
    [_saved removeObjectForKey:mediaId];
  } else if (sec > 1.0) {
    _saved[mediaId] = @(sec);
  }
}

- (BOOL)urlMatches:(NSString *)url {
  return url == nil || [url isEqualToString:_currentUrl];
}

#pragma mark - Player setup

- (void)ensurePlayer {
  if (_player) return;

  AVAudioSession *session = [AVAudioSession sharedInstance];
  [session setCategory:AVAudioSessionCategoryPlayback
                  mode:AVAudioSessionModeDefault
               options:0
                 error:nil];
  [session setActive:YES error:nil];

  _player = [AVPlayer new];
  _player.automaticallyWaitsToMinimizeStalling = YES;
  [_player addObserver:self
            forKeyPath:@"timeControlStatus"
               options:NSKeyValueObservingOptionNew
               context:kTimeControlCtx];

  [self setupRemoteCommands];
}

- (void)setupRemoteCommands {
  if (_commandsReady) return;
  _commandsReady = YES;

  __weak MusicPlay *weakSelf = self;
  MPRemoteCommandCenter *c = [MPRemoteCommandCenter sharedCommandCenter];

  [c.playCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    if (!weakSelf->_player.currentItem) return MPRemoteCommandHandlerStatusNoActionableNowPlayingItem;
    [weakSelf->_player play];
    return MPRemoteCommandHandlerStatusSuccess;
  }];

  [c.pauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    [weakSelf->_player pause];
    return MPRemoteCommandHandlerStatusSuccess;
  }];

  [c.togglePlayPauseCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    MusicPlay *s = weakSelf;
    if (!s->_player.currentItem) return MPRemoteCommandHandlerStatusNoActionableNowPlayingItem;
    if (s->_player.timeControlStatus == AVPlayerTimeControlStatusPaused) [s->_player play];
    else [s->_player pause];
    return MPRemoteCommandHandlerStatusSuccess;
  }];

  [c.nextTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    MusicPlay *s = weakSelf;
    if (s->_index + 1 >= (NSInteger)s->_queue.count) return MPRemoteCommandHandlerStatusNoActionableNowPlayingItem;
    [s goToIndex:s->_index + 1 reason:@"seek"];
    return MPRemoteCommandHandlerStatusSuccess;
  }];

  [c.previousTrackCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    MusicPlay *s = weakSelf;
    if ([s currentSeconds] > 3.0 || s->_index <= 0) {
      [s->_player seekToTime:kCMTimeZero];
    } else {
      [s goToIndex:s->_index - 1 reason:@"seek"];
    }
    return MPRemoteCommandHandlerStatusSuccess;
  }];

  [c.changePlaybackPositionCommand addTargetWithHandler:^MPRemoteCommandHandlerStatus(MPRemoteCommandEvent *e) {
    MPChangePlaybackPositionCommandEvent *pe = (MPChangePlaybackPositionCommandEvent *)e;
    [weakSelf->_player seekToTime:CMTimeMakeWithSeconds(pe.positionTime, 1000)
                completionHandler:^(BOOL finished) { [weakSelf updateNowPlaying]; }];
    return MPRemoteCommandHandlerStatusSuccess;
  }];
}

#pragma mark - Item handling

- (void)detachItem {
  if (_observedItem) {
    [_observedItem removeObserver:self forKeyPath:@"status" context:kItemStatusCtx];
    [[NSNotificationCenter defaultCenter] removeObserver:self
                                                    name:AVPlayerItemDidPlayToEndTimeNotification
                                                  object:_observedItem];
    _observedItem = nil;
  }
}

- (void)attachItem:(AVPlayerItem *)item {
  _observedItem = item;
  [item addObserver:self
         forKeyPath:@"status"
            options:NSKeyValueObservingOptionNew
            context:kItemStatusCtx];
  [[NSNotificationCenter defaultCenter] addObserver:self
                                           selector:@selector(itemDidFinish:)
                                               name:AVPlayerItemDidPlayToEndTimeNotification
                                             object:item];
}

- (void)loadIndex:(NSInteger)i {
  [self ensurePlayer];

  NSDictionary *t = _queue[i];
  _index = i;
  _currentUrl = t[@"url"];
  _currentMediaId = t[@"id"];
  _stopped = NO;

  [self detachItem];

  NSURL *url = [self urlFrom:_currentUrl];
  if (!url) {
    [self emit:@"onError" extra:@{ @"message": @"Invalid audio URL" }];
    return;
  }

  AVPlayerItem *item = [AVPlayerItem playerItemWithURL:url];
  [self attachItem:item];

  [self emit:@"onLoading" extra:@{ @"loading": @YES }];
  [_player replaceCurrentItemWithPlayerItem:item];

  // resume where the user left this track
  double resume = _currentMediaId ? _saved[_currentMediaId].doubleValue : 0;
  if (resume > 0) {
    [item seekToTime:CMTimeMakeWithSeconds(resume, 1000) completionHandler:nil];
  }

  [_player play];
  [self startProgress];
  [self updateNowPlaying];
  [self loadArtwork];
}

- (void)goToIndex:(NSInteger)i reason:(NSString *)reason {
  if (i < 0 || i >= (NSInteger)_queue.count) return;
  [self rememberPosition:_currentMediaId seconds:[self currentSeconds] finished:NO];
  [self loadIndex:i];
  [self emit:@"onTrackChanged" extra:@{ @"reason": reason }];
}

- (void)itemDidFinish:(NSNotification *)n {
  // finished tracks restart from 0 next time
  [self rememberPosition:_currentMediaId seconds:0 finished:YES];

  if (_loop) {
    [_player seekToTime:kCMTimeZero];
    [_player play];
    return;
  }

  if (_index + 1 < (NSInteger)_queue.count) {
    [self loadIndex:_index + 1];
    [self emit:@"onTrackChanged" extra:@{ @"reason": @"auto" }];
    return;
  }

  _stopped = YES;
  [self emit:@"onFinish" extra:nil];
  [self emit:@"onStateChange" extra:@{ @"state": @"stopped" }];
  [self stopProgress];
}

#pragma mark - KVO

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary *)change
                       context:(void *)context {
  if (context == kTimeControlCtx) {
    if (_stopped || !_player.currentItem) return;

    switch (_player.timeControlStatus) {
      case AVPlayerTimeControlStatusPlaying:
        [self emit:@"onLoading" extra:@{ @"loading": @NO }];
        [self emit:@"onStateChange" extra:@{ @"state": @"playing" }];
        break;
      case AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate:
        [self emit:@"onLoading" extra:@{ @"loading": @YES }];
        break;
      case AVPlayerTimeControlStatusPaused: {
        // ignore the automatic pause at the end of a track
        double d = [self duration];
        if (d > 0 && [self currentSeconds] >= d - 0.1) break;
        [self emit:@"onStateChange" extra:@{ @"state": @"paused" }];
        break;
      }
    }
    [self updateNowPlaying];
    return;
  }

  if (context == kItemStatusCtx) {
    AVPlayerItem *item = (AVPlayerItem *)object;
    if (item != _observedItem) return;

    if (item.status == AVPlayerItemStatusReadyToPlay) {
      [self emit:@"onLoad" extra:@{ @"duration": @([self duration]) }];
      [self emit:@"onLoading" extra:@{ @"loading": @NO }];
      [self updateNowPlaying];
    } else if (item.status == AVPlayerItemStatusFailed) {
      NSString *msg = item.error.localizedDescription ?: @"Unable to play audio";
      [self emit:@"onError" extra:@{ @"message": msg }];
      [self emit:@"onLoading" extra:@{ @"loading": @NO }];
    }
    return;
  }

  [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
}

#pragma mark - Progress

- (void)startProgress {
  [self stopProgress];
  __weak MusicPlay *weakSelf = self;
  _timeObserver = [_player
      addPeriodicTimeObserverForInterval:CMTimeMakeWithSeconds(0.5, 600)
                                   queue:dispatch_get_main_queue()
                              usingBlock:^(CMTime time) {
    MusicPlay *s = weakSelf;
    if (!s) return;
    [s emit:@"onProgress"
        extra:@{ @"currentTime": @([s safeSeconds:time]),
                 @"duration": @([s duration]) }];
  }];
}

- (void)stopProgress {
  if (_timeObserver) {
    [_player removeTimeObserver:_timeObserver];
    _timeObserver = nil;
  }
}

#pragma mark - Lock screen / Control Center

- (void)updateNowPlaying {
  if (!_player.currentItem || _index < 0 || _index >= (NSInteger)_queue.count) return;

  NSDictionary *t = _queue[_index];
  NSMutableDictionary *info = [NSMutableDictionary new];
  if (t[@"title"]) info[MPMediaItemPropertyTitle] = t[@"title"];
  if (t[@"artist"]) info[MPMediaItemPropertyArtist] = t[@"artist"];
  info[MPMediaItemPropertyPlaybackDuration] = @([self duration]);
  info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = @([self currentSeconds]);
  info[MPNowPlayingInfoPropertyPlaybackRate] =
      @(_player.timeControlStatus == AVPlayerTimeControlStatusPlaying ? 1.0 : 0.0);

  MPNowPlayingInfoCenter *center = [MPNowPlayingInfoCenter defaultCenter];
  MPMediaItemArtwork *art = center.nowPlayingInfo[MPMediaItemPropertyArtwork];
  if (art) info[MPMediaItemPropertyArtwork] = art;
  center.nowPlayingInfo = info;
}

- (void)loadArtwork {
  NSDictionary *t = _queue[_index];
  NSString *artUrl = t[@"artworkUrl"];
  NSString *forId = _currentMediaId;

  // clear the previous track's artwork
  NSMutableDictionary *info = [[MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo mutableCopy];
  [info removeObjectForKey:MPMediaItemPropertyArtwork];
  [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = info;

  if (artUrl.length == 0) return;
  NSURL *url = [NSURL URLWithString:artUrl];
  if (!url) return;

  __weak MusicPlay *weakSelf = self;
  [[[NSURLSession sharedSession] dataTaskWithURL:url
                               completionHandler:^(NSData *data, NSURLResponse *r, NSError *e) {
    UIImage *img = data ? [UIImage imageWithData:data] : nil;
    if (!img) return;
    dispatch_async(dispatch_get_main_queue(), ^{
      MusicPlay *s = weakSelf;
      if (!s || ![s->_currentMediaId isEqualToString:forId]) return; // track already changed
      MPMediaItemArtwork *art =
          [[MPMediaItemArtwork alloc] initWithBoundsSize:img.size
                                          requestHandler:^UIImage *(CGSize size) { return img; }];
      NSMutableDictionary *ni = [[MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo mutableCopy]
                                ?: [NSMutableDictionary new];
      ni[MPMediaItemPropertyArtwork] = art;
      [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = ni;
    });
  }] resume];
}

#pragma mark - Spec methods

- (void)playInPlaylist:(NSArray *)items startid:(NSString *)startid {
  NSMutableArray<NSDictionary *> *list = [NSMutableArray new];
  for (id obj in items) {
    if (![obj isKindOfClass:NSDictionary.class]) continue;
    NSDictionary *d = obj;
    NSString *id = d[@"id"];
    NSString *url = d[@"url"];
    if (![id isKindOfClass:NSString.class] || ![url isKindOfClass:NSString.class]) continue;
    if ([url stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet].length == 0) continue;
    [list addObject:d];
  }

  NSInteger start = -1;
  for (NSInteger i = 0; i < (NSInteger)list.count; i++) {
    if ([list[i][@"id"] isEqualToString:startid]) { start = i; break; }
  }

  if (start < 0) {
    _currentMediaId = startid;
    [self emit:@"onError" extra:@{ @"message": @"Track not found in playlist" }];
    return;
  }

  // same track already loaded: just resume it
  BOOL alreadyThisTrack = _player.currentItem && !_stopped &&
                          [_currentMediaId isEqualToString:startid];

  if (_currentMediaId && _player.currentItem) {
    [self rememberPosition:_currentMediaId seconds:[self currentSeconds] finished:NO];
  }

  _queue = list;
  _loop = NO;

  if (alreadyThisTrack) {
    _index = start;
    [self emit:@"onLoading" extra:@{ @"loading": @NO }];
    [_player play];
    [self startProgress];
    return;
  }

  [self loadIndex:start];
}

- (void)play:(NSString *)url {
  if ([url stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet].length == 0) {
    [self emit:@"onError" extra:@{ @"message": @"Audio URL is empty" }];
    return;
  }
  _queue = @[ @{ @"url": url } ];
  _loop = NO;
  [self loadIndex:0];
  _currentMediaId = nil; // legacy single-track play has no id
}

- (void)pause:(NSString *)url {
  if (![self urlMatches:url]) return;
  [_player pause];
}

- (void)resume:(NSString *)url {
  if (![self urlMatches:url]) return;
  [_player play];
}

- (void)stop:(NSString *)url {
  if (![self urlMatches:url]) return;

  NSString *stoppedUrl = _currentUrl;
  NSString *stoppedId = _currentMediaId;

  [self rememberPosition:_currentMediaId seconds:[self currentSeconds] finished:NO];

  _stopped = YES;
  [self stopProgress];
  [self detachItem];
  [_player pause];
  [_player replaceCurrentItemWithPlayerItem:nil];
  _queue = @[];
  _index = -1;
  [MPNowPlayingInfoCenter defaultCenter].nowPlayingInfo = nil;

  NSMutableDictionary *body = [@{ @"state": @"stopped" } mutableCopy];
  if (stoppedUrl) body[@"url"] = stoppedUrl;
  if (stoppedId) body[@"id"] = stoppedId;
  [self sendEventWithName:@"onStateChange" body:body];

  _currentUrl = nil;
  _currentMediaId = nil;
}

- (void)seekTo:(double)seconds url:(NSString *)url {
  if (![self urlMatches:url]) return;
  __weak MusicPlay *weakSelf = self;
  [_player seekToTime:CMTimeMakeWithSeconds(seconds, 1000)
      completionHandler:^(BOOL finished) { [weakSelf updateNowPlaying]; }];
}

- (void)setLooping:(BOOL)enabled {
  _loop = enabled;
}

- (void)getDuration:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@([self duration]));
}

- (void)getCurrentTime:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@([self currentSeconds]));
}

- (void)getCurrentTrackInfo:(RCTPromiseResolveBlock)resolve reject:(RCTPromiseRejectBlock)reject {
  resolve(@{
    @"id": _currentMediaId ?: [NSNull null],
    @"url": _currentUrl ?: [NSNull null],
    @"positionSeconds": @([self currentSeconds]),
    @"isPlaying": @(_player.timeControlStatus == AVPlayerTimeControlStatusPlaying),
  });
}

#pragma mark - Turbo module

- (std::shared_ptr<facebook::react::TurboModule>)getTurboModule:
    (const facebook::react::ObjCTurboModule::InitParams &)params {
  return std::make_shared<facebook::react::NativeMusicPlaySpecJSI>(params);
}

#pragma mark - Cleanup

- (void)invalidate {
  [self stopProgress];
  [self detachItem];
  if (_player) {
    [_player removeObserver:self forKeyPath:@"timeControlStatus" context:kTimeControlCtx];
    [_player pause];
    _player = nil;
  }
  [super invalidate];
}

@end