/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

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
    await _instance.destroy(_id);
    _initialized = false;
  }

  Future<void> updateBackground(
    LivekitBackgroundEffectsOptions background,
  ) async {
    if (_initialized) {
      await _instance.updateBackground(_id, background);
    }
    _options = background;
  }

  @override
  Future<void> init(ProcessorOptions<TrackType> options) async {
    await _instance.initVideoBlurring(_id, options);
    _initialized = true;
    if (_options != null) {
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
