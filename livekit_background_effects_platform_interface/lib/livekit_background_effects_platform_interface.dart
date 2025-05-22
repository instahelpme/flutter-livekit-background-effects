import 'package:flutter/foundation.dart';
import 'package:livekit_background_effects_platform_interface/livekit_background_effects_method_channel.dart';
import 'package:livekit_client/livekit_client.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

abstract interface class Background {
  String get assetId;
  String? get package;
  String get name;
  String? get license;
  String? get author;
  String? get source;
}

enum BackgroundPresets implements Background {
  mountains(
    filename: "bg1.jpg",
    license: "CC BY 4.0",
    author: "Julian Herzog",
    source:
        "https://commons.wikimedia.org/wiki/File:Llyn_Bochlwyd_Y_Gribin_Castell_y_Gwynt_2019_01.jpg",
  ),
  manorHouse(
    filename: "bg2.jpg",
    license: "CC BY-SA 4.0",
    author: "Crisco 1492",
    source:
        "https://commons.wikimedia.org/wiki/File:Interior_of_Willistead_Manor_(two_chairs_by_window),_Windsor,_Ontario,_2025-06-07.jpg",
  );

  const BackgroundPresets({
    required this.filename,
    required this.license,
    required this.author,
    required this.source,
  });

  final String filename;
  @override
  final String license;
  @override
  final String author;
  @override
  final String source;

  @override
  String get assetId {
    return "assets/backgrounds/$filename";
  }

  @override
  String get package => "livekit_background_effects_platform_interface";

  @override
  String get name => (this as Enum).name;
}

enum BlurLevel { light, heavy }

enum _Platform { web, ios, android }

_Platform _getPlatform() {
  if (kIsWeb) {
    return _Platform.web;
  } else if (defaultTargetPlatform == TargetPlatform.android) {
    return _Platform.android;
  } else if (defaultTargetPlatform == TargetPlatform.iOS) {
    return _Platform.ios;
  }

  throw UnimplementedError("platform not supported");
}

class LivekitBackgroundEffectsOptions {
  const LivekitBackgroundEffectsOptions._(this.blurLevel, this.virtualBackground);

  const LivekitBackgroundEffectsOptions.none() : this._(null, null);
  const LivekitBackgroundEffectsOptions.heavyBlurring()
    : this._(BlurLevel.heavy, null);
  const LivekitBackgroundEffectsOptions.lightBlurring()
    : this._(BlurLevel.light, null);
  const LivekitBackgroundEffectsOptions.virtualBackground(
    Background virtualBackground,
  ) : this._(null, virtualBackground);

  int? get blurRadius => switch (blurLevel) {
    null => null,
    BlurLevel.light => switch (_getPlatform()) {
      _Platform.web => 10,
      _Platform.ios => 12,
      _Platform.android => 9,
    },
    BlurLevel.heavy => switch (_getPlatform()) {
      _Platform.web => 16,
      _Platform.ios => 25,
      _Platform.android => 20,
    },
  };

  final BlurLevel? blurLevel;
  final Background? virtualBackground;
}

abstract class LivekitBackgroundEffectsPlatform extends PlatformInterface {
  /// Constructs a FlutterVideoBlurringPlatform.
  LivekitBackgroundEffectsPlatform() : super(token: _token);

  static final Object _token = Object();

  static LivekitBackgroundEffectsPlatform _instance =
      MethodChannelFlutterVideoBlurring();

  /// The default instance of [LivekitBackgroundEffectsPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterVideoBlurring].
  static LivekitBackgroundEffectsPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [LivekitBackgroundEffectsPlatform] when
  /// they register themselves.
  static set instance(LivekitBackgroundEffectsPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<bool> blurringSupported();
  Future<bool> platformSupported();
  Future<void> initVideoBlurring(
    int processorId,
    ProcessorOptions<TrackType> options,
  );
  Future<void> destroy(int processorId);
  Future<void> onPublish(int processorId, Room room);
  Future<void> onUnpublish(int processorId);
  Future<void> updateBackground(
    int processorId,
    LivekitBackgroundEffectsOptions background,
  );
  Future<void> restart(int processorId, ProcessorOptions<TrackType> options);
  MediaStreamTrack? getProcessedTrack(int processorId);
}
