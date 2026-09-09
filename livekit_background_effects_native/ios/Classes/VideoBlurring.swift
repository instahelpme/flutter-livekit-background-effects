/*
 * Copyright 2025 Insta Communications GmbH
 *
 * This file is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

import CoreImage
import Foundation
import Vision
import WebRTC
import flutter_webrtc

@available(iOS 15.0, *)
private var segmentationRequest = VNGeneratePersonSegmentationRequest()

private typealias BlurOptions = (Int?, CIImage?)

@objc public class VideoBlurring: NSObject, ExternalVideoProcessingDelegate {
  private var sink: (any RTCVideoCapturerDelegate)? = nil
  private var lock: NSLock = NSLock()
  private var inFlight: RTCVideoFrame?
  private var inFlightCapturer: RTCVideoCapturer?
  private var next: RTCVideoFrame?
  private var nextCapturer: RTCVideoCapturer?
  private var bgLock: NSLock = NSLock()
  private var virtualBG: CIImage?
  private var blurRadius: Int?
  private var scaledVirtualBG: CIImage?
  private var virtualBGRotation: RTCVideoRotation?

  private let requestHandler = VNSequenceRequestHandler()

  // Dedicated queue so Vision + CIContext work never blocks the camera capture thread.
  private let processingQueue = DispatchQueue(
    label: "instahelp.video-blurring.processing", qos: .userInitiated)

  // Reuse CIContext across frames — creating one per frame recreates a full GPU pipeline.
  private lazy var ciContext: CIContext = CIContext(options: [.useSoftwareRenderer: false])

  // Reuse CIFilter instances — filter setup has non-trivial overhead at 30 fps.
  private let maskInvertFilter = CIFilter(name: "CIColorInvert")!
  private let maskedBlurFilter = CIFilter(name: "CIMaskedVariableBlur")!
  private let blendWithMaskFilter = CIFilter(name: "CIBlendWithMask")!

  // Pool for output pixel buffers — avoids a malloc + zero-fill on every frame.
  private var outputPool: CVPixelBufferPool?
  private var outputPoolWidth = 0
  private var outputPoolHeight = 0
  private var outputPoolFormat: OSType = 0

  override init() {
    super.init()
    if #available(iOS 15.0, *) {
      segmentationRequest.qualityLevel = .balanced
      segmentationRequest.outputPixelFormat = kCVPixelFormatType_OneComponent8
    }
  }

  public func setSink(_ sink: any RTCVideoCapturerDelegate) {
    self.sink = sink
  }

  public func capturer(
    _ capturer: RTCVideoCapturer,
    didCapture frame: RTCVideoFrame
  ) {
    let blur = bgLock.withLock({ () -> BlurOptions in
      getBlurOptions(
        forWidth: Int(frame.width),
        andHeight: Int(frame.height),
        andRotation: frame.rotation
      )
    })
    let run = lock.withLock({ () -> Bool in
      if inFlight == nil {
        inFlight = frame
        inFlightCapturer = capturer
        next = nil
        nextCapturer = nil
        return true
      } else {
        next = frame
        nextCapturer = capturer
        return false
      }
    })
    if run {
      processingQueue.async {
        self.processFrame(frame, capturer: capturer, options: blur)
      }
    }
  }

  public func setBg(_ virtualBackground: UIImage?, and radius: Int?) {
    var ciimage: CIImage?
    if virtualBackground != nil {
      ciimage = CIImage(image: virtualBackground!)
    }
    bgLock.withLock({ () in
      blurRadius = radius
      virtualBG = ciimage
      scaledVirtualBG = nil
    })
  }

  private func processFrame(
    _ frame: RTCVideoFrame,
    capturer: RTCVideoCapturer,
    options: BlurOptions
  ) {
    if options.0 == nil && options.1 == nil {
      sink?.capturer(capturer, didCapture: frame)
      finalizeFrame()
      return
    }

    if #available(iOS 15.0, *) {
      guard let pixelBuffer = videoFrameToPixelBuffer(frame) else {
        print("Failed to convert video frame to pixel buffer")
        finalizeFrame()
        return
      }

      applyBackground(
        from: frame,
        to: pixelBuffer,
        capturer: capturer,
        options: options
      )
    } else {
      // Blurring requires iOS 15; pass frame through unmodified on older OS.
      sink?.capturer(capturer, didCapture: frame)
      finalizeFrame()
    }
  }

  @available(iOS 15.0, *)
  private func applyBackground(
    from frame: RTCVideoFrame,
    to pixelBuffer: CVPixelBuffer,
    capturer: RTCVideoCapturer,
    options: BlurOptions,
  ) {

    try? requestHandler.perform([segmentationRequest], on: pixelBuffer)

    guard let maskBuffer = segmentationRequest.results?.first?.pixelBuffer
    else {
      finalizeFrame()
      return
    }

    var blended: CIImage
    if options.0 != nil {
      blended = blurImage(pixelBuffer, with: maskBuffer, andRadius: options.0!)
    } else {
      blended = applyVirtualBackground(
        pixelBuffer,
        with: maskBuffer,
        andBG: options.1!
      )
    }

    guard
      let processedFrame = ciImageToVideoFrame(
        blended,
        rotation: frame.rotation,
        timeStampNs: frame.timeStampNs
      )
    else {
      print("Failed to convert pixel buffer to video frame")
      finalizeFrame()
      return
    }
    sink?.capturer(capturer, didCapture: processedFrame)
    finalizeFrame()
  }

  private func blurImage(
    _ original: CVPixelBuffer,
    with mask: CVPixelBuffer,
    andRadius radius: Int
  )
    -> CIImage
  {
    let originalImage = CIImage(cvPixelBuffer: original)

    var maskImage = CIImage(cvImageBuffer: mask)
    maskInvertFilter.setValue(maskImage, forKey: kCIInputImageKey)
    maskImage = maskInvertFilter.outputImage!

    // Scale the mask image to fit the bounds of the video frame.
    let scaleX = originalImage.extent.width / maskImage.extent.width
    let scaleY = originalImage.extent.height / maskImage.extent.height
    let scaledMaskImage = maskImage.transformed(by: .init(scaleX: scaleX, y: scaleY))

    maskedBlurFilter.setValue(originalImage, forKey: kCIInputImageKey)
    maskedBlurFilter.setValue(scaledMaskImage, forKey: "inputMask")
    maskedBlurFilter.setValue(radius, forKey: kCIInputRadiusKey)

    return maskedBlurFilter.outputImage!.cropped(to: originalImage.extent)
  }

  private func applyVirtualBackground(
    _ original: CVPixelBuffer,
    with mask: CVPixelBuffer,
    andBG bg: CIImage
  )
    -> CIImage
  {
    let originalImage = CIImage(cvPixelBuffer: original)
    let maskImage = CIImage(cvImageBuffer: mask)

    // Scale the mask image to fit the bounds of the video frame.
    let scaleX = originalImage.extent.width / maskImage.extent.width
    let scaleY = originalImage.extent.height / maskImage.extent.height
    let scaledMaskImage = maskImage.transformed(by: .init(scaleX: scaleX, y: scaleY))

    blendWithMaskFilter.setValue(originalImage, forKey: kCIInputImageKey)
    blendWithMaskFilter.setValue(scaledMaskImage, forKey: kCIInputMaskImageKey)
    blendWithMaskFilter.setValue(bg, forKey: kCIInputBackgroundImageKey)

    return blendWithMaskFilter.outputImage!
  }

  private func finalizeFrame() {
    let (run, nextFrame, nextCap) = lock.withLock(
      { () -> (Bool, RTCVideoFrame?, RTCVideoCapturer?) in
        if next == nil {
          inFlight = nil
          inFlightCapturer = nil
          return (false, nil, nil)
        } else {
          let f = next!
          let c = nextCapturer!
          inFlight = f
          inFlightCapturer = c
          next = nil
          nextCapturer = nil
          return (true, f, c)
        }
      })
    if run, let frame = nextFrame, let cap = nextCap {
      let blur = bgLock.withLock({
        getBlurOptions(
          forWidth: Int(frame.width),
          andHeight: Int(frame.height),
          andRotation: frame.rotation
        )
      })
      // Already on processingQueue — call directly (tail-call style) rather than re-enqueuing.
      processFrame(frame, capturer: cap, options: blur)
    }
  }

  private func videoFrameToPixelBuffer(_ rtcVideoFrame: RTCVideoFrame) -> CVPixelBuffer? {
    guard let cvPixelBuffer = rtcVideoFrame.buffer as? RTCCVPixelBuffer else {
      print("Error: RTCVideoFrame is not of type RTCCVPixelBuffer")
      return nil
    }
    // Return the underlying buffer directly — Vision can work with it as-is,
    // so there's no need for the CIImage round-trip that was here before.
    return cvPixelBuffer.pixelBuffer
  }

  private func ciImageToVideoFrame(
    _ ciImage: CIImage,
    rotation: RTCVideoRotation,
    timeStampNs: Int64
  ) -> RTCVideoFrame? {
    guard
      let formattedBuffer = ciImageToPixelBuffer(
        ciImage,
        pixelFormat: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
      )
    else {
      return nil
    }

    let rtcPixelBuffer = RTCCVPixelBuffer(pixelBuffer: formattedBuffer)
    return RTCVideoFrame(
      buffer: rtcPixelBuffer,
      rotation: rotation,
      timeStampNs: timeStampNs
    )
  }

  private func ciImageToPixelBuffer(
    _ ciImage: CIImage,
    pixelFormat: OSType = kCVPixelFormatType_32BGRA
  ) -> CVPixelBuffer? {
    let width = Int(ciImage.extent.width)
    let height = Int(ciImage.extent.height)

    guard let result = allocatePixelBuffer(width: width, height: height, pixelFormat: pixelFormat)
    else {
      return nil
    }
    ciContext.render(ciImage, to: result)
    return result
  }

  private func allocatePixelBuffer(width: Int, height: Int, pixelFormat: OSType) -> CVPixelBuffer? {
    if outputPool == nil || outputPoolWidth != width || outputPoolHeight != height
      || outputPoolFormat != pixelFormat
    {
      // IOSurface-backed buffers let the GPU access them directly, avoiding CPU↔GPU copies.
      let attrs: [String: Any] = [
        kCVPixelBufferPixelFormatTypeKey as String: pixelFormat,
        kCVPixelBufferWidthKey as String: width,
        kCVPixelBufferHeightKey as String: height,
        kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any](),
      ]
      var pool: CVPixelBufferPool?
      guard
        CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attrs as CFDictionary, &pool)
          == kCVReturnSuccess,
        let pool
      else { return nil }
      outputPool = pool
      outputPoolWidth = width
      outputPoolHeight = height
      outputPoolFormat = pixelFormat
    }

    var pixelBuffer: CVPixelBuffer?
    guard
      let pool = outputPool,
      CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &pixelBuffer)
        == kCVReturnSuccess,
      let pixelBuffer
    else { return nil }
    return pixelBuffer
  }

  private func getBlurOptions(
    forWidth width: Int,
    andHeight height: Int,
    andRotation rotation: RTCVideoRotation
  ) -> BlurOptions {
    if blurRadius != nil {
      return (Int(blurRadius!), nil)
    }
    if virtualBG == nil {
      return (nil, nil)
    }

    if scaledVirtualBG != nil {
      let scaledBG: CIImage = scaledVirtualBG!
      if scaledBG.extent.size.equalTo(CGSize(width: width, height: height))
        && rotation == virtualBGRotation
      {
        return (nil, scaledVirtualBG)
      }
    }

    let s1 = virtualBG!.extent.size
    let vBG: CIImage
    switch rotation {
    case ._0:
      vBG = virtualBG!
    case ._90:
      vBG = virtualBG!.transformed(
        by: CGAffineTransform(rotationAngle: 90 * .pi / 180).translatedBy(x: 0, y: -s1.height))
    case ._180:
      vBG = virtualBG!.transformed(
        by: CGAffineTransform(rotationAngle: 180 * .pi / 180).translatedBy(
          x: -s1.width, y: -s1.height))
    case ._270:
      vBG = virtualBG!.transformed(
        by: CGAffineTransform(rotationAngle: 270 * .pi / 180).translatedBy(x: -s1.width, y: 0))
    @unknown default:
      return (nil, nil)
    }
    let size = vBG.extent.size
    let scaleWidth = Double(width) / size.width
    let scaleHeight = Double(height) / size.height

    let maxScale = Double.maximum(scaleHeight, scaleWidth)

    let transformation = CGAffineTransform(scaleX: maxScale, y: maxScale)

    scaledVirtualBG = vBG.transformed(by: transformation).cropped(
      to: CGRect(x: 0, y: 0, width: width, height: height)
    )
    virtualBGRotation = rotation

    return (nil, scaledVirtualBG)
  }
}
