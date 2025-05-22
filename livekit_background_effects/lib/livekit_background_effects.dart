import 'package:livekit_background_effects_platform_interface/livekit_background_effects_platform_interface.dart';
import 'package:livekit_client/livekit_client.dart';
import 'package:logging/logging.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

export 'package:livekit_background_effects_platform_interface/livekit_background_effects_platform_interface.dart'
    show
        LivekitBackgroundEffectsOptions,
        Background,
        BackgroundPresets,
        BlurLevel;

class LivekitBackgroundEffects extends TrackProcessor<VideoProcessorOptions> {
  static final LivekitBackgroundEffectsPlatform _instance =
      LivekitBackgroundEffectsPlatform.instance;
  static int _counter = 1;
  // ignore: unused_field
  final _log = Logger("LivekitBackgroundEffects");

  final int _id;
  bool _initialized = false;
  LivekitBackgroundEffectsOptions? _options;

  LivekitBackgroundEffects() : _id = _counter++;
  @override
  Future<void> destroy() async {
    _log.info("destroy $_id");
    await _instance.destroy(_id);
    _initialized = false;
  }

  Future<void> updateBackground(
    LivekitBackgroundEffectsOptions background,
  ) async {
    if (_initialized) {
      _log.info("update options for $_id");
      await _instance.updateBackground(_id, background);
    } else {
      _log.info("defer update options");
    }
    _options = background;
  }

  @override
  Future<void> init(ProcessorOptions<TrackType> options) async {
    _log.info("initializing processor $_id");
    await _instance.initVideoBlurring(_id, options);
    _log.info("initializing processor $_id done");
    _initialized = true;
    if (_options != null) {
      _log.info("update background for $_id");
      await updateBackground(_options!);
    }
  }

  @override
  String get name => "LivekitBackgroundEffects";

  @override
  Future<void> onPublish(Room room) async {
    await _instance.onPublish(_id, room);
  }

  @override
  Future<void> onUnpublish() async {
    await _instance.onUnpublish(_id);
  }

  @override
  MediaStreamTrack? get processedTrack => _instance.getProcessedTrack(_id);

  @override
  Future<void> restart(ProcessorOptions<TrackType> options) async {
    await _instance.restart(_id, options);
  }

  static Future<bool> blurringSupported() {
    return _instance.blurringSupported();
  }
}
