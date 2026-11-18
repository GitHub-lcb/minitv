import 'dart:async';

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../api.dart';
import '../copy.dart';
import '../hls.dart';
import '../player.dart';
import '../ui.dart';

/// Plays a single episode: mints a signed URL, plays it, reports real events,
/// and can pin one HLS rendition when the viewer picks a quality.
///
/// The URL is requested here instead of being shipped in the feed payload
/// because playback tokens live five minutes - a URL minted when the page was
/// fetched would already be dead by the time the viewer swipes to it.
///
/// Quality switching works by loading the variant's own media playlist
/// (`480p/video.m3u8`) instead of the master: AVPlayer then has nothing to
/// adapt and plays exactly that ladder rung. The signature token rides in the
/// URL path, so the resolved variant URL stays authorised.
class EpisodePlayer extends StatefulWidget {
  const EpisodePlayer({
    super.key,
    required this.dramaId,
    required this.episodeId,
    this.resumeSeconds = 0,
    this.active = true,
    this.token,
    this.onFinished,
    this.showProgressBar = false,
    this.player,
    this.speed = 1.0,
    this.quality = 'auto',
    this.onQualities,
  });

  final int dramaId;
  final int episodeId;
  final int resumeSeconds;
  final bool active;
  final String? token;
  final VoidCallback? onFinished;

  /// Injected only by tests; production builds their own player.
  final MiniPlayer? player;

  /// Playback rate applied once the video is ready, and whenever it changes.
  final double speed;

  /// 'auto' lets the player adapt; any other value is an [HlsVariant.label].
  final String quality;

  /// Reports the rendition ladder this episode actually offers, so the
  /// playback settings sheet can list real options instead of a fixed set.
  final ValueChanged<List<String>>? onQualities;

  /// Thin white progress bar pinned to the bottom edge (design draft P6/P8).
  final bool showProgressBar;

  /// Debug only: jump to the end so auto-advance can be verified quickly.
  static const debugSeekEnd = bool.fromEnvironment('DEBUG_SEEK_END', defaultValue: false);

  @override
  State<EpisodePlayer> createState() => _EpisodePlayerState();
}

class _EpisodePlayerState extends State<EpisodePlayer> {
  late final MiniPlayer player = widget.player ?? MiniPlayer();
  Map<String, dynamic>? auth;
  String? error;
  Timer? heartbeat;
  bool finished = false;

  /// Master playlist plus its parsed ladder, kept for mid-playback switches.
  String? _masterUrl;
  List<HlsVariant> _variants = const [];
  String _loadedQuality = 'auto';

  /// Third-party player URL when the episode is an external embed
  /// (YouTube / TikTok / Vimeo / Facebook / 抖音 / 通用 iframe). The platform
  /// owns the surface, so there is no controller, ladder or progress bar.
  String? _embedUrl;
  double _embedAspect = 16 / 9;

