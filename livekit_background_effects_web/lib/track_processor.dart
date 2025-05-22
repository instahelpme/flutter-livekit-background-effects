/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import 'dart:js_interop';
import 'dart:ui_web';

import 'package:livekit_background_effects_platform_interface/livekit_background_effects_platform_interface.dart';
import 'package:livekit_background_effects_web/track_processor_js.dart';
import 'package:livekit_client/livekit_client.dart';
import 'package:dart_webrtc/dart_webrtc.dart';
import 'package:logging/logging.dart';
import 'package:web/web.dart' as web;
import 'package:webrtc_interface/webrtc_interface.dart' as rtc;

class TrackProcessorWrapper {
  // ignore: unused_field
  final _log = Logger("TrackProcessorWrapper");
  TrackProcessorWrapper();
  late final JSProcessor jsProcessor;
  late final web.HTMLVideoElement element;
  late final web.MediaStreamTrack jsTrack;

  Future<void> init(VideoProcessorOptions options) async {
    final blurOptions = JSBackgroundOptions(
      assetPaths: JSAssetPaths(
        tasksVisionFileSet: assetManager
            .getAssetUrl(
              "packages/livekit_background_effects_web/assets/mediapipe",
            )
            .toJS,
        modelAssetPath: assetManager
            .getAssetUrl(
              "packages/livekit_background_effects_web/assets/segmenter/selfie_segmenter.tflite",
            )
            .toJS,
      ),
    );
    jsProcessor = generateJSBackgroundProcessor(blurOptions);
    element = web.document.createElement("video") as web.HTMLVideoElement;
    jsTrack = (options.track as MediaStreamTrackWeb).jsTrack;
    element.srcObject = web.MediaStream([jsTrack].toJS);
    element.autoplay = true;
    element.muted = true;
    await jsProcessor
        .init(
          JSVideoProcessorOptions(
            kind: 'video'.toJS,
            track: jsTrack,
            element: element,
          ),
        )
        .toDart;
  }

  Future<void> destroy() async {
    jsProcessor.processedTrack?.stop();
    await jsProcessor.destroy().toDart;
    element.pause();
    element.srcObject = null;
    element.remove();
    jsTrack.stop();
  }

  Future<void> restart(VideoProcessorOptions options) async {
    await destroy();
    await init(options);
  }

  rtc.MediaStreamTrack? get processedTrack {
    final jsTrack = jsProcessor.processedTrack;
    if (jsTrack != null) {
      return MediaStreamTrackWeb(jsTrack);
    } else {
      return null;
    }
  }

  Future<void> updateOptions(LivekitBackgroundEffectsOptions options) async {
    final String? imagePath;
    if (options.virtualBackground != null) {
      if (options.virtualBackground!.package != null) {
        imagePath = assetManager.getAssetUrl(
          'packages/${options.virtualBackground!.package}/${options.virtualBackground!.assetId}',
        );
      } else {
        imagePath = assetManager.getAssetUrl(
          options.virtualBackground!.assetId,
        );
      }
    } else {
      imagePath = null;
    }
    final blurOptions = JSBackgroundOptions(
      blurRadius: options.blurRadius?.toJS,
      imagePath: imagePath?.toJS,
    );
    jsProcessor.transformer.update(blurOptions);
  }
}
