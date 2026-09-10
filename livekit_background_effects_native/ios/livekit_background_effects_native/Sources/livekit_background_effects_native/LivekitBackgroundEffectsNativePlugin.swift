/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import Flutter
import UIKit
import flutter_webrtc

public class LivekitBackgroundEffectsNativePlugin: NSObject, FlutterPlugin {
  private var processors: [Int: VideoBlurring] = [:]
  private var tracks: [Int: String] = [:]
  private var registrar: FlutterPluginRegistrar
  
  init(registrar: FlutterPluginRegistrar) {
    self.registrar = registrar
  }
  
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "livekit_background_effects", binaryMessenger: registrar.messenger())
    let instance = LivekitBackgroundEffectsNativePlugin(registrar:registrar)
    registrar.addMethodCallDelegate(instance, channel: channel)
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    let arguments = call.arguments as? NSDictionary
    switch call.method {
    case "initVideoBlurring":
      initVideoBlurring(arguments!, result:result)
      break
    case "destroy":
      destroy(arguments!, result:result)
      break
    case "restart":
      restart(result:result)
      break
    case "blurringSupported":
      blurringSupported(result:result)
      break
    case "updateBackground":
      updateBackground(arguments!, result:result)
      break
    case "onPublish":
      onPublish(result:result)
      break
    case "onUnpublish":
      onUnpublish(result:result)
      break
    default:
      result(FlutterMethodNotImplemented)
    }
  }
  
  private func initVideoBlurring(_ arguments: NSDictionary, result: @escaping FlutterResult) {

    if #unavailable(iOS 15.0) {
      result(FlutterError(code: "UNSUPPORTED_DEVICE", message: "video blurring is unsupported on iOS < 15", details: nil))
      return
    }
    let plugin = FlutterWebRTCPlugin.sharedSingleton()!
    guard let processorId = arguments["processorId"] as? Int else {
      result(FlutterError(code: "INVALID_ARGUMENT", message: "processorId is required", details: nil))
      return
    }

    guard let trackId = arguments["trackId"] as? String else {
      result(FlutterError(code: "INVALID_ARGUMENT", message: "trackId is required", details: nil))
      return
    }

    if (processors[processorId] != nil) {
      result(FlutterError(code: "INVALID_STATE", message: "processorId is already used", details: nil))
      return
    }

    guard let track = plugin.localTracks?[trackId] as? LocalVideoTrack else {
      result(FlutterError(code: "INVALID_STATE", message: "track not found", details: nil))
      return
    }
    if #available(iOS 15.0, *) {
      let processor = VideoBlurring()
      processors[processorId] = processor
      tracks[processorId] = trackId
      track.addProcessing(processor)
    }

    result(nil)
  }

  private func destroy(_ arguments: NSDictionary, result: @escaping FlutterResult) {
    let plugin = FlutterWebRTCPlugin.sharedSingleton()!
    
    guard let processorId = arguments["processorId"] as? Int else {
      result(FlutterError(code: "INVALID_ARGUMENT", message: "processorId is required", details: nil))
      return
    }
    

    guard let trackId = tracks[processorId] else {
      result(FlutterError(code: "INVALID_STATE", message: "trackId not found", details: nil))
      return
    }


    guard let processor = processors[processorId] else {
      result(FlutterError(code: "INVALID_STATE", message: "processorId not found", details: nil))
      return
    }

    if let track = plugin.localTracks?[trackId] as? LocalVideoTrack {
      track.removeProcessing(processor)
    }
    
    processors.removeValue(forKey: processorId)
    tracks.removeValue(forKey: processorId)

    result(nil)
  }

  private func updateBackground(_ arguments: NSDictionary, result: @escaping FlutterResult) {
    guard let processorId = arguments["processorId"] as? Int else {
      result(FlutterError(code: "INVALID_ARGUMENT", message: "processorId is required", details: nil))
      return
    }
    
    guard let processor = processors[processorId] else {
      result(FlutterError(code: "INVALID_STATE", message: "processorId not found", details: nil))
      return
    }

    let virtualBackgroundAssetId = arguments["virtualBackgroundAssetId"] as? String
    let virtualBackgroundPackage = arguments["virtualBackgroundPackage"] as? String
    let blurRadius = arguments["blurRadius"] as? Int
    var bgImage: UIImage?;

    if (virtualBackgroundAssetId != nil) {
      var lookup: String;
      if (virtualBackgroundPackage != nil) {
        lookup = registrar.lookupKey(forAsset: virtualBackgroundAssetId!, fromPackage: virtualBackgroundPackage!)
      } else {
        lookup = registrar.lookupKey(forAsset: virtualBackgroundAssetId!)
      }
      let bundle = Bundle.main
      guard let path = bundle.path(forResource: lookup, ofType: nil) else {
        result(FlutterError(code: "INVALID_ARGUMENT", message: "background not found", details: nil))
        return
        
      }
      
      guard let image = UIImage(named: path) else {
        result(FlutterError(code: "INVALID_ARGUMENT", message: "image not found", details: nil))
        return
      }
      bgImage = image
    }
    
    processor.setBg(bgImage, and: blurRadius)
    result(nil)
    
  }
  private func restart(result: @escaping FlutterResult) {
    result(nil)
  }
  private func blurringSupported( result: @escaping FlutterResult) {
    if #available(iOS 16.0, *) {
      result(Bool(true))
    } else {
      result(Bool(false))
    }
  }
  private func onPublish(result: @escaping FlutterResult) {
    result(nil)
  }
  private func onUnpublish(result: @escaping FlutterResult) {
    result(nil)
  }
}
