/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

@JS('track_processor')
library;

import 'dart:js_interop';

import 'package:web/web.dart';

@JS("supportsBackgroundProcessors")
external bool supportsBackgroundProcessors();
@JS("supportsModernBackgroundProcessors")
external bool supportsModernBackgroundProcessors();

@JS("ProcessorWrapper")
extension type JSProcessor._(JSObject _) implements JSObject {
  // external JSProcessor(JSBackgroundProcessor transformer, JSString name);

  external static bool get isSupported;
  external static bool get hasModernApiSupport;

  external JSPromise init(JSObject opts);
  external JSPromise destroy();
  external MediaStreamTrack? processedTrack;
  external JSVideoTrackTransformer transformer;
}

@JS("BackgroundProcessor")
external JSProcessor generateJSBackgroundProcessor(JSBackgroundOptions options);

@JS("BackgroundOptions")
extension type JSBackgroundOptions._(JSObject _) implements JSObject {
  external JSBackgroundOptions({
    JSNumber? blurRadius,
    JSString? imagePath,
    JSAssetPaths? assetPaths,
  });
  external JSNumber? blurRadius;
  external JSString? imagePath;
  external JSAssetPaths? assetPaths;
}

@JS()
@anonymous
extension type JSAssetPaths._(JSObject _) implements JSObject {
  external factory JSAssetPaths({
    JSString? tasksVisionFileSet,
    JSString? modelAssetPath,
  });
}

@JS("VideoTrackTransformer")
extension type JSVideoTrackTransformer._(JSObject _) implements JSObject {
  external void update(JSBackgroundOptions options);
}

@JS("VideoProcessorOptions")
extension type JSVideoProcessorOptions._(JSObject _) implements JSObject {
  external JSVideoProcessorOptions({
    JSString kind,
    MediaStreamTrack track,
    HTMLMediaElement? element,
  });
}
