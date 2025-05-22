/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:livekit_background_effects_platform_interface/livekit_background_effects_platform_interface.dart';
import 'package:livekit_client/livekit_client.dart';
import 'package:webrtc_interface/webrtc_interface.dart';

/// An implementation of [LivekitBackgroundEffectsPlatform] that uses method channels.
class MethodChannelFlutterVideoBlurring
    extends LivekitBackgroundEffectsPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('livekit_background_effects');

  @override
  Future<void> initVideoBlurring(
    int processorId,
    ProcessorOptions<TrackType> options,
  ) async {
    await methodChannel.invokeMethod("initVideoBlurring", {
      "processorId": processorId,
      "trackId": options.track.id,
    });
  }

  @override
  Future<void> onPublish(int processorId, Room room) async {
    await methodChannel.invokeMethod("onPublish", {
      "processorId": processorId,
      // "room": room,
    });
  }

  @override
  Future<void> onUnpublish(int processorId) async {
    await methodChannel.invokeMethod("onUnpublish", {
      "processorId": processorId,
    });
  }

  @override
  // this is handled in flutter-webrtc
  MediaStreamTrack? getProcessedTrack(int processorId) => null;

  @override
  Future<void> restart(
    int processorId,
    ProcessorOptions<TrackType> options,
  ) async {
    await methodChannel.invokeMethod("restart", {
      "processorId": processorId,
      "trackId": options.track.id,
    });
  }

  @override
  Future<void> destroy(int processorId) async {
    await methodChannel.invokeMethod("destroy", {"processorId": processorId});
  }

  @override
  Future<bool> blurringSupported() async {
    return await methodChannel.invokeMethod("blurringSupported");
  }

  @override
  Future<bool> platformSupported() async {
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
      case TargetPlatform.iOS:
        return true;
      case TargetPlatform.fuchsia:
      case TargetPlatform.linux:
      case TargetPlatform.macOS:
      case TargetPlatform.windows:
        return false;
    }
  }

  @override
  Future<void> updateBackground(
    int processorId,
    LivekitBackgroundEffectsOptions background,
  ) async {
    await methodChannel.invokeMethod("updateBackground", {
      "processorId": processorId,
      "virtualBackgroundAssetId": background.virtualBackground?.assetId,
      "virtualBackgroundPackage": background.virtualBackground?.package,
      "blurRadius": background.blurRadius,
    });
  }
}
