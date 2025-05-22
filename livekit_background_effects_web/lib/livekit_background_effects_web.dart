/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import 'dart:async';

import 'package:flutter_web_plugins/flutter_web_plugins.dart';
import 'package:livekit_background_effects_web/import_js_library.dart';
import 'package:livekit_background_effects_web/track_processor.dart';
import 'package:livekit_background_effects_web/track_processor_js.dart';
import 'package:livekit_client/livekit_client.dart';
import 'package:logging/logging.dart';
// ignore: unused_import
import 'package:web/web.dart' as web;
import 'package:livekit_background_effects_platform_interface/livekit_background_effects_platform_interface.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

/// A web implementation of the LivekitBackgroundEffectsWebPlatform of the LivekitBackgroundEffectsWeb plugin.
class LivekitBackgroundEffectsWeb extends LivekitBackgroundEffectsPlatform {
  Future<void>? _loaded;
  final _log = Logger("LivekitBackgroundEffects");
  final Map<int, TrackProcessorWrapper> _processors = {};

  /// Constructs a LivekitBackgroundEffectsWebWeb
  LivekitBackgroundEffectsWeb();

  Future<void> _ensureLoaded() {
    _loaded ??= importJsLibrary(
      url: "assets/track-processor.js",
      flutterPluginName: "livekit_background_effects_web",
    );
    return _loaded!;
  }

  static void registerWith(Registrar registrar) {
    LivekitBackgroundEffectsPlatform.instance = LivekitBackgroundEffectsWeb();
  }

  @override
  Future<void> destroy(int processorId) async {
    await _ensureLoaded();
    final processor = _processors[processorId];
    if (processor == null) {
      throw Exception("can't destroy processor $processorId");
    }
    await processor.destroy();
    _processors.remove(processorId);
  }

  @override
  MediaStreamTrack? getProcessedTrack(int processorId) {
    return _processors[processorId]?.processedTrack;
  }

  @override
  Future<void> initVideoBlurring(
    int processorId,
    ProcessorOptions<TrackType> options,
  ) async {
    await _ensureLoaded();

    if (_processors.containsKey(processorId)) {
      throw Exception("processor $processorId is already initialized");
    }

    _processors[processorId] = TrackProcessorWrapper();
    final converted = VideoProcessorOptions(track: options.track);
    await _processors[processorId]!.init(converted);
  }

  @override
  Future<void> onPublish(int processorId, Room room) async {
    await _ensureLoaded();
    _log.warning("$processorId onPublish not implemented");
  }

  @override
  Future<void> onUnpublish(int processorId) async {
    await _ensureLoaded();
    _log.warning("$processorId onUnpublish not implemented");
  }

  @override
  Future<void> restart(
    int processorId,
    ProcessorOptions<TrackType> options,
  ) async {
    await _ensureLoaded();
    final processor = _processors[processorId];
    if (processor == null) {
      throw Exception("can't restart processor $processorId");
    }
    await processor.restart(options as VideoProcessorOptions);
  }

  @override
  Future<bool> blurringSupported() async {
    await _ensureLoaded();
    return JSProcessor.isSupported;
  }

  @override
  Future<bool> platformSupported() async {
    return true;
  }

  @override
  Future<void> updateBackground(
    int processorId,
    LivekitBackgroundEffectsOptions background,
  ) async {
    await _ensureLoaded();
    final processor = _processors[processorId];
    if (processor == null) {
      throw Exception("can't update options for $processorId");
    }
    await processor.updateOptions(background);
  }
}

// Firefox 27% CPU no blurring, 45+25% (70%) CPU with blurring
// Chromium 23% CPU no blurring, 20+10+13% (43%) CPU with blurring