  /// User paused by tapping the video. Kept separate from `active` so swiping
  /// away and back resumes playback, while an explicit pause sticks.
  bool pausedByUser = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void didUpdateWidget(covariant EpisodePlayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.episodeId != oldWidget.episodeId) {
      finished = false;
      pausedByUser = false;
      heartbeat?.cancel();
      _masterUrl = null;
      _variants = const [];
      _embedUrl = null;
      unawaited(_load());
      return;
    }
    if (widget.quality != oldWidget.quality) {
      unawaited(_applyQuality(widget.quality));
    }
    if (widget.speed != oldWidget.speed) {
      unawaited(player.setSpeed(widget.speed));
    }
    if (widget.active && !oldWidget.active) {
      if (!pausedByUser) unawaited(player.play());
    } else if (!widget.active && oldWidget.active) {
      unawaited(player.pause());
    }
  }

  @override
  void dispose() {
    heartbeat?.cancel();
    player.removeListener(_checkFinished);
    unawaited(player.release());
    super.dispose();
  }

  /// The variant URL for [quality], or the master when Auto / unknown.
  String _urlFor(String quality, String master) {
    if (quality == 'auto') return master;
    for (final variant in _variants) {
      if (variant.label == quality) return variant.url;
    }
    return master;
  }

  String _effectiveQuality(String quality) => _variants.any((v) => v.label == quality) ? quality : 'auto';

  Future<void> _load() async {
    final api = MiniApi(token: widget.token);
    final resume = widget.resumeSeconds;
    try {
      auth = await api.postJson(
        '/v1/playback',
        {'drama_id': widget.dramaId, 'episode_id': widget.episodeId},
      );
      if (auth?['allowed'] == true) {
        final type = (auth!['type'] as String?) ?? 'hls';
        final embedUrl = auth!['embed_url'] as String?;
        if (type == 'embed' && embedUrl != null && embedUrl.isNotEmpty) {
          // Third-party players own their controls: load the embed page and
          // count the open as "start"; there is no heartbeat or completion.
          _embedUrl = embedUrl;
          _embedAspect = _aspectFrom(auth!['aspect_ratio']);
          _masterUrl = null;
          _variants = const [];
          widget.onQualities?.call(const []);
          _report('start', 0);
        } else {
          final source = auth!['url'] as String;
          _embedUrl = null;
          _masterUrl = source;
          if (type == 'mp4') {
            // Progressive MP4: no manifest to parse and no rendition ladder.
            _variants = const [];
            widget.onQualities?.call(const []);
            _loadedQuality = 'auto';
          } else {
            _variants = await loadHlsVariants(source);
            widget.onQualities?.call([for (final variant in _variants) variant.label]);
            _loadedQuality = _effectiveQuality(widget.quality);
          }

          await player.load(_urlFor(widget.quality, source));
          player.removeListener(_checkFinished);
          if (EpisodePlayer.debugSeekEnd) {
            final end = player.duration - const Duration(seconds: 2);
            if (end > Duration.zero) await player.seek(end);
          } else if (resume > 0) {
            await player.seek(Duration(seconds: resume));
          }
          player.addListener(_checkFinished);
          await player.setSpeed(widget.speed);
          await player.play();
          // Real analytics: one "start", then a progress heartbeat. Switching
          // quality mid-episode reloads the surface but is not a new start.
          _report('start', resume);
          heartbeat = Timer.periodic(
            const Duration(seconds: 15),
            (_) => _report('progress', player.position.inSeconds),
          );
        }
      }
      error = null;
    } catch (_) {
      error = 'network';
    }
    if (mounted) setState(() {});
  }

  /// Swap the rendition without losing the playhead or the play state.
  Future<void> _applyQuality(String quality) async {
    // Embeds and progressive MP4 files have no rendition ladder to switch.
    if (_embedUrl != null) return;
    final master = _masterUrl;
    if (master == null) return; // still loading; _load() will honour it
    final target = _urlFor(quality, master);
    final nextQuality = _effectiveQuality(quality);
    if (nextQuality == _loadedQuality) return;

    final position = player.position;
    final wasPlaying = player.isPlaying && !pausedByUser;
    try {
      await player.load(target);
      player.removeListener(_checkFinished);
      _loadedQuality = nextQuality;
      if (position > Duration.zero) await player.seek(position);
      player.addListener(_checkFinished);
      await player.setSpeed(widget.speed);
      if (wasPlaying) await player.play();
      if (mounted) setState(() {});
    } catch (_) {
      // Keep watching on the old rendition rather than showing a dead surface.
    }
  }

  /// Fire-and-forget: playback never waits on analytics and a failed report
  /// must not interrupt the viewer.
  void _report(String kind, int positionSeconds) {
    unawaited(
      MiniApi(token: widget.token)
          .postJson('/v1/playback/$kind', {
            'episode_id': widget.episodeId,
            'position_seconds': positionSeconds,
          })
          .catchError((_) => <String, dynamic>{}),
    );
  }

  /// Tap toggles playback. The icon only shows while paused, so the video
  /// stays unobstructed while it plays.
  void _togglePlay() {
    if (pausedByUser) {
      pausedByUser = false;
      unawaited(player.play());
    } else {
      pausedByUser = true;
      unawaited(player.pause());
    }
    if (mounted) setState(() {});
  }

  void _checkFinished() {
    if (finished || !player.isFinished) return;
    finished = true;
    heartbeat?.cancel();
    _report('complete', player.duration.inSeconds);
    widget.onFinished?.call();
    if (mounted) setState(() {});
  }

  Widget _surface() {
    final copy = Copy(Localizations.localeOf(context).languageCode);
    if (error != null) return RetryBody(label: copy.t('retry'), onRetry: _load);
    if (auth == null) return const Center(child: CircularProgressIndicator(color: red));
    if (auth!['allowed'] != true) {
      return Center(
        child: Text(copy.t('locked'), style: const TextStyle(color: gold, fontSize: 18)),
      );
    }
    final embedUrl = _embedUrl;
    if (embedUrl != null) {
      // The platform player owns the whole surface: no tap overlay (it would
      // swallow taps meant for YouTube/TikTok controls) and no progress bar.
      return ColoredBox(
        color: Colors.black,
        child: Center(
          child: AspectRatio(
            aspectRatio: _embedAspect,
            child: _EmbedSurface(key: ValueKey(embedUrl), url: embedUrl),
          ),
        ),
      );
    }
    final controller = player.controller;
    final ready = controller != null && controller.value.isInitialized;
    if (!ready) return const ColoredBox(color: Colors.black);
    return ColoredBox(
      color: Colors.black,
      child: Stack(
        fit: StackFit.expand,
        children: [
          FittedBox(
            fit: BoxFit.cover,
            child: SizedBox(
              width: controller.value.size.width,
              height: controller.value.size.height,
              child: VideoPlayer(controller),
            ),
          ),
          if (widget.showProgressBar)
            Positioned(
              left: 0,
              right: 0,
              bottom: 0,
              child: _PlaybackProgressBar(controller: controller),
            ),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      fit: StackFit.expand,
      children: [
        _surface(),
        // Sits above every state so a tap always reaches the player, and the
        // play icon marks a paused video. Embeds must keep their own controls
        // tappable, so they get no overlay.
        if (_embedUrl == null)
          GestureDetector(
            behavior: HitTestBehavior.translucent,
            onTap: _togglePlay,
            child: pausedByUser
                ? const Center(
                    child: Icon(
                      Icons.play_arrow_rounded,
                      color: Colors.white,
                      size: 72,
                      shadows: [Shadow(color: Colors.black54, blurRadius: 12)],
                    ),
                  )
                : const SizedBox.expand(),
          ),
      ],
    );
  }
}

/// "9:16" → 0.5625. Falls back to 16:9 for missing or malformed values.
double _aspectFrom(Object? raw) {
  if (raw is String) {
    final parts = raw.split(':');
    if (parts.length == 2) {
      final width = double.tryParse(parts[0].trim());
      final height = double.tryParse(parts[1].trim());
      if (width != null && height != null && width > 0 && height > 0) {
        return width / height;
      }
    }
  }
  return 16 / 9;
}

/// Hosts a third-party embed page (YouTube / TikTok / Vimeo / Facebook / 抖音 /
/// generic iframe). The webview is created lazily when an embed episode first
/// plays, so Bunny-only builds never touch the platform channel.
class _EmbedSurface extends StatefulWidget {
  const _EmbedSurface({super.key, required this.url});

  final String url;

  @override
  State<_EmbedSurface> createState() => _EmbedSurfaceState();
}

class _EmbedSurfaceState extends State<_EmbedSurface> {
  late final WebViewController _controller;

  @override
  void initState() {
    super.initState();
    _controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(Colors.black)
      // YouTube rejects embedded players without an HTTP Referer (error 153),
      // and WebView sends none by default. The value only has to be a valid
      // origin, so the public site doubles as the embed referrer.
      ..loadRequest(
        Uri.parse(widget.url),
        headers: const {'Referer': 'https://minitv.us/'},
      );
  }

  @override
  Widget build(BuildContext context) {
    return WebViewWidget(controller: _controller);
  }
}

/// Live playhead bar. Listens to the controller, so it repaints only when the
/// video position actually moves.
class _PlaybackProgressBar extends StatelessWidget {
  const _PlaybackProgressBar({required this.controller});
  final VideoPlayerController controller;

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<VideoPlayerValue>(
      valueListenable: controller,
      builder: (context, value, _) {
        final total = value.duration.inMilliseconds;
        final position = value.position.inMilliseconds;
        return SizedBox(
          height: 3,
          child: LinearProgressIndicator(
            value: total > 0 ? (position / total).clamp(0.0, 1.0) : 0.0,
            minHeight: 3,
            backgroundColor: Colors.white24,
            valueColor: const AlwaysStoppedAnimation<Color>(Colors.white),
          ),
        );
      },
    );
  }
}

/// The episode metadata strip shown under a playing video.
class EpisodeCaption extends StatelessWidget {
  const EpisodeCaption({
    super.key,
    required this.title,
    required this.episodeNumber,
    required this.episodeCount,
  });

  final String title;
  final int episodeNumber;
  final int episodeCount;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          title,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 17,
            fontWeight: FontWeight.w700,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          'Episode $episodeNumber / $episodeCount',
          style: const TextStyle(color: Colors.white70, fontSize: 13),
        ),
      ],
    );
  }
}
